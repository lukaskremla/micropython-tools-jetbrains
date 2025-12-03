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

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.checkCanceled
import dev.micropythontools.communication.MpyComm.Companion.retry
import dev.micropythontools.i18n.MpyBundle
import jssc.SerialPort
import jssc.SerialPort.FLOWCONTROL_NONE
import jssc.SerialPortEventListener
import jssc.SerialPortException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.charset.StandardCharsets

private const val SERIAL_PROBE_STRING = "__MPY_TOOLS_SERIAL_PROBE_9x7k13A1ds56Sd__"

internal class MpySerialClient(private val comm: MpyComm) : MpyClient {
    // Subtract the part between delimiters
    private fun String.countOccurrencesOf(sub: String) = split(sub).size - 1

    val port = SerialPort(comm.connectionParameters.portName)

    override val isConnected: Boolean
        get() = try {
            port.getLinesStatus()
            port.isOpened && port.getInputBufferBytesCount() >= 0
        } catch (_: SerialPortException) {
            false
        }

    private val listener = SerialPortEventListener { event ->
        if (event.eventType and SerialPort.MASK_RXCHAR != 0) {
            val count = event.eventValue
            val bytes = port.readBytes(count)
            comm.dataReceived(bytes)
            this@MpySerialClient.thisLogger().debug("> ${bytes.toString(StandardCharsets.UTF_8)}")
        }
    }

    @Throws(IOException::class)
    override suspend fun connect(progressIndicatorText: String): MpySerialClient {
        try {
            port.openPort()
            port.addEventListener(listener, SerialPort.MASK_RXCHAR)
            port.setParams(
                SerialPort.BAUDRATE_115200,
                SerialPort.DATABITS_8,
                SerialPort.STOPBITS_1,
                SerialPort.PARITY_NONE
            )
            port.flowControlMode = FLOWCONTROL_NONE

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
        } catch (e: SerialPortException) {
            throw IOException("${e.port.portName}: ${e.exceptionType}")
        }
    }

    override fun send(string: String) {
        port.writeString(string)
    }

    override fun send(bytes: ByteArray) {
        port.writeBytes(bytes)
    }

    override fun hasPendingData(): Boolean = port.inputBufferBytesCount > 0

    override fun close() = closeBlocking()

    override fun closeBlocking() {
        port.closePort()
    }
}