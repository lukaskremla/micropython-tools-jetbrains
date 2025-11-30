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
import com.intellij.util.ui.UIUtil
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import dev.micropythontools.core.MpyValidators
import dev.micropythontools.freemium.MpyProServiceInterface
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.DEFAULT_WEBREPL_URL
import dev.micropythontools.settings.MpyConfigurable
import dev.micropythontools.settings.MpySettingsService
import dev.micropythontools.ui.MpyComponentRegistryService
import dev.micropythontools.ui.MpyFileSystemWidget
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal typealias StateListener = (State) -> Unit

internal data class DeviceInformation(
    val defaultFreeMem: Int = 0,
    val hasCRC32: Boolean = false,
    val canEncodeBase64: Boolean = false,
    val canDecodeBase64: Boolean = false,
)

internal data class ConnectionParameters(
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

internal class PerformReplActionResult<T>(
    val result: T? = null,
    val shouldRefresh: Boolean = false
)

@Service(Service.Level.PROJECT)
internal class MpyDeviceService(val project: Project) : Disposable {
    val ttyConnector: TtyConnector
        get() = comm.ttyConnector

    val fileSystemWidget: MpyFileSystemWidget?
        get() = componentRegistryService.getFileSystemWidget()

    val stateListeners = mutableListOf<StateListener>()

    // Also allow setting the value from outside MpyComm for complex scenarios (like with uploads),
    // if the initial quiet (stays in tty detached) refresh finds all files are up to date, the state needs to be reset
    // manually
    var state: State
        get() = comm.state
        set(value) {
            comm.state = value
        }

    var deviceInformation: DeviceInformation = DeviceInformation()

    private val settings = project.service<MpySettingsService>()
    private val proService = project.service<MpyProServiceInterface>()
    private val componentRegistryService = project.service<MpyComponentRegistryService>()
    private var comm: MpyComm = createMpyComm()
    private var connectionChecker: ScheduledExecutorService? = null

    private val terminalContent: Content?
        get() = componentRegistryService.getTerminalContent()

    init {
        val newDisposable = Disposer.newDisposable("MpyDeviceServiceDisposable")
        Disposer.register(newDisposable, this)
    }

    // Some complex scenarios (such as uploads) may require the ability to manually print the MicroPython banner
    // which would be stored in the offTtyBuffer
    fun writeOffTtyBufferToTerminal() {
        comm.outPipe.write(comm.offTtyByteBuffer.toUtf8String())
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

    fun <T> performReplAction(
        project: Project,
        connectionRequired: Boolean,
        requiresRefreshAfter: Boolean,
        canRunInBackground: Boolean,
        @NlsContexts.DialogMessage description: String,
        cancelledMessage: String,
        timedOutMessage: String,
        action: suspend (RawProgressReporter) -> T,
        cleanUpAction: (suspend (RawProgressReporter) -> Unit)? = null,
        finalCheckAction: (() -> Unit)? = null
    ): T? {
        val proService = project.service<MpyProServiceInterface>()

        if (proService.isActive) {
            return proService.performReplAction(
                project = project,
                connectionRequired = connectionRequired,
                requiresRefreshAfter = requiresRefreshAfter,
                canRunInBackground = canRunInBackground,
                description = description,
                cancelledMessage = cancelledMessage,
                timedOutMessage = timedOutMessage,
                action = action,
                cleanUpAction = cleanUpAction,
                finalCheckAction = finalCheckAction
            )
        }

        val deviceService = project.service<MpyDeviceService>()

        if (connectionRequired && deviceService.state != State.CONNECTED) {
            val settings = project.service<MpySettingsService>()

            val deviceToConnectTo = when {
                settings.state.usingUart -> settings.state.portName

                else -> settings.webReplUrl
            }

            if (deviceToConnectTo == null ||
                !MessageDialogBuilder.yesNo(
                    MpyBundle.message("comm.connection.required.dialog.title"),
                    MpyBundle.message("comm.connection.required.dialog.message", deviceToConnectTo)
                ).ask(project)
            ) {
                return null
            }
        }

        var result: T? = null

        var mainActionExecuted = false
        var cleanUpActionExecuted = false
        var wasCancelled = false

        try {
            runWithModalProgressBlocking(project, MpyBundle.message("comm.progress.communicating.with.device")) {
                reportRawProgress { reporter ->
                    var error: String? = null
                    var errorType = NotificationType.ERROR

                    try {
                        if (connectionRequired) {
                            deviceService.doConnect(reporter)
                        }
                        result = action(reporter)

                        mainActionExecuted = true
                    } catch (_: TimeoutCancellationException) {
                        error = timedOutMessage
                    } catch (e: CancellationException) {
                        wasCancelled = true

                        error = cancelledMessage
                        errorType = NotificationType.INFORMATION
                        throw e
                    } catch (e: IOException) {
                        val noMsg = MpyBundle.message("comm.error.no.message")
                        val ioLabel = MpyBundle.message("comm.error.io.label")
                        val msg = e.localizedMessage ?: e.message ?: noMsg
                        error = "$description $ioLabel - $msg"
                        deviceService.disconnect(reporter)
                    } catch (e: Exception) {
                        val errLabel = MpyBundle.message("comm.error.error.label")
                        val base = "$description $errLabel - ${e::class.simpleName}"
                        val msg = e.localizedMessage ?: e.message
                        error = if (msg.isNullOrBlank()) base else "$base: $msg"
                        deviceService.disconnect(reporter)
                    } finally {
                        withContext(NonCancellable) {
                            if (!error.isNullOrBlank()) {
                                Notifications.Bus.notify(
                                    Notification(
                                        MpyBundle.message("notification.group.name"),
                                        error,
                                        errorType
                                    ), project
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            if (mainActionExecuted || wasCancelled) {
                runWithModalProgressBlocking(
                    project,
                    MpyBundle.message("comm.progress.cleaning.up.after.operation")
                ) {
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

                            cleanUpActionExecuted = true
                        } catch (_: TimeoutCancellationException) {
                            error = MpyBundle.message("comm.error.cleanup.timeout")
                        } catch (e: CancellationException) {
                            error = MpyBundle.message("comm.error.cleanup.cancelled")
                            throw e

                        } catch (e: Throwable) {
                            val errLabel = MpyBundle.message("comm.error.error.label")
                            val base = "$description $errLabel - ${e::class.simpleName}"
                            val msg = e.localizedMessage ?: e.message ?: MpyBundle.message("comm.error.no.message")
                            val core = if (msg.isBlank()) base else "$base: $msg"

                            error = MpyBundle.message("comm.cleanup.exception.prefix", core)

                        } finally {
                            withContext(NonCancellable) {
                                if (!error.isNullOrBlank()) {
                                    deviceService.disconnect(reporter)

                                    Notifications.Bus.notify(
                                        Notification(
                                            MpyBundle.message("notification.group.name"),
                                            "$error - ${MpyBundle.message("comm.cleanup.prevent.desync")}",
                                            NotificationType.ERROR
                                        ), project
                                    )
                                }
                            }
                        }
                    }
                }

                if (mainActionExecuted && cleanUpActionExecuted && !wasCancelled) {
                    finalCheckAction?.let { finalCheckAction() }
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

    /**
     * Method for handling the procedure for connecting to a MicroPython board
     *
     * @param reporter RawProgressReporter to use
     * @param isConnectAction An optional parameter to be used by MpyConnectAction and FileSystemWidget empty text,
     * prevents duplicate cancellation notifications, since these actions handle their own cancellation notifications
     */
    suspend fun doConnect(reporter: RawProgressReporter, isConnectAction: Boolean = false) {
        try {
            if (state == State.CONNECTED) return

            val settings = project.service<MpySettingsService>()

            val device = if (settings.state.usingUart) settings.state.portName ?: "" else settings.webReplUrl
            reporter.text(MpyBundle.message("comm.progress.connecting", device))
            reporter.fraction(null)

            var msg: String? = null
            val connectionParameters: ConnectionParameters?
            if (settings.state.usingUart) {
                val portName = settings.state.portName ?: ""
                if (portName.isBlank()) {
                    msg = MpyBundle.message("comm.error.connecting.no.port")
                    connectionParameters = null
                } else {
                    connectionParameters = ConnectionParameters(portName)
                }

            } else {
                val url = settings.webReplUrl
                val password = withContext(Dispatchers.EDT) {
                    runWithModalProgressBlocking(project, MpyBundle.message("comm.progress.retrieving.credentials")) {
                        project.service<MpySettingsService>().retrieveWebReplPassword()
                    }
                }

                val ipMsg = MpyValidators.messageForBrokenIp(settings.state.webReplIp ?: "")
                val portMsg = MpyValidators.messageForBrokenPort(settings.state.webReplPort.toString())
                val passwordMsg = MpyValidators.messageForBrokenPassword(password.toCharArray())

                msg = when {
                    ipMsg.isNullOrBlank() -> ipMsg
                    portMsg.isNullOrBlank() -> portMsg
                    passwordMsg.isNullOrBlank() -> passwordMsg
                    else -> null
                }

                connectionParameters = if (msg == null) {
                    ConnectionParameters(url, password)
                } else null
            }
            if (msg != null) {
                withContext(Dispatchers.EDT) {
                    val result = Messages.showIdeaMessageDialog(
                        project,
                        msg,
                        MpyBundle.message("comm.error.cannot.connect.dialog.title"),
                        arrayOf(
                            MpyBundle.message("comm.error.cannot.connect.dialog.ok.button"),
                            MpyBundle.message("comm.error.cannot.connect.dialog.settings.button")
                        ),
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

            if (settings.state.usingUart) {
                startSerialConnectionMonitoring()
            }
        } catch (_: TimeoutCancellationException) {
            Notifications.Bus.notify(
                Notification(
                    MpyBundle.message("notification.group.name"),
                    MpyBundle.message("comm.error.connection.timeout"),
                    NotificationType.ERROR
                ), project
            )
            disconnect(reporter)
        } catch (e: CancellationException) {
            // Connect actions handle their own cancellation notifications
            if (!isConnectAction) {
                Notifications.Bus.notify(
                    Notification(
                        MpyBundle.message("notification.group.name"),
                        MpyBundle.message("comm.error.connection.cancelled"),
                        NotificationType.INFORMATION
                    ), project
                )
            }
            disconnect(reporter)
            throw e
        }
    }

    suspend fun disconnect(reporter: RawProgressReporter?) {
        if (settings.state.usingUart) {
            reporter?.text(MpyBundle.message("comm.progress.disconnecting", settings.state.portName ?: ""))
            stopSerialConnectionMonitoring()
        } else {
            reporter?.text(MpyBundle.message("comm.progress.disconnecting", settings.webReplUrl))
        }

        reporter?.fraction(null)
        comm.disconnect()
        deviceInformation = DeviceInformation()
    }

    /**
     * The file system refresh must be executed after an upload otherwise the plugin will stay stuck in a detached state
     */
    suspend fun upload(
        relativeName: String,
        contentsToByteArray: ByteArray,
        progressCallback: (uploadedBytes: Double) -> Unit,
        freeMemBytes: Int
    ) {
        comm.upload(relativeName, contentsToByteArray, progressCallback, freeMemBytes)
    }

    suspend fun download(deviceFileName: String): ByteArray =
        comm.download(deviceFileName)

    suspend fun instantRun(code: String) {
        clearTerminalIfNeeded()
        comm.instantRun(code)
        withContext(Dispatchers.EDT) {
            activateRepl()
        }
    }

    suspend fun safeCreateDirectories(paths: Set<String>) = comm.safeCreateDirectories(paths)

    suspend fun recursivelySafeDeletePaths(paths: Set<String>) = comm.recursivelySafeDeletePaths(paths)

    fun activateRepl(): Content? = terminalContent?.apply {
        project.service<ToolWindowManager>().getToolWindow(MpyBundle.message("toolwindow.id"))
            ?.activate(null, true, true)
        manager?.setSelectedContent(this)
    }

    suspend fun reset() {
        clearTerminalIfNeeded()
        comm.reset()
    }

    /**
     * Executes a command/script on the device.
     *
     * @param command The command or script to execute
     *
     * @return The execution result as a string
     *
     * @throws MicroPythonExecutionException when REPL returns a non-empty stderr result
     */
    suspend fun blindExecute(command: String, shouldStayDetached: Boolean = false): String {
        clearTerminalIfNeeded()
        return comm.blindExecute(command, shouldStayDetached)
    }

    /**
     * Convenience method for executing lists of command lines
     * Internally joins all commands with newlines and executes them as a single script.
     *
     * @param commands The list of commands to join and execute
     *
     * @return The execution result as a string
     *
     * @throws MicroPythonExecutionException when REPL returns a non-empty stderr result
     */
    suspend fun blindExecute(commands: List<String>): String {
        val combinedCommand = commands.joinToString("\n")
        // Instead of passing the command list directly to doBlindExecute this joins them to a string
        // It makes the command as efficient to execute as possible
        return blindExecute(combinedCommand)
    }

    suspend fun interrupt() {
        comm.interrupt()
    }

    fun checkConnected() = comm.checkConnected()

    fun recreateTtyConnector() {
        comm.dispose()
        comm = createMpyComm()
        componentRegistryService.getTerminal()?.ttyConnector = ttyConnector
    }

    override fun dispose() {
        stopSerialConnectionMonitoring()
    }

    private fun createMpyComm(): MpyComm {
        return MpyComm(project, this).also {
            val newDisposable = Disposer.newDisposable("MpyCommDisposable")
            Disposer.register(newDisposable, it)
        }
    }

    private suspend fun initializeDevice() = proService.initializeDevice(project)

    private suspend fun connect() = comm.connect()

    private fun setConnectionParams(connectionParameters: ConnectionParameters) =
        comm.setConnectionParams(connectionParameters)

    private fun startSerialConnectionMonitoring() {
        connectionChecker = Executors.newSingleThreadScheduledExecutor { r ->
            val thread = Thread(r, "MPY-Connection-Checker")
            thread.isDaemon = true
            thread
        }

        connectionChecker?.scheduleAtFixedRate({
            if (state == State.CONNECTED && !comm.isConnected) {
                ApplicationManager.getApplication().invokeLater {
                    @Suppress("DialogTitleCapitalization")
                    Notifications.Bus.notify(
                        Notification(
                            MpyBundle.message("notification.group.name"),
                            MpyBundle.message("comm.error.connection.checker.connection.lost.dialog.title"),
                            MpyBundle.message("comm.error.connection.checker.connection.lost.dialog.message"),
                            NotificationType.ERROR
                        )
                    )
                }

                runBlocking {
                    try {
                        disconnect(null)
                    } catch (_: Throwable) {
                        // Ignore
                    }
                }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun stopSerialConnectionMonitoring() {
        connectionChecker?.shutdown()
        try {
            if (connectionChecker?.awaitTermination(1, TimeUnit.SECONDS) != true) {
                connectionChecker?.shutdownNow()
            }
        } catch (_: InterruptedException) {
            connectionChecker?.shutdownNow()
        }
    }

    internal suspend fun clearTerminalIfNeeded() {
        if (settings.state.autoClearRepl) {
            withContext(Dispatchers.EDT) {
                val widget = UIUtil.findComponentOfType(terminalContent?.component, JediTermWidget::class.java)
                widget?.let {
                    try {
                        it.terminalPanel?.clearBuffer()

                        // CRITICAL FIX: Ensure cursor is at valid position after clear
                        // JediTerm expects row >= 1, and this prevents resize crashes
                        val terminal = it.terminal
                        if (terminal != null) {
                            val cursorY = terminal.cursorY
                            if (cursorY < 1) {
                                // Use cursorPosition method to reset cursor to valid position (1,1)
                                terminal.cursorPosition(1, 1)
                            }
                        }
                    } catch (e: Exception) {
                        // Log but don't crash if cursor reset fails
                        com.intellij.openapi.diagnostic.Logger.getInstance("MpyDeviceService")
                            .warn("Error during terminal clear/cursor reset", e)
                    }
                }
            }
        }
    }
}