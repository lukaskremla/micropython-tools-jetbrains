package dev.micropythontools.intellij.communication

import org.junit.jupiter.api.fail

open class MpyCommForTest : MpyComm() {
    public override fun isTtySuspended(): Boolean = super.isTtySuspended()
    override fun errorLogger(exception: Exception) {
        fail(exception)
    }
}