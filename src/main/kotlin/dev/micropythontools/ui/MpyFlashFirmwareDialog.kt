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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import dev.micropythontools.firmware.Board
import dev.micropythontools.firmware.MpyFirmwareService
import dev.micropythontools.settings.EMPTY_PORT_NAME_TEXT
import io.ktor.util.*
import javax.swing.JComponent
import javax.swing.JLabel

internal class MpyFlashFirmwareDialog(private val project: Project) : DialogWrapper(project, true) {
    private val firmwareService = project.service<MpyFirmwareService>()

    // Current board data
    private var currentBoards: List<Board> = emptyList()

    // UI components
    private lateinit var serialPortSelector: ComboBox<String>
    private lateinit var deviceTypeComboBox: ComboBox<String>
    private lateinit var mcuComboBox: ComboBox<String>
    private lateinit var boardVariantComboBox: ComboBox<String>
    private lateinit var firmwareVariantComboBox: ComboBox<String>
    private lateinit var versionComboBox: ComboBox<String>
    private lateinit var statusLabel: JLabel

    // Selected board for tracking
    private var selectedBoard: Board? = null

    init {
        title = "Flash Firmware/MicroPython"

        // Load cached boards immediately (instant)
        currentBoards = firmwareService.getCachedBoards()

        println("Got current boards size: \"${currentBoards.size}\"")

        init() // Required for DialogWrapper

        // Start background update
        ApplicationManager.getApplication().executeOnPooledThread {
            updateStatus("Checking for firmware updates...")

            firmwareService.updateCachedBoards()
            val newBoards = firmwareService.getCachedBoards()

            println("Got updated boards size: \"${currentBoards.size}\"")

            ApplicationManager.getApplication().invokeLater {
                if (newBoards != currentBoards && newBoards.isNotEmpty()) {
                    updateBoardsInUI(newBoards)
                    updateStatus("Firmware list updated")
                } else {
                    updateStatus("")
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val deviceService = project.service<dev.micropythontools.communication.MpyDeviceService>()

        // Initialize combo box models
        val portModel = MutableCollectionComboBoxModel<String>()
        val deviceTypeModel = MutableCollectionComboBoxModel<String>()
        val mcuModel = MutableCollectionComboBoxModel<String>()
        val boardVariantModel = MutableCollectionComboBoxModel<String>()
        val firmwareVariantModel = MutableCollectionComboBoxModel<String>()
        val versionModel = MutableCollectionComboBoxModel<String>()

        // Helper to update port list
        fun updatePortList() {
            val ports = deviceService.listSerialPorts(true)
            val currentSelection = portModel.selectedItem

            portModel.items
                .filterNot { it in ports || it == currentSelection }
                .forEach { portModel.remove(it) }

            val newPorts = ports.filterNot { portModel.contains(it) }
            portModel.addAll(portModel.size, newPorts)
        }

        // Initial population
        updatePortList()

        // Set initial selection from settings
        val settings = project.service<dev.micropythontools.settings.MpySettingsService>()
        val portName = settings.state.portName
        if (!portName.isNullOrBlank()) {
            portModel.selectedItem = portName
        } else if (portModel.isEmpty) {
            portModel.selectedItem = EMPTY_PORT_NAME_TEXT
        }

        return panel {
            row {
                statusLabel = label("")
                    .applyToComponent {
                        foreground = UIUtil.getContextHelpForeground()
                    }
                    .component
            }

            group("Connection") {
                row("Serial Port:") {
                    serialPortSelector = comboBox(portModel)
                        .columns(20)
                        .applyToComponent {
                            addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
                                override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {
                                    updatePortList()
                                }

                                override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {}
                                override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
                            })
                        }
                        .component
                }.layout(RowLayout.LABEL_ALIGNED)
            }

            group("Firmware Selection") {
                row("Device Type:") {
                    deviceTypeComboBox = comboBox(deviceTypeModel)
                        .columns(20)
                        .applyToComponent {
                            addActionListener {
                                onDeviceTypeSelected()
                            }
                        }
                        .component
                }.layout(RowLayout.LABEL_ALIGNED)

                row("MCU:") {
                    mcuComboBox = comboBox(mcuModel)
                        .columns(20)
                        .applyToComponent {
                            addActionListener {
                                onMcuSelected()
                            }
                        }
                        .component
                }.layout(RowLayout.LABEL_ALIGNED)

                row("Board Variant:") {
                    boardVariantComboBox = comboBox(boardVariantModel)
                        .columns(20)
                        .applyToComponent {
                            addActionListener {
                                onBoardVariantSelected()
                            }
                        }
                        .component
                }.layout(RowLayout.LABEL_ALIGNED)

                row("Firmware Variant:") {
                    firmwareVariantComboBox = comboBox(firmwareVariantModel)
                        .columns(20)
                        .applyToComponent {
                            addActionListener {
                                onFirmwareVariantSelected()
                            }
                        }
                        .component
                }.layout(RowLayout.LABEL_ALIGNED)

                row("Version:") {
                    versionComboBox = comboBox(versionModel)
                        .columns(20)
                        .component
                }.layout(RowLayout.LABEL_ALIGNED)
            }
        }.also {
            // Initialize cascading dropdowns with current board data
            updateDeviceTypeComboBox()
        }
    }

    private fun updateStatus(message: String) {
        statusLabel.text = message
    }

    private fun updateDeviceTypeComboBox() {
        val deviceTypes = firmwareService.getDeviceTypes()
        val model = deviceTypeComboBox.model as MutableCollectionComboBoxModel<String>

        model.removeAll()
        deviceTypes.forEach { model.add(it.toUpperCasePreservingASCIIRules()) }

        if (deviceTypes.isNotEmpty()) {
            deviceTypeComboBox.selectedIndex = 0
            onDeviceTypeSelected()
        }
    }

    private fun onDeviceTypeSelected() {
        val selectedDeviceType = deviceTypeComboBox.selectedItem as? String ?: return
        val mcus = firmwareService.getMcusForPort(selectedDeviceType)

        val model = mcuComboBox.model as MutableCollectionComboBoxModel<String>
        model.removeAll()
        mcus.forEach { model.add(it.toUpperCasePreservingASCIIRules()) }

        if (mcus.isNotEmpty()) {
            mcuComboBox.selectedIndex = 0
            onMcuSelected()
        } else {
            clearDownstreamComboBoxes(mcuComboBox)
        }
    }

    private fun onMcuSelected() {
        val selectedMcu = mcuComboBox.selectedItem as? String ?: return
        val boards = firmwareService.getBoardsForMcu(selectedMcu)

        val model = boardVariantComboBox.model as MutableCollectionComboBoxModel<String>
        model.removeAll()
        boards.forEach { model.add(it.name) }

        if (boards.isNotEmpty()) {
            boardVariantComboBox.selectedIndex = 0
            onBoardVariantSelected()
        } else {
            clearDownstreamComboBoxes(boardVariantComboBox)
        }
    }

    private fun onBoardVariantSelected() {
        val selectedMcu = mcuComboBox.selectedItem as? String ?: return
        val selectedBoardName = boardVariantComboBox.selectedItem as? String ?: return

        // Find the selected board
        selectedBoard = firmwareService.getBoardsForMcu(selectedMcu)
            .find { it.name == selectedBoardName }

        selectedBoard?.let { board ->
            val variants = firmwareService.getFirmwareVariants(board)

            val model = firmwareVariantComboBox.model as MutableCollectionComboBoxModel<String>
            model.removeAll()
            variants.forEach { model.add(it) }

            if (variants.isNotEmpty()) {
                firmwareVariantComboBox.selectedIndex = 0
                onFirmwareVariantSelected()
            } else {
                clearDownstreamComboBoxes(firmwareVariantComboBox)
            }
        }
    }

    private fun onFirmwareVariantSelected() {
        val board = selectedBoard ?: return
        val selectedVariant = firmwareVariantComboBox.selectedItem as? String ?: return

        val versions = firmwareService.getFirmwareVersions(board, selectedVariant)

        val model = versionComboBox.model as MutableCollectionComboBoxModel<String>
        model.removeAll()
        versions.forEach { model.add(it) }

        if (versions.isNotEmpty()) {
            versionComboBox.selectedIndex = 0
        }
    }

    private fun clearDownstreamComboBoxes(fromComboBox: ComboBox<String>) {
        when (fromComboBox) {
            deviceTypeComboBox -> {
                (mcuComboBox.model as MutableCollectionComboBoxModel<String>).removeAll()
                clearDownstreamComboBoxes(mcuComboBox)
            }

            mcuComboBox -> {
                (boardVariantComboBox.model as MutableCollectionComboBoxModel<String>).removeAll()
                clearDownstreamComboBoxes(boardVariantComboBox)
            }

            boardVariantComboBox -> {
                (firmwareVariantComboBox.model as MutableCollectionComboBoxModel<String>).removeAll()
                clearDownstreamComboBoxes(firmwareVariantComboBox)
            }

            firmwareVariantComboBox -> {
                (versionComboBox.model as MutableCollectionComboBoxModel<String>).removeAll()
            }
        }
    }

    private fun updateBoardsInUI(newBoards: List<Board>) {
        // Save current selections
        val savedDeviceType = deviceTypeComboBox.selectedItem as? String
        val savedMcu = mcuComboBox.selectedItem as? String
        val savedBoardVariant = boardVariantComboBox.selectedItem as? String
        val savedFirmwareVariant = firmwareVariantComboBox.selectedItem as? String
        val savedVersion = versionComboBox.selectedItem as? String

        // Update underlying data
        currentBoards = newBoards

        // Rebuild device type dropdown
        updateDeviceTypeComboBox()

        // Try to restore selections
        if (savedDeviceType != null) {
            deviceTypeComboBox.selectedItem = savedDeviceType
            if (savedMcu != null) {
                mcuComboBox.selectedItem = savedMcu
                if (savedBoardVariant != null) {
                    boardVariantComboBox.selectedItem = savedBoardVariant
                    if (savedFirmwareVariant != null) {
                        firmwareVariantComboBox.selectedItem = savedFirmwareVariant
                        if (savedVersion != null) {
                            versionComboBox.selectedItem = savedVersion
                        }
                    }
                }
            }
        }
    }
}