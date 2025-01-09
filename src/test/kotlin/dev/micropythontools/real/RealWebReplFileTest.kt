package dev.micropythontools.real

import dev.micropythontools.ui.ConnectionParameters
import org.junit.jupiter.api.Disabled

private const val URL = "ws://192.168.50.68:8266"
private const val PASSWORD = "passwd"

@Disabled("Works only if a real board is at address $URL having password $PASSWORD")
class RealWebReplFileTest : FileTest(ConnectionParameters(URL, PASSWORD))