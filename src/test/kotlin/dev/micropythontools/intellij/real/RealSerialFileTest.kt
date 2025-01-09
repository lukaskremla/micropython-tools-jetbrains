package dev.micropythontools.intellij.real

import ui.ConnectionParameters
import org.junit.jupiter.api.Disabled

private const val PORT_NAME = "COM11"

@Disabled("Works only if a real board is at $PORT_NAME")
class RealSerialFileTest : FileTest(ConnectionParameters(PORT_NAME))