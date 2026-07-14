package com.adbconnect.plugin.model

/**
 * Represents the finite state machine states for the remote ADB connection lifecycle.
 *
 * The state machine follows this primary flow:
 * ```
 * Idle → WaitingForWindowsServer → WaitingForWindowsDevice → PreparingDevice
 *   → Connecting → Connected → MonitoringLinux → (Reconnecting → WaitingForWindowsDevice)
 * ```
 *
 * Any state can transition to [Idle] via disconnect, and most states can transition
 * to [Error] on failure.
 */
sealed class ConnectionState {

    /** Plugin is idle — not connected, not attempting connection. */
    data object Idle : ConnectionState()

    /** Attempting to reach the Windows ADB server. */
    data object WaitingForWindowsServer : ConnectionState()

    /** Windows server reachable; polling for USB-connected devices. */
    data object WaitingForWindowsDevice : ConnectionState()

    /** A device was found on Windows; switching it to TCP mode. */
    data class PreparingDevice(val device: Device, val connectedDevices: List<Device> = emptyList()) : ConnectionState()

    /** Device prepared; connecting from local Linux ADB server. */
    data class Connecting(val device: Device, val connectedDevices: List<Device> = emptyList()) : ConnectionState()

    /** Successfully connected — device is available locally. */
    data class Connected(val devices: List<Device>) : ConnectionState()

    /** Monitoring the locally-connected device for disconnection. */
    data class MonitoringLinux(val devices: List<Device>) : ConnectionState()

    /** Device was lost; attempting to re-establish the connection. */
    data object Reconnecting : ConnectionState()

    /** An error occurred. Contains a human-readable message and optional cause. */
    data class Error(val message: String, val cause: Throwable? = null) : ConnectionState()

    /**
     * Returns a short human-readable label for UI display.
     */
    fun displayName(): String = when (this) {
        is Idle -> "Idle"
        is WaitingForWindowsServer -> "Waiting for Windows Server"
        is WaitingForWindowsDevice -> "Waiting for Device"
        is PreparingDevice -> "Preparing Device"
        is Connecting -> "Connecting"
        is Connected -> "Connected"
        is MonitoringLinux -> "Monitoring"
        is Reconnecting -> "Reconnecting"
        is Error -> "Error"
    }

    /**
     * Returns the status indicator emoji for UI display.
     */
    fun statusIndicator(): String = when (this) {
        is Idle -> "⚪"
        is WaitingForWindowsServer, is WaitingForWindowsDevice -> "🟡"
        is PreparingDevice, is Connecting -> "🟡"
        is Connected, is MonitoringLinux -> "🟢"
        is Reconnecting -> "🟠"
        is Error -> "🔴"
    }
}
