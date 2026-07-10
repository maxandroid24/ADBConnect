package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.Device
import com.adbconnect.plugin.settings.PluginSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors the remote Windows ADB server for USB-connected devices.
 *
 * Periodically polls `adb devices` using the Windows ADB server socket
 * and reports device list changes via a callback.
 *
 * Uses [ScheduledExecutorService] — never blocks the EDT.
 */
@Service(Service.Level.PROJECT)
class WindowsAdbMonitor(private val project: Project) : Disposable {

    private val log = Logger.getInstance(WindowsAdbMonitor::class.java)

    private var executor: ScheduledExecutorService? = null
    private var scheduledTask: ScheduledFuture<*>? = null
    private val running = AtomicBoolean(false)

    /** The last known device list from the Windows server. */
    @Volatile
    var lastDevices: List<Device> = emptyList()
        private set

    /** Timestamp of the last successful poll. */
    @Volatile
    var lastPollTime: Long = 0L
        private set

    /**
     * Whether the monitor is currently running.
     */
    val isRunning: Boolean
        get() = running.get()

    /**
     * Starts polling the Windows ADB server for devices.
     *
     * @param onDevicesChanged Called on the executor thread when the device list changes.
     *                         Also called on first successful poll.
     * @param onError Called when polling fails (e.g., server unreachable).
     */
    fun start(
        onDevicesChanged: (List<Device>) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (running.getAndSet(true)) {
            log.warn("WindowsAdbMonitor already running — ignoring start()")
            return
        }

        val settings = PluginSettings.getInstance()
        val intervalSeconds = settings.state.pollingIntervalSeconds.toLong()

        log.info("Starting WindowsAdbMonitor (interval=${intervalSeconds}s, host=${settings.state.windowsHost})")

        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "WindowsAdbMonitor").apply { isDaemon = true }
        }

        scheduledTask = executor?.scheduleWithFixedDelay(
            {
                try {
                    poll(onDevicesChanged, onError)
                } catch (e: Exception) {
                    log.error("WindowsAdbMonitor poll failed", e)
                    onError("Poll error: ${e.message}")
                }
            },
            0, // Initial delay — poll immediately
            intervalSeconds,
            TimeUnit.SECONDS
        )
    }

    /**
     * Stops polling and releases resources.
     */
    fun stop() {
        if (!running.getAndSet(false)) return

        log.info("Stopping WindowsAdbMonitor")
        scheduledTask?.cancel(false)
        scheduledTask = null
        executor?.shutdown()
        executor = null
        lastDevices = emptyList()
    }

    /**
     * Performs a single poll of the Windows ADB server.
     */
    private fun poll(
        onDevicesChanged: (List<Device>) -> Unit,
        onError: (String) -> Unit
    ) {
        val adbExecutor = AdbExecutor.getInstance(project)
        val result = adbExecutor.executeOnWindows("devices", "-l")

        if (result.isFailure) {
            log.warn("Windows ADB poll failed: ${result.stderr}")
            onError("Failed to reach Windows ADB server: ${result.stderr}")
            return
        }

        lastPollTime = System.currentTimeMillis()
        val devices = DeviceParser.parseLong(result.stdout)

        // Detect changes
        val previousSerials = lastDevices.map { it.serial }.toSet()
        val currentSerials = devices.map { it.serial }.toSet()

        if (previousSerials != currentSerials || lastDevices.map { it.state } != devices.map { it.state }) {
            log.info("Windows device list changed: ${devices.map { "${it.serial}(${it.state})" }}")
            lastDevices = devices
            onDevicesChanged(devices)
        }
    }

    override fun dispose() {
        stop()
    }

    companion object {
        fun getInstance(project: Project): WindowsAdbMonitor = project.service()
    }
}
