package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.ConnectionState
import com.adbconnect.plugin.model.Device
import com.adbconnect.plugin.notification.NotificationService
import com.adbconnect.plugin.settings.PluginSettings
import com.adbconnect.plugin.settings.PluginSettingsState
import com.adbconnect.plugin.util.IpValidator
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Top-level orchestrator for the Remote ADB Connector plugin.
 *
 * This service is the "brain" of the plugin — it coordinates all other services
 * and manages the overall lifecycle:
 *
 * - Validates settings before starting.
 * - Launches the [ConnectionManager] workflow in a background coroutine.
 * - Starts/stops [WindowsAdbMonitor] and [LinuxAdbMonitor] as appropriate.
 * - Handles auto-connect, auto-reconnect, and graceful shutdown.
 *
 * The service creates a [CoroutineScope] tied to the project lifecycle.
 * All background work is cancelled when the project closes.
 */
@Service(Service.Level.PROJECT)
class RemoteAdbService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(RemoteAdbService::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stateMachine: StateMachine
        get() = StateMachine.getInstance(project)

    private val connectionManager: ConnectionManager
        get() = ConnectionManager.getInstance(project)

    private val windowsMonitor: WindowsAdbMonitor
        get() = WindowsAdbMonitor.getInstance(project)

    private val linuxMonitor: LinuxAdbMonitor
        get() = LinuxAdbMonitor.getInstance(project)

    private val notifications: NotificationService
        get() = NotificationService.getInstance(project)

    private val settings: PluginSettings
        get() = PluginSettings.getInstance()

    /** Current background connection job, if any. */
    private var connectionJob: Job? = null

    /** State flow of available devices on Windows. */
    private val _availableDevices = MutableStateFlow<List<Device>>(emptyList())
    val availableDevices: StateFlow<List<Device>> = _availableDevices.asStateFlow()

    /** Whether the service has been started. */
    @Volatile
    var isActive: Boolean = false
        private set

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Validates settings and starts the connection workflow.
     *
     * This is the main entry point triggered by the "Connect" button.
     * It validates the configuration, then launches the workflow in a
     * background coroutine.
     *
     * @return A validation error message, or null if the workflow started successfully.
     */
    fun connect(): String? {
        // Validate settings
        val validationError = validateSettings()
        if (validationError != null) {
            log.warn("Validation failed: $validationError")
            notifications.notifyError("Configuration Error", validationError)
            return validationError
        }

        if (isActive) {
            log.info("Already active — disconnecting first")
            disconnect()
        }

        isActive = true
        notifications.clearThrottleCache()

        connectionJob = scope.launch {
            try {
                executeConnectionWorkflow()
            } catch (e: CancellationException) {
                log.info("Connection workflow cancelled")
            } catch (e: Exception) {
                log.error("Connection workflow failed", e)
                stateMachine.transition(
                    ConnectionState.Error("Unexpected error: ${e.message}", e)
                )
                notifications.notifyError("Connection Error", e.message ?: "Unknown error")
            }
        }

        return null
    }

    /**
     * Stops all monitoring, disconnects devices, and resets state.
     *
     * This is the main entry point triggered by the "Disconnect" button.
     */
    fun disconnect() {
        log.info("Disconnect requested")

        isActive = false

        // Cancel background work
        connectionJob?.cancel()
        connectionJob = null

        // Stop monitors
        windowsMonitor.stop()
        linuxMonitor.stop()

        // Clear available devices
        _availableDevices.value = emptyList()

        // Disconnect devices
        connectionManager.disconnect()

        log.info("Disconnected and cleaned up")
    }

    /**
     * Initiates connection for a specific device.
     */
    fun connectDevice(device: Device, type: String) {
        if (!isActive) {
            isActive = true
            notifications.clearThrottleCache()
        }
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    connectionManager.connectDevice(device, type)
                }
                if (success) {
                    // Restart/update Linux monitoring
                    startLinuxMonitoring()
                }
            } catch (e: Exception) {
                log.error("Failed to connect device: ${device.serial}", e)
                stateMachine.transition(
                    ConnectionState.Error("Failed to connect: ${e.message}", e)
                )
            }
        }
    }

    /**
     * Initiates disconnection for a specific device.
     */
    fun disconnectDevice(device: Device) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    connectionManager.disconnectDevice(device)
                }
                // Restart/update Linux monitoring
                startLinuxMonitoring()
            } catch (e: Exception) {
                log.error("Failed to disconnect device: ${device.serial}", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal workflow
    // ──────────────────────────────────────────────────────────────────────

    /**
     * The main connection workflow executed in a background coroutine.
     */
    private suspend fun executeConnectionWorkflow() {
        log.info("Executing connection workflow - starting Windows monitoring")
        startWindowsMonitoring()
    }

    /**
     * Starts monitoring the Windows ADB server for device changes.
     */
    private fun startWindowsMonitoring() {
        log.info("Starting Windows device monitoring")

        windowsMonitor.start(
            onDevicesChanged = { devices ->
                if (!isActive) return@start

                val readyDevices = devices.filter {
                    it.state == com.adbconnect.plugin.model.DeviceState.DEVICE
                }

                _availableDevices.value = readyDevices
            },
            onError = { error ->
                log.warn("Windows monitor error: $error")
            }
        )
    }

    /**
     * Starts monitoring the local Linux ADB server for device presence.
     */
    private fun startLinuxMonitoring() {
        val currentState = stateMachine.currentState
        val devices = when (currentState) {
            is ConnectionState.Connected -> currentState.devices
            is ConnectionState.MonitoringLinux -> currentState.devices
            else -> return
        }

        stateMachine.transition(ConnectionState.MonitoringLinux(devices))

        // Keep Windows monitor running in the background so list stays updated

        linuxMonitor.start(
            devices = devices,
            onDeviceLost = { lostDevice ->
                log.warn("Device lost: ${lostDevice.serial}")
                notifications.notifyDeviceLost(lostDevice.displayName())
                if (settings.state.autoReconnect && isActive) {
                    log.info("Attempting auto-reconnect for ${lostDevice.serial}")
                    scope.launch {
                        connectionManager.reconnectDevice(lostDevice)
                        startLinuxMonitoring()
                    }
                }
            },
            onAllDevicesLost = {
                if (!isActive) return@start

                log.warn("All devices lost")
                linuxMonitor.stop()

                if (!settings.state.autoReconnect) {
                    stateMachine.transition(
                        ConnectionState.Error("All devices lost. Auto-reconnect is disabled.")
                    )
                }
            }
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Validation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Validates the current plugin settings.
     *
     * @return An error message if validation fails, or null if everything is valid.
     */
    internal fun validateSettings(): String? {
        val state = settings.state

        val host = state.windowsHost.orEmpty()

        if (host.isBlank()) {
            return "Windows host address is required."
        }

        val hostError = IpValidator.getValidationError(host)
        if (hostError != null) return hostError

        if (!IpValidator.isValidPort(state.adbPort)) {
            return "ADB port must be between 1 and 65535."
        }

        if (state.connectionType == PluginSettingsState.CONNECTION_TYPE_WIFI && !IpValidator.isValidPort(state.deviceTcpPort)) {
            return "Device TCP port must be between 1 and 65535."
        }

        if (state.pollingIntervalSeconds < 1) {
            return "Polling interval must be at least 1 second."
        }

        return null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun dispose() {
        log.info("RemoteAdbService disposing")
        disconnect()
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): RemoteAdbService = project.service()
    }
}
