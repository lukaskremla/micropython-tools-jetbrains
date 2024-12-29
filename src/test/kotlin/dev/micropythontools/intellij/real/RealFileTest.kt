package dev.micropythontools.intellij.real

import dev.micropythontools.intellij.nova.ConnectionParameters
import dev.micropythontools.intellij.nova.MpyClient
import dev.micropythontools.intellij.nova.MpyCommForTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.NonNls
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.random.Random

abstract class FileTest(private val connectionParameters: ConnectionParameters) {
    private lateinit var comm: CountingMpyComm

    private var uploadedFiles = mutableListOf<String>()

    @BeforeEach
    fun init() {
        uploadedFiles.clear()
        comm = CountingMpyComm()
        comm.setConnectionParams(connectionParameters)
        runBlocking {
            comm.connect()
        }
        comm.resetData()
    }

    private fun upload(fullName: @NonNls String, content: ByteArray) {
        uploadedFiles.add(fullName)
        runBlocking {
            comm.upload(fullName, content)
        }
    }

    private fun downLoad(fullName: @NonNls String): ByteArray {
        return runBlocking {
            comm.download(fullName)
        }
    }

    @Test
    fun testUploadDownload() {
        val testData = "LoremIpsum".toByteArray()
        val fullName = "/file_for_test.bin"
        upload(fullName, testData)
        comm.printData("Upload result:")
        comm.resetData()
        val copy = downLoad(fullName)
        assertEquals(testData.toString(StandardCharsets.UTF_8), copy.toString(StandardCharsets.UTF_8))
    }

    /*
    ************************************
    Chars transmitted: 62508
    Chars received: 42499
    Pings: 0
    Connection open time: 14741ms
    ************************************
    */
    @Test
    fun testLongUploadDownload() {
        val testData = Random(0).nextBytes(20000)
        val fullName = "/file_for_test.txt"
        upload(fullName, testData)
        comm.printData("Upload result:")
        comm.resetData()
        val copy = downLoad(fullName)
        assertArrayEquals(testData, copy)
    }

    /*
    ************************************
    Chars transmitted: 11085
    Chars received: 21412
    Pings: 0
    Connection open time: 6433ms
    ************************************
    */
    @Test
    fun testLongUploadDownloadText() {
        val testData = LOREM_IPSUM.toByteArray(Charsets.UTF_8)
        val fullName = "/file_for_test.txt"
        upload(fullName, testData)
        comm.printData("Upload result:")
        comm.resetData()
        val copy = downLoad(fullName)
        assertArrayEquals(testData, copy)
    }

    @AfterEach
    fun teardown() {
        comm.printData("Final step result:")
        runBlocking {
            comm.instantRun("import os")
            uploadedFiles.forEach { fileName ->
                comm.blindExecute(1000, "os.remove('$fileName')")
            }
        }
        comm.close()
    }

}

class CountingMpyComm : MpyCommForTest() {
    @Volatile
    var charsTransmitted = 0L

    @Volatile
    var charsReceived = 0L

    @Volatile
    var pings = 0L

    @Volatile
    var connectTimestamp = 0L

    override fun dataReceived(s: String) {
        charsReceived += s.length
        super.dataReceived(s)
    }

    override fun createClient(): MpyClient {
        return CountingMpyClient(super.createClient())
    }

    private inner class CountingMpyClient(private val innerMpyClient: MpyClient) : MpyClient {
        override fun send(string: String) {
            charsTransmitted += string.length
            innerMpyClient.send(string)
        }

        override fun hasPendingData(): Boolean = innerMpyClient.hasPendingData()

        override fun close() = innerMpyClient.close()

        override suspend fun connect(progressIndicatorText: String): MpyClient {
            connectTimestamp = System.currentTimeMillis()
            innerMpyClient.connect(progressIndicatorText)
            return this
        }

        override fun closeBlocking() = innerMpyClient.closeBlocking()

        override fun sendPing() {
            pings++
            innerMpyClient.sendPing()
        }

        override val isConnected: Boolean = innerMpyClient.isConnected
    }

    fun printData(header: String) {
        println("************************************")
        if (header.isNotBlank()) println(header)
        println("Chars transmitted: $charsTransmitted")
        println("Chars received: $charsReceived")
        println("Pings: $pings")
        println("Elapsed time: ${System.currentTimeMillis() - connectTimestamp}ms")
        println("************************************")
    }

    fun resetData() {
        charsTransmitted = 0
        charsReceived = 0
        pings = 0
        connectTimestamp = System.currentTimeMillis()
    }
}