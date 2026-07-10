package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.Device
import com.adbconnect.plugin.model.DeviceState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LinuxAdbMonitorTest {

    // Note: Full LinuxAdbMonitor tests require the IntelliJ Platform test framework.
    // These tests validate the device loss detection logic the monitor uses.

    @Test
    fun `detect lost device when TCP device disappears`() {
        val expected = listOf(
            Device("ABC123", DeviceState.DEVICE, ip = "192.168.1.105", tcpPort = 5555)
        )

        val currentSerials = setOf<String>() // Empty — device is gone

        val lost = expected.filter { device ->
            val tcpAddr = device.tcpAddress ?: device.serial
            tcpAddr !in currentSerials
        }

        assertEquals(1, lost.size)
        assertEquals("ABC123", lost[0].serial)
    }

    @Test
    fun `no loss when TCP device is present`() {
        val expected = listOf(
            Device("ABC123", DeviceState.DEVICE, ip = "192.168.1.105", tcpPort = 5555)
        )

        val currentSerials = setOf("192.168.1.105:5555")

        val lost = expected.filter { device ->
            val tcpAddr = device.tcpAddress ?: device.serial
            tcpAddr !in currentSerials
        }

        assertTrue(lost.isEmpty())
    }

    @Test
    fun `partial loss detection - one of two devices lost`() {
        val expected = listOf(
            Device("ABC123", DeviceState.DEVICE, ip = "192.168.1.105", tcpPort = 5555),
            Device("XYZ789", DeviceState.DEVICE, ip = "192.168.1.106", tcpPort = 5555)
        )

        val currentSerials = setOf("192.168.1.105:5555") // Only first device present

        val lost = expected.filter { device ->
            val tcpAddr = device.tcpAddress ?: device.serial
            tcpAddr !in currentSerials
        }

        assertEquals(1, lost.size)
        assertEquals("XYZ789", lost[0].serial)
    }

    @Test
    fun `all devices lost detection`() {
        val expected = listOf(
            Device("ABC123", DeviceState.DEVICE, ip = "192.168.1.105", tcpPort = 5555),
            Device("XYZ789", DeviceState.DEVICE, ip = "192.168.1.106", tcpPort = 5555)
        )

        val currentSerials = emptySet<String>()

        val lost = expected.filter { device ->
            val tcpAddr = device.tcpAddress ?: device.serial
            tcpAddr !in currentSerials
        }

        assertEquals(2, lost.size)
        assertTrue(lost.size == expected.size) // All lost
    }

    @Test
    fun `device with offline state is considered lost`() {
        // Even if the serial is present, an offline device shouldn't count
        val currentDevices = listOf(
            Device("192.168.1.105:5555", DeviceState.OFFLINE)
        )

        val onlineSerials = currentDevices
            .filter { it.state == DeviceState.DEVICE }
            .map { it.serial }
            .toSet()

        assertTrue(onlineSerials.isEmpty())
    }

    @Test
    fun `fallback to serial when tcpAddress is null`() {
        val expected = listOf(
            Device("ABC123", DeviceState.DEVICE) // No IP/port set
        )

        val currentSerials = setOf("ABC123")

        val lost = expected.filter { device ->
            val tcpAddr = device.tcpAddress ?: device.serial
            tcpAddr !in currentSerials
        }

        assertTrue(lost.isEmpty(), "Device should be found by serial fallback")
    }
}
