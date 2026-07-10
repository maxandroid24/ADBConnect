package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.ConnectionState
import com.adbconnect.plugin.notification.NotificationService
import com.adbconnect.plugin.settings.PluginSettings
import com.adbconnect.plugin.util.IpValidator
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*

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

        // Disconnect devices
        connectionManager.disconnect()

        log.info("Disconnected and cleaned up")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal workflow
    // ──────────────────────────────────────────────────────────────────────

    /**
     * The main connection workflow executed in a background coroutine.
     */
    private suspend fun executeConnectionWorkflow() {
        log.info("Executing connection workflow")

        // Attempt initial connection
        val connected = withContext(Dispatchers.IO) {
            connectionManager.connect()
        }

        if (connected) {
            // Connection succeeded — start Linux monitoring
            startLinuxMonitoring()
        } else {
            // Connection failed — start Windows monitoring to wait for devices
            if (isActive && settings.state.autoDetectDevices) {
                startWindowsMonitoring()
            }
        }
    }

    /**
     * Starts monitoring the Windows ADB server for device changes.
     * When devices appear, automatically attempts connection.
     */
    private fun startWindowsMonitoring() {
        log.info("Starting Windows device monitoring")
        linuxMonitor.stop()

        windowsMonitor.start(
            onDevicesChanged = { devices ->
                if (!isActive) return@start

                val readyDevices = devices.filter {
                    it.state == com.adbconnect.plugin.model.DeviceState.DEVICE
                }

                if (readyDevices.isNotEmpty()) {
                    log.info("Devices found on Windows — attempting connection")
                    readyDevices.forEach { notifications.notifyNewDevice(it.displayName()) }

                    // Stop Windows monitoring and attempt connection
                    windowsMonitor.stop()

                    connectionJob = scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            connectionManager.connect()
                        }
                        if (success) {
                            startLinuxMonitoring()
                        } else if (isActive && settings.state.autoDetectDevices) {
                            // Resume Windows monitoring
                            startWindowsMonitoring()
                        }
                    }
                }
            },
            onError = { error ->
                log.warn("Windows monitor error: $error")
            }
        )
    }

    /**
     * Starts monitoring the local Linux ADB server for device presence.
     * If devices are lost, attempts reconnection.
     */
    private fun startLinuxMonitoring() {
        val currentState = stateMachine.currentState
        val devices = when (currentState) {
            is ConnectionState.Connected -> currentState.devices
            is ConnectionState.MonitoringLinux -> currentState.devices
            else -> return
        }

        stateMachine.transition(ConnectionState.MonitoringLinux(devices))

        windowsMonitor.stop()

        linuxMonitor.start(
            devices = devices,
            onDeviceLost = { lostDevice ->
                log.warn("Device lost: ${lostDevice.serial}")
                notifications.notifyDeviceLost(lostDevice.displayName())
            },
            onAllDevicesLost = {
                if (!isActive) return@start

                log.warn("All devices lost — initiating reconnect")
                linuxMonitor.stop()

                if (settings.state.autoReconnect) {
                    connectionJob = scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            connectionManager.reconnect()
                        }
                        if (success) {
                            startLinuxMonitoring()
                        } else if (isActive) {
                            // Fall back to Windows monitoring
                            startWindowsMonitoring()
                        }
                    }
                } else {
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

        if (!IpValidator.isValidPort(state.deviceTcpPort)) {
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
