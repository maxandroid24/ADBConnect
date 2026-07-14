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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.*
import java.awt.datatransfer.StringSelection
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

    private val settingsTitleLabel = JBLabel("▶ Settings").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val settingsContentPanel = JPanel()
    private var isSettingsExpanded = false

    // Status
    private val statusIndicatorLabel = JBLabel("⚪ Idle")
    private val lastPollLabel = JBLabel("Last update: —")

    // Dynamic Device Panels
    private val availableDevicesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val connectedDevicesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val devicesTabbedPane = JTabbedPane()

    // Buttons
    private val connectButton = JButton("Connect")
    private val disconnectButton = JButton("Disconnect")
    private val helpButton = JButton("Help")

    // Error details UI elements
    private val errorSection = JPanel(BorderLayout(8, 4))
    private val errorLabel = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = null
        border = null
        font = Font("Monospaced", Font.PLAIN, 12)
        foreground = JBColor.RED
    }
    private val copyErrorButton = JButton("Copy Details")

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
        rootPanel.border = JBUI.Borders.empty(6)

        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)

        mainPanel.add(buildHeaderSection())
        mainPanel.add(Box.createVerticalStrut(8))
        mainPanel.add(buildSettingsSection())
        mainPanel.add(Box.createVerticalStrut(8))
        mainPanel.add(buildErrorSection())
        mainPanel.add(Box.createVerticalStrut(8))
        mainPanel.add(buildDevicesTabbedPane())
        mainPanel.add(Box.createVerticalGlue())

        val scrollPane = JBScrollPane(mainPanel)
        scrollPane.border = JBUI.Borders.empty()

        rootPanel.add(scrollPane, BorderLayout.CENTER)
        rootPanel.add(buildButtonPanel(), BorderLayout.SOUTH)
    }

    private fun buildHeaderSection(): JPanel {
        val panel = JPanel(BorderLayout(8, 0)).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 30)
            border = JBUI.Borders.empty(2, 4)
        }

        statusIndicatorLabel.font = statusIndicatorLabel.font.deriveFont(Font.BOLD, 14f)
        lastPollLabel.font = lastPollLabel.font.deriveFont(11f)
        lastPollLabel.foreground = JBColor.GRAY

        panel.add(statusIndicatorLabel, BorderLayout.WEST)
        panel.add(lastPollLabel, BorderLayout.EAST)

        return panel
    }

    private fun buildSettingsSection(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val headerPanel = JPanel(BorderLayout()).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 24)
            border = JBUI.Borders.empty(4, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        headerPanel.add(settingsTitleLabel, BorderLayout.WEST)

        settingsContentPanel.layout = BoxLayout(settingsContentPanel, BoxLayout.Y_AXIS)
        settingsContentPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            JBUI.Borders.empty(6, 4, 10, 4)
        )

        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(3, 4)
        }

        // Row 0: Host and Port
        gbc.gridy = 0
        
        gbc.gridx = 0
        gbc.weightx = 0.0
        formPanel.add(JBLabel("Host:"), gbc)
        
        gbc.gridx = 1
        gbc.weightx = 1.0
        formPanel.add(hostField, gbc)
        
        gbc.gridx = 2
        gbc.weightx = 0.0
        formPanel.add(JBLabel("Port:"), gbc)
        
        gbc.gridx = 3
        gbc.weightx = 0.0
        adbPortSpinner.preferredSize = Dimension(70, adbPortSpinner.preferredSize.height)
        formPanel.add(adbPortSpinner, gbc)

        // Row 1: TCP Port and Poll Interval
        gbc.gridy = 1
        
        gbc.gridx = 0
        gbc.weightx = 0.0
        formPanel.add(JBLabel("TCP Port:"), gbc)
        
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.WEST
        deviceTcpPortSpinner.preferredSize = Dimension(80, deviceTcpPortSpinner.preferredSize.height)
        formPanel.add(deviceTcpPortSpinner, gbc)
        
        gbc.gridx = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.CENTER
        formPanel.add(JBLabel("Poll (s):"), gbc)
        
        gbc.gridx = 3
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.WEST
        pollingSpinner.preferredSize = Dimension(70, pollingSpinner.preferredSize.height)
        formPanel.add(pollingSpinner, gbc)

        settingsContentPanel.add(formPanel)
        settingsContentPanel.add(Box.createVerticalStrut(6))

        val checkboxPanel = JPanel(GridLayout(0, 1, 0, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        checkboxPanel.add(autoConnectCheckbox)
        checkboxPanel.add(autoDetectCheckbox)
        checkboxPanel.add(autoReconnectCheckbox)

        settingsContentPanel.add(checkboxPanel)

        panel.add(headerPanel)
        panel.add(settingsContentPanel)

        val toggleAction = {
            isSettingsExpanded = !isSettingsExpanded
            settingsTitleLabel.text = if (isSettingsExpanded) "▼ Settings" else "▶ Settings"
            settingsContentPanel.isVisible = isSettingsExpanded
            rootPanel.revalidate()
            rootPanel.repaint()
        }

        headerPanel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                toggleAction()
            }
        })

        return panel
    }

    private fun buildDevicesTabbedPane(): JComponent {
        val availableScroll = JBScrollPane(availableDevicesPanel).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createEmptyBorder()
        }
        val connectedScroll = JBScrollPane(connectedDevicesPanel).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createEmptyBorder()
        }

        devicesTabbedPane.addTab("Available (0)", availableScroll)
        devicesTabbedPane.addTab("Connected (0)", connectedScroll)

        devicesTabbedPane.preferredSize = Dimension(0, 180)
        devicesTabbedPane.minimumSize = Dimension(0, 100)

        return devicesTabbedPane
    }

    private fun buildErrorSection(): JPanel {
        errorSection.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Error Details"),
            JBUI.Borders.empty(4)
        )
        errorSection.maximumSize = Dimension(Int.MAX_VALUE, 120)
        errorSection.preferredSize = Dimension(0, 100)

        val scrollPane = JBScrollPane(errorLabel).apply {
            border = null
        }
        
        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        bottomPanel.add(copyErrorButton)

        errorSection.add(scrollPane, BorderLayout.CENTER)
        errorSection.add(bottomPanel, BorderLayout.SOUTH)
        errorSection.isVisible = false // Hidden by default

        // Bind action for copying
        copyErrorButton.addActionListener {
            val state = stateMachine.state.value
            if (state is ConnectionState.Error) {
                val fullError = buildString {
                    appendLine("Remote ADB Connector - Error Log")
                    appendLine("===============================")
                    appendLine("Time: ${timeFormatter.format(stateMachine.lastTransitionTime)}")
                    appendLine("Error: ${state.message}")
                    state.cause?.let { cause ->
                        appendLine("Cause: ${cause.message}")
                        appendLine("Stack Trace:")
                        appendLine(cause.stackTraceToString())
                    }
                }
                try {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    val selection = StringSelection(fullError)
                    clipboard.setContents(selection, null)
                } catch (e: Exception) {
                    log.warn("Failed to copy error to clipboard", e)
                }
            }
        }

        return errorSection
    }

    private fun buildButtonPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER, 6, 6))

        connectButton.preferredSize = Dimension(90, 28)
        disconnectButton.preferredSize = Dimension(90, 28)
        disconnectButton.isEnabled = false
        helpButton.preferredSize = Dimension(70, 28)

        panel.add(connectButton)
        panel.add(disconnectButton)
        panel.add(helpButton)

        return panel
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

        // Collapsed by default if host is already configured, otherwise expanded
        isSettingsExpanded = hostField.text.isBlank()
        settingsContentPanel.isVisible = isSettingsExpanded
        settingsTitleLabel.text = if (isSettingsExpanded) "▼ Settings" else "▶ Settings"
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

        helpButton.addActionListener {
            showHelpDialog()
        }
    }

    private fun showHelpDialog() {
        val message = """
            <html>
            <body style='width: 300px;'>
            <h3>Windows Host Configuration</h3>
            <p>By default, the Windows ADB server only accepts connections from localhost (127.0.0.1).</p>
            <p>To allow this plugin to connect from Linux, run the following commands in <b>Command Prompt</b> or <b>PowerShell</b> on your Windows machine:</p>
            <hr/>
            <pre style='background-color: #2b2b2b; color: #a9b7c6; padding: 6px; font-family: monospace;'>
adb kill-server
adb -a nodaemon server</pre>
            <hr/>
            <p><b>Firewall:</b> Ensure Windows Firewall allows incoming connections on port <b>5037</b>. If prompted, select "Allow access" for Private networks.</p>
            </body>
            </html>
        """.trimIndent()

        Messages.showMessageDialog(
            project,
            message,
            "Remote ADB Help",
            Messages.getInformationIcon()
        )
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
        scope.launch {
            remoteAdbService.availableDevices.collectLatest { devices ->
                val currentConnected = extractDevices(stateMachine.state.value)
                updateAvailableDevicesUI(devices, currentConnected)
            }
        }
    }

    private fun updateAvailableDevicesUI(devices: List<Device>, connectedDevices: List<Device>) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            availableDevicesPanel.removeAll()
            val activeSerials = connectedDevices.map { it.serial }.toSet()

            // Update tab title with count
            if (devicesTabbedPane.tabCount > 0) {
                devicesTabbedPane.setTitleAt(0, "Available (${devices.size})")
            }

            if (devices.isEmpty()) {
                val emptyLabel = JBLabel("No devices detected on Windows server").apply {
                    foreground = JBColor.GRAY
                    border = JBUI.Borders.empty(8)
                }
                availableDevicesPanel.add(emptyLabel)
            } else {
                for (device in devices) {
                    val row = JPanel(BorderLayout(8, 0)).apply {
                        border = JBUI.Borders.empty(4, 8)
                    }

                    val nameLabel = JBLabel(device.displayName())
                    row.add(nameLabel, BorderLayout.CENTER)

                    val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
                    val isAlreadyConnected = device.serial in activeSerials

                    val wifiButton = JButton("WiFi").apply {
                        toolTipText = "Connect via WiFi"
                        isEnabled = !isAlreadyConnected && stateMachine.currentState !is ConnectionState.Error && remoteAdbService.isActive
                        addActionListener {
                            remoteAdbService.connectDevice(device, "WiFi")
                        }
                    }

                    val usbButton = JButton("USB").apply {
                        toolTipText = "Connect via USB Forwarding"
                        isEnabled = !isAlreadyConnected && stateMachine.currentState !is ConnectionState.Error && remoteAdbService.isActive
                        addActionListener {
                            remoteAdbService.connectDevice(device, "USB")
                        }
                    }

                    actionPanel.add(wifiButton)
                    actionPanel.add(usbButton)
                    row.add(actionPanel, BorderLayout.EAST)

                    row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
                    availableDevicesPanel.add(row)
                }
            }
            availableDevicesPanel.add(Box.createVerticalGlue())
            availableDevicesPanel.revalidate()
            availableDevicesPanel.repaint()
        }
    }

    private fun updateUI(state: ConnectionState) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            // Status indicator with host info
            val host = settings.state.windowsHost
            val hostInfo = if (state !is ConnectionState.Idle && !host.isNullOrBlank()) " ($host)" else ""
            statusIndicatorLabel.text = "${state.statusIndicator()} ${state.displayName()}$hostInfo"
            statusIndicatorLabel.foreground = getStateColor(state)

            // Last poll time
            val lastTransition = stateMachine.lastTransitionTime
            lastPollLabel.text = "Last update: ${timeFormatter.format(lastTransition)}"

            // Connected devices list
            val devices = extractDevices(state)
            if (devicesTabbedPane.tabCount > 1) {
                devicesTabbedPane.setTitleAt(1, "Connected (${devices.size})")
            }

            connectedDevicesPanel.removeAll()
            if (devices.isEmpty()) {
                val emptyLabel = JBLabel("No devices connected").apply {
                    foreground = JBColor.GRAY
                    border = JBUI.Borders.empty(8)
                }
                connectedDevicesPanel.add(emptyLabel)
            } else {
                for (device in devices) {
                    val row = JPanel(BorderLayout(8, 0)).apply {
                        border = JBUI.Borders.empty(4, 8)
                    }

                    val stateEmoji = when (device.state) {
                        com.adbconnect.plugin.model.DeviceState.DEVICE -> "🟢"
                        com.adbconnect.plugin.model.DeviceState.OFFLINE -> "🔴"
                        com.adbconnect.plugin.model.DeviceState.UNAUTHORIZED -> "🟡"
                        else -> "⚪"
                    }
                    val addr = device.tcpAddress?.let { " ($it)" } ?: ""
                    val typeText = device.connectionType?.let { " [$it]" } ?: ""

                    val nameLabel = JBLabel("$stateEmoji ${device.displayName()}$addr$typeText")
                    row.add(nameLabel, BorderLayout.CENTER)

                    val disconnectBtn = JButton("Disconnect").apply {
                        addActionListener {
                            remoteAdbService.disconnectDevice(device)
                        }
                    }
                    row.add(disconnectBtn, BorderLayout.EAST)

                    row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
                    connectedDevicesPanel.add(row)
                }
            }
            connectedDevicesPanel.add(Box.createVerticalGlue())
            connectedDevicesPanel.revalidate()
            connectedDevicesPanel.repaint()

            // Trigger sync of available devices list
            val currentAvailable = remoteAdbService.availableDevices.value
            updateAvailableDevicesUI(currentAvailable, devices)

            // Button states
            val isIdle = state is ConnectionState.Idle || state is ConnectionState.Error
            connectButton.isEnabled = isIdle
            disconnectButton.isEnabled = !isIdle

            // Error details visibility and content
            if (state is ConnectionState.Error) {
                errorLabel.text = state.message
                errorSection.isVisible = true
            } else {
                errorLabel.text = ""
                errorSection.isVisible = false
            }

            // Disable settings editing while connected
            val settingsEnabled = isIdle
            hostField.isEnabled = settingsEnabled
            adbPortSpinner.isEnabled = settingsEnabled
            deviceTcpPortSpinner.isEnabled = settingsEnabled
            pollingSpinner.isEnabled = settingsEnabled

            // Force layout update and repaint
            rootPanel.revalidate()
            rootPanel.repaint()
        }
    }

    private fun extractDevices(state: ConnectionState): List<Device> = when (state) {
        is ConnectionState.PreparingDevice -> state.connectedDevices + state.device
        is ConnectionState.Connecting -> state.connectedDevices + state.device
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
