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

import com.fazecast.jSerialComm.SerialPort
import com.fazecast.jSerialComm.SerialPortDataListener
import com.fazecast.jSerialComm.SerialPortEvent
import com.intellij.openapi.progress.checkCanceled
import dev.micropythontools.communication.MpyComm.Companion.retry
import dev.micropythontools.i18n.MpyBundle
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.charset.StandardCharsets

private const val SERIAL_PROBE_STRING = "__MPY_TOOLS_SERIAL_PROBE_9x7k13A1ds56Sd__"

internal fun SerialPort.openPortOrThrow() {
    if (!this.openPort()) {
        val errorCode = this.lastErrorCode
        val errorLocation = this.lastErrorLocation
        val portName = this.systemPortName

        val errorMessage = when (errorCode) {
            // 1 Additional permission issue (Linux: EPERM)
            // 5, 13 Permission denied (Linux/macOS: EACCES, Windows: ERROR_ACCESS_DENIED)
            1, 5, 13 -> MpyBundle.message("comm.error.serial.access.denied", portName)

            // 16  Device busy/in use (Linux: EBUSY, macOS: similar)
            // 32 Sharing violation (Windows: port in use by another process)
            16, 32 -> MpyBundle.message("comm.error.serial.port.busy", portName)

            // 2 Port not found (Linux/macOS: ENOENT)
            // 6 Invalid handle (Windows: ERROR_INVALID_HANDLE)
            2, 6 -> MpyBundle.message("comm.error.serial.port.not.found", portName)

            // Zero or negative (shouldn't happen, but defensive)
            else -> MpyBundle.message("comm.error.serial.unknown", portName, errorCode, errorLocation)
        }

        throw IOException(errorMessage)
    }
}

internal class MpySerialClient(private val comm: MpyComm, private val connectionParameters: ConnectionParameters) :
    MpyClient {
    // Subtract the part between delimiters
    private fun String.countOccurrencesOf(sub: String) = split(sub).size - 1

    val port: SerialPort = SerialPort.getCommPort(connectionParameters.portName).also {
        it.allowElevatedPermissionsRequest()
    }

    override val isConnected: Boolean
        get() = try {
            port.isOpen && port.bytesAvailable() >= 0
        } catch (_: Exception) {
            false
        }

    override val name: String
        get() = connectionParameters.portName

    private val listener = object : SerialPortDataListener {
        override fun getListeningEvents() = SerialPort.LISTENING_EVENT_DATA_AVAILABLE

        override fun serialEvent(event: SerialPortEvent) {
            val available = port.bytesAvailable()
            if (available > 0) {
                val bytes = ByteArray(available)
                val numRead = port.readBytes(bytes, bytes.size)
                if (numRead > 0) {
                    val actualBytes = if (numRead < bytes.size) bytes.copyOf(numRead) else bytes
                    comm.dataReceived(actualBytes)
                }
            }
        }
    }

    @Throws(IOException::class)
    override suspend fun connect(progressIndicatorText: String): MpySerialClient {
        try {
            port.openPortOrThrow()

            port.setComPortParameters(
                115200,
                8,
                SerialPort.ONE_STOP_BIT,
                SerialPort.NO_PARITY
            )
            port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)

            // Add event listener before setting timeouts (listener will override timeout behavior)
            port.addDataListener(listener)

            try {
                retry(3, listOf(0L, 1000L, 3000L)) {
                    // Ensure we start off with a clean state
                    comm.offTtyByteBuffer.reset()

                    // Interrupt all running code by sending Ctrl-C, (send 3 as defensive programming)
                    send("\u0003")
                    send("\u0003")
                    send("\u0003")

                    // Send MicroPython installation verification probe
                    send("print(\"$SERIAL_PROBE_STRING\")\r\n")

                    // Check to see if MicroPython is installed and responsive
                    try {
                        // If the probe string isn't printed, it means there is no MicroPython REPL on this serial connection
                        // Checks for both the echoed print command and the print output (two occurrences)
                        withTimeout(SHORT_TIMEOUT) {
                            while (comm.offTtyByteBuffer
                                    .toUtf8String()
                                    .countOccurrencesOf(SERIAL_PROBE_STRING) < 2
                            ) {
                                checkCanceled()
                            }
                        }

                        // Reset the buffer back to a clean state
                        comm.offTtyByteBuffer.reset()
                    } catch (_: TimeoutCancellationException) {
                        throw IOException(MpyBundle.message("comm.error.micropython.not.installed"))
                    }
                }
            } catch (e: Throwable) {
                // Ensure the port is closed if connection fails
                port.closePort()
                throw e
            }

            comm.state = State.CONNECTED
            return this
        } catch (e: Exception) {
            throw IOException(
                MpyBundle.message(
                    "comm.error.serial.connection.failed",
                    port.systemPortName,
                    e.localizedMessage
                )
            )
        }
    }

    override fun send(string: String) {
        val bytes = string.toByteArray(StandardCharsets.UTF_8)
        port.writeBytes(bytes, bytes.size)
    }

    override fun send(bytes: ByteArray) {
        port.writeBytes(bytes, bytes.size)
    }

    override fun hasPendingData(): Boolean = port.bytesAvailable() > 0

    override fun close() = closeBlocking()

    override fun closeBlocking() {
        port.removeDataListener()
        port.closePort()
    }
}