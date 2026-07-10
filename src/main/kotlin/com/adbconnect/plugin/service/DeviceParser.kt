package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.Device
import com.adbconnect.plugin.model.DeviceState

/**
 * Parses raw `adb devices` output into structured [Device] objects.
 *
 * Expected input format:
 * ```
 * List of devices attached
 * ABC123    device
 * XYZ789    offline
 * 192.168.1.100:5555    device
 * ```
 *
 * This is a pure stateless parser — no side effects, easily testable.
 */
object DeviceParser {

    private const val HEADER = "List of devices attached"

    /**
     * Parses the output of `adb devices` into a list of [Device] objects.
     *
     * @param output The raw stdout from `adb devices`.
     * @return A list of parsed devices. Empty list if no devices are found or output is malformed.
     */
    fun parse(output: String): List<Device> {
        if (output.isBlank()) return emptyList()

        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { !it.startsWith(HEADER, ignoreCase = true) }
            .filter { !it.startsWith("*") } // Skip daemon messages like "* daemon started *"
            .mapNotNull { parseLine(it) }
            .toList()
    }

    /**
     * Parses a single line of `adb devices` output.
     *
     * Expected format: `<serial>\t<state>` or `<serial>    <state>`
     *
     * @param line A single line from the devices listing.
     * @return A [Device] if the line is valid, or null if it cannot be parsed.
     */
    private fun parseLine(line: String): Device? {
        // Split on tab first (standard ADB output), fall back to whitespace
        val parts = if ('\t' in line) {
            line.split('\t', limit = 2)
        } else {
            line.split(Regex("\\s+"), limit = 2)
        }

        if (parts.size < 2) return null

        val serial = parts[0].trim()
        val stateStr = parts[1].trim()

        if (serial.isBlank() || stateStr.isBlank()) return null

        return Device(
            serial = serial,
            state = DeviceState.fromAdbOutput(stateStr)
        )
    }

    /**
     * Parses the output of `adb devices -l` (long format) into [Device] objects
     * with model information populated.
     *
     * Expected format:
     * ```
     * List of devices attached
     * ABC123    device usb:1-1 product:oriole model:Pixel_6 device:oriole transport_id:1
     * ```
     */
    fun parseLong(output: String): List<Device> {
        if (output.isBlank()) return emptyList()

        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { !it.startsWith(HEADER, ignoreCase = true) }
            .filter { !it.startsWith("*") }
            .mapNotNull { parseLongLine(it) }
            .toList()
    }

    /**
     * Parses a single line from `adb devices -l` output.
     */
    private fun parseLongLine(line: String): Device? {
        val parts = if ('\t' in line) {
            line.split('\t', limit = 2)
        } else {
            line.split(Regex("\\s+"), limit = 2)
        }

        if (parts.size < 2) return null

        val serial = parts[0].trim()
        val remainder = parts[1].trim()

        if (serial.isBlank()) return null

        // The state is the first token, rest are key:value pairs
        val tokens = remainder.split(Regex("\\s+"))
        val stateStr = tokens.firstOrNull() ?: return null

        // Extract model from key:value pairs
        val model = tokens.drop(1)
            .firstOrNull { it.startsWith("model:") }
            ?.substringAfter("model:")
            ?.replace('_', ' ')

        return Device(
            serial = serial,
            state = DeviceState.fromAdbOutput(stateStr),
            model = model
        )
    }

    /**
     * Parses the device IP address from `adb shell ip route` output.
     *
     * Example output:
     * ```
     * 192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.105
     * ```
     *
     * @param output The raw stdout from `adb shell ip route`.
     * @return The device's IP address, or null if it cannot be determined.
     */
    fun parseDeviceIp(output: String): String? {
        if (output.isBlank()) return null

        // Look for "src <ip>" pattern in the output
        val srcPattern = Regex("""src\s+(\d+\.\d+\.\d+\.\d+)""")
        for (line in output.lines()) {
            // Prefer wlan interface for LAN connectivity
            if (line.contains("wlan") || line.contains("wifi")) {
                srcPattern.find(line)?.let { return it.groupValues[1] }
            }
        }

        // Fall back to any "src <ip>" match
        srcPattern.find(output)?.let { return it.groupValues[1] }

        return null
    }
}
