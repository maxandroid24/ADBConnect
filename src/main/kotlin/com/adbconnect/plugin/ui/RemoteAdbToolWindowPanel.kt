package com.adbconnect.plugin.ui

import com.adbconnect.plugin.model.ConnectionState
import com.adbconnect.plugin.model.Device
import com.adbconnect.plugin.service.RemoteAdbService
import com.adbconnect.plugin.service.StateMachine
import com.adbconnect.plugin.settings.PluginSettings
import com.adbconnect.plugin.settings.PluginSettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * The main UI panel for the Remote ADB Connector tool window.
 *
 * Observes the [StateMachine] state flow and updates all UI elements
 * reactively. All UI updates are dispatched to the EDT.
 *
 * Layout:
 * - Settings section (host, port, intervals, checkboxes)
 * - Status section (state indicator, metadata)
 * - Connected devices list
 * - Action buttons (Connect / Disconnect)
 */
class RemoteAdbToolWindowPanel(
    private val project: Project
) : Disposable {

    private val log = Logger.getInstance(RemoteAdbToolWindowPanel::class.java)

    private val settings = PluginSettings.getInstance()
    private val stateMachine = StateMachine.getInstance(project)
    private val remoteAdbService = RemoteAdbService.getInstance(project)

    // ── UI Components ────────────────────────────────────────────────────
    private val rootPanel = JPanel(BorderLayout())

    // Settings
    private val hostField = JTextField(20)
    private val adbPortSpinner = JSpinner(SpinnerNumberModel(5037, 1, 65535, 1))
    private val deviceTcpPortSpinner = JSpinner(SpinnerNumberModel(5555, 1, 65535, 1))
    private val pollingSpinner = JSpinner(SpinnerNumberModel(5, 1, 300, 1))
    private val autoConnectCheckbox = JCheckBox("Auto Connect on Startup")
    private val autoDetectCheckbox = JCheckBox("Auto Detect Devices")
    private val autoReconnectCheckbox = JCheckBox("Auto Reconnect")

    // Status
    private val statusIndicatorLabel = JBLabel("⚪ Idle")
    private val hostStatusLabel = JBLabel("Host: —")
    private val lastPollLabel = JBLabel("Last poll: —")
    private val deviceCountLabel = JBLabel("Devices: 0")

    // Devices list
    private val deviceListModel = DefaultListModel<String>()
    private val deviceList = JBList(deviceListModel)

    // Buttons
    private val connectButton = JButton("Connect")
    private val disconnectButton = JButton("Disconnect")

    // Coroutine scope for state observation
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    init {
        Disposer.register(project, this)
        buildUI()
        loadSettings()
        bindActions()
        observeState()
    }

    /**
     * Returns the root Swing component for embedding in the tool window.
     */
    fun getContent(): JComponent = rootPanel

    // ──────────────────────────────────────────────────────────────────────
    // UI Construction
    // ──────────────────────────────────────────────────────────────────────

    private fun buildUI() {
        rootPanel.border = JBUI.Borders.empty(8)

        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)

        mainPanel.add(buildHeaderSection())
        mainPanel.add(Box.createVerticalStrut(12))
        mainPanel.add(buildSettingsSection())
        mainPanel.add(Box.createVerticalStrut(12))
        mainPanel.add(buildStatusSection())
        mainPanel.add(Box.createVerticalStrut(12))
        mainPanel.add(buildDevicesSection())
        mainPanel.add(Box.createVerticalGlue())

        val scrollPane = JBScrollPane(mainPanel)
        scrollPane.border = JBUI.Borders.empty()

        rootPanel.add(scrollPane, BorderLayout.CENTER)
        rootPanel.add(buildButtonPanel(), BorderLayout.SOUTH)
    }

    private fun buildHeaderSection(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.maximumSize = Dimension(Int.MAX_VALUE, 40)

        val title = JBLabel("Remote ADB Connector")
        title.font = title.font.deriveFont(Font.BOLD, 16f)
        title.border = JBUI.Borders.emptyBottom(4)
        panel.add(title, BorderLayout.WEST)

        return panel
    }

    private fun buildSettingsSection(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Settings"),
            JBUI.Borders.empty(4)
        )
        panel.maximumSize = Dimension(Int.MAX_VALUE, 260)

        // Windows Host
        val hostRow = createFormRow("Windows Host:", hostField)
        panel.add(hostRow)
        panel.add(Box.createVerticalStrut(4))

        // ADB Port
        val portRow = createFormRow("ADB Port:", adbPortSpinner)
        panel.add(portRow)
        panel.add(Box.createVerticalStrut(4))

        // Device TCP Port
        val tcpRow = createFormRow("Device TCP Port:", deviceTcpPortSpinner)
        panel.add(tcpRow)
        panel.add(Box.createVerticalStrut(4))

        // Polling Interval
        val pollRow = createFormRow("Polling Interval (s):", pollingSpinner)
        panel.add(pollRow)
        panel.add(Box.createVerticalStrut(8))

        // Checkboxes
        val checkboxPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        checkboxPanel.maximumSize = Dimension(Int.MAX_VALUE, 80)
        val checkboxColumn = JPanel()
        checkboxColumn.layout = BoxLayout(checkboxColumn, BoxLayout.Y_AXIS)
        checkboxColumn.add(autoConnectCheckbox)
        checkboxColumn.add(autoDetectCheckbox)
        checkboxColumn.add(autoReconnectCheckbox)
        checkboxPanel.add(checkboxColumn)
        panel.add(checkboxPanel)

        return panel
    }

    private fun buildStatusSection(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Status"),
            JBUI.Borders.empty(4)
        )
        panel.maximumSize = Dimension(Int.MAX_VALUE, 120)

        statusIndicatorLabel.font = statusIndicatorLabel.font.deriveFont(Font.BOLD, 14f)

        panel.add(statusIndicatorLabel)
        panel.add(Box.createVerticalStrut(4))
        panel.add(hostStatusLabel)
        panel.add(Box.createVerticalStrut(2))
        panel.add(lastPollLabel)
        panel.add(Box.createVerticalStrut(2))
        panel.add(deviceCountLabel)

        return panel
    }

    private fun buildDevicesSection(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Connected Devices"),
            JBUI.Borders.empty(4)
        )
        panel.maximumSize = Dimension(Int.MAX_VALUE, 200)
        panel.preferredSize = Dimension(0, 120)

        deviceList.emptyText.text = "No devices connected"
        deviceList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        val scrollPane = JBScrollPane(deviceList)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun buildButtonPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER, 8, 8))

        connectButton.preferredSize = Dimension(120, 32)
        disconnectButton.preferredSize = Dimension(120, 32)
        disconnectButton.isEnabled = false

        panel.add(connectButton)
        panel.add(disconnectButton)

        return panel
    }

    private fun createFormRow(label: String, component: JComponent): JPanel {
        val row = JPanel(BorderLayout(8, 0))
        row.maximumSize = Dimension(Int.MAX_VALUE, 30)

        val jbLabel = JBLabel(label)
        jbLabel.preferredSize = Dimension(130, 24)
        row.add(jbLabel, BorderLayout.WEST)
        row.add(component, BorderLayout.CENTER)

        return row
    }

    // ──────────────────────────────────────────────────────────────────────
    // Settings Binding
    // ──────────────────────────────────────────────────────────────────────

    private fun loadSettings() {
        val state = settings.state
        hostField.text = state.windowsHost ?: ""
        adbPortSpinner.value = state.adbPort
        deviceTcpPortSpinner.value = state.deviceTcpPort
        pollingSpinner.value = state.pollingIntervalSeconds
        autoConnectCheckbox.isSelected = state.autoConnect
        autoDetectCheckbox.isSelected = state.autoDetectDevices
        autoReconnectCheckbox.isSelected = state.autoReconnect
    }

    private fun saveSettings() {
        val state = settings.state
        state.windowsHost = hostField.text.trim()
        state.adbPort = adbPortSpinner.value as Int
        state.deviceTcpPort = deviceTcpPortSpinner.value as Int
        state.pollingIntervalSeconds = pollingSpinner.value as Int
        state.autoConnect = autoConnectCheckbox.isSelected
        state.autoDetectDevices = autoDetectCheckbox.isSelected
        state.autoReconnect = autoReconnectCheckbox.isSelected
    }

    // ──────────────────────────────────────────────────────────────────────
    // Actions
    // ──────────────────────────────────────────────────────────────────────

    private fun bindActions() {
        connectButton.addActionListener {
            saveSettings()
            val error = remoteAdbService.connect()
            if (error != null) {
                JOptionPane.showMessageDialog(
                    rootPanel,
                    error,
                    "Configuration Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }

        disconnectButton.addActionListener {
            remoteAdbService.disconnect()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // State Observation
    // ──────────────────────────────────────────────────────────────────────

    private fun observeState() {
        scope.launch {
            stateMachine.state.collectLatest { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: ConnectionState) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            // Status indicator
            statusIndicatorLabel.text = "${state.statusIndicator()} ${state.displayName()}"
            statusIndicatorLabel.foreground = getStateColor(state)

            // Host status
            val host = settings.state.windowsHost
            hostStatusLabel.text = if (host.isNullOrBlank()) "Host: —" else "Host: $host"

            // Last poll time
            val lastTransition = stateMachine.lastTransitionTime
            lastPollLabel.text = "Last update: ${timeFormatter.format(lastTransition)}"

            // Device count and list
            val devices = extractDevices(state)
            deviceCountLabel.text = "Devices: ${devices.size}"

            deviceListModel.clear()
            for (device in devices) {
                val stateEmoji = when (device.state) {
                    com.adbconnect.plugin.model.DeviceState.DEVICE -> "🟢"
                    com.adbconnect.plugin.model.DeviceState.OFFLINE -> "🔴"
                    com.adbconnect.plugin.model.DeviceState.UNAUTHORIZED -> "🟡"
                    else -> "⚪"
                }
                val addr = device.tcpAddress?.let { " ($it)" } ?: ""
                deviceListModel.addElement("$stateEmoji ${device.displayName()}$addr")
            }

            // Button states
            val isIdle = state is ConnectionState.Idle || state is ConnectionState.Error
            connectButton.isEnabled = isIdle
            disconnectButton.isEnabled = !isIdle

            // Disable settings editing while connected
            val settingsEnabled = isIdle
            hostField.isEnabled = settingsEnabled
            adbPortSpinner.isEnabled = settingsEnabled
            deviceTcpPortSpinner.isEnabled = settingsEnabled
            pollingSpinner.isEnabled = settingsEnabled
        }
    }

    private fun extractDevices(state: ConnectionState): List<Device> = when (state) {
        is ConnectionState.PreparingDevice -> listOf(state.device)
        is ConnectionState.Connecting -> listOf(state.device)
        is ConnectionState.Connected -> state.devices
        is ConnectionState.MonitoringLinux -> state.devices
        else -> emptyList()
    }

    private fun getStateColor(state: ConnectionState): Color = when (state) {
        is ConnectionState.Idle -> JBColor.GRAY
        is ConnectionState.WaitingForWindowsServer,
        is ConnectionState.WaitingForWindowsDevice,
        is ConnectionState.PreparingDevice,
        is ConnectionState.Connecting -> JBColor.YELLOW

        is ConnectionState.Connected,
        is ConnectionState.MonitoringLinux -> JBColor.GREEN

        is ConnectionState.Reconnecting -> JBColor.ORANGE
        is ConnectionState.Error -> JBColor.RED
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun dispose() {
        scope.cancel()
    }
}
