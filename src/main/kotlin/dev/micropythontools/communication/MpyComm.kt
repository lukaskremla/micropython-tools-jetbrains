/*
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2025 Lukas Kremla
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
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.util.ExceptionUtil
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import dev.micropythontools.settings.retrieveMpyScriptAsString
import dev.micropythontools.ui.NOTIFICATION_GROUP
import jssc.SerialPort
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.properties.Delegates


internal const val SHORT_DELAY = 20L
internal const val SHORT_TIMEOUT = 2000L
internal const val TIMEOUT = 5000L
internal const val MEDIUM_TIMEOUT = 10000L
internal const val LONG_TIMEOUT = 20000L
internal const val LONG_LONG_TIMEOUT = 50000L

internal enum class State {
    DISCONNECTING, DISCONNECTED, CONNECTING, CONNECTED, TTY_DETACHED
}

internal fun ByteArrayOutputStream.toUtf8String(): String = this.toString(StandardCharsets.UTF_8)

internal class MicroPythonExecutionException(message: String) : IOException(message)

/**
 * @author elmot, Lukas Kremla
 */
internal open class MpyComm(
    val project: Project,
    private val deviceService: MpyDeviceService
) : Disposable, Closeable {
    val ttyConnector: TtyConnector = WebSocketTtyConnector()

    val isConnected
        get() = mpyClient?.isConnected == true

    var state: State by Delegates.observable(State.DISCONNECTED) { _, _, newValue ->
        deviceService.stateListeners.forEach { it(newValue) }
    }

    internal var connectionParameters: ConnectionParameters = ConnectionParameters("http://192.168.4.1:8266", "")

    @Volatile
    private var mpyClient: MpyClient? = null

    internal val offTtyByteBuffer = ByteArrayOutputStream()
    private val webSocketMutex = Mutex()
    internal val outPipe = PipedWriter()
    private val inPipe = PipedReader(outPipe, 1000)

    // These variables are used by instantRun functionality
    // It works by redirecting the raw paste mode output directly to REPL when the commands start getting executed
    // This means that for the entire duration of the scripts execution the terminal remains in raw REPL
    // This should be exited either after all 3 EOT characters are captured - indicating the code finished executing
    // Or when invoking interrupt or soft reset
    // Alternatively, any doBlindExecute call will re-establish raw REPL and then clean up, also exiting it.
    private var shouldExitRawRepl = false
    private var foundEotCharacters = 0

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
    suspend fun upload(
        fullName: String,
        content: ByteArray,
        progressCallback: (uploadedBytes: Double) -> Unit,
        freeMemBytes: Int?
    ) {
        checkConnected()

        // Initialize the command list with necessary imports
        val setupCommands = mutableListOf("import os, gc")

        // Ensure parent directories are always created
        var slashIdx = 0
        while (slashIdx >= 0) {
            slashIdx = fullName.indexOf('/', slashIdx + 1)
            if (slashIdx > 0) {
                val folderName = fullName.substring(0, slashIdx)
                setupCommands.add("try: os.mkdir(\"$folderName\");")
                setupCommands.add("except: pass;")
            }
        }

        // Format the initial command for creating directories
        val setupCommand = setupCommands.joinToString("\n")

        // Expect at least 10 KB of free memory as a safe minimum
        if (freeMemBytes != null && freeMemBytes < 10000) {
            throw IOException("Insufficient free memory for upload. Please reset the device.")
        }

        val maxChunkSize = if (freeMemBytes != null) {
            // Use at most 80 percent of the free memory unless we'd be leaving more than 10 KB unused
            val freeMemBuffer = minOf(freeMemBytes / 5 * 4, 10000)
            // Leave the buffer free and use the rest of the memory
            freeMemBytes - freeMemBuffer
        } else {
            // Data about free memory is missing, don't chunk
            content.size
        }

        // Determine if using base64 encoding is possible and viable
        val shouldEncodeBase64 = deviceService.deviceInformation.canDecodeBase64 &&
                content.size > 256 &&
                content.count { b -> b in 32..127 } < content.size / 2

        var index = 0
        var endIndex = 0
        var isFirstChunk = true

        // Allocate a list for all the chunked upload commands
        val uploadCommands = mutableListOf<String>()

        if (content.isEmpty()) {
            uploadCommands.add("with open('$fullName', 'w'): pass")
        }

        while (index < content.size) {
            // If writing first chunk, truncate. Append otherwise
            val openMode = if (isFirstChunk) "wb" else "ab"

            if (shouldEncodeBase64) {
                // Calculate how many bytes can be written at once (base64 has a 33% overhead)
                val safeBytes = (maxChunkSize * 0.75).toInt()

                endIndex = minOf(index + safeBytes, content.size)

                val chunk = content.copyOfRange(index, endIndex)

                uploadCommands.add(binUpload(fullName, chunk, openMode))
            } else {
                val (txtCommand, size) = txtUpload(fullName, content, openMode, index, maxChunkSize)
                uploadCommands.add(txtCommand)
                endIndex += size
            }

            index = endIndex

            isFirstChunk = false
        }

        // Allocate a list for the final combined commands and add the setup command
        val commands = mutableListOf(setupCommand)

        // Remove the first upload command from the list and save it
        val firstUploadCommand = uploadCommands.removeFirst()

        // Combine the setupCommand with the first u    ploadCommand to avoid extra REPL executions
        commands[0] += "\n$firstUploadCommand"

        // Add there rest of the uploadCommands
        if (uploadCommands.isNotEmpty())
            commands.addAll(uploadCommands)

        val totalProgressCommandSize = commands.sumOf { it.toByteArray(Charsets.UTF_8).size }

        commands.forEach { command ->
            doBlindExecute(
                command,
                progressCallback = progressCallback,
                totalProgressCommandSize = totalProgressCommandSize,
                payloadSize = content.size,
                shouldStayDetached = true
            )
        }
    }

    suspend fun download(fileName: String): ByteArray {
        val canEncodeBase64 = deviceService.deviceInformation.canEncodeBase64

        // Prefer base64 over hex as it is more efficient
        val command = when (canEncodeBase64) {
            true -> retrieveMpyScriptAsString("download_file_base_64.py")
            else -> retrieveMpyScriptAsString("download_file_hex.py")
        }

        val result = blindExecute(command.format("\"$fileName\""))

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

    suspend fun setBaudrate(baudrate: Int) {
        checkConnected()
        webSocketMutex.withLock {
            if (mpyClient is MpySerialClient) {
                // Reconfigure the REPL to the specified baudrate
                doBlindExecute(
                    "import machine, time; machine.UART(0, baudrate=$baudrate); time.sleep(2)",
                    redirectToRepl = true
                )

                // Move back to TTY_DETACHED
                state = State.TTY_DETACHED

                // Reconfigure the JSSC to the specified baudrate
                (mpyClient as MpySerialClient).port.setParams(
                    baudrate,
                    SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE
                )

                // Clear the garbage output that switching baudrates causes
                offTtyByteBuffer.reset()

                // Return to CONNECTED state
                state = State.CONNECTED
            }
        }
    }

    override fun dispose() {
        close()
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

    internal suspend fun connect() {
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

    internal suspend fun disconnect() {
        webSocketMutex.withLock {
            state = State.DISCONNECTING
            mpyClient?.close()
            mpyClient = null
            state = State.DISCONNECTED
        }
    }

    internal fun setConnectionParams(parameters: ConnectionParameters) {
        this.connectionParameters = parameters
    }

    internal fun dataReceived(bytes: ByteArray) {
        when (state) {
            State.TTY_DETACHED, State.CONNECTING -> offTtyByteBuffer.writeBytes(bytes)

            else -> {
                if (shouldExitRawRepl) {
                    val count = bytes.toString(StandardCharsets.UTF_8).count { it == '\u0004' }
                    foundEotCharacters += count

                    if (foundEotCharacters > 2) {
                        ApplicationManager.getApplication().invokeLater {
                            runWithModalProgressBlocking(project, "Leaving instant run raw REPL") {
                                withTimeout(SHORT_TIMEOUT) {
                                    state = State.TTY_DETACHED
                                    // Interrupt running code
                                    mpyClient?.send("\u0003")
                                    mpyClient?.send("\u0002")
                                    if (state == State.TTY_DETACHED) {
                                        state = State.CONNECTED
                                    }
                                }
                            }
                        }

                        shouldExitRawRepl = false
                        foundEotCharacters = 0
                    }
                }

                ApplicationManager.getApplication().invokeLater {
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
    }

    internal suspend fun reset() {
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

                    if (shouldExitRawRepl) {
                        state = State.TTY_DETACHED
                        mpyClient?.send("\u0002")
                        if (state == State.TTY_DETACHED && mpyClient !is MpyWebSocketClient) {
                            state = State.CONNECTED
                        }
                        shouldExitRawRepl = false
                        foundEotCharacters = 0
                    }

                    // Soft reset
                    mpyClient?.send("\u0004")
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Timed out while performing reset", e)
            }
        }

        if (mpyClient is MpyWebSocketClient) {
            val mpyWebsocketClient = (mpyClient as MpyWebSocketClient)

            state = State.TTY_DETACHED

            // Disconnect WebREPL after reset
            mpyWebsocketClient.closeBlocking()

            reportRawProgress { reporter ->
                try {
                    mpyWebsocketClient.connect("Reconnecting WebREPL After Reset...")
                } catch (e: TimeoutCancellationException) {
                    Notifications.Bus.notify(
                        Notification(
                            NOTIFICATION_GROUP,
                            "Connection attempt timed out",
                            NotificationType.ERROR
                        ), project
                    )
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Notifications.Bus.notify(
                        Notification(
                            NOTIFICATION_GROUP,
                            "Connection attempt cancelled",
                            NotificationType.ERROR
                        ), project
                    )
                    throw e
                }
            }
        }
    }

    internal suspend fun interrupt() {
        checkConnected()
        webSocketMutex.withLock {
            try {
                withTimeout(SHORT_TIMEOUT) {
                    // Interrupt running code
                    mpyClient?.send("\u0003")

                    if (shouldExitRawRepl) {
                        state = State.TTY_DETACHED
                        mpyClient?.send("\u0002")
                        if (state == State.TTY_DETACHED) {
                            state = State.CONNECTED
                        }

                        shouldExitRawRepl = false
                        foundEotCharacters = 0
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Timed out while interrupting running code", e)
            }
        }
    }

    internal suspend fun recursivelySafeDeletePaths(paths: Set<String>) {
        val commands = mutableListOf(retrieveMpyScriptAsString("recursively_safe_delete_base.py"))

        val filteredPaths = paths.filter { path ->
            // Keep this path only if no other path is a prefix of it
            paths.none { otherPath ->
                otherPath != path && path.startsWith("$otherPath/")
            }
        }

        filteredPaths.forEach {
            commands.add("___m('${it}')")
        }

        commands.add("del ___m")
        commands.add("import gc")
        commands.add("gc.collect()")

        blindExecute(commands.joinToString("\n"), shouldStayDetached = true)
    }

    internal suspend fun safeCreateDirectories(paths: Set<String>) {
        val commands = mutableListOf(retrieveMpyScriptAsString("safe_create_directories_base.py"))

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
        commands.add("import gc")
        commands.add("gc.collect()")

        blindExecute(commands.joinToString("\n"), shouldStayDetached = true)
    }

    /**
     * Method for executing MicroPython scripts and commands over REPL
     *
     * @param command Command string to execute
     * @param shouldStayDetached Whether to redirect output back to REPL console after executing the command,
     * this is only used in complex operations involving multiple executions, such as uploads
     *
     * @return The execution result as a string
     *
     * @throws MicroPythonExecutionException when REPL returns a non-empty stderr result
     */
    internal suspend fun blindExecute(command: String, shouldStayDetached: Boolean = false): String {
        checkConnected()
        webSocketMutex.withLock {
            return doBlindExecute(command, shouldStayDetached = shouldStayDetached)
        }
    }

    internal suspend fun instantRun(command: String) {
        checkConnected()
        webSocketMutex.withLock {
            doBlindExecute(command, redirectToRepl = true)
        }
    }

    internal fun checkConnected() {
        when (state) {
            State.CONNECTED, State.TTY_DETACHED -> {}
            else -> throw IOException("Not connected")
        }
    }

    protected open fun createClient(): MpyClient {
        return if (connectionParameters.usingUart) MpySerialClient(this) else MpyWebSocketClient(this)
    }

    private fun txtUpload(
        fullName: String,
        content: ByteArray,
        openMode: String,
        index: Int,
        maxChunkSize: Int
    ): Pair<String, Int> {
        val maxSafeChunkSize = maxChunkSize
        var commandSize = 200 // Safe overhead for the non ___f.write() calls

        var contentSize = 0
        val commands = mutableListOf("___f=open('$fullName','$openMode')")
        val chunk = StringBuilder()
        val maxDataChunk = 400
        var contentIdx = index // Start at the index given
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

            val writeCommand = "___f.write(b'$chunk')"
            val writeCommandSize = writeCommand.toByteArray(Charsets.UTF_8).size

            // Avoid appending the write command if it would exceed the total maxSafeChunkSize
            if ((commandSize + writeCommandSize) > maxSafeChunkSize) break

            contentSize += chunkSize // This chunk will be written, extend contentSize value
            commandSize += writeCommandSize // This chunk will be written, extend commandSize value
            commands.add(writeCommand)
        }
        commands.add("___f.close()")
        commands.add("del(___f)")
        commands.add("gc.collect()")

        val command = commands.joinToString("\n")

        return Pair(command, contentSize)
    }

    private fun binUpload(fullName: String, content: ByteArray, openMode: String): String {
        val commands = mutableListOf(
            "import binascii",
            "___e=lambda b:___f.write(binascii.a2b_base64(b))",
            "___f=open('$fullName','$openMode')"
        )
        val maxDataChunk = 384
        var contentIdx = 0
        val encoder = Base64.getEncoder()
        while (contentIdx < content.size) {
            val len = maxDataChunk.coerceAtMost(content.size - contentIdx)
            val chunk = encoder.encodeToString(content.copyOfRange(contentIdx, contentIdx + len))
            contentIdx += len
            commands.add("___e('$chunk')")
        }
        commands.add("___f.close()")
        commands.add("del ___f,___e")
        commands.add("gc.collect()")

        val command = commands.joinToString("\n")

        return command
    }

    /**
     * Method for executing MicroPython scripts and commands over REPL
     * This method is only intended to be called internally in MpyComm
     *
     * @param command Command string to execute
     * @param progressCallback An optional callback for incremental progress reporting
     * @param payloadSize The total size of the payload for progress reporting (i.e., file size)
     * @param redirectToRepl If enabled, command execution output won't be collected, and it will instead be sent to the REPL terminal
     *
     * @return The execution result as a string
     *
     * @throws MicroPythonExecutionException when REPL returns a non-empty stderr result
     */
    private suspend fun doBlindExecute(
        command: String,
        progressCallback: ((uploadedBytes: Double) -> Unit)? = null,
        totalProgressCommandSize: Int = 0,
        payloadSize: Int = 0,
        redirectToRepl: Boolean = false,
        shouldStayDetached: Boolean = false
    ): String {
        var stayInTtyDetached = shouldStayDetached

        // Redirect REPL input from the terminal view
        state = State.TTY_DETACHED

        // Ensure the buffer is clean
        offTtyByteBuffer.reset()

        // Reset raw repl instantRun state variables
        shouldExitRawRepl = false
        foundEotCharacters = 0

        try {
            try {
                // Allow enough time for potential retries
                withTimeout(LONG_TIMEOUT) {
                    // Retry if an exception arises
                    retry(3, listOf(0L, 1000L, 3000L)) {
                        // Interrupt all running code by sending Ctrl-C, (send 3 as defensive programming)
                        mpyClient?.send("\u0003")
                        mpyClient?.send("\u0003")
                        mpyClient?.send("\u0003")

                        // Attempt to enter raw REPL by sending Ctrl-A
                        mpyClient?.send("\u0001")

                        // Wait until raw-REPL is ready (the prompt will contain the below suffix)
                        try {
                            withTimeout(TIMEOUT) {
                                while (!offTtyByteBuffer.toUtf8String().endsWith("\n>")) {
                                    checkCanceled()
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            throw IOException("Timed out waiting for raw REPL being ready")
                        }

                        // Clear the buffer before establishing a raw paste mode handshake
                        offTtyByteBuffer.reset()

                        // Initiate the b"\x05A\x01" (Ctrl-E + A + Ctrl-A) raw paste mode handshake
                        mpyClient?.send("\u0005A\u0001")

                        // Wait for the buffer to contain at least 2 raw paste mode response bytes
                        try {
                            withTimeout(TIMEOUT) {
                                while (offTtyByteBuffer.size() < 2) {
                                    checkCanceled()
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            throw IOException("Timed out waiting for raw paste mode response")
                        }

                        // Read exactly two bytes that indicate whether raw paste mode was established successfully
                        val resultBytes = readAndDiscardXBytesFromBuffer(2)

                        val b0 = resultBytes[0]
                        val b1 = resultBytes[1]

                        when {
                            // The Device supports raw paste and has entered it
                            b0 == 0x52.toByte() && b1 == 0x01.toByte() -> {
                                // Raw paste mode established successfully
                            }

                            // The Device understands the command but doesn't support raw paste,
                            // or it doesn't even know about it
                            b0 == 0x52.toByte() && b1 == 0x00.toByte() ||
                                    b0 == 0x72.toByte() && b1 == 0x61.toByte()
                                -> {
                                throw IOException("Device failed to enter raw paste mode. Please try again and if it doesn't help open an issue on our GitHub.")
                            }

                            // Unknown response
                            else -> {
                                throw IOException(
                                    ("Unknown raw paste response: \"b0=0x%02X b1=0x%02X\". " +
                                            "Please try again and if it doesn't help open an issue on our GitHub.").format(
                                        b0,
                                        b1
                                    )
                                )
                            }
                        }

                        // Wait for the buffer to contain at least 2 initial flow control bytes
                        try {
                            withTimeout(TIMEOUT) {
                                while (offTtyByteBuffer.size() < 2) {
                                    checkCanceled()
                                }
                            }
                        } catch (_: TimeoutCancellationException) {
                            throw IOException("Timed out waiting for raw paste mode flow control bytes")
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw IOException("Timed out while establishing raw paste mode")
            }

            // Read two flow control bytes
            val bytes = readAndDiscardXBytesFromBuffer(2)
            // This is the flow control window-size-increment in bytes
            // stored as a 16 unsigned little endian integer
            val flowControlWindowSize = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
            // The initial value for the remaining-window-size variable should be set to this number
            var remainingFlowControlWindowSize = flowControlWindowSize

            // Convert the command to a bytearray
            val commandBytes = command.toByteArray(Charsets.UTF_8)

            // Calculate an approximate progress-per-byte ratio for reporting upload progress proportionally to the sent data.
            // This avoids tracking precise writes, which would impact performance,
            // while still providing consistent and accurate enough progress reporting
            val singleByteProgress = if (progressCallback != null && payloadSize > 0) {
                payloadSize.toDouble() / totalProgressCommandSize.toDouble()
            } else null

            var index = 0

            while (index < commandBytes.size) {
                if (remainingFlowControlWindowSize <= 0) {
                    // Wait until the buffer contains at least one flow control byte
                    try {
                        withTimeout(TIMEOUT) {
                            while (offTtyByteBuffer.size() < 1) {
                                checkCanceled()
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw IOException("Timed out waiting for raw paste mode flow control bytes: $e")
                    }

                    // Read one flow control byte
                    val bytes = readAndDiscardXBytesFromBuffer(1)

                    // 0x01 (Ctrl-A) flow control window size can be extended
                    if (bytes[0] == 0x01.toByte()) {
                        remainingFlowControlWindowSize += flowControlWindowSize
                        // 0x04 (Ctrl-D) device wants to abort raw paste mode
                    } else if (bytes[0] == 0x04.toByte()) {
                        // Acknowledge (Ctrl-D)
                        mpyClient?.send("\u0004")
                        throw IOException(
                            "Device aborted raw paste mode. " +
                                    "The device's memory might be fragmented or insufficient for this operation. " +
                                    "Please reset the board and retry."
                        )
                    }
                }

                val endIndex = minOf(index + remainingFlowControlWindowSize, commandBytes.size)

                // Get chunk
                val chunk = commandBytes.copyOfRange(index, endIndex)

                // Send chunk
                withTimeout(TIMEOUT) {
                    mpyClient?.send(chunk)
                }

                // Report progress if applicable
                if (progressCallback != null && singleByteProgress != null) {
                    // Calculate this chunk's progress by converting it back to actual payload size progress
                    val progress = chunk.size * singleByteProgress
                    progressCallback(progress)
                }

                // Increment index
                index = endIndex

                // Decrease the remaining flow control window size
                remainingFlowControlWindowSize -= chunk.size
            }

            // Indicate the end of transmission (Ctrl-D)
            withTimeout(TIMEOUT) {
                mpyClient?.send("\u0004")
            }

            // No output should be collected, return early
            if (redirectToRepl) {
                return ""
            }

            // The device is executing the command now, once done it will have output 3 EOT (Ctrl-D) characters
            // and the buffer will end with "\u0004>" (Ctrl-D + >)
            try {
                withTimeout(LONG_LONG_TIMEOUT) {
                    while (!(offTtyByteBuffer.toUtf8String().endsWith("\u0004>") && offTtyByteBuffer.toUtf8String()
                            .count { it == '\u0004' } == 3)
                    ) checkCanceled()
                }
            } catch (_: TimeoutCancellationException) {
                throw IOException("Timed out while executing command: $command")
            }

            // Decode the output
            val collectedOutput = offTtyByteBuffer.toUtf8String()

            var currIndex = 0
            // Collect EOT (Ctrl-D) positions into a list
            val foundEotCharacterIndexes = mutableListOf<Int>()
            // Iterate over all characters and locate EOT (Ctrl-D) characters
            while (currIndex < collectedOutput.length) {
                val foundIndex = collectedOutput.indexOf("\u0004", currIndex)
                if (foundIndex == -1) break
                foundEotCharacterIndexes.add(foundIndex)
                currIndex = foundIndex + 1
            }

            // Stdout is everything between the first and second EOT (Ctrl-D)
            val stdout = collectedOutput
                .substring(foundEotCharacterIndexes[0] + 1, foundEotCharacterIndexes[1]).trim()
            // Stderr is everything between the second and third EOT (Ctrl-D)
            val stderr = collectedOutput
                .substring(foundEotCharacterIndexes[1] + 1, foundEotCharacterIndexes[2]).trim()

            if (stderr.isNotBlank()) throw MicroPythonExecutionException(stderr)

            // Return the output string
            return stdout
        } catch (e: CancellationException) {
            stayInTtyDetached = false
            throw e
        } catch (e: MicroPythonExecutionException) {
            // MicroPython command error, meant to bubble up and not disconnect
            stayInTtyDetached = false
            throw e
        } catch (e: Throwable) {
            // Unhandled exception occurred, disconnect
            state = State.DISCONNECTED
            mpyClient?.close()
            mpyClient = null
            throw e
        } finally {
            withContext(NonCancellable) {
                // If state isn't TTY_DETACHED an unhandled exception caused a disconnection before
                if (state == State.TTY_DETACHED) {
                    // Only leave raw REPL (Ctrl-B) if we're not meant to collect the command output
                    if (!redirectToRepl) {
                        mpyClient?.send("\u0002")
                        offTtyByteBuffer.reset()
                    } else {
                        // Raw REPL wasn't exited now, it should be in the future should some communication happen
                        // outside doBlindExecute
                        shouldExitRawRepl = true
                    }
                    // Clear the buffer
                    offTtyByteBuffer.reset()

                    // Return to the CONNECTED state
                    if (!stayInTtyDetached) {
                        state = State.CONNECTED
                    }
                }
            }
        }
    }

    private fun readAndDiscardXBytesFromBuffer(byteCount: Int): ByteArray {
        val readBytes = offTtyByteBuffer.toByteArray().copyOfRange(0, byteCount)
        val remainingBytes = offTtyByteBuffer.toByteArray().copyOfRange(byteCount, offTtyByteBuffer.toByteArray().size)

        offTtyByteBuffer.reset()
        if (remainingBytes.isNotEmpty()) {
            offTtyByteBuffer.writeBytes(remainingBytes)
        }

        return readBytes
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
            } catch (e: TimeoutCancellationException) {
                if (exception == null) exception = e
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

    private inner class WebSocketTtyConnector : TtyConnector {
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
}