package dev.micropythontools.intellij.real

import dev.micropythontools.intellij.nova.ConnectionParameters
import dev.micropythontools.intellij.nova.MpyCommForTest
import org.junit.jupiter.api.Disabled

private const val PORT_NAME = "COM2"

@Disabled("Works only if a real board is at $PORT_NAME")
class RealSerialConnect : RealConnectTestBase() {
    private val serialParams = ConnectionParameters(PORT_NAME)

    override fun doInit() {
        Thread.sleep(1000)
        comm = MpyCommForTest()
        comm.setConnectionParams(serialParams)
        Thread.sleep(500)
    }

}