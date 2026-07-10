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
 * Monitors the local Linux ADB server for connected devices.
 *
 * Once a device has been connected via the remote workflow, this monitor
 * continuously verifies the device remains available locally. If a device
 * disappears, it signals the [ConnectionManager] to attempt reconnection.
 *
 * Uses [ScheduledExecutorService] — never blocks the EDT.
 */
@Service(Service.Level.PROJECT)
class LinuxAdbMonitor(private val project: Project) : Disposable {

    private val log = Logger.getInstance(LinuxAdbMonitor::class.java)

    private var executor: ScheduledExecutorService? = null
    private var scheduledTask: ScheduledFuture<*>? = null
    private val running = AtomicBoolean(false)

    /** The devices we expect to be connected locally. */
    @Volatile
    var expectedDevices: List<Device> = emptyList()
        private set

    /** The currently detected local devices. */
    @Volatile
    var currentDevices: List<Device> = emptyList()
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
     * Starts monitoring the local ADB server for expected devices.
     *
     * @param devices The devices we expect to see locally (connected via TCP).
     * @param onDeviceLost Called when an expected device is no longer present.
     * @param onAllDevicesLost Called when all expected devices have been lost.
     */
    fun start(
        devices: List<Device>,
        onDeviceLost: (Device) -> Unit,
        onAllDevicesLost: () -> Unit = {}
    ) {
        if (running.getAndSet(true)) {
            log.warn("LinuxAdbMonitor already running — ignoring start()")
            return
        }

        expectedDevices = devices
        val settings = PluginSettings.getInstance()
        val intervalSeconds = settings.state.pollingIntervalSeconds.toLong()

        log.info(
            "Starting LinuxAdbMonitor (interval=${intervalSeconds}s, " +
                    "expecting=${devices.map { it.serial }})"
        )

        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LinuxAdbMonitor").apply { isDaemon = true }
        }

        scheduledTask = executor?.scheduleWithFixedDelay(
            {
                try {
                    poll(onDeviceLost, onAllDevicesLost)
                } catch (e: Exception) {
                    log.error("LinuxAdbMonitor poll failed", e)
                }
            },
            intervalSeconds, // Wait one interval before first check
            intervalSeconds,
            TimeUnit.SECONDS
        )
    }

    /**
     * Stops monitoring and releases resources.
     */
    fun stop() {
        if (!running.getAndSet(false)) return

        log.info("Stopping LinuxAdbMonitor")
        scheduledTask?.cancel(false)
        scheduledTask = null
        executor?.shutdown()
        executor = null
        expectedDevices = emptyList()
        currentDevices = emptyList()
    }

    /**
     * Updates the list of expected devices (e.g., after a new device is added).
     */
    fun updateExpectedDevices(devices: List<Device>) {
        expectedDevices = devices
        log.info("Updated expected devices: ${devices.map { it.serial }}")
    }

    /**
     * Performs a single poll of the local ADB server.
     */
    private fun poll(
        onDeviceLost: (Device) -> Unit,
        onAllDevicesLost: () -> Unit
    ) {
        val adbExecutor = AdbExecutor.getInstance(project)
        val result = adbExecutor.executeOnLocal("devices")

        if (result.isFailure) {
            log.warn("Local ADB poll failed: ${result.stderr}")
            return
        }

        lastPollTime = System.currentTimeMillis()
        currentDevices = DeviceParser.parse(result.stdout)

        val currentSerials = currentDevices
            .filter { it.state == com.adbconnect.plugin.model.DeviceState.DEVICE }
            .map { it.serial }
            .toSet()

        // Check each expected device
        val lostDevices = expectedDevices.filter { expected ->
            val tcpAddr = expected.tcpAddress ?: expected.serial
            tcpAddr !in currentSerials
        }

        if (lostDevices.isNotEmpty()) {
            log.warn("Lost devices detected: ${lostDevices.map { it.serial }}")
            lostDevices.forEach { onDeviceLost(it) }

            if (lostDevices.size == expectedDevices.size) {
                log.warn("All expected devices lost")
                onAllDevicesLost()
            }
        }
    }

    override fun dispose() {
        stop()
    }

    companion object {
        fun getInstance(project: Project): LinuxAdbMonitor = project.service()
    }
}
