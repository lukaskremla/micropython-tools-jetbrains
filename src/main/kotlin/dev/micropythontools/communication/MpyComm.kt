/*
 * Copyright 2000-2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.micropythontools.communication

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.Strings
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.util.ExceptionUtil
import com.intellij.util.text.nullize
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import dev.micropythontools.ui.NOTIFICATION_GROUP
import dev.micropythontools.util.MpyPythonService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.NonNls
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.properties.Delegates


internal const val SHORT_DELAY = 20L
internal const val SHORT_TIMEOUT = 2000L
internal const val TIMEOUT = 5000L
internal const val LONG_TIMEOUT = 20000L

data class SingleExecResponse(
    val stdout: String, val stderr: String
)

typealias ExecResponse = List<SingleExecResponse>

enum class State {
    DISCONNECTING, DISCONNECTED, CONNECTING, CONNECTED, TTY_DETACHED
}

fun ExecResponse.extractSingleResponse(): String {
    if (this.size != 1 || this[0].stderr.isNotEmpty()) {
        val message = this.joinToString("\n") { it.stderr }
        throw IOException(message)
    } else {
        return this[0].stdout
    }
}

fun ExecResponse.extractResponse(): String {
    val stderr = this.mapNotNull { it.stderr.nullize(true) }.joinToString("\n")
    if (stderr.isNotEmpty()) {
        throw IOException(stderr)
    }

    return this.mapNotNull { it.stdout.nullize(true) }.joinToString("\n")
}

fun ByteArrayOutputStream.toUtf8String(): String = this.toString(StandardCharsets.UTF_8)

/**
 * @author elmot, Lukas Kremla
 */
open class MpyComm(val project: Project, private val deviceService: MpyDeviceService, private val pythonService: MpyPythonService) : Disposable, Closeable {
    @Volatile
    private var mpyClient: MpyClient? = null

    private val offTtyByteBuffer = ByteArrayOutputStream()

    private val webSocketMutex = Mutex()

    private val outPipe = PipedWriter()

    private val inPipe = PipedReader(outPipe, 1000)

    internal var connectionParameters: ConnectionParameters = ConnectionParameters("http://192.168.4.1:8266", "")

    val ttyConnector: TtyConnector = WebSocketTtyConnector()

    val isConnected
        get() = mpyClient?.isConnected == true

    var state: State by Delegates.observable(State.DISCONNECTED) { _, _, newValue ->
        deviceService.stateListeners.forEach { it(newValue) }
    }

    open fun errorLogger(exception: Exception) {
        thisLogger().warn(exception)
        Notifications.Bus.notify(
            Notification(
                NOTIFICATION_GROUP,
                ExceptionUtil.getMessage(exception) ?: exception.toString(),
                NotificationType.WARNING
            )
        )
    }

    /**
     * Uploads the binary file [content] to the [fullName] path. Accepts a [progressCallback] to report back incremental file writes.
     *
     * This method will create all parent directories if they don't exist
     */
    suspend fun upload(fullName: @NonNls String, content: ByteArray, progressCallback: (uploadedBytes: Double) -> Unit) {
        checkConnected()
        val commands = mutableListOf<Any>("import os, gc")

        var slashIdx = 0
        while (slashIdx >= 0) {
            slashIdx = fullName.indexOf('/', slashIdx + 1)
            if (slashIdx > 0) {
                val folderName = fullName.substring(0, slashIdx)
                commands.add("try: os.mkdir(\"$folderName\");")
                commands.add("except: pass;")
            }
        }

        if (deviceService.deviceInformation.canDecodeBase64 && content.size > 256 && content.count { b -> b in 32..127 } < content.size / 2) {
            commands.addAll(binUpload(fullName, content))
        } else {
            commands.addAll(txtUpload(fullName, content))
        }
        commands.add("gc.collect()")

        val result = webSocketMutex.withLock {
            doBlindExecute(commands, progressCallback = progressCallback, uploadFileSize = content.size)
        }
        val error = result.mapNotNull { Strings.nullize(it.stderr) }.joinToString(separator = "\n", limit = 1000)
        if (error.isNotEmpty()) {
            throw IOException(error)
        }
    }

    private fun txtUpload(fullName: @NonNls String, content: ByteArray): List<Any> {
        val commands = mutableListOf<Any>("___f=open('$fullName','wb')")
        val chunk = StringBuilder()
        val maxDataChunk = 400
        var contentIdx = 0
        while (contentIdx < content.size) {
            chunk.setLength(0)
            val startIdx = contentIdx
            while (chunk.length < maxDataChunk && contentIdx < content.size) {
                val b = content[contentIdx++]
                chunk.append(
                    when (b) {
                        '\''.code.toByte() -> "\\'"
                        '\\'.code.toByte() -> "\\\\"
                        in 32.toByte()..126.toByte() -> b.toInt().toChar()
                        0x0D.toByte() -> "\\r"
                        0x0A.toByte() -> "\\n"
                        else -> "\\x%02x".format(b)
                    }
                )
            }
            val chunkSize = contentIdx - startIdx
            commands.add(Pair("___f.write(b'$chunk')", chunkSize))
        }
        commands.add("___f.close()")
        commands.add("del(___f)")
        return commands
    }

    private fun binUpload(fullName: @NonNls String, content: ByteArray): List<Any> {
        val commands = mutableListOf<Any>(
            "import binascii",
            "___e=lambda b:___f.write(binascii.a2b_base64(b))",
            "___f=open('$fullName','wb')"
        )
        val maxDataChunk = 384
        assert(maxDataChunk % 4 == 0)
        var contentIdx = 0
        val encoder = Base64.getEncoder()
        while (contentIdx < content.size) {
            val len = maxDataChunk.coerceAtMost(content.size - contentIdx)
            val chunk = encoder.encodeToString(content.copyOfRange(contentIdx, contentIdx + len))
            contentIdx += len
            commands.add(Pair("___e('$chunk')", len))
        }
        commands.add("___f.close()")
        commands.add("del ___f,___e")
        return commands
    }

    suspend fun download(fileName: @NonNls String): ByteArray {
        val canEncodeBase64 = deviceService.deviceInformation.canEncodeBase64

        val command = when (canEncodeBase64) {
            true -> pythonService.retrieveMpyScriptAsString("download_file_base_64.py")
            else -> pythonService.retrieveMpyScriptAsString("download_file_hex.py")
        }

        val result = blindExecute(command.format("\"$fileName\"")).extractSingleResponse()

        return if (canEncodeBase64) {
            val cleanOutput = result
                .replace("\\n'", "")
                .replace("b'", "")

            val decoder = Base64.getDecoder()
            decoder.decode(cleanOutput)
        } else {
            result.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        }
    }

    /**
     * Method for executing MicroPython scripts and commands over REPL
     * This method is only intended to be called internally in MpyComm
     *
     * @param commands List of commands to join and execute, can be a list of Strings or Pair(String, Int) pairs with uploadedBytes progress
     * @param progressCallback A callback that will be contained if the commands contain Pair(String, Int) pairs with uploadedBytes progress
     * @return The execution response
     */
    @OptIn(ExperimentalStdlibApi::class)
    @Throws(IOException::class)
    private suspend fun doBlindExecute(
        commands: List<Any>,
        progressCallback: ((uploadedBytes: Double) -> Unit)? = null,
        uploadFileSize: Int = 0,
        redirectToRepl: Boolean = false
    ): ExecResponse {
        state = State.TTY_DETACHED
        try {
            // Enter raw-REPL
            withTimeout(LONG_TIMEOUT) {
                retry(3, listOf(0L, 1000L, 3000L)) {
                    // Repeatedly attempt to enter raw-REPL
                    do {
                        var promptNotReady = true

                        // Interrupt all running code
                        mpyClient?.send("\u0003")
                        mpyClient?.send("\u0003")
                        mpyClient?.send("\u0003")

                        // Attempt to enter raw-REPL by sending ctrl-A
                        mpyClient?.send("\u0001")
                        withTimeout(TIMEOUT) {
                            while (!offTtyByteBuffer.toUtf8String().endsWith("\n>")) {
                                delay(SHORT_DELAY)
                            }
                            promptNotReady = false
                        }
                    } while (promptNotReady)
                    offTtyByteBuffer.reset()

                    // Enter raw paste mode
                    do {
                        var pasteModeNotReady = true

                        // Send bytes to initiate raw paste mode
                        mpyClient?.send("\u0005A\u0001")
                        withTimeout(TIMEOUT) {
                            // Wait for the buffer to contain at least 2 raw paste mode response bytes
                            while (offTtyByteBuffer.size() < 2) {
                                delay(SHORT_DELAY)
                            }

                            val resultBytes = readAndDiscardXBytesFromBuffer(2)

                            val b0 = resultBytes[0]
                            val b1 = resultBytes[1]

                            when {
                                // Device supports raw paste and has entered it
                                b0 == 0x52.toByte() && b1 == 0x01.toByte() -> {
                                    // Success
                                }

                                // Device understands the command but doesn’t support raw paste or
                                // it doesn't even know about it
                                b0 == 0x52.toByte() && b1 == 0x00.toByte() ||
                                        b0 == 0x72.toByte() && b1 == 0x61.toByte()
                                    -> {
                                    throw IOException("Device failed to enter raw paste mode. Please try again and if it doesn't help open an issue on our GitHub.")
                                }

                                // Unknown response
                                else -> {
                                    throw IOException("Unknown raw paste response: \"b0=0x%02X b1=0x%02X\". Please try again and if it doesn't help open an issue on our GitHub.".format(b0, b1))
                                }
                            }
                        }

                        try {
                            withTimeout(TIMEOUT) {
                                // Wait for the buffer to contain at least 2 flow control bytes
                                while (offTtyByteBuffer.size() < 2) {
                                    delay(SHORT_DELAY)
                                }
                                pasteModeNotReady = false
                            }
                        } catch (_: TimeoutCancellationException) {
                            throw IOException("Missing paste mode flow control bytes")
                        }
                    } while (pasteModeNotReady)
                }
            }

            val bytes = readAndDiscardXBytesFromBuffer(2)
            val flowControlWindowSize = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
            var remainingFlowControlWindowSize = flowControlWindowSize

            val result = mutableListOf<SingleExecResponse>()

            try {
                val commandList = mutableListOf<String>()

                var trackedChunks = 0

                for (command in commands) {
                    when (command) {
                        is String -> {
                            val formattedCommand = "$command\n"
                            commandList.add(formattedCommand)
                        }

                        is Pair<*, *> -> {
                            val cmd = command.first
                            val size = command.second ?: 0

                            if (cmd is String && size is Int) {
                                val formattedCommand = "$cmd\n"

                                trackedChunks += size
                                commandList.add(formattedCommand)
                            } else {
                                throw IllegalArgumentException("Expected Pair<String, Int?> but got ${command::class.java}")
                            }
                        }

                        else -> {
                            throw IllegalArgumentException("Unexpected command type: ${command::class.java}")
                        }
                    }
                }

                val commandBytes = commandList.joinToString("").toByteArray(Charsets.UTF_8)

                // Precisely accurate progress reporting is impossible without compromising on
                // maximally utilizing the available flow control window.
                // This logic divides the size of the uploaded file by the size of the total command, which
                // is comprised of the minimal setup and cleanup logic and of the inflated encoded data
                // Every write will push the progress reporting forward a bit even if no actual write command was transferred
                // This is a minimal inaccuracy and is acceptable in the context of this plugin, where
                // fast uploads and consistent indication of the progress being made are what matters
                val singleByteProgress = when {
                    progressCallback != null && uploadFileSize > 0 -> uploadFileSize.toDouble() / commandBytes.size.toDouble()
                    else -> null
                }

                var uploadProgress = 0.0

                var index = 0

                while (index < commandBytes.size) {
                    if (remainingFlowControlWindowSize <= 0) {
                        withTimeout(TIMEOUT) {
                            // Check if the buffer contains at least 1 byte indicating how to continue
                            while (offTtyByteBuffer.size() < 1) {
                                delay(SHORT_DELAY)
                            }

                            val bytes = readAndDiscardXBytesFromBuffer(1)

                            if (bytes[0] == 0x01.toByte()) {
                                remainingFlowControlWindowSize += flowControlWindowSize
                            } else if (bytes[0] == 0x04.toByte()) {
                                mpyClient?.send(byteArrayOf(0x04.toByte()))
                                throw IOException("Device aborted raw paste mode")
                            }
                        }
                    }

                    val endIndex = minOf(index + remainingFlowControlWindowSize, commandBytes.size)

                    // Get chunk
                    val chunk = commandBytes.copyOfRange(index, endIndex)

                    // Report progress if applicable
                    if (progressCallback != null && singleByteProgress != null) {
                        val progress = chunk.size * singleByteProgress
                        val coercedProgress = progress.coerceIn(progress, uploadFileSize.toDouble())
                        uploadProgress += coercedProgress
                        progressCallback(coercedProgress)
                    }

                    // Send chunk
                    withTimeout(TIMEOUT) {
                        mpyClient?.send(chunk)
                    }

                    // Increment index
                    index = endIndex

                    // Decrease the remaining flow control window size
                    remainingFlowControlWindowSize -= chunk.size
                }

                // Indicate end of transmission
                withTimeout(TIMEOUT) {
                    mpyClient?.send("\u0004")
                }

                if (redirectToRepl) {
                    return emptyList()
                }

                withTimeout(LONG_TIMEOUT) {
                    while (!(offTtyByteBuffer.toUtf8String().endsWith("\u0004>") &&
                                offTtyByteBuffer.toUtf8String().count { it == '\u0004' } == 3)
                    ) {
                        delay(SHORT_DELAY)
                    }
                }

                var currIndex = 0
                val foundEotCharacterIndexes = mutableListOf<Int>()
                while (currIndex < offTtyByteBuffer.toUtf8String().length) {
                    val foundIndex = offTtyByteBuffer.toUtf8String().indexOf("\u0004", currIndex)
                    if (foundIndex == -1) break
                    foundEotCharacterIndexes.add(foundIndex)
                    currIndex = foundIndex + 1
                }

                val stdout = offTtyByteBuffer.toUtf8String().substring(foundEotCharacterIndexes[0] + 1, foundEotCharacterIndexes[1]).trim()
                val stderr = offTtyByteBuffer.toUtf8String().substring(foundEotCharacterIndexes[1] + 1, foundEotCharacterIndexes[2]).trim()
                result.add(SingleExecResponse(stdout, stderr))
                offTtyByteBuffer.reset()
            } catch (e: TimeoutCancellationException) {
                throw IOException("Timeout during command execution: $commands", e)
            }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state = State.DISCONNECTED
            mpyClient?.close()
            mpyClient = null
            throw e
        } finally {
            withContext(NonCancellable) {
                // Leave raw-REPL
                // TODO: Raw repl must somehow be exited at the end
                if (!redirectToRepl) {
                    try {
                        withTimeout(SHORT_TIMEOUT) {
                            mpyClient?.send("\u0002")
                        }
                    } catch (e: TimeoutCancellationException) {
                        if (state == State.TTY_DETACHED) {
                            throw IOException("Timed out while leaving raw-REPL", e)
                        }
                    }
                }
                offTtyByteBuffer.reset()
                if (state == State.TTY_DETACHED) {
                    state = State.CONNECTED
                }
            }
        }
    }

    fun readAndDiscardXBytesFromBuffer(byteCount: Int): ByteArray {
        val readBytes = offTtyByteBuffer.toByteArray().copyOfRange(0, byteCount)
        val remainingBytes = offTtyByteBuffer.toByteArray().copyOfRange(byteCount, offTtyByteBuffer.toByteArray().size)

        offTtyByteBuffer.reset()
        if (remainingBytes.isNotEmpty()) {
            offTtyByteBuffer.writeBytes(remainingBytes)
        }

        return readBytes
    }

    fun checkConnected() {
        when (state) {
            State.CONNECTED -> {}
            State.DISCONNECTING, State.DISCONNECTED, State.CONNECTING -> throw IOException("Not connected")
            State.TTY_DETACHED -> throw IOException("Websocket is busy")
        }
    }

    @Throws(IOException::class)
    suspend fun blindExecute(command: String): ExecResponse {
        checkConnected()
        webSocketMutex.withLock {
            return doBlindExecute(listOf(command))
        }
    }

    @Throws(IOException::class)
    suspend fun instantRun(command: @NonNls String) {
        checkConnected()
        webSocketMutex.withLock {
            doBlindExecute(listOf(command), redirectToRepl = true)
        }
    }

    private suspend fun retry(attempts: Int, delayList: List<Long>, codeToRetry: suspend () -> Unit) {
        var exception: Throwable? = null

        var i = 0
        do {
            try {
                val delayToUse = delayList[minOf(i, delayList.size)]
                delay(delayToUse)
                codeToRetry()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (exception == null) exception = e
            }
            i++
        } while (i < attempts)

        // If the code gets here retry attempts ran out
        throw exception
    }

    override fun dispose() {
        close()
    }

    inner class WebSocketTtyConnector : TtyConnector {
        override fun getName(): String = connectionParameters.webReplUrl
        override fun close() = Disposer.dispose(this@MpyComm)
        override fun isConnected(): Boolean = true
        override fun ready(): Boolean {
            return inPipe.ready() || mpyClient?.hasPendingData() == true
        }

        override fun resize(termSize: TermSize) = Unit

        override fun waitFor(): Int = 0

        override fun write(bytes: ByteArray) = write(bytes.toString(StandardCharsets.UTF_8))

        override fun write(text: String) {
            if (state == State.CONNECTED) {
                // Soft reset will terminate the WebREPL session
                if (text.contains('\u0004') && mpyClient is MpyWebSocketClient) {
                    runWithModalProgressBlocking(project, "Soft Resetting Device...") {
                        reset()
                    }
                    return
                }

                mpyClient?.send(text)
            }
        }

        override fun read(text: CharArray, offset: Int, length: Int): Int {
            while (isConnected) {
                try {
                    return inPipe.read(text, offset, length)
                } catch (_: IOException) {
                    try {
                        Thread.sleep(SHORT_DELAY)
                    } catch (_: InterruptedException) {
                        // pass
                    }
                }
            }
            return -1
        }
    }

    override fun close() {
        try {
            mpyClient?.close()
            mpyClient = null
        } catch (_: IOException) {
        }
        try {
            inPipe.close()
        } catch (_: IOException) {
        }
        try {
            outPipe.close()
        } catch (_: IOException) {
        }
        try {
            mpyClient?.close()
        } catch (_: IOException) {
        }
        state = State.DISCONNECTED
    }

    protected open fun createClient(): MpyClient {
        return if (connectionParameters.usingUart) MpySerialClient(this) else MpyWebSocketClient(this)
    }

    suspend fun connect() {
        val name = with(connectionParameters) {
            if (usingUart) portName else webReplUrl
        }
        state = State.CONNECTING
        offTtyByteBuffer.reset()
        webSocketMutex.withLock {
            try {
                mpyClient = createClient().connect("Connecting to $name")
            } catch (e: Exception) {
                state = State.DISCONNECTED
                throw e
            }
        }
    }

    suspend fun disconnect() {
        webSocketMutex.withLock {
            state = State.DISCONNECTING
            mpyClient?.close()
            mpyClient = null
            state = State.DISCONNECTED
        }
    }

    fun setConnectionParams(parameters: ConnectionParameters) {
        this.connectionParameters = parameters
    }

    open fun dataReceived(bytes: ByteArray) {
        when (state) {
            State.TTY_DETACHED, State.CONNECTING -> offTtyByteBuffer.writeBytes(bytes)
            else -> {
                runBlocking {
                    try {
                        outPipe.write(bytes.toString(StandardCharsets.UTF_8))
                        outPipe.flush()
                    } catch (_: Throwable) {
                        disconnect()

                        Notifications.Bus.notify(
                            Notification(
                                NOTIFICATION_GROUP,
                                "Error writing to the IDE terminal widget. Please connect again and retry.",
                                NotificationType.ERROR
                            ), deviceService.project
                        )

                        ApplicationManager.getApplication().invokeLater {
                            deviceService.recreateTtyConnector()
                        }
                    }
                }
            }
        }
    }

    suspend fun reset() {
        checkConnected()
        webSocketMutex.withLock {
            if (mpyClient is MpyWebSocketClient) {
                // Hide reset output via TTY_DETACHED and mute disconnect error reporting for WebREPL
                state = State.TTY_DETACHED
                (mpyClient as MpyWebSocketClient).resetInProgress = true
            }

            try {
                withTimeout(SHORT_TIMEOUT) {
                    // Interrupt running code
                    mpyClient?.send("\u0003")
                    // Soft reset
                    mpyClient?.send("\u0004")
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Timed out while performing reset", e)
            }
        }

        if (mpyClient is MpyWebSocketClient) {
            // Disconnect WebREPL after reset
            deviceService.disconnect(null)
            // Give the device time to reset
            delay(3000)

            ApplicationManager.getApplication().invokeLater {
                runWithModalProgressBlocking(project, "Reconnecting WebREPL After Reset...") {
                    reportRawProgress { reporter ->
                        // Reconnect WebREPL
                        deviceService.doConnect(reporter)
                    }
                }
            }
        }
    }

    suspend fun interrupt() {
        checkConnected()
        webSocketMutex.withLock {
            try {
                withTimeout(SHORT_TIMEOUT) {
                    // Interrupt running code
                    mpyClient?.send("\u0003")
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Timed out while interrupting running code", e)
            }
        }
    }

    suspend fun recursivelySafeDeletePaths(paths: Set<String>) {
        val commands = mutableListOf(
            pythonService.retrieveMpyScriptAsString("recursively_safe_delete_base.py")
        )

        val filteredPaths = paths.filter { path ->
            // Keep this path only if no other path is a prefix of it
            paths.none { otherPath ->
                otherPath != path && path.startsWith("$otherPath/")
            }
        }

        filteredPaths.forEach {
            commands.add("___d('${it}')")
        }

        commands.add("del ___d")
        commands.add("gc.collect()")

        blindExecute(commands.joinToString("\n")).extractSingleResponse()
    }

    suspend fun safeCreateDirectories(paths: Set<String>) {
        val commands = mutableListOf(
            pythonService.retrieveMpyScriptAsString("safe_create_directories_base.py")
        )

        val allPaths = buildSet {
            paths.forEach { path ->
                // Generate and add all parent directories
                val parts = path.split("/")
                var currentPath = ""
                for (part in parts) {
                    if (part.isEmpty()) continue
                    currentPath += "/$part"
                    add(currentPath)
                }
            }
        }

        val sortedPaths = allPaths
            // Sort shortest paths first, ensures parents are created before children
            .sortedBy { it.split("/").filter { subPath -> subPath.isNotEmpty() }.size }
            .toList()

        sortedPaths.forEach {
            commands.add("___m('$it')")
        }
        commands.add("del ___m")
        commands.add("gc.collect()")

        blindExecute(commands.joinToString("\n")).extractSingleResponse()
    }
}