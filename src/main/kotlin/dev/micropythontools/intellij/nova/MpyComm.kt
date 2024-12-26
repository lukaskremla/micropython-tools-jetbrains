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

package dev.micropythontools.intellij.nova

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.Strings
import com.intellij.util.ExceptionUtil
import com.intellij.util.text.nullize
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
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

internal const val TIMEOUT = 2000L
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

open class MpyComm() : Disposable, Closeable {

    val stateListeners = mutableListOf<StateListener>()

    @Volatile
    private var client: Client? = null

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

    @Throws(IOException::class, CancellationException::class, TimeoutCancellationException::class)
    suspend fun upload(fullName: @NonNls String, content: ByteArray) {
        checkConnected()
        val commands = mutableListOf<String>("import os,errno")
        var slashIdx = 0
        while (slashIdx >= 0) {
            slashIdx = fullName.indexOf('/', slashIdx + 1)
            if (slashIdx > 0) {
                val folderName = fullName.substring(0, slashIdx)
                commands.add(
                    """try: os.mkdir('$folderName');
except OSError as e:
    if e.errno != errno.EEXIST: raise """
                )
            }
        }
        if (content.size > 100 && content.count { b -> b in 32..127 } < content.size / 2) {
            commands.addAll(binUpload(fullName, content))
        } else {
            commands.addAll(txtUpload(fullName, content))
        }

        commands.add("print(os.stat('$fullName'))")


        val result = webSocketMutex.withLock {
            doBlindExecute(LONG_TIMEOUT, *commands.toTypedArray())
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

    private fun txtUpload(fullName: @NonNls String, content: ByteArray): List<String> {
        val commands = mutableListOf("__f=open('$fullName','wb')")
        val chunk = StringBuilder()
        val maxDataChunk = 220
        var contentIdx = 0
        while (contentIdx < content.size) {
            chunk.setLength(0)
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
            commands.add("__f.write(b'$chunk')")
        }
        commands.add("__f.close()")
        commands.add("del(__f)")
        return commands
    }

    private fun binUpload(fullName: @NonNls String, content: ByteArray): List<String> {
        val commands = mutableListOf(
            "import binascii",
            "__e=lambda b:__f.write(binascii.a2b_base64(b))",
            "__f=open('$fullName','wb')"
        )
        val maxDataChunk = 120
        assert(maxDataChunk % 4 == 0)
        var contentIdx = 0
        val encoder = Base64.getEncoder()
        while (contentIdx < content.size) {
            val len = maxDataChunk.coerceAtMost(content.size - contentIdx)
            val chunk = encoder.encodeToString(content.copyOfRange(contentIdx, contentIdx + len))
            contentIdx += len
            commands.add("__e('$chunk')")
        }
        commands.add("__f.close()")
        commands.add("del __f,__e")
        return commands
    }

    @Throws(IOException::class)
    private suspend fun doBlindExecute(commandTimeout: Long, vararg commands: String): ExecResponse {
        state = State.TTY_DETACHED
        return try {
            withTimeout(LONG_TIMEOUT) {
                do {
                    var promptNotReady = true
                    client?.send("\u0003")
                    client?.send("\u0003")
                    client?.send("\u0003")
                    delay(SHORT_DELAY)
                    client?.send("\u0001")
                    withTimeoutOrNull(TIMEOUT) {
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
                    withTimeout(commandTimeout) {
                        command.lines().forEachIndexed { index, s ->
                            if (index > 0) {
                                delay(SHORT_DELAY)
                            }
                            client?.send("$s\n")
                        }
                        client?.send("\u0004")
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
            client?.close()
            client = null
            throw e
        } finally {
            client?.send("\u0002")
            offTtyBuffer.clear()
            if (state == State.TTY_DETACHED) {
                state = State.CONNECTED
            }
        }
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
            return doBlindExecute(commandTimeout, *commands)
        }
    }

    @Throws(IOException::class)
    suspend fun instantRun(command: @NonNls String) {
        checkConnected()
        webSocketMutex.withLock {
            state = State.TTY_DETACHED
            try {
                client?.send("\u0003")
                client?.send("\u0003")
                client?.send("\u0003")
                client?.send("\u0005")
                while (!offTtyBuffer.contains("===")) {
                    delay(SHORT_DELAY)
                }
                command.lines().forEach {
                    offTtyBuffer.clear()
                    client?.send("$it\n")
                    offTtyBuffer.clear()
                    delay(SHORT_DELAY)
                }
                client?.send("#$BOUNDARY\n")
                while (!offTtyBuffer.contains(BOUNDARY)) {
                    delay(SHORT_DELAY)
                }
                offTtyBuffer.clear()
            } finally {
                if (state == State.TTY_DETACHED) {
                    state = State.CONNECTED
                }
                client?.send("\u0004")
            }
        }
    }


    override fun dispose() {
        close()
    }

    inner class WebSocketTtyConnector : TtyConnector {
        override fun getName(): String = connectionParameters.url
        override fun close() = Disposer.dispose(this@MpyComm)
        override fun isConnected(): Boolean = true
        override fun ready(): Boolean {
            return inPipe.ready() || client?.hasPendingData() == true
        }

        override fun resize(termSize: TermSize) = Unit

        override fun waitFor(): Int = 0

        override fun write(bytes: ByteArray) = write(bytes.toString(StandardCharsets.UTF_8))

        override fun write(text: String) {
            if (state == State.CONNECTED) {
                client?.send(text)
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
            client?.close()
            client = null
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
            client?.close()
        } catch (_: IOException) {
        }
        state = State.DISCONNECTED
    }

    protected open fun createClient(): Client {
        return if (connectionParameters.uart) SerialClient(this) else MpyWebSocketClient(this)
    }

    @Throws(IOException::class)
    suspend fun connect() {
        val name = with(connectionParameters) {
            if (uart) portName else url
        }
        state = State.CONNECTING
        offTtyBuffer.clear()
        webSocketMutex.withLock {
            try {
                client = createClient().connect("Connecting to $name")
            } catch (e: Exception) {
                state = State.DISCONNECTED
                throw e
            }
        }
    }

    suspend fun disconnect() {
        webSocketMutex.withLock {
            state = State.DISCONNECTING
            client?.closeBlocking()
            client = null
        }
    }

    fun ping() {
        if (state == State.CONNECTED) {
            client?.sendPing()
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
        client?.send("\u0003")
        client?.send("\u0003")
        client?.send("\u0004")
    }

    fun interrupt() {
        client?.send("\u0003")
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
        return result.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.chunked(2).map { it.toInt(16).toByte() }
            .toByteArray()
    }

}