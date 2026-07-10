package com.adbconnect.plugin.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionStateTest {

    @Test
    fun `Idle displayName returns correct label`() {
        assertEquals("Idle", ConnectionState.Idle.displayName())
    }

    @Test
    fun `all states have unique display names`() {
        val states = listOf(
            ConnectionState.Idle,
            ConnectionState.WaitingForWindowsServer,
            ConnectionState.WaitingForWindowsDevice,
            ConnectionState.PreparingDevice(Device("test", DeviceState.DEVICE)),
            ConnectionState.Connecting(Device("test", DeviceState.DEVICE)),
            ConnectionState.Connected(emptyList()),
            ConnectionState.MonitoringLinux(emptyList()),
            ConnectionState.Reconnecting,
            ConnectionState.Error("test error")
        )

        val names = states.map { it.displayName() }
        assertEquals(names.size, names.distinct().size, "All display names should be unique")
    }

    @Test
    fun `all states have status indicators`() {
        val states = listOf(
            ConnectionState.Idle,
            ConnectionState.WaitingForWindowsServer,
            ConnectionState.WaitingForWindowsDevice,
            ConnectionState.PreparingDevice(Device("test", DeviceState.DEVICE)),
            ConnectionState.Connecting(Device("test", DeviceState.DEVICE)),
            ConnectionState.Connected(emptyList()),
            ConnectionState.MonitoringLinux(emptyList()),
            ConnectionState.Reconnecting,
            ConnectionState.Error("test error")
        )

        for (state in states) {
            assertTrue(state.statusIndicator().isNotBlank(), "State ${state.displayName()} should have an indicator")
        }
    }

    @Test
    fun `Connected state contains devices`() {
        val devices = listOf(
            Device("ABC123", DeviceState.DEVICE),
            Device("XYZ789", DeviceState.DEVICE)
        )
        val state = ConnectionState.Connected(devices)
        assertEquals(2, state.devices.size)
        assertEquals("ABC123", state.devices[0].serial)
    }

    @Test
    fun `Error state contains message and optional cause`() {
        val cause = RuntimeException("test")
        val state = ConnectionState.Error("Something failed", cause)
        assertEquals("Something failed", state.message)
        assertEquals(cause, state.cause)
    }

    @Test
    fun `Error state with null cause`() {
        val state = ConnectionState.Error("Something failed")
        assertNull(state.cause)
    }

    @Test
    fun `PreparingDevice state contains device`() {
        val device = Device("ABC123", DeviceState.DEVICE, model = "Pixel 9")
        val state = ConnectionState.PreparingDevice(device)
        assertEquals(device, state.device)
        assertEquals("Pixel 9", state.device.model)
    }

    @Test
    fun `status indicators match expected patterns`() {
        assertEquals("⚪", ConnectionState.Idle.statusIndicator())
        assertEquals("🟡", ConnectionState.WaitingForWindowsServer.statusIndicator())
        assertEquals("🟡", ConnectionState.WaitingForWindowsDevice.statusIndicator())
        assertEquals("🟢", ConnectionState.Connected(emptyList()).statusIndicator())
        assertEquals("🟢", ConnectionState.MonitoringLinux(emptyList()).statusIndicator())
        assertEquals("🟠", ConnectionState.Reconnecting.statusIndicator())
        assertEquals("🔴", ConnectionState.Error("err").statusIndicator())
    }

    @Test
    fun `data equality for Connected state`() {
        val devices = listOf(Device("ABC", DeviceState.DEVICE))
        val state1 = ConnectionState.Connected(devices)
        val state2 = ConnectionState.Connected(devices)
        assertEquals(state1, state2)
    }

    @Test
    fun `data inequality for different devices`() {
        val state1 = ConnectionState.Connected(listOf(Device("ABC", DeviceState.DEVICE)))
        val state2 = ConnectionState.Connected(listOf(Device("XYZ", DeviceState.DEVICE)))
        assertNotEquals(state1, state2)
    }
}
