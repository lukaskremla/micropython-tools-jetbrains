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

package dev.micropythontools.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.platform.util.progress.withProgressText
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.*
import dev.micropythontools.core.MpyPythonInterpreterService
import dev.micropythontools.firmware.*
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.EMPTY_PORT_NAME_TEXT
import dev.micropythontools.settings.EMPTY_VOLUME_TEXT
import dev.micropythontools.settings.MpySettingsService
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.awt.Dimension
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.io.path.absolutePathString

internal class MpyFlashFirmwareDialog(private val project: Project) : DialogWrapper(project, true) {
    private val settings = project.service<MpySettingsService>()
    private val deviceService = project.service<dev.micropythontools.communication.MpyDeviceService>()
    private val firmwareService = project.service<MpyFirmwareService>()
    private val interpreterService = project.service<MpyPythonInterpreterService>()

    fun Cell<ComboBox<String>>.getSafely(nameToShow: String): String {
        return component.selectedItem as? String
            ?: throw RuntimeException(MpyBundle.message("flash.error.combobox.value.not.found", nameToShow))
    }

    private val isEsp
        get() = deviceTypeComboBox
            .getSafely(MpyBundle.message("flash.dialog.device.type.label").removeSuffix(":"))
            .startsWith("esp", ignoreCase = true)

    // Current board data
    private val mpyBoardsJson: MpyBoardsJson?
    private val boards: List<Board>

    // UI components
    private lateinit var statusComment: Cell<JEditorPane>

    private lateinit var deviceGroup: Row

    private lateinit var deviceTypeComboBox: Cell<ComboBox<String>>

    private lateinit var serialPortPanel: Panel
    private lateinit var enableManualEditingCheckbox: Cell<JBCheckBox>
    private lateinit var filterManufacturersCheckBox: Cell<JBCheckBox>
    private lateinit var portSelectComboBox: Cell<ComboBox<String>>

    private lateinit var volumeSelectComboBoxRow: Row
    private lateinit var volumeSelectComboBox: Cell<ComboBox<String>>

    private lateinit var mcuComboBoxRow: Row
    private lateinit var mcuComboBox: Cell<ComboBox<String>>
    private lateinit var microPythonOrgRadioButton: Cell<JBRadioButton>
    private lateinit var localFileRadioButton: Cell<JBRadioButton>

    private lateinit var boardVariantComboBox: Cell<ComboBox<String>>
    private lateinit var firmwareVariantComboBox: Cell<ComboBox<String>>

    private lateinit var showOlderVersionsCheckBox: Cell<JBCheckBox>
    private lateinit var showPreviewReleasesCheckBox: Cell<JBCheckBox>
    private lateinit var versionComboBox: Cell<ComboBox<String>>

    private lateinit var localFileTextFieldWithBrowseButton: Cell<TextFieldWithBrowseButton>

    private lateinit var flashingOptionsGroup: Row
    private lateinit var connectAfterRow: Row

    private lateinit var eraseFlashCheckBox: Cell<JBCheckBox>
    private lateinit var connectAfterCheckBox: Cell<JBCheckBox>
    //private lateinit var eraseFileSystemAfter: Cell<JBCheckBox>

    // Selected board for tracking
    private var selectedBoard: Board? = null

    // Initialize combo box models
    private val portSelectModel = MutableCollectionComboBoxModel<String>()
    private val volumeSelectModel = MutableCollectionComboBoxModel<String>()

    private val statusMessage: String

    init {
        title = MpyBundle.message("flash.dialog.title")
        setOKButtonText(MpyBundle.message("flash.dialog.ok.button.text"))

        var capturedStatusMessage = ""

        mpyBoardsJson = runWithModalProgressBlocking(project, MpyBundle.message("flash.dialog.progress.opening")) {
            withProgressText(MpyBundle.message("flash.dialog.progress.fetching.boards")) {
                return@withProgressText try {
                    val boardsJson = firmwareService.getMpyBoardsJson()

                    val formattedDate = formatReadableDateTime(boardsJson.timestamp)
                    capturedStatusMessage = MpyBundle.message("flash.status.up.to.date", formattedDate)

                    boardsJson
                } catch (_: IncompatibleBoardsJsonVersionException) {
                    capturedStatusMessage = MpyBundle.message("flash.status.incompatible")
                    null
                } catch (_: SerializationException) {
                    capturedStatusMessage = MpyBundle.message("flash.status.incompatible")
                    null
                } catch (_: Throwable) {
                    capturedStatusMessage = MpyBundle.message("flash.status.failed")
                    null
                }
            }
        }

        statusMessage = capturedStatusMessage

        title = MpyBundle.message("flash.dialog.title")
        setOKButtonText(MpyBundle.message("flash.dialog.ok.button.text"))

        // Load cached boards immediately (instant)
        boards = mpyBoardsJson?.boards ?: emptyList()

        init() // Make the dialog appear
    }

    override fun createCenterPanel(): JComponent {
        val deviceTypeModel = MutableCollectionComboBoxModel<String>()
        val mcuModel = MutableCollectionComboBoxModel<String>()
        val boardVariantModel = MutableCollectionComboBoxModel<String>()
        val firmwareVariantModel = MutableCollectionComboBoxModel<String>()
        val versionModel = MutableCollectionComboBoxModel<String>()

        val portName = settings.state.portName
        if (!portName.isNullOrBlank()) {
            portSelectModel.selectedItem = portName
        } else if (portSelectModel.isEmpty) {
            portSelectModel.selectedItem = EMPTY_PORT_NAME_TEXT
        }

        volumeSelectModel.selectedItem = EMPTY_VOLUME_TEXT

        return panel {
            row {
                statusComment = comment(statusMessage)
            }

            deviceGroup = group(MpyBundle.message("flash.dialog.group.device")) {
                row(MpyBundle.message("flash.dialog.device.type.label")) {
                    deviceTypeComboBox = comboBox(deviceTypeModel)
                        .columns(10)
                        .applyToComponent {
                            addActionListener {
                                onDeviceTypeSelected()

                                serialPortPanel.visible(isEsp)
                                volumeSelectComboBoxRow.visible(!isEsp)

                                mcuComboBoxRow.visible(isEsp || microPythonOrgRadioButton.component.isSelected)

                                val isSamd = deviceTypeComboBox
                                    .getSafely(MpyBundle.message("flash.dialog.device.type.label").removeSuffix(":"))
                                    .startsWith("samd", ignoreCase = true)

                                flashingOptionsGroup.visible(!isSamd)
                                connectAfterRow.visible(isEsp)
                            }
                        }
                }.layout(RowLayout.LABEL_ALIGNED)

                serialPortPanel = panel {
                    row {
                        enableManualEditingCheckbox =
                            checkBox(MpyBundle.message("configurable.enable.manual.port.editing.checkbox.text"))
                                .applyToComponent {
                                    isSelected = settings.state.enableManualEditing
                                    addActionListener {
                                        val comboBox = portSelectComboBox.component
                                        comboBox.isEditable = isSelected
                                        comboBox.revalidate()
                                        comboBox.repaint()
                                    }
                                }
                    }
                    row {
                        filterManufacturersCheckBox =
                            checkBox(MpyBundle.message("configurable.filter.out.unknown.manufacturers.checkbox.text"))
                                .applyToComponent {
                                    isSelected = settings.state.filterManufacturers
                                }
                    }

                    row(MpyBundle.message("flash.dialog.serial.port.label")) {
                        portSelectComboBox = comboBox(portSelectModel)
                            .columns(20)
                            .applyToComponent {
                                isEditable = enableManualEditingCheckbox.component.isSelected

                                addPopupMenuListener(object : PopupMenuListener {
                                    override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                                        updatePortList()
                                    }

                                    override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
                                    override fun popupMenuCanceled(e: PopupMenuEvent?) {}
                                })
                            }
                    }.layout(RowLayout.LABEL_ALIGNED)
                }

                volumeSelectComboBoxRow = row(MpyBundle.message("flash.dialog.target.volume.label")) {
                    volumeSelectComboBox = comboBox(volumeSelectModel)
                        .columns(20)
                        .applyToComponent {
                            addPopupMenuListener(object : PopupMenuListener {
                                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                                    updateVolumeList()
                                }

                                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
                                override fun popupMenuCanceled(e: PopupMenuEvent?) {}
                            })
                        }
                        .gap(RightGap.SMALL)

                    contextHelp(MpyBundle.message("flash.dialog.target.volume.help"))
                }
            }.bottomGap(BottomGap.NONE).enabled(!deviceService.isConnected)

            indent {
                row {
                    comment(MpyBundle.message("configurable.board.currently.connected.comment"), action = {
                        deviceService.performReplAction(
                            project,
                            connectionRequired = false,
                            requiresRefreshAfter = false,
                            canRunInBackground = false,
                            description = MpyBundle.message("action.disconnect.text"),
                            cancelledMessage = MpyBundle.message("action.disconnect.cancelled"),
                            timedOutMessage = MpyBundle.message("action.disconnect.timeout"),
                            { reporter ->
                                deviceService.disconnect(reporter)

                                deviceGroup.enabled(true)

                                this.visible(false)
                            }
                        )
                    })
                }
            }.visible(deviceService.isConnected)

            group(MpyBundle.message("flash.dialog.group.firmware.selection")) {
                mcuComboBoxRow = row(MpyBundle.message("flash.dialog.mcu.label")) {
                    mcuComboBox = comboBox(mcuModel)
                        .columns(10)
                        .applyToComponent {
                            addActionListener {
                                onMcuSelected()
                            }
                        }
                }.layout(RowLayout.LABEL_ALIGNED)

                buttonsGroup {
                    row(MpyBundle.message("flash.dialog.firmware.source.label")) {
                        microPythonOrgRadioButton =
                            radioButton(MpyBundle.message("flash.dialog.firmware.source.micropython.org"))
                                .applyToComponent {
                                    isSelected = mpyBoardsJson != null

                                    addActionListener { mcuComboBoxRow.visible(true) }
                                }

                        localFileRadioButton = radioButton(MpyBundle.message("flash.dialog.firmware.source.local.file"))
                            .applyToComponent {
                                isSelected = mpyBoardsJson == null

                                addActionListener { mcuComboBoxRow.visible(isEsp) }
                            }
                    }
                }.enabled(mpyBoardsJson != null)

                indent {
                    row(MpyBundle.message("flash.dialog.board.variant.label")) {
                        boardVariantComboBox = comboBox(boardVariantModel)
                            .columns(25)
                            .applyToComponent {
                                addActionListener {
                                    onBoardVariantSelected()
                                }
                            }
                            .comment("<a>View on MicroPython.org</a>", action = {
                                val boardId = boards
                                    .find { it.name == boardVariantComboBox.component.selectedItem }?.id
                                    ?: MpyBundle.message("flash.error.board.variant.unknown")

                                BrowserUtil.open("https://micropython.org/download/$boardId")
                            })
                    }.layout(RowLayout.LABEL_ALIGNED)

                    row(MpyBundle.message("flash.dialog.firmware.variant.label")) {
                        firmwareVariantComboBox = comboBox(firmwareVariantModel)
                            .columns(25)
                            .applyToComponent {
                                addActionListener {
                                    onFirmwareVariantSelected()
                                }
                            }
                    }.layout(RowLayout.LABEL_ALIGNED)

                    row {
                        showOlderVersionsCheckBox =
                            checkBox(MpyBundle.message("flash.dialog.show.older.versions.checkbox"))
                                .applyToComponent {
                                    addActionListener {
                                        onFirmwareVariantSelected()
                                    }
                                }
                    }

                    row {
                        showPreviewReleasesCheckBox =
                            checkBox(MpyBundle.message("flash.dialog.show.preview.releases.checkbox"))
                                .gap(RightGap.SMALL)
                                .applyToComponent {
                                    addActionListener {
                                        onFirmwareVariantSelected()
                                    }
                                }

                        contextHelp(MpyBundle.message("flash.dialog.show.preview.releases.help"))
                    }

                    row(MpyBundle.message("flash.dialog.version.label")) {
                        versionComboBox = comboBox(versionModel)
                            .columns(25)
                    }.layout(RowLayout.LABEL_ALIGNED)
                }.visibleIf(microPythonOrgRadioButton.selected)

                indent {
                    row(MpyBundle.message("flash.dialog.local.file.label")) {
                        localFileTextFieldWithBrowseButton = textFieldWithBrowseButton(
                            FileChooserDescriptor(true, false, false, false, false, false)
                                .withTitle(MpyBundle.message("flash.dialog.file.chooser.title"))
                                .withRoots(project.guessProjectDir())
                                .withExtensionFilter(
                                    MpyBundle.message("flash.dialog.file.chooser.extension.filter"),
                                    "bin",
                                    "uf2",
                                    "dfu"
                                ),
                            project
                        ).columns(25)
                            .validationOnApply {
                                // Only validate if local file option is selected
                                if (!localFileRadioButton.component.isSelected) {
                                    return@validationOnApply null
                                }

                                val localFilePath = it.text
                                val localFile = LocalFileSystem.getInstance().findFileByPath(localFilePath)

                                val selectedDeviceType = deviceTypeComboBox.component.selectedItem as? String
                                    ?: return@validationOnApply error(MpyBundle.message("flash.dialog.validation.select.device.type"))

                                val expectedExtension =
                                    firmwareService.portToExtension[selectedDeviceType.toLowerCasePreservingASCIIRules()]?.removePrefix(
                                        "."
                                    )

                                when {
                                    localFilePath.isBlank() -> error(MpyBundle.message("flash.dialog.validation.select.firmware.file"))
                                    localFile == null -> error(MpyBundle.message("flash.dialog.validation.invalid.file.path"))
                                    localFile.extension != expectedExtension -> error(
                                        MpyBundle.message(
                                            "flash.dialog.validation.invalid.extension",
                                            localFile.extension ?: "nullI ",
                                            expectedExtension!!,
                                            deviceTypeComboBox.component.selectedItem!!
                                        )
                                    )

                                    else -> null
                                }
                            }
                    }
                }.visibleIf(localFileRadioButton.selected)
            }

            flashingOptionsGroup = group(MpyBundle.message("flash.dialog.group.flashing.options")) {
                row {
                    eraseFlashCheckBox = checkBox(MpyBundle.message("flash.dialog.erase.flash.checkbox"))
                        .gap(RightGap.SMALL)
                }

                connectAfterRow = row {
                    connectAfterCheckBox = checkBox(MpyBundle.message("flash.dialog.connect.after.checkbox"))
                        .applyToComponent {
                            isSelected = true
                        }
                }

                /*row {
                    eraseFileSystemAfter = checkBox(MpyBundle.message("flash.dialog.erase.filesystem.checkbox"))
                        .enabledIf(connectAfterCheckBox.selected)
                        .gap(RightGap.SMALL)
                        .applyToComponent {
                            toolTipText = MpyBundle.message("flash.dialog.erase.filesystem.tooltip")
                        }

                    contextHelp(MpyBundle.message("flash.dialog.erase.filesystem.help"))
                }*/
            }
        }.also {
            // Initialize cascading dropdowns with current board data
            updateDeviceTypeComboBox()
        }
    }

    private fun updateDeviceTypeComboBox() {
        val deviceTypes = firmwareService.supportedPorts
        val model = deviceTypeComboBox.component.model as MutableCollectionComboBoxModel<String>

        model.removeAll()
        deviceTypes.forEach { model.add(it.toUpperCasePreservingASCIIRules()) }

        if (deviceTypes.isNotEmpty()) {
            deviceTypeComboBox.component.selectedIndex = 0
            onDeviceTypeSelected()
        }
    }

    private fun onDeviceTypeSelected() {
        val selectedDeviceType = deviceTypeComboBox.component.selectedItem as? String ?: return
        val mcus = firmwareService.getMcusForPort(selectedDeviceType, boards)

        val model = mcuComboBox.component.model as MutableCollectionComboBoxModel<String>
        model.removeAll()
        mcus.forEach { model.add(it.toUpperCasePreservingASCIIRules()) }

        if (mcus.isNotEmpty()) {
            mcuComboBox.component.selectedIndex = 0
            onMcuSelected()
        } else {
            clearDownstreamComboBoxes(mcuComboBox.component)
        }
    }

    private fun onMcuSelected() {
        val selectedMcu = mcuComboBox.component.selectedItem as? String ?: return
        val mcuBoards = firmwareService.getBoardsForMcu(selectedMcu, boards)

        val model = boardVariantComboBox.component.model as MutableCollectionComboBoxModel<String>
        model.removeAll()
        mcuBoards.forEach { model.add(it.name) }

        if (mcuBoards.isNotEmpty()) {
            boardVariantComboBox.component.selectedIndex = 0
            onBoardVariantSelected()
        } else {
            clearDownstreamComboBoxes(boardVariantComboBox.component)
        }
    }

    private fun onBoardVariantSelected() {
        val selectedMcu = mcuComboBox.component.selectedItem as? String ?: return
        val selectedBoardName = boardVariantComboBox.component.selectedItem as? String ?: return

        // Find the selected board
        selectedBoard = firmwareService.getBoardsForMcu(selectedMcu, boards)
            .find { it.name == selectedBoardName }

        selectedBoard?.let { board ->
            val variants = firmwareService.getFirmwareVariants(board)

            val model = firmwareVariantComboBox.component.model as MutableCollectionComboBoxModel<String>
            model.removeAll()
            variants.forEach { model.add(it) }

            if (variants.isNotEmpty()) {
                firmwareVariantComboBox.component.selectedIndex = 0
                onFirmwareVariantSelected()
            } else {
                clearDownstreamComboBoxes(firmwareVariantComboBox.component)
            }
        }
    }

    private fun onFirmwareVariantSelected() {
        val board = selectedBoard ?: return
        val selectedVariant = firmwareVariantComboBox.component.selectedItem as? String ?: return

        val versions = firmwareService.getFirmwareVersions(board, selectedVariant).toMutableList()

        val model = versionComboBox.component.model as MutableCollectionComboBoxModel<String>
        model.removeAll()

        val onlySupportedInPreview =
            versions
                .filter { !it.contains(PREVIEW_FIRMWARE_STRING) }
                .map { it }
                .isEmpty()

        if (!showPreviewReleasesCheckBox.component.isSelected && !onlySupportedInPreview) {
            versions.removeIf { it.contains(PREVIEW_FIRMWARE_STRING) }
        }

        if (!showOlderVersionsCheckBox.component.isSelected) {
            val latestRelease = versions.first()
            val latestReleasePartToKeep = latestRelease.substringBeforeLast(".")

            versions.removeIf { !it.contains(latestReleasePartToKeep) && !it.contains(PREVIEW_FIRMWARE_STRING) }
        }

        versions.forEach { model.add(it) }

        if (versions.isNotEmpty()) {
            versionComboBox.component.selectedIndex = 0
        }
    }

    private fun clearDownstreamComboBoxes(fromComboBox: ComboBox<String>) {
        when (fromComboBox) {
            deviceTypeComboBox -> {
                (mcuComboBox.component.model as MutableCollectionComboBoxModel<String>).removeAll()
                clearDownstreamComboBoxes(mcuComboBox.component)
            }

            mcuComboBox -> {
                (boardVariantComboBox.component.model as MutableCollectionComboBoxModel<String>).removeAll()
                clearDownstreamComboBoxes(boardVariantComboBox.component)
            }

            boardVariantComboBox -> {
                (firmwareVariantComboBox.component.model as MutableCollectionComboBoxModel<String>).removeAll()
                clearDownstreamComboBoxes(firmwareVariantComboBox.component)
            }

            firmwareVariantComboBox -> {
                (versionComboBox.component.model as MutableCollectionComboBoxModel<String>).removeAll()
            }
        }
    }

    private fun updatePortList() {
        val filterManufacturers = filterManufacturersCheckBox.component.isSelected

        val ports = deviceService.listSerialPorts(filterManufacturers)

        portSelectModel.items
            .filterNot { it in ports || it == portSelectModel.selectedItem }
            .forEach { portSelectModel.remove(it) }

        val newPorts = ports.filterNot { portSelectModel.contains(it) }
        portSelectModel.addAll(portSelectModel.size, newPorts)

        val selectedItem = portSelectModel.selectedItem

        if (portSelectModel.isEmpty && (selectedItem == null || selectedItem.toString().isBlank())) {
            portSelectModel.selectedItem = EMPTY_PORT_NAME_TEXT
        }
    }

    private fun updateVolumeList() {
        val flasher = firmwareService.getMpyFlasherFromDeviceType(
            deviceTypeComboBox.getSafely(MpyBundle.message("flash.dialog.device.type.label"))
        )

        val newVolumes = (flasher as? MpyUf2Flasher)?.findCompatibleUf2Volumes() ?: emptyList()

        val newVolumesList = newVolumes.map { it.absolutePathString() }

        val selectedItem = volumeSelectModel.selectedItem

        volumeSelectModel.items
            .filterNot { it in newVolumesList || it == volumeSelectModel.selectedItem }
            .forEach { volumeSelectModel.remove(it) }

        val filteredNewVolumesList = newVolumesList.filterNot { volumeSelectModel.contains(it) }
        volumeSelectModel.addAll(volumeSelectModel.size, filteredNewVolumesList)

        if (volumeSelectModel.isEmpty && (selectedItem == null || selectedItem.toString().isBlank())) {
            volumeSelectModel.selectedItem = EMPTY_VOLUME_TEXT
        }
    }

    private fun formatReadableDateTime(isoTimestamp: String): String {
        val updateTime = if (isoTimestamp.endsWith("Z") || isoTimestamp.contains("+")) {
            // Has timezone info, parse directly
            Instant.parse(isoTimestamp)
        } else {
            // No timezone, assume UTC
            LocalDateTime.parse(isoTimestamp).toInstant(ZoneOffset.UTC)
        }

        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a")
            .withZone(ZoneId.systemDefault())
        return formatter.format(updateTime)
    }

    override fun doOKAction() {
        // Validate interpreter
        val interpreterResult = interpreterService.checkInterpreterValid()
        if (!interpreterResult.isOk) {
            Messages.showErrorDialog(
                project,
                interpreterResult.errorMessage,
                MpyBundle.message("flash.dialog.error.python.interpreter.required.title")
            )
            return // Return to the original dialog without closing it abruptly
        }

        // Validate dependencies
        val dependenciesResult = interpreterService.checkDependenciesValid()
        if (!dependenciesResult.isOk) {
            // Dependencies quick fix is never null
            val quickFix = dependenciesResult.quickFix!!

            val result = Messages.showDialog(
                project,
                dependenciesResult.errorMessage,
                MpyBundle.message("flash.dialog.error.dependencies.required.title"),
                arrayOf(quickFix.fixButtonText, MpyBundle.message("flash.dialog.error.dependencies.cancel.button")),
                0, // Default button index (Install)
                Messages.getErrorIcon()
            )

            // Exit back to the parent dialog in both cases without abruptly closing it
            if (result == 0) { // Install button clicked
                quickFix.run(null)
                return
            } else {
                return
            }
        }

        runWithModalProgressBlocking(project, MpyBundle.message("flash.dialog.progress.flashing")) {
            reportRawProgress { reporter ->
                try {
                    val board = boards.find {
                        it.name == boardVariantComboBox.component.selectedItem
                    } ?: throw RuntimeException(MpyBundle.message("flash.dialog.error.no.board.selected"))

                    val flasherForBoard = firmwareService.getMpyFlasherFromDeviceType(board.port)

                    val firmwareVariant = firmwareVariantComboBox.getSafely(
                        MpyBundle.message("flash.dialog.firmware.variant.label").removeSuffix(":")
                    )
                    val version =
                        versionComboBox.getSafely(MpyBundle.message("flash.dialog.version.label").removeSuffix(":"))
                    val target = if (isEsp) {
                        portSelectComboBox.getSafely(
                            MpyBundle.message("flash.dialog.serial.port.label").removeSuffix(":")
                        )
                    } else {
                        volumeSelectComboBox.getSafely(MpyBundle.message("flash.dialog.target.volume.label"))
                    }
                    val eraseFlash = eraseFlashCheckBox.component.isSelected
                    val connectAfter = connectAfterCheckBox.component.isSelected

                    val localFirmwarePath = if (localFileRadioButton.component.isSelected) {
                        localFileTextFieldWithBrowseButton.component.text
                    } else null

                    // Validate the flasher parameters
                    val flasherValidation = flasherForBoard.validate(target)

                    // Throw error if flasher parameters aren't valid
                    if (!flasherValidation.isOk) {
                        withContext(Dispatchers.EDT) {
                            Messages.showErrorDialog(
                                project,
                                flasherValidation.errorMessage,
                                MpyBundle.message("flash.dialog.error.invalid.parameters.title")
                            )
                        }
                        // Return to the original dialog without closing it abruptly
                        return@runWithModalProgressBlocking
                    }

                    val offsetMapTouse = mpyBoardsJson?.espMcuToOffset ?: firmwareService.espMcuToOffset

                    // Set the board's offset for ESP
                    board.offset = offsetMapTouse[board.mcu.toLowerCasePreservingASCIIRules()]

                    // Perform the flashing operation, download firmware if needed
                    firmwareService.getFirmwareAndFlash(
                        reporter,
                        board,
                        firmwareVariant,
                        version,
                        target,
                        eraseFlash,
                        localFirmwarePath
                    )

                    // Handle after tasks
                    if (isEsp && connectAfter) {
                        deviceService.doConnect(reporter, forceLegacyVolumeSupport = true)

                        /*if (eraseFileSystemAfter.component.isSelected && !supportsEraseFlash) {
                            val flashFilesToDelete = deviceService.fileSystemWidget
                                ?.allNodes()
                                ?.filter { it is VolumeRootNode && it.isFileSystemRoot }
                                ?.map { it.fullName }
                                ?.toSet()

                            flashFilesToDelete?.let {
                                deviceService.recursivelySafeDeletePaths(it)
                            }
                        }*/
                    }

                    // If flashing succeeded show confirmation dialog and close the firmware flashing dialog
                    withContext(Dispatchers.EDT) {
                        Messages.showInfoMessage(
                            project,
                            MpyBundle.message("flash.success.message"),
                            MpyBundle.message("flash.success.title")
                        )

                        // Close the parent flashing dialog
                        super.doOKAction()
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.EDT) {
                        showFlashingErrorDialog(project, "${e.javaClass.name}\n${e.localizedMessage}")
                    }
                }
            }
        }
    }

    private fun showFlashingErrorDialog(project: Project, errorMessage: String) {
        object : DialogWrapper(project, true) {
            init {
                title = MpyBundle.message("flash.error.title")
                init()
            }

            override fun createCenterPanel(): JComponent {
                val textArea = JBTextArea(errorMessage)
                textArea.isEditable = false
                textArea.lineWrap = false
                textArea.caretColor = textArea.background

                val scrollPane = JBScrollPane(textArea)
                scrollPane.preferredSize = Dimension(600, 400)

                return scrollPane
            }
        }.show()
    }
}