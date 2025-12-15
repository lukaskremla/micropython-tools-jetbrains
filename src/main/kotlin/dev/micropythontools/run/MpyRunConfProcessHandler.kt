package dev.micropythontools.run

import com.intellij.execution.process.ProcessHandler
import kotlinx.coroutines.Job
import java.io.OutputStream

internal class MpyRunConfProcessHandler : ProcessHandler() {
    private var jobToCancel: Job? = null

    fun completeWithSuccess() {
        notifyProcessTerminated(0)
    }

    fun completeWithFailure() {
        notifyProcessTerminated(1)
    }

    fun registerJobToCancelOnDestroy(job: Job) {
        jobToCancel = job
    }

    override fun destroyProcessImpl() {
        if (!isProcessTerminated) {
            jobToCancel?.cancel()
            notifyProcessTerminated(1)
        }
    }

    override fun detachProcessImpl() {
        notifyProcessDetached()
    }

    override fun detachIsDefault() = false

    override fun getProcessInput(): OutputStream? = null
}