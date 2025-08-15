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
import com.intellij.openapi.progress.checkCanceled
import com.intellij.platform.util.progress.withProgressText
import com.intellij.util.io.toByteArray
import dev.micropythontools.i18n.MpyBundle
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.connection.ConnectionException
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

// Constants
private const val PASSWORD_PROMPT = "Password:"
private const val LOGIN_SUCCESS = "WebREPL connected"
private const val LOGIN_FAIL = "Access denied"

internal open class MpyWebSocketClient(private val comm: MpyComm) : MpyClient {
    // Properties
    override val isConnected: Boolean
        get() = webSocketClient?.isOpen ?: false

    @Volatile
    var resetInProgress = false

    @Volatile
    private var connectInProgress = true

    private val loginBuffer = StringBuffer()
    private var webSocketClient: WebSocketClient? = null

    // Protected methods
    protected open fun open() = Unit
    protected open fun close(code: Int, reason: String, remote: Boolean) = Unit
    protected open fun error(ex: Exception) = Unit
    protected open fun message(message: String) = Unit

    // Methods
    override suspend fun connect(progressIndicatorText: String): MpyWebSocketClient {
        var retryCount = 0

        try {
            retryCount += 1

            withTimeout(MEDIUM_TIMEOUT) {
                do {
                    checkCanceled()
                    try {
                        loginBuffer.setLength(0)
                        connectInProgress = true
                        webSocketClient = createWebSocketClient()
                        webSocketClient?.isTcpNoDelay = true
                        webSocketClient?.connectionLostTimeout = 0
                        webSocketClient?.connect()
                        try {
                            var time = TIMEOUT
                            withProgressText(progressIndicatorText) {
                                while (!isConnected && time > 0) {
                                    checkCanceled()
                                    if (webSocketClient?.connectionErrorMessage != null) {
                                        throw ConnectionException("WebREPL connection failed: ${webSocketClient?.connectionErrorMessage}")
                                    }
                                    delay(SHORT_DELAY)
                                    time -= SHORT_DELAY.toInt()
                                }
                                if (!isConnected) {
                                    throw ConnectException("WebREPL connection failed")
                                }
                            }
                            withTimeout(SHORT_TIMEOUT) {
                                while (isConnected) {
                                    checkCanceled()
                                    when {
                                        loginBuffer.length < PASSWORD_PROMPT.length -> delay(SHORT_DELAY)
                                        loginBuffer.length > PASSWORD_PROMPT.length * 2 -> {
                                            loginBuffer.setLength(PASSWORD_PROMPT.length * 2)
                                            throw ConnectException("Password exchange error. Received prompt: $loginBuffer")
                                        }

                                        loginBuffer.toString().contains(PASSWORD_PROMPT) -> break
                                        else -> throw ConnectException("Password exchange error. Received prompt: $loginBuffer")
                                    }
                                }
                                loginBuffer.setLength(0)
                                send("${comm.connectionParameters.webReplPassword}\n")
                                while (connectInProgress && isConnected) {
                                    checkCanceled()
                                    when {
                                        loginBuffer.contains(LOGIN_SUCCESS) -> break
                                        loginBuffer.contains(LOGIN_FAIL) -> throw ConnectException("Access denied")
                                        else -> delay(SHORT_DELAY)
                                    }
                                }
                                connectInProgress = false
                                comm.state = State.CONNECTED
                            }
                        } catch (e: Exception) {
                            try {
                                close()
                            } catch (_: IOException) {
                            }
                            connectInProgress = false
                            if (!resetInProgress) {
                                comm.state = State.DISCONNECTED
                            }
                            webSocketClient?.reset()
                            when (e) {
                                is TimeoutCancellationException -> throw ConnectException("Password exchange timeout. Received prompt: $loginBuffer")
                                is InterruptedException -> throw ConnectException("Connection interrupted")
                                else -> throw e
                            }
                        } finally {
                            connectInProgress = false
                            loginBuffer.setLength(0)
                            loginBuffer.trimToSize()
                        }
                    } catch (_: ConnectException) {
                        // pass
                    }
                } while (webSocketClient?.isOpen != true && comm.state != State.CONNECTED && retryCount < 5)
            }
        } catch (e: Throwable) {
            comm.state = State.DISCONNECTED
            throw e
        }

        return this
    }

    override fun send(string: String) = webSocketClient?.send(string) ?: Unit

    override fun send(bytes: ByteArray) {
        // The WebSocket send(ByteArray) implementation doesn't work well with WebREPL
        // Converting the binary data to a string before sending will make sure WebREPL recognizes it
        webSocketClient?.send(String(bytes.map { it.toInt().toChar() }.toCharArray()))
    }

    override fun hasPendingData(): Boolean = webSocketClient?.hasBufferedData() ?: false

    override fun close() = webSocketClient?.close() ?: Unit

    override fun closeBlocking() = webSocketClient?.closeBlocking() ?: Unit

    // Private methods
    private fun createWebSocketClient(): WebSocketClient {
        return object : WebSocketClient(URI(comm.connectionParameters.webReplUrl)) {
            override fun onOpen(handshakedata: ServerHandshake) = open() //Nothing to do

            // The custom Java-Websocket modification never uses the onMessage with string param
            override fun onMessage(p0: String?) = Unit

            override fun onMessage(byteBuffer: ByteBuffer) {
                val byteArray = byteBuffer.toByteArray()

                val message = try {
                    byteArray.toString(StandardCharsets.UTF_8)
                } catch (_: Throwable) {
                    null
                }

                message?.let { this@MpyWebSocketClient.message(it) }

                if (connectInProgress && message != null) {
                    loginBuffer.append(byteArray.toString(StandardCharsets.UTF_8))
                } else {
                    comm.dataReceived(byteArray)
                }
            }

            override fun onError(ex: Exception) {
                error(ex)
                comm.errorLogger(ex)
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                close(code, reason, remote)
                try {
                    if (remote && comm.state == State.CONNECTED) {
                        //Counterparty closed the connection
                        Notifications.Bus.notify(
                            Notification(
                                MpyBundle.message("notification.group.name"),
                                "WebREPL connection error - Code:$code ($reason)",
                                NotificationType.ERROR
                            ), comm.project
                        )
                        throw IOException("Connection closed. Code:$code ($reason)")
                    }
                } finally {
                    if (!resetInProgress) {
                        connectInProgress = false
                        comm.state = State.DISCONNECTED
                    }
                }
            }
        }
    }
}