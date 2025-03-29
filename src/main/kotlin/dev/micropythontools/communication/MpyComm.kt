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
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.Strings
import com.intellij.util.ExceptionUtil
import com.intellij.util.text.nullize
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.ConnectionParameters
import dev.micropythontools.ui.FileSystemWidget
import dev.micropythontools.ui.NOTIFICATION_GROUP
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.NonNls
import java.io.Closeable
import java.io.IOException
import java.io.PipedReader
import java.io.PipedWriter
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.properties.Delegates

/**
 * @author elmot
 */
private const val BOUNDARY = "*********FSOP************"

internal const val SHORT_TIMEOUT = 2000L
internal const val TIMEOUT = 5000L
internal const val LONG_TIMEOUT = 20000L
internal const val LONG_LONG_TIMEOUT = 600000L
internal const val SHORT_DELAY = 20L

data class SingleExecResponse(
    val stdout: String, val stderr: String
)

typealias ExecResponse = List<SingleExecResponse>

enum class State {
    DISCONNECTING, DISCONNECTED, CONNECTING, CONNECTED, TTY_DETACHED
}

typealias StateListener = (State) -> Unit

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

open class MpyComm(private val fileSystemWidget: FileSystemWidget) : Disposable, Closeable {
    private val settings = fileSystemWidget.project.service<MpySettingsService>()

    val stateListeners = mutableListOf<StateListener>()

    @Volatile
    private var mpyClient: MpyClient? = null

    protected open fun isTtySuspended(): Boolean = state == State.TTY_DETACHED

    private var offTtyBuffer = StringBuilder()

    private val webSocketMutex = Mutex()

    private val outPipe = PipedWriter()

    private val inPipe = PipedReader(outPipe, 1000)

    internal var connectionParameters: ConnectionParameters = ConnectionParameters("http://192.168.4.1:8266", "")

    val ttyConnector: TtyConnector = WebSocketTtyConnector()

    var state: State by Delegates.observable(State.DISCONNECTED) { _, _, newValue ->
        stateListeners.forEach { it(newValue) }
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
     * This method does not handle directory creation, all necessary directories must be created before hand other wise the upload will fail
     */
    @Throws(IOException::class, CancellationException::class, TimeoutCancellationException::class)
    suspend fun upload(fullName: @NonNls String, content: ByteArray, progressCallback: (uploadedBytes: Int) -> Unit) {
        checkConnected()
        val commands = mutableListOf<Any>("import os")
        if (fileSystemWidget.deviceInformation.hasBase64 && content.size > 100 && content.count { b -> b in 32..127 } < content.size / 2) {
            commands.addAll(binUpload(fullName, content))
        } else {
            commands.addAll(txtUpload(fullName, content))
        }

        commands.add("print(os.stat('$fullName'))")

        val result = webSocketMutex.withLock {
            doBlindExecute(LONG_TIMEOUT, commands, progressCallback = progressCallback)
        }
        val error = result.mapNotNull { Strings.nullize(it.stderr) }.joinToString(separator = "\n", limit = 1000)
        if (error.isNotEmpty()) {
            throw IOException(error)
        }
        val fileData = result.last().stdout.split('(', ')', ',').map { it.trim().toIntOrNull() }
        if (fileData.getOrNull(7) != content.size) {
            throw IOException("Expected size is ${content.size}, uploaded ${fileData[5]}")
        } else if (fileData.getOrNull(1) != 32768) {
            throw IOException("Expected type is 32768, uploaded ${fileData[1]}")
        }
    }

    private fun txtUpload(fullName: @NonNls String, content: ByteArray): List<Any> {
        val commands = mutableListOf<Any>("___f=open('$fullName','wb')")
        val chunk = StringBuilder()
        val maxDataChunk = 220
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
        val maxDataChunk = 120
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

    @Throws(IOException::class)
    private suspend fun doBlindExecute(
        commandTimeout: Long,
        commands: List<Any>,
        progressCallback: ((uploadedBytes: Int) -> Unit)? = null
    ): ExecResponse {
        state = State.TTY_DETACHED
        try {
            withTimeout(LONG_TIMEOUT) {
                do {
                    var promptNotReady = true
                    mpyClient?.send("\u0003")
                    mpyClient?.send("\u0003")
                    mpyClient?.send("\u0003")
                    delay(SHORT_DELAY)
                    mpyClient?.send("\u0001")
                    withTimeoutOrNull(SHORT_TIMEOUT) {
                        while (!offTtyBuffer.endsWith("\n>")) {
                            delay(SHORT_DELAY)
                        }
                        promptNotReady = false
                    }
                } while (promptNotReady)
            }
            delay(SHORT_DELAY)
            offTtyBuffer.clear()
            val result = mutableListOf<SingleExecResponse>()

            for (command in commands) {
                try {
                    var toExecute = when (command) {
                        is String -> {
                            command
                        }

                        is Pair<*, *> -> {
                            val cmd = command.first
                            val size = command.second

                            if (cmd is String && (size == null || size is Int)) {
                                if (size != null && progressCallback != null) {
                                    progressCallback(size)
                                }
                                cmd
                            } else {
                                throw IllegalArgumentException("Expected Pair<String, Int?> but got ${command::class.java}")
                            }
                        }

                        else -> {
                            throw IllegalArgumentException("Unexpected command type: ${command::class.java}")
                        }
                    }

                    // Increase the timeout dynamically using WebREPL
                    val adjustedTimeout = when {
                        !settings.state.usingUart -> {
                            // The timeout should accommodate for the additional time WebREPL requires
                            // due to the MPY WebREPL overflow-preventing delays
                            // Add an extra 200 ms tolerance at the end for good measure
                            commandTimeout + (toExecute.toByteArray(Charsets.UTF_8).size / 255 * 300) + 200
                        }

                        else -> commandTimeout
                    }

                    withTimeout(adjustedTimeout) {
                        sendCommand(toExecute)

                        while (!(offTtyBuffer.startsWith("OK") && offTtyBuffer.endsWith("\u0004>") && offTtyBuffer.count { it == '\u0004' } == 2)) {
                            delay(SHORT_DELAY)
                        }
                        val eotPos = offTtyBuffer.indexOf('\u0004')
                        val stdout = offTtyBuffer.substring(2, eotPos).trim()
                        val stderr = offTtyBuffer.substring(eotPos + 1, offTtyBuffer.length - 2).trim()
                        result.add(SingleExecResponse(stdout, stderr))
                        offTtyBuffer.clear()
                    }
                } catch (e: TimeoutCancellationException) {
                    throw IOException("Timeout during command execution:$command", e)
                }
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
            mpyClient?.send("\u0002")
            offTtyBuffer.clear()
            if (state == State.TTY_DETACHED) {
                state = State.CONNECTED
            }
        }
    }

    // Helper method to send a command line by line
    private suspend fun sendCommand(command: String) {
        if (settings.state.usingUart) {
            command.lines().forEach { line ->
                mpyClient?.send("$line\n")
                delay(SHORT_DELAY)
            }
        } else {
            command.lines().map { "$it\n" }.forEach { line ->
                // Convert to bytes for precise measuring
                val bytes = line.toByteArray(Charsets.UTF_8)
                var index = 0

                while (index < bytes.size) {
                    // Iterate over chunks of 255 bytes or whatever size remains
                    val endIndex = minOf(index + 255, bytes.size)

                    // Get chunk
                    val chunk = bytes.copyOfRange(index, endIndex)

                    // Convert back to string before sending
                    val chunkStr = String(chunk, Charsets.UTF_8)

                    // Send and wait
                    mpyClient?.send(chunkStr)
                    print(chunkStr)
                    delay(200)

                    index = endIndex
                }
            }
        }

        mpyClient?.send("\u0004")
    }

    fun checkConnected() {
        when (state) {
            State.CONNECTED -> {}
            State.DISCONNECTING, State.DISCONNECTED, State.CONNECTING -> throw IOException("Not connected")
            State.TTY_DETACHED -> throw IOException("Websocket is busy")
        }
    }

    @Throws(IOException::class)
    suspend fun blindExecute(commandTimeout: Long, vararg commands: String): ExecResponse {
        checkConnected()
        webSocketMutex.withLock {
            if (commands.size > 1) {
                val scriptContent = commands.joinToString("\n")
                return doBlindExecute(commandTimeout, listOf(scriptContent))
            }
            return doBlindExecute(commandTimeout, commands.toList())
        }
    }

    @Throws(IOException::class)
    suspend fun instantRun(command: @NonNls String) {
        checkConnected()
        webSocketMutex.withLock {
            state = State.TTY_DETACHED
            try {
                mpyClient?.send("\u0003")
                mpyClient?.send("\u0003")
                mpyClient?.send("\u0003")
                mpyClient?.send("\u0005")
                while (!offTtyBuffer.contains("===")) {
                    delay(SHORT_DELAY)
                }
                command.lines().forEach {
                    offTtyBuffer.clear()
                    mpyClient?.send("$it\n")
                    offTtyBuffer.clear()
                    delay(SHORT_DELAY)
                }
                mpyClient?.send("#$BOUNDARY\n")
                while (!offTtyBuffer.contains(BOUNDARY)) {
                    delay(SHORT_DELAY)
                }
                offTtyBuffer.clear()
            } finally {
                if (state == State.TTY_DETACHED) {
                    state = State.CONNECTED
                }
                mpyClient?.send("\u0004")
            }
        }
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

    @Throws(IOException::class)
    suspend fun connect() {
        val name = with(connectionParameters) {
            if (usingUart) portName else webReplUrl
        }
        state = State.CONNECTING
        offTtyBuffer.clear()
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
            mpyClient?.closeBlocking()
            mpyClient = null
            state = State.DISCONNECTED
        }
    }

    fun ping() {
        if (state == State.CONNECTED) {
            mpyClient?.sendPing()
        }
    }

    fun setConnectionParams(parameters: ConnectionParameters) {
        this.connectionParameters = parameters
    }

    open fun dataReceived(s: String) {
        when (state) {
            State.TTY_DETACHED, State.CONNECTING -> offTtyBuffer.append(s)
            else -> {
                runBlocking {
                    outPipe.write(s)
                    outPipe.flush()
                }
            }
        }
    }

    fun reset() {
        mpyClient?.send("\u0003")
        mpyClient?.send("\u0003")
        mpyClient?.send("\u0004")
    }

    fun interrupt() {
        mpyClient?.send("\u0003")
    }

    suspend fun download(fileName: @NonNls String): ByteArray {
        val command = """
with open('$fileName','rb') as f:
    while 1:
          b=f.read(50)
          if not b:break
          print(b.hex())
"""
        val result = blindExecute(LONG_LONG_TIMEOUT, command).extractSingleResponse()
        return result.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

}