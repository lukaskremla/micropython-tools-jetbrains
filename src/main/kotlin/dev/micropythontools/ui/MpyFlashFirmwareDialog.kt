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
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.*
import dev.micropythontools.core.MpyPythonInterpreterService
import dev.micropythontools.firmware.Board
import dev.micropythontools.firmware.IncompatibleBoardsJsonVersionException
import dev.micropythontools.firmware.MpyFirmwareService
import dev.micropythontools.firmware.PREVIEW_FIRMWARE_STRING
import dev.micropythontools.i18n.MpyBundle
import dev.micropythontools.settings.EMPTY_PORT_NAME_TEXT
import dev.micropythontools.settings.MpySettingsService
import io.ktor.util.*
import jssc.SerialPort
import jssc.SerialPortException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

internal class MpyFlashFirmwareDialog(private val project: Project) : DialogWrapper(project, true) {
    private val settings = project.service<MpySettingsService>()
    private val deviceService = project.service<dev.micropythontools.communication.MpyDeviceService>()
    private val firmwareService = project.service<MpyFirmwareService>()
    private val interpreterService = project.service<MpyPythonInterpreterService>()

    // Current board data
    private var currentBoards: List<Board> = emptyList()

    // UI components
    private lateinit var statusComment: Cell<JEditorPane>

    private lateinit var enableManualEditingCheckbox: Cell<JBCheckBox>
    private lateinit var filterManufacturersCheckBox: Cell<JBCheckBox>
    private lateinit var portSelectComboBox: Cell<ComboBox<String>>

    private lateinit var deviceTypeComboBox: Cell<ComboBox<String>>
    private lateinit var mcuComboBox: Cell<ComboBox<String>>

    private lateinit var microPythonOrgRadioButton: Cell<JBRadioButton>
    private lateinit var localFileRadioButton: Cell<JBRadioButton>

    private lateinit var boardVariantComboBox: Cell<ComboBox<String>>
    private lateinit var firmwareVariantComboBox: Cell<ComboBox<String>>

    private lateinit var showOlderVersionsCheckBox: Cell<JBCheckBox>
    private lateinit var showPreviewReleasesCheckBox: Cell<JBCheckBox>
    private lateinit var versionComboBox: Cell<ComboBox<String>>

    private lateinit var localFileTextFieldWithBrowseButton: Cell<TextFieldWithBrowseButton>

    private lateinit var eraseFlashCheckBox: Cell<JBCheckBox>
    private lateinit var eraseFileSystemAfter: Cell<JBCheckBox>

    // Selected board for tracking
    private var selectedBoard: Board? = null

    // Initialize combo box models
    private val portSelectModel = MutableCollectionComboBoxModel<String>()

    init {
        title = "Flash Firmware/MicroPython"
        setOKButtonText("Flash")

        // Load cached boards immediately (instant)
        currentBoards = firmwareService.getCachedBoards()

        init() // Required for DialogWrapper

        try {
            // Update the cached boards json
            firmwareService.updateCachedBoards()

            // Retrieve cached boards
            val newBoards = firmwareService.getCachedBoards()

            val timeStamp = firmwareService.getCachedBoardsTimestamp()

            if (newBoards != currentBoards && newBoards.isNotEmpty()) {
                updateBoardsInUI(newBoards)
            }

            setStatusUpToDate(timeStamp)
        } catch (_: IncompatibleBoardsJsonVersionException) {
            val timeStamp = firmwareService.getCachedBoardsTimestamp()

            setStatusIncompatible(timeStamp)
        } catch (_: Throwable) {
            val timeStamp = firmwareService.getCachedBoardsTimestamp()

            setStatusFailed(timeStamp)
        }
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

        return panel {
            row {
                statusComment = comment("Checking for firmware updates...")
            }

            group("Connection") {
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

                row("Serial port:") {
                    portSelectComboBox = comboBox(portSelectModel)
                        .columns(20)
                        .applyToComponent {
                            addPopupMenuListener(object : PopupMenuListener {
                                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                                    updatePortList()
                                }

                                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
                                override fun popupMenuCanceled(e: PopupMenuEvent?) {}
                            })
                        }
                        .validationOnApply {
                            try {
                                val portToConnectTo = (portSelectComboBox.component.selectedItem ?: "").toString()
                                val port = SerialPort(portToConnectTo)
                                port.openPort()
                                port.closePort()
                                return@validationOnApply null
                            } catch (e: SerialPortException) {
                                return@validationOnApply error(e.exceptionType)
                            }
                        }
                }.layout(RowLayout.LABEL_ALIGNED)
            }

            group("Firmware Selection") {
                row("Device type:") {
                    deviceTypeComboBox = comboBox(deviceTypeModel)
                        .columns(10)
                        .applyToComponent {
                            addActionListener {
                                onDeviceTypeSelected()

                                selectedItem?.let {
                                    val selectedDeviceType = it.toString().toLowerCasePreservingASCIIRules()

                                    val supportsEraseFlash =
                                        selectedDeviceType.contains("esp") || selectedDeviceType.contains("stm")

                                    eraseFlashCheckBox.enabled(supportsEraseFlash)
                                }
                            }
                        }
                }.layout(RowLayout.LABEL_ALIGNED)

                row("MCU:") {
                    mcuComboBox = comboBox(mcuModel)
                        .columns(10)
                        .applyToComponent {
                            addActionListener {
                                onMcuSelected()
                            }
                        }
                }.layout(RowLayout.LABEL_ALIGNED)

                buttonsGroup {
                    row("Firmware source:") {
                        microPythonOrgRadioButton = radioButton("MicroPython.org")
                            .applyToComponent {
                                isSelected = true // This is the default option
                            }

                        localFileRadioButton = radioButton("Local file")
                    }
                }

                indent {
                    row("Board variant:") {
                        boardVariantComboBox = comboBox(boardVariantModel)
                            .columns(25)
                            .applyToComponent {
                                addActionListener {
                                    onBoardVariantSelected()
                                }
                            }
                            .comment("<a>View on MicroPython.org</a>", action = {
                                val boardId = firmwareService.getCachedBoards()
                                    .find { it.name == boardVariantComboBox.component.selectedItem }?.id ?: "UNKNOWN"

                                BrowserUtil.open("https://micropython.org/download/$boardId")
                            })
                    }.layout(RowLayout.LABEL_ALIGNED)

                    row("Firmware variant:") {
                        firmwareVariantComboBox = comboBox(firmwareVariantModel)
                            .columns(25)
                            .applyToComponent {
                                addActionListener {
                                    onFirmwareVariantSelected()
                                }
                            }
                    }.layout(RowLayout.LABEL_ALIGNED)

                    row {
                        showOlderVersionsCheckBox = checkBox("Show older versions")
                            .applyToComponent {
                                addActionListener {
                                    onFirmwareVariantSelected()
                                }
                            }
                    }

                    row {
                        showPreviewReleasesCheckBox = checkBox("Show preview releases")
                            .gap(RightGap.SMALL)
                            .applyToComponent {
                                addActionListener {
                                    onFirmwareVariantSelected()
                                }
                            }

                        contextHelp("Shows/Hides preview releases. Selecting some newer boards might override this option as they are only supported in preview releases.")
                    }

                    row("Version:") {
                        versionComboBox = comboBox(versionModel)
                            .columns(25)
                    }.layout(RowLayout.LABEL_ALIGNED)
                }.visibleIf(microPythonOrgRadioButton.selected)

                indent {
                    row("Local file:") {
                        localFileTextFieldWithBrowseButton = textFieldWithBrowseButton(
                            FileChooserDescriptor(true, false, false, false, false, false)
                                .withTitle("Select Firmware File")
                                .withRoots(project.guessProjectDir())
                                .withExtensionFilter("Compatible firmware extensions", "bin", "uf2", "dfu"),
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
                                    ?: return@validationOnApply error("Please select a device type first")

                                val expectedExtension =
                                    firmwareService.getExtensionForPort(selectedDeviceType).removePrefix(".")

                                when {
                                    localFilePath.isBlank() -> error("Please select a firmware file")
                                    localFile == null -> error("Invalid file path")
                                    localFile.extension != expectedExtension -> error("Invalid extension \".${localFile.extension}\", expected \".$expectedExtension\" for the \"${deviceTypeComboBox.component.selectedItem}\" device type")
                                    else -> null
                                }
                            }
                    }
                }.visibleIf(localFileRadioButton.selected)
            }

            group("Flashing Options") {
                row {
                    eraseFlashCheckBox = checkBox("Erase flash first")
                        .comment("Only ESP32, ESP8266 and STM32 devices support flash erase")
                }

                row {
                    eraseFileSystemAfter = checkBox("Erase MicroPython filesystem after flashing")
                }
            }
        }.also {
            // Initialize cascading dropdowns with current board data
            updateDeviceTypeComboBox()
        }
    }

    private fun setStatusUpToDate(cachedTimestamp: String) {
        val formattedDate = formatReadableDateTime(cachedTimestamp)
        statusComment.component.text = "Firmware index up-to date, created on: $formattedDate"
    }

    private fun setStatusIncompatible(cachedTimestamp: String) {
        val formattedDate = formatReadableDateTime(cachedTimestamp)
        statusComment.component.text =
            "Remote index is incompatible, consider updating the plugin.<br>Using cached data from: $formattedDate"
    }

    private fun setStatusFailed(cachedTimestamp: String) {
        val formattedDate = formatReadableDateTime(cachedTimestamp)
        statusComment.component.text = "Up-to date check failed.<br>Using cached data from: $formattedDate"
    }

    private fun updateDeviceTypeComboBox() {
        val deviceTypes = firmwareService.getDeviceTypes()
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
        val mcus = firmwareService.getMcusForPort(selectedDeviceType)

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
        val boards = firmwareService.getBoardsForMcu(selectedMcu)

        val model = boardVariantComboBox.component.model as MutableCollectionComboBoxModel<String>
        model.removeAll()
        boards.forEach { model.add(it.name) }

        if (boards.isNotEmpty()) {
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
        selectedBoard = firmwareService.getBoardsForMcu(selectedMcu)
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

    private fun updateBoardsInUI(newBoards: List<Board>) {
        // Save current selections
        val savedDeviceType = deviceTypeComboBox.component.selectedItem as? String
        val savedMcu = mcuComboBox.component.selectedItem as? String
        val savedBoardVariant = boardVariantComboBox.component.selectedItem as? String
        val savedFirmwareVariant = firmwareVariantComboBox.component.selectedItem as? String
        val savedVersion = versionComboBox.component.selectedItem as? String

        // Update underlying data
        currentBoards = newBoards

        // Rebuild device type dropdown
        updateDeviceTypeComboBox()

        // Try to restore selections
        if (savedDeviceType != null) {
            deviceTypeComboBox.component.selectedItem = savedDeviceType
            if (savedMcu != null) {
                mcuComboBox.component.selectedItem = savedMcu
                if (savedBoardVariant != null) {
                    boardVariantComboBox.component.selectedItem = savedBoardVariant
                    if (savedFirmwareVariant != null) {
                        firmwareVariantComboBox.component.selectedItem = savedFirmwareVariant
                        if (savedVersion != null) {
                            versionComboBox.component.selectedItem = savedVersion
                        }
                    }
                }
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
        val interpreterResult = interpreterService.checkInterpreterValid()
        if (!interpreterResult.isOk) {
            Messages.showErrorDialog(
                project,
                interpreterResult.errorMessage,
                "Python Interpreter Required"
            )
            return
        }

        val dependenciesResult = interpreterService.checkDependenciesValid()
        if (!dependenciesResult.isOk) {
            val quickFix = dependenciesResult.quickFix

            if (quickFix != null) {
                val result = Messages.showDialog(
                    project,
                    dependenciesResult.errorMessage,
                    "Dependencies Required",
                    arrayOf(quickFix.fixButtonText, "Cancel"),
                    0, // Default button index (Install)
                    Messages.getErrorIcon()
                )

                if (result == 0) { // Install button clicked
                    quickFix.run(null)
                    return
                } else {
                    return
                }
            }
        }

        var result = false

        runWithModalProgressBlocking(project, "Flashing Firmware...") {
            reportRawProgress { reporter ->
                val board = firmwareService.getCachedBoards().find {
                    it.name == boardVariantComboBox.component.selectedItem
                } ?: throw RuntimeException("Failed to find selected board")

                val firmwarePath = if (microPythonOrgRadioButton.component.isSelected) {
                    reporter.text("Downloading MicroPython firmware...")
                    firmwareService.downloadFirmwareToTemp(
                        board,
                        (firmwareVariantComboBox.component.selectedItem
                            ?: throw RuntimeException("Failed to find selected firmware variant")).toString(),
                        (versionComboBox.component.selectedItem
                            ?: throw RuntimeException("Failed to find selected version")).toString()
                    )
                } else {
                    localFileTextFieldWithBrowseButton.component.text
                }

                val firmwareFile =
                    LocalFileSystem.getInstance().findFileByPath(firmwarePath)
                        ?: throw RuntimeException("Firmware file doesn't exist")

                firmwareService.flashFirmware(
                    reporter,
                    (portSelectComboBox.component.selectedItem
                        ?: throw RuntimeException("Failed to get selected port ")).toString(),
                    firmwareFile.path,
                    (mcuComboBox.component.selectedItem
                        ?: throw RuntimeException("Failed to get selected MCU")).toString(),
                    board.offset,
                    eraseFlashCheckBox.component.isSelected,
                    eraseFileSystemAfter.component.isSelected
                )
            }
        }

        super.doOKAction()
    }
}