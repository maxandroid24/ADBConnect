package com.adbconnect.plugin.model

/**
 * Represents an Android device as reported by `adb devices`.
 *
 * @property serial The device serial number (e.g., "ABC123" for USB, "192.168.1.100:5555" for TCP).
 * @property state The current connection state of the device.
 * @property ip The device's LAN IP address, populated after querying the device via ADB shell.
 * @property tcpPort The TCP port used for ADB-over-network connections (typically 5555).
 * @property model The device model name, if available (e.g., "Pixel 9").
 */
data class Device(
    val serial: String,
    val state: DeviceState,
    val ip: String? = null,
    val tcpPort: Int? = null,
    val model: String? = null
) {
    /**
     * Returns the TCP connection address in `ip:port` format.
     * Returns null if either [ip] or [tcpPort] is not set.
     */
    val tcpAddress: String?
        get() = if (ip != null && tcpPort != null) "$ip:$tcpPort" else null

    /**
     * Whether this device is in a usable state for development.
     */
    val isOnline: Boolean
        get() = state == DeviceState.DEVICE

    /**
     * Whether this serial looks like a TCP connection (ip:port format).
     */
    val isTcpDevice: Boolean
        get() = serial.matches(Regex("""\d+\.\d+\.\d+\.\d+:\d+"""))

    /**
     * Returns a human-friendly display name: model if available, otherwise serial.
     */
    fun displayName(): String = model ?: serial
}

/**
 * Possible states of an Android device as reported by `adb devices`.
 */
enum class DeviceState {
    /** Device is connected and ready for debugging. */
    DEVICE,

    /** Device is detected but not responding. */
    OFFLINE,

    /** Device requires USB debugging authorization. */
    UNAUTHORIZED,

    /** Device is in recovery mode. */
    RECOVERY,

    /** Device is being connected (transient state). */
    CONNECTING,

    /** Unknown or unrecognized state. */
    UNKNOWN;

    companion object {
        /**
         * Parses a state string from `adb devices` output.
         * Returns [UNKNOWN] for unrecognized values.
         */
        fun fromAdbOutput(value: String): DeviceState = when (value.trim().lowercase()) {
            "device" -> DEVICE
            "offline" -> OFFLINE
            "unauthorized" -> UNAUTHORIZED
            "recovery" -> RECOVERY
            "connecting" -> CONNECTING
            else -> UNKNOWN
        }
    }
}
