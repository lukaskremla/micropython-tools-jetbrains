package dev.micropythontools.real

import dev.micropythontools.communication.WebSocketCommTest
import dev.micropythontools.ui.ConnectionParameters
import org.junit.jupiter.api.Disabled

private const val URL = "ws://192.168.50.68:8266"

private const val PASSWORD = "passwd"

@Disabled("Works only if a real board is at address $URL having password $PASSWORD")
class RealWebReplConnect : RealConnectTestBase() {
    override fun doInit() {
        Thread.sleep(200)
        comm = WebSocketCommTest()
        comm.setConnectionParams(ConnectionParameters(URL, PASSWORD))
        Thread.sleep(100)
    }
}