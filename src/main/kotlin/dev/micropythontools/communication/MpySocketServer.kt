package dev.micropythontools.communication

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.util.progress.RawProgressReporter
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.util.MpyPythonService
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okio.IOException
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets

class MpySocketServer(project: Project) {
    private val settings = project.service<MpySettingsService>()
    private val pythonService = project.service<MpyPythonService>()
    private val deviceService = project.service<MpyDeviceService>()

    private var server: ServerSocket? = null
    private var client: Socket? = null
    private var reader: ByteReadChannel? = null
    private var writer: ByteWriteChannel? = null

    suspend fun establishConnection(reporter: RawProgressReporter) {
        withTimeout(TIMEOUT * 2) {
            establishMpyClient(reporter)

            server = aSocket(ActorSelectorManager(Dispatchers.IO))
                .tcp()
                .bind("0.0.0.0", 8765)

            client = server?.accept()

            reader = client?.openReadChannel()
            writer = client?.openWriteChannel(true)

            val byteArray = "PING".toByteArray(StandardCharsets.UTF_8)
            writer?.writeFully(byteArray, 0, byteArray.size)
            println("Sent ping")

            val line = reader?.readUTF8Line()

            if (line == "PONG") {
                println("Received pong")
            } else {
                throw IOException("Did not receive PONG response")
            }
        }
    }

    private suspend fun establishMpyClient(reporter: RawProgressReporter) {
        if (settings.state.usingUart) {
            reporter.text("Retrieving Wi-Fi credentials...")

            val wifiCredentials = settings.retrieveWifiCredentials()
            val ssid = wifiCredentials.userName
            val password = wifiCredentials.getPasswordAsString()

            if (ssid.isNullOrBlank()) {
                throw Exception("Cannot upload over network, no SSID was provided in settings! Falling back to standard uploads.")
            }

            reporter.text("Connecting to Wi-Fi...")

            teardownMpyClient()

            val wifiConnectScriptName = "connect_to_wifi.py"
            val wifiConnectScript = pythonService.retrieveMpyScriptAsString(wifiConnectScriptName)
            val formattedWifiConnectScript = wifiConnectScript.format(
                """"$ssid"""",
                """"$password"""",
                20, // Wi-Fi connection timeout
            )
            try {
                deviceService.blindExecute(formattedWifiConnectScript).extractSingleResponse()
            } catch (e: Throwable) {
                if (e.localizedMessage.contains("timed")) {
                    throw Exception("ERROR: Wi-fi connection attempt timed out")
                } else {
                    throw Exception("ERROR: There was a problem attempting to establish the wi-fi connection $e")
                }
            }
        }

        reporter.text("Establishing socket connection")

        println("Executing socket script")

        val localIp = InetAddress.getLocalHost().hostAddress

        val socketScript = pythonService.retrieveMpyScriptAsString("socket_transfer.py")
        val formattedSocketScript = socketScript.format(
            """"$localIp"""",
            8765
        )
        deviceService.instantRun(formattedSocketScript)

        println("\n")
        print(formattedSocketScript)
        println("\n")

        println("Executed socket script")
    }

    suspend fun teardownMpyClient() {
        return

        val socketUploadCleanupScript = pythonService.retrieveMpyScriptAsString("socket_transfer_cleanup.py")
        val formattedSocketUploadCleanupScript = socketUploadCleanupScript.format(
            if (settings.state.usingUart) "True" else "False"
        )

        print(formattedSocketUploadCleanupScript)

        deviceService.instantRun(formattedSocketUploadCleanupScript)
    }

    suspend fun uploadFile(path: String, bytes: ByteArray, progressCallback: (uploadedBytes: Int) -> Unit) {
        progressCallback(0) // Initial callback to force UI update before the upload fully sets off
        var line = ""
        withTimeout(TIMEOUT * 2) {
            launch {
                // Send the upload header with the file path and size
                val header = "UPLOAD:\"$path\"&SIZE:\"${bytes.size}\"".toByteArray(StandardCharsets.UTF_8)
                writer?.writeFully(header, 0, header.size)
                println("Sent header")

                line = reader?.readUTF8Line() ?: "No response"
                println("Received header response: \"$line\"")

                if (line != "CONTINUE") throw IOException("Missing upload header acknowledgement from socket")

                // Send the byteArray representation of the file
                writer?.writeFully(bytes, 0, bytes.size)
                println("Sent bytes ${bytes.size}")


                while (true) {
                    line = ""
                    line = reader?.readUTF8Line() ?: break
                    println("Read line: $line")

                    if (line.contains("WROTE")) {
                        println("Handling bytes written")
                        val bytesWritten = line.removePrefix("WROTE ").toInt()
                        progressCallback(bytesWritten)
                    } else if (line.contains("DONE")) {
                        break
                    } else if (line.contains("ERROR")) {
                        throw IOException("Socket Upload Error - $line")
                    }
                }
            }
        }
        println("Timed out / after loop")
        println(line)
    }

    suspend fun downloadFile(path: String): ByteArray? {
        var fileData: ByteArray? = null

        withTimeout(LONG_TIMEOUT) {
            launch {
                // Send the upload header with the file path and size
                val header = "DOWNLOAD:\"$path\"".toByteArray(StandardCharsets.UTF_8)
                writer?.writeFully(header, 0, header.size)
                println("Sent header")

                val line = reader?.readUTF8Line() ?: ""

                println(line)

                if (!line.startsWith("SIZE:")) {
                    if (line.contains("ERROR")) {
                        throw IOException("Socket Download Error - $line")
                    } else {
                        throw IOException("Socket Download Error - Failed to read file size. Make sure the file exists.")
                    }
                }

                val size = line.removePrefix("SIZE:").toInt()
                println(size)

                writer?.writeFully("CONTINUE".toByteArray(StandardCharsets.UTF_8))

                val buffer = ByteArrayOutputStream()
                val tempBuffer = ByteArray(8192)
                var totalBytesRead = 0
                while (totalBytesRead < size) {
                    val bytesRead = reader?.readAvailable(tempBuffer, 0, minOf(tempBuffer.size, (size - totalBytesRead))) ?: 0
                    totalBytesRead += bytesRead

                    if (bytesRead <= 0) break

                    buffer.write(tempBuffer, 0, bytesRead)
                }

                fileData = buffer.toByteArray()
            }
        }
        return fileData
    }
}

fun shouldUseSockets(project: Project, uploadSizeBytes: Double? = null): Boolean {
    val settings = project.service<MpySettingsService>()

    with(settings.state) {
        return useSockets && (!requireMinimumSocketTransferSize || uploadSizeBytes == null || uploadSizeBytes > minimumSocketTransferSize * 1000)
    }
}