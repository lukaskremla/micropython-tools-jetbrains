package dev.micropythontools.intellij.nova

interface Client {
    fun send(string: String)
    fun hasPendingData(): Boolean
    fun close()
    suspend fun connect(progressIndicatorText: String): Client
    fun closeBlocking()
    fun sendPing()

    val isConnected: Boolean
}