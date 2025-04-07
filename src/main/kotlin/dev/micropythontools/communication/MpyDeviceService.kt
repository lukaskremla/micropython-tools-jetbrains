/*
 * Copyright 2025 Lukas Kremla
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

import com.fazecast.jSerialComm.SerialPort
import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.util.progress.RawProgressReporter
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.ui.content.Content
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import dev.micropythontools.settings.DEFAULT_WEBREPL_URL
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.settings.messageForBrokenUrl
import dev.micropythontools.ui.*
import dev.micropythontools.util.MpyPythonService
import kotlinx.coroutines.*
import org.jetbrains.annotations.NonNls
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class DeviceInformation(
    var version: String = "Unknown",
    var description: String = "Unknown",
    var hasCRC32: Boolean = false,
    var canEncodeBase64: Boolean = false,
    var canDecodeBase64: Boolean = false,
    var platform: String? = null,
    var byteorder: String? = null,
    var maxsize: Long? = null,
    var mpyVersion: Int? = null,
    var mpySubversion: Int? = null,
    var mpyArchIdx: Int? = null,
    var mpyArchName: String? = null,
    var wordSize: Int? = null,
    var smallIntBits: Int? = null
) {
    /**
     * Generates mpy-cross command-line arguments based on device capabilities
     */
    fun getMpyCrossArgs(): List<String> {
        val args = mutableListOf<String>()

        // Add architecture if known
        if (mpyArchName != null) {
            args.add("-march=${mpyArchName}")
        }

        // Add small int bits if different from default
        if (smallIntBits != 31) {
            args.add("-msmall-int-bits=${smallIntBits}")
        }

        // Add bytecode version if available
        if (mpyVersion != null) {
            args.add("-b")
            args.add(mpyVersion.toString())
        }

        return args
    }

    /**
     * Determines if the device supports MPY compilation
     */
    fun supportsMpyCompilation(): Boolean {
        return mpyVersion != null && mpyArchName != null
    }
}

data class ConnectionParameters(
    var usingUart: Boolean = true,
    var portName: String,
    var webReplUrl: String,
    var webReplPassword: String,
    var ssid: String,
    var wifiPassword: String,
    var activeStubsPackage: String? = null
) {
    constructor(portName: String) : this(
        usingUart = true,
        portName = portName,
        webReplUrl = DEFAULT_WEBREPL_URL,
        webReplPassword = "",
        ssid = "",
        wifiPassword = "",
        activeStubsPackage = ""
    )

    constructor(webReplUrl: String, webReplPassword: String) : this(
        usingUart = false,
        portName = "",
        webReplUrl = webReplUrl,
        webReplPassword = webReplPassword,
        ssid = "",
        wifiPassword = "",
        activeStubsPackage = null
    )
}

typealias StateListener = (State) -> Unit

@Service(Service.Level.PROJECT)
class MpyDeviceService(private val project: Project) : Disposable {
    init {
        val newDisposable = Disposer.newDisposable("MpyDeviceServiceDisposable")
        Disposer.register(newDisposable, this)
    }

    private val settings = project.service<MpySettingsService>()
    private val pythonService = project.service<MpyPythonService>()
    private val componentRegistryService = project.service<MpyComponentRegistryService>()

    val ttyConnector: TtyConnector
        get() = comm.ttyConnector

    private val comm: MpyComm = MpyComm(this, pythonService).also {
        val newDisposable = Disposer.newDisposable("MpyCommDisposable")
        Disposer.register(newDisposable, it)
    }

    val fileSystemWidget: FileSystemWidget?
        get() = componentRegistryService.getFileSystemWidget()

    private val terminalContent: Content?
        get() = componentRegistryService.getTerminalContent()

    val stateListeners = mutableListOf<StateListener>()

    val state: State
        get() = comm.state

    var deviceInformation: DeviceInformation = DeviceInformation()

    private val connectionChecker = Executors.newSingleThreadScheduledExecutor { r ->
        val thread = Thread(r, "MPY-Connection-Checker")
        thread.isDaemon = true
        thread
    }

    fun startConnectionMonitoring() {
        connectionChecker.scheduleAtFixedRate({
            if (state == State.CONNECTED && !comm.isConnected) {
                ApplicationManager.getApplication().invokeLater {
                    Notifications.Bus.notify(
                        Notification(
                            NOTIFICATION_GROUP,
                            "Device to Connection Lost",
                            "Connection to the device was lost unexpectedly. This may have been caused by a disconnected cable or a network issue.",
                            NotificationType.ERROR
                        )
                    )
                }

                runBlocking {
                    try {
                        disconnect(null)
                    } catch (e: Exception) {
                        Notifications.Bus.notify(
                            Notification(
                                NOTIFICATION_GROUP,
                                "Error during disconnect",
                                e.message ?: "Unknown error",
                                NotificationType.ERROR
                            )
                        )
                    }
                }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    fun listSerialPorts(filterManufacturers: Boolean = settings.state.filterManufacturers): MutableList<String> {
        val os = System.getProperty("os.name").lowercase()

        val isWindows = os.contains("win")

        val filteredPorts = mutableListOf<String>()
        val ports = SerialPort.getCommPorts()

        for (port in ports) {
            if ((filterManufacturers && port.manufacturer == "Unknown") || port.systemPortPath.startsWith("/dev/tty.")) continue

            if (isWindows) {
                filteredPorts.add(port.systemPortName)
            } else {
                filteredPorts.add(port.systemPortPath)
            }
        }

        return filteredPorts
    }

    private suspend fun initializeDevice() {
        val scriptFileName = "initialize_device.py"
        val initializeDeviceScript = pythonService.retrieveMpyScriptAsString(scriptFileName)

        val scriptResponse = blindExecute(initializeDeviceScript).extractSingleResponse()

        if (!scriptResponse.contains("ERROR")) {
            val responseFields = scriptResponse.split("&")

            deviceInformation = DeviceInformation(
                version = responseFields.getOrNull(0) ?: "Unknown",
                description = responseFields.getOrNull(1) ?: "Unknown",
                hasCRC32 = responseFields.getOrNull(2)?.toBoolean() == true,
                canEncodeBase64 = responseFields.getOrNull(3)?.toBoolean() == true,
                canDecodeBase64 = responseFields.getOrNull(4)?.toBoolean() == true,
                platform = responseFields.getOrNull(5),
                byteorder = responseFields.getOrNull(6),
                maxsize = responseFields.getOrNull(7)?.toLongOrNull(),
                mpyVersion = responseFields.getOrNull(8)?.toIntOrNull(),
                mpySubversion = responseFields.getOrNull(9)?.toIntOrNull(),
                mpyArchIdx = responseFields.getOrNull(10)?.toIntOrNull(),
                mpyArchName = responseFields.getOrNull(11),
                wordSize = responseFields.getOrNull(12)?.toIntOrNull(),
                smallIntBits = responseFields.getOrNull(13)?.toIntOrNull()
            )
        } else {
            deviceInformation = DeviceInformation()
        }

        var message: String? = if (!deviceInformation.hasCRC32) {
            if (!deviceInformation.canDecodeBase64) {
                "The connected board is missing the crc32 and a2b_base64 binascii functions. " +
                        "The already uploaded files check won't work and uploads may be slower."
            } else {
                "The connected board is missing the crc32 binascii function." +
                        "The already uploaded files check won't work."
            }
        } else if (!deviceInformation.canDecodeBase64) {
            "The connected board is missing the a2b_base64 binascii function. " +
                    "Uploads may be slower."
        } else {
            null
        }

        if (message != null) {
            MessageDialogBuilder.Message(
                "Missing MicroPython Libraries",
                message
            ).asWarning().buttons("Acknowledge").show(project)
        }
    }

    suspend fun doConnect(reporter: RawProgressReporter) {
        try {
            if (state == State.CONNECTED) return

            val settings = project.service<MpySettingsService>()

            val device = if (settings.state.usingUart) settings.state.portName else settings.state.webReplUrl
            reporter.text("Connecting to $device")
            reporter.fraction(null)

            var msg: String? = null
            val connectionParameters: ConnectionParameters?
            if (settings.state.usingUart) {
                val portName = settings.state.portName ?: ""
                if (portName.isBlank()) {
                    msg = "No port is selected"
                    connectionParameters = null
                } else {
                    connectionParameters = ConnectionParameters(portName)
                }

            } else {
                val url = settings.state.webReplUrl ?: DEFAULT_WEBREPL_URL
                val password = withContext(Dispatchers.EDT) {
                    runWithModalProgressBlocking(project, "Retrieving credentials...") {
                        project.service<MpySettingsService>().retrieveWebReplPassword()
                    }
                }

                msg = messageForBrokenUrl(url)
                if (password.isBlank()) {
                    msg = "Empty password"
                    connectionParameters = null
                } else {
                    connectionParameters = ConnectionParameters(url, password)
                }
            }
            if (msg != null) {
                withContext(Dispatchers.EDT) {
                    val result = Messages.showIdeaMessageDialog(
                        project,
                        msg,
                        "Cannot Connect",
                        arrayOf("OK", "Settings..."),
                        1,
                        AllIcons.General.ErrorDialog,
                        null
                    )
                    if (result == 1) {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, MpyConfigurable::class.java)
                    }
                }
            } else {
                if (connectionParameters != null) {
                    setConnectionParams(connectionParameters)
                    connect()
                    try {
                        if (state == State.CONNECTED) {
                            initializeDevice()
                            fileSystemWidget?.initialRefresh(reporter)
                        }
                    } finally {
                        ActivityTracker.getInstance().inc()
                    }
                }
            }
        } catch (_: CancellationException) {
            disconnect(reporter)
        }

        startConnectionMonitoring()
    }

    suspend fun disconnect(reporter: RawProgressReporter?) {
        val settings = MpySettingsService.getInstance(project)

        reporter?.text("Disconnecting from ${settings.state.portName}")
        reporter?.fraction(null)
        comm.disconnect()
        deviceInformation = DeviceInformation()
        connectionChecker.shutdown()
        println("Performed disconnect")
    }

    @Throws(IOException::class)
    suspend fun upload(relativeName: @NonNls String, contentsToByteArray: ByteArray, progressCallback: (uploadedBytes: Int) -> Unit) {
        comm.upload(relativeName, contentsToByteArray, progressCallback)
    }

    @Throws(IOException::class)
    suspend fun download(deviceFileName: @NonNls String): ByteArray =
        comm.download(deviceFileName)

    @Throws(IOException::class)
    suspend fun instantRun(code: @NonNls String, showCode: Boolean) {
        withContext(Dispatchers.EDT) {
            activateRepl()
        }
        if (showCode) {
            val terminal = UIUtil.findComponentOfType(terminalContent?.component, JediTermWidget::class.java)?.terminal
            terminal?.apply {
                carriageReturn()
                newLine()
                code.lines().forEach {
                    val savedStyle = styleState.current
                    val inactive = NamedColorUtil.getInactiveTextColor()
                    val color = TerminalColor(inactive.red, inactive.green, inactive.blue)
                    styleState.reset()
                    styleState.current = styleState.current.toBuilder().setForeground(color).build()
                    writeUnwrappedString(it)
                    carriageReturn()
                    newLine()
                    styleState.current = savedStyle
                }
            }
        }
        comm.instantRun(code)
    }

    suspend fun safeCreateDirectories(paths: Set<String>) = comm.safeCreateDirectories(paths)

    suspend fun recursivelySafeDeletePaths(paths: Set<String>) = comm.recursivelySafeDeletePaths(paths)

    fun activateRepl(): Content? = terminalContent?.apply {
        project.service<ToolWindowManager>().getToolWindow(TOOL_WINDOW_ID)?.activate(null, true, true)
        manager?.setSelectedContent(this)
    }

    fun reset() = comm.reset()

    /**
     * Executes a single command/script on the device.
     *
     * @param command The command or script to execute
     * @return The execution response
     */
    suspend fun blindExecute(command: String): ExecResponse {
        clearTerminalIfNeeded()
        return comm.blindExecute(command)
    }

    /**
     * Convenience method for executing lists of command lines
     * Internally joins all commands with newlines and executes them as a single script.
     *
     * @param commands List of commands to join and execute
     * @return The execution response
     */
    suspend fun blindExecute(commands: List<String>): ExecResponse {
        clearTerminalIfNeeded()
        val combinedCommand = commands.joinToString("\n")
        // Instead of passing the command list directly to doBlindExecute this joins them to a string
        // It makes the command as efficient to execute as possible
        return blindExecute(combinedCommand)
    }

    suspend fun connect() = comm.connect()

    private fun setConnectionParams(connectionParameters: ConnectionParameters) = comm.setConnectionParams(connectionParameters)
    fun interrupt() {
        comm.interrupt()
    }

    fun checkConnected() = comm.checkConnected()

    internal suspend fun clearTerminalIfNeeded() {
        if (AutoClearAction.isAutoClearEnabled) {
            withContext(Dispatchers.EDT) {
                val widget = UIUtil.findComponentOfType(terminalContent?.component, JediTermWidget::class.java)
                widget?.terminalPanel?.clearBuffer()
            }
        }
    }

    override fun dispose() {
        connectionChecker.shutdown()
        try {
            if (!connectionChecker.awaitTermination(1, TimeUnit.SECONDS)) {
                connectionChecker.shutdownNow()
            }
        } catch (_: InterruptedException) {
            connectionChecker.shutdownNow()
        }
    }
}

class PerformReplActionResult<T>(
    val result: T? = null,
    val shouldRefresh: Boolean = false
)

fun <T> performReplAction(
    project: Project,
    connectionRequired: Boolean,
    @NlsContexts.DialogMessage description: String,
    requiresRefreshAfter: Boolean,
    cancelledMessage: String? = null,
    action: suspend (RawProgressReporter) -> T,
    cleanUpAction: (suspend (RawProgressReporter) -> Unit)? = null
): T? {
    val deviceService = project.service<MpyDeviceService>()

    if (connectionRequired && deviceService.state != State.CONNECTED) {
        val settings = project.service<MpySettingsService>().state

        val deviceToConnectTo = when {
            settings.usingUart -> settings.portName

            else -> settings.webReplUrl
        }

        if (deviceToConnectTo == null ||
            !MessageDialogBuilder.yesNo("No device is connected", "Connect to $deviceToConnectTo?").ask(project)
        ) {
            return null
        }
    }

    var result: T? = null

    var gotThroughTryBlock = false
    var wasCancelled = false

    try {
        runWithModalProgressBlocking(project, "Communicating with the board...") {
            reportRawProgress { reporter ->
                var error: String? = null
                var errorType = NotificationType.ERROR

                try {
                    if (connectionRequired) {
                        deviceService.doConnect(reporter)
                    }
                    result = action(reporter)

                    gotThroughTryBlock = true
                } catch (_: TimeoutCancellationException) {
                    error = "$description timed out"
                } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                    wasCancelled = true

                    error = cancelledMessage ?: "$description cancelled"
                    errorType = NotificationType.INFORMATION
                } catch (e: IOException) {
                    error = "$description I/O error - ${e.localizedMessage ?: e.message ?: "No message"}"
                } catch (e: Exception) {
                    error = e.localizedMessage ?: e.message
                    error = if (error.isNullOrBlank()) "$description error - ${e::class.simpleName}"
                    else "$description error - ${e::class.simpleName}: $error"
                }
                if (!error.isNullOrBlank()) {
                    Notifications.Bus.notify(Notification(NOTIFICATION_GROUP, error, errorType), project)
                }
            }
        }
    } finally {
        if (gotThroughTryBlock || wasCancelled) {
            runWithModalProgressBlocking(project, "Cleaning up after board operation...") {
                reportRawProgress { reporter ->
                    var error: String? = null

                    try {
                        cleanUpAction?.let { cleanUpAction(reporter) }

                        val finalResult = result
                        val shouldRefresh = when {
                            finalResult is PerformReplActionResult<*> ->
                                @Suppress("UNCHECKED_CAST")
                                (finalResult as PerformReplActionResult<T>).shouldRefresh

                            else -> true
                        }

                        if (requiresRefreshAfter && shouldRefresh) {
                            deviceService.fileSystemWidget?.refresh(reporter)
                        }
                    } catch (e: Throwable) {
                        error = e.localizedMessage ?: e.message
                        error = if (error.isNullOrBlank()) {
                            "$description error - ${e::class.simpleName}"
                        } else {
                            "$description error - ${e::class.simpleName}: $error"
                        }
                        error = "Clean up Exception: $error"
                    }
                    if (!error.isNullOrBlank()) {
                        deviceService.disconnect(reporter)

                        Notifications.Bus.notify(
                            Notification(
                                NOTIFICATION_GROUP,
                                "$error - disconnecting to prevent a de-synchronized state",
                                NotificationType.ERROR
                            ), project
                        )
                    }
                }
            }
        }
    }

    // At the end of performReplAction function
    val finalResult = result
    return when {
        finalResult is PerformReplActionResult<*> ->
            @Suppress("UNCHECKED_CAST")
            (finalResult as PerformReplActionResult<T>).result

        else -> finalResult
    }
}