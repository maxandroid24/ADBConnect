package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.ConnectionState
import com.adbconnect.plugin.model.Device
import com.adbconnect.plugin.model.DeviceState
import com.adbconnect.plugin.notification.NotificationService
import com.adbconnect.plugin.settings.PluginSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Orchestrates the complete connection workflow between a Windows ADB server
 * and the local Linux ADB server.
 *
 * This is the core "engine" that replaces the shell scripts. It drives
 * the [StateMachine] through each phase of the connection lifecycle:
 *
 * 1. Verify Windows ADB server is reachable
 * 2. Detect USB-connected devices on Windows
 * 3. Switch device(s) to TCP mode
 * 4. Connect from the local Linux ADB server
 * 5. Verify the connection
 *
 * All ADB operations are performed via [AdbExecutor], which manages
 * per-process `ADB_SERVER_SOCKET` environment variables.
 */
@Service(Service.Level.PROJECT)
class ConnectionManager(private val project: Project) {

    private val log = Logger.getInstance(ConnectionManager::class.java)

    private val adbExecutor: AdbExecutor
        get() = AdbExecutor.getInstance(project)

    private val stateMachine: StateMachine
        get() = StateMachine.getInstance(project)

    private val notifications: NotificationService
        get() = NotificationService.getInstance(project)

    private val settings: PluginSettings
        get() = PluginSettings.getInstance()

    /**
     * Executes the full connection workflow.
     *
     * This method is designed to be called from a background coroutine.
     * It drives the state machine through the entire connection lifecycle.
     *
     * @return `true` if at least one device was successfully connected.
     */
    fun connect(): Boolean {
        log.info("Starting connection workflow")

        // Step 1: Verify Windows ADB server
        stateMachine.transition(ConnectionState.WaitingForWindowsServer)
        if (!verifyWindowsServer()) {
            val host = settings.state.windowsHost.orEmpty()
            notifications.notifyServerUnreachable(host)
            stateMachine.transition(
                ConnectionState.Error("Cannot reach ADB server at ${host}:${settings.state.adbPort}")
            )
            return false
        }

        // Step 2: Find devices on Windows
        stateMachine.transition(ConnectionState.WaitingForWindowsDevice)
        val windowsDevices = findWindowsDevices()
        if (windowsDevices.isEmpty()) {
            log.info("No devices found on Windows server")
            // Don't error — the monitor will continue polling
            return false
        }

        val connectedDevices = mutableListOf<Device>()

        for (device in windowsDevices) {
            // Step 3: Prepare device (switch to TCP mode)
            stateMachine.transition(ConnectionState.PreparingDevice(device))
            val preparedDevice = prepareDevice(device)
            if (preparedDevice == null) {
                log.warn("Failed to prepare device: ${device.serial}")
                continue
            }

            // Step 4: Connect from Linux
            stateMachine.transition(ConnectionState.Connecting(preparedDevice))
            val connected = connectToLinux(preparedDevice)
            if (connected) {
                connectedDevices.add(preparedDevice)
                notifications.notifyConnected(preparedDevice.displayName())
            } else {
                log.warn("Failed to connect device locally: ${preparedDevice.serial}")
                notifications.notifyConnectionFailed(
                    "Could not connect to ${preparedDevice.displayName()} at ${preparedDevice.tcpAddress}"
                )
            }
        }

        // Step 5: Finalize
        if (connectedDevices.isNotEmpty()) {
            stateMachine.transition(ConnectionState.Connected(connectedDevices))
            log.info("Successfully connected ${connectedDevices.size} device(s)")
            return true
        } else {
            stateMachine.transition(
                ConnectionState.Error("Failed to connect any devices")
            )
            return false
        }
    }

    /**
     * Disconnects all remotely-connected devices and cleans up.
     */
    fun disconnect() {
        log.info("Disconnecting all remote devices")

        val currentState = stateMachine.currentState
        val devicesToDisconnect = when (currentState) {
            is ConnectionState.Connected -> currentState.devices
            is ConnectionState.MonitoringLinux -> currentState.devices
            else -> emptyList()
        }

        for (device in devicesToDisconnect) {
            val tcpAddr = device.tcpAddress
            if (tcpAddr != null) {
                val result = adbExecutor.executeOnLocal("disconnect", tcpAddr)
                if (result.isSuccess) {
                    log.info("Disconnected: $tcpAddr")
                } else {
                    log.warn("Failed to disconnect $tcpAddr: ${result.stderr}")
                }
            }
            notifications.notifyDisconnected(device.displayName())
        }

        stateMachine.reset()
    }

    /**
     * Attempts to reconnect previously connected devices.
     */
    fun reconnect(): Boolean {
        log.info("Attempting reconnect")
        stateMachine.transition(ConnectionState.Reconnecting)
        notifications.notifyRetrying()

        // Re-run the full workflow
        stateMachine.transition(ConnectionState.WaitingForWindowsServer)
        return connect()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal workflow steps
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Step 1: Verifies the Windows ADB server is reachable by running `adb devices`.
     */
    internal fun verifyWindowsServer(): Boolean {
        val result = adbExecutor.executeOnWindows("devices", timeoutSeconds = 10)
        if (result.isSuccess) {
            log.info("Windows ADB server is reachable")
            return true
        }
        log.warn("Windows ADB server unreachable: ${result.stderr}")
        return false
    }

    /**
     * Step 2: Queries the Windows ADB server for connected USB devices.
     * Filters to only DEVICE-state (ready) devices.
     */
    internal fun findWindowsDevices(): List<Device> {
        val result = adbExecutor.executeOnWindows("devices", "-l")
        if (result.isFailure) {
            log.warn("Failed to list Windows devices: ${result.stderr}")
            return emptyList()
        }

        val allDevices = DeviceParser.parseLong(result.stdout)
        val readyDevices = allDevices.filter { it.state == DeviceState.DEVICE }

        log.info(
            "Found ${allDevices.size} device(s) on Windows, ${readyDevices.size} ready: " +
                    readyDevices.map { "${it.serial} (${it.model ?: "unknown"})" }
        )

        return readyDevices
    }

    /**
     * Step 3: Switches a USB-connected device to TCP mode and resolves its IP address.
     *
     * @param device The device to prepare.
     * @return The device with [Device.ip] and [Device.tcpPort] populated, or null on failure.
     */
    internal fun prepareDevice(device: Device): Device? {
        val tcpPort = settings.state.deviceTcpPort

        // Switch to TCP mode
        log.info("Switching ${device.serial} to TCP mode on port $tcpPort")
        val tcpResult = adbExecutor.executeOnWindows(
            "-s", device.serial, "tcpip", tcpPort.toString()
        )

        if (tcpResult.isFailure) {
            log.error("Failed to switch ${device.serial} to TCP mode: ${tcpResult.stderr}")
            return null
        }

        // Wait briefly for TCP mode to take effect
        Thread.sleep(TCP_MODE_SETTLE_MS)

        // Resolve device IP address
        val ip = resolveDeviceIp(device.serial)
        if (ip == null) {
            log.error("Could not determine IP address for ${device.serial}")
            return null
        }

        log.info("Device ${device.serial} prepared: IP=$ip, TCP port=$tcpPort")
        return device.copy(ip = ip, tcpPort = tcpPort)
    }

    /**
     * Step 4–5: Connects to the device from the local Linux ADB server and verifies.
     *
     * @param device The prepared device with IP and TCP port set.
     * @return `true` if the device is now available on the local ADB server.
     */
    internal fun connectToLinux(device: Device): Boolean {
        val tcpAddress = device.tcpAddress
            ?: throw IllegalArgumentException("Device must have IP and TCP port set")

        log.info("Connecting to $tcpAddress from local ADB server")

        val connectResult = adbExecutor.executeOnLocal("connect", tcpAddress)

        if (connectResult.isFailure) {
            log.error("adb connect failed: ${connectResult.stderr}")
            return false
        }

        // Check for success in output (adb connect prints "connected to..." on success)
        val output = connectResult.stdout.lowercase()
        if ("connected" !in output && "already connected" !in output) {
            log.warn("Unexpected adb connect output: ${connectResult.stdout}")
            return false
        }

        // Verify the device appears in local device list
        Thread.sleep(CONNECT_SETTLE_MS)
        return verifyLocalDevice(tcpAddress)
    }

    /**
     * Verifies a device is present and in DEVICE state on the local ADB server.
     */
    internal fun verifyLocalDevice(tcpAddress: String): Boolean {
        val result = adbExecutor.executeOnLocal("devices")
        if (result.isFailure) return false

        val devices = DeviceParser.parse(result.stdout)
        val found = devices.any { it.serial == tcpAddress && it.state == DeviceState.DEVICE }

        if (found) {
            log.info("Device verified locally: $tcpAddress")
        } else {
            log.warn("Device NOT found locally after connect: $tcpAddress")
        }
        return found
    }

    /**
     * Resolves the LAN IP address of a device connected via USB on the Windows server.
     * Uses `adb shell ip route` to find the device's WiFi/LAN IP.
     */
    private fun resolveDeviceIp(serial: String): String? {
        // Try `ip route` first (preferred, works on modern Android)
        val ipRouteResult = adbExecutor.executeOnWindows(
            "-s", serial, "shell", "ip", "route"
        )
        if (ipRouteResult.isSuccess) {
            val ip = DeviceParser.parseDeviceIp(ipRouteResult.stdout)
            if (ip != null) return ip
        }

        // Fallback: try `ip addr show wlan0`
        val ipAddrResult = adbExecutor.executeOnWindows(
            "-s", serial, "shell", "ip", "addr", "show", "wlan0"
        )
        if (ipAddrResult.isSuccess) {
            val inetPattern = Regex("""inet\s+(\d+\.\d+\.\d+\.\d+)/""")
            inetPattern.find(ipAddrResult.stdout)?.let { return it.groupValues[1] }
        }

        // Last resort: try ifconfig
        val ifconfigResult = adbExecutor.executeOnWindows(
            "-s", serial, "shell", "ifconfig", "wlan0"
        )
        if (ifconfigResult.isSuccess) {
            val addrPattern = Regex("""inet addr:(\d+\.\d+\.\d+\.\d+)""")
            addrPattern.find(ifconfigResult.stdout)?.let { return it.groupValues[1] }
        }

        return null
    }

    companion object {
        /** Time to wait after `adb tcpip` for the mode switch to settle. */
        private const val TCP_MODE_SETTLE_MS = 2000L

        /** Time to wait after `adb connect` before verifying. */
        private const val CONNECT_SETTLE_MS = 1000L

        fun getInstance(project: Project): ConnectionManager = project.service()
    }
}
