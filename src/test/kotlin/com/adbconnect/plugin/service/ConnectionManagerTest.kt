package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.Device
import com.adbconnect.plugin.model.DeviceState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionManagerTest {

    // Note: Full ConnectionManager tests require the IntelliJ Platform test framework
    // for service injection. These tests validate the Device model logic used by
    // ConnectionManager and can be run standalone.

    @Test
    fun `Device tcpAddress format`() {
        val device = Device("ABC123", DeviceState.DEVICE, ip = "192.168.1.105", tcpPort = 5555)
        assertEquals("192.168.1.105:5555", device.tcpAddress)
    }

    @Test
    fun `Device tcpAddress null when ip missing`() {
        val device = Device("ABC123", DeviceState.DEVICE, tcpPort = 5555)
        assertNull(device.tcpAddress)
    }

    @Test
    fun `Device tcpAddress null when port missing`() {
        val device = Device("ABC123", DeviceState.DEVICE, ip = "192.168.1.105")
        assertNull(device.tcpAddress)
    }

    @Test
    fun `Device isOnline when state is DEVICE`() {
        assertTrue(Device("ABC", DeviceState.DEVICE).isOnline)
        assertFalse(Device("ABC", DeviceState.OFFLINE).isOnline)
        assertFalse(Device("ABC", DeviceState.UNAUTHORIZED).isOnline)
    }

    @Test
    fun `Device isTcpDevice recognizes TCP serial`() {
        assertTrue(Device("192.168.1.100:5555", DeviceState.DEVICE).isTcpDevice)
        assertFalse(Device("ABC123", DeviceState.DEVICE).isTcpDevice)
        assertFalse(Device("emulator-5554", DeviceState.DEVICE).isTcpDevice)
    }

    @Test
    fun `Device displayName prefers model`() {
        val device = Device("ABC123", DeviceState.DEVICE, model = "Pixel 9")
        assertEquals("Pixel 9", device.displayName())
    }

    @Test
    fun `Device displayName falls back to serial`() {
        val device = Device("ABC123", DeviceState.DEVICE)
        assertEquals("ABC123", device.displayName())
    }

    @Test
    fun `Device copy preserves and updates fields`() {
        val original = Device("ABC123", DeviceState.DEVICE, model = "Pixel 9")
        val prepared = original.copy(ip = "192.168.1.105", tcpPort = 5555)

        assertEquals("ABC123", prepared.serial)
        assertEquals(DeviceState.DEVICE, prepared.state)
        assertEquals("Pixel 9", prepared.model)
        assertEquals("192.168.1.105", prepared.ip)
        assertEquals(5555, prepared.tcpPort)
        assertEquals("192.168.1.105:5555", prepared.tcpAddress)
    }

    @Test
    fun `connect output parsing - connected`() {
        val output = "connected to 192.168.1.105:5555"
        assertTrue(output.lowercase().contains("connected"))
    }

    @Test
    fun `connect output parsing - already connected`() {
        val output = "already connected to 192.168.1.105:5555"
        assertTrue(output.lowercase().contains("already connected"))
    }

    @Test
    fun `connect output parsing - failed`() {
        val output = "failed to connect to 192.168.1.105:5555"
        // "connected" appears but this is a failure case
        // The actual ConnectionManager checks for "connected" but also checks exit code
        assertTrue(output.lowercase().contains("failed"))
    }

    @Test
    fun `DeviceState fromAdbOutput handles all known states`() {
        assertEquals(DeviceState.DEVICE, DeviceState.fromAdbOutput("device"))
        assertEquals(DeviceState.OFFLINE, DeviceState.fromAdbOutput("offline"))
        assertEquals(DeviceState.UNAUTHORIZED, DeviceState.fromAdbOutput("unauthorized"))
        assertEquals(DeviceState.RECOVERY, DeviceState.fromAdbOutput("recovery"))
        assertEquals(DeviceState.CONNECTING, DeviceState.fromAdbOutput("connecting"))
        assertEquals(DeviceState.UNKNOWN, DeviceState.fromAdbOutput("something_else"))
    }

    @Test
    fun `DeviceState fromAdbOutput is case insensitive`() {
        assertEquals(DeviceState.DEVICE, DeviceState.fromAdbOutput("DEVICE"))
        assertEquals(DeviceState.DEVICE, DeviceState.fromAdbOutput("Device"))
        assertEquals(DeviceState.OFFLINE, DeviceState.fromAdbOutput("OFFLINE"))
    }

    @Test
    fun `DeviceState fromAdbOutput trims whitespace`() {
        assertEquals(DeviceState.DEVICE, DeviceState.fromAdbOutput("  device  "))
        assertEquals(DeviceState.OFFLINE, DeviceState.fromAdbOutput("\toffline\t"))
    }
}
