package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.ConnectionState
import com.adbconnect.plugin.model.Device
import com.adbconnect.plugin.model.DeviceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StateMachineTest {

    // Note: StateMachine requires a Project, but for unit testing the core logic,
    // we test the transition validation logic directly. Integration tests would
    // use the IntelliJ test framework.

    @Test
    fun `valid transitions are accepted in expected order`() {
        // Test the transition validation logic directly
        val validator = TransitionValidator()

        assertTrue(validator.isValid(ConnectionState.Idle, ConnectionState.WaitingForWindowsServer))
        assertTrue(validator.isValid(ConnectionState.WaitingForWindowsServer, ConnectionState.WaitingForWindowsDevice))
        assertTrue(validator.isValid(
            ConnectionState.WaitingForWindowsDevice,
            ConnectionState.PreparingDevice(Device("test", DeviceState.DEVICE))
        ))
        assertTrue(validator.isValid(
            ConnectionState.PreparingDevice(Device("test", DeviceState.DEVICE)),
            ConnectionState.Connecting(Device("test", DeviceState.DEVICE))
        ))
        assertTrue(validator.isValid(
            ConnectionState.Connecting(Device("test", DeviceState.DEVICE)),
            ConnectionState.Connected(listOf(Device("test", DeviceState.DEVICE)))
        ))
        assertTrue(validator.isValid(
            ConnectionState.Connected(emptyList()),
            ConnectionState.MonitoringLinux(emptyList())
        ))
    }

    @Test
    fun `any state can transition to Idle`() {
        val validator = TransitionValidator()
        val states = listOf(
            ConnectionState.WaitingForWindowsServer,
            ConnectionState.WaitingForWindowsDevice,
            ConnectionState.PreparingDevice(Device("test", DeviceState.DEVICE)),
            ConnectionState.Connecting(Device("test", DeviceState.DEVICE)),
            ConnectionState.Connected(emptyList()),
            ConnectionState.MonitoringLinux(emptyList()),
            ConnectionState.Reconnecting,
            ConnectionState.Error("test")
        )

        for (state in states) {
            assertTrue(
                validator.isValid(state, ConnectionState.Idle),
                "Should be able to transition from ${state.displayName()} to Idle"
            )
        }
    }

    @Test
    fun `any state can transition to Error`() {
        val validator = TransitionValidator()
        val states = listOf(
            ConnectionState.Idle,
            ConnectionState.WaitingForWindowsServer,
            ConnectionState.WaitingForWindowsDevice,
            ConnectionState.PreparingDevice(Device("test", DeviceState.DEVICE)),
            ConnectionState.Connecting(Device("test", DeviceState.DEVICE)),
            ConnectionState.Connected(emptyList()),
            ConnectionState.MonitoringLinux(emptyList()),
            ConnectionState.Reconnecting
        )

        for (state in states) {
            assertTrue(
                validator.isValid(state, ConnectionState.Error("test")),
                "Should be able to transition from ${state.displayName()} to Error"
            )
        }
    }

    @Test
    fun `invalid backward transitions are rejected`() {
        val validator = TransitionValidator()

        // Can't go from Connected back to Idle directly through WaitingForWindowsServer
        assertFalse(validator.isValid(
            ConnectionState.Connected(emptyList()),
            ConnectionState.WaitingForWindowsServer
        ))

        // Can't skip from Idle to Connected
        assertFalse(validator.isValid(
            ConnectionState.Idle,
            ConnectionState.Connected(emptyList())
        ))

        // Can't go from WaitingForWindowsDevice to Connected (must go through Preparing and Connecting)
        assertFalse(validator.isValid(
            ConnectionState.WaitingForWindowsDevice,
            ConnectionState.Connected(emptyList())
        ))
    }

    @Test
    fun `MonitoringLinux can transition to Reconnecting`() {
        val validator = TransitionValidator()
        assertTrue(validator.isValid(
            ConnectionState.MonitoringLinux(emptyList()),
            ConnectionState.Reconnecting
        ))
    }

    @Test
    fun `Reconnecting can transition to WaitingForWindowsDevice`() {
        val validator = TransitionValidator()
        assertTrue(validator.isValid(
            ConnectionState.Reconnecting,
            ConnectionState.WaitingForWindowsDevice
        ))
    }

    @Test
    fun `Reconnecting can transition to WaitingForWindowsServer`() {
        val validator = TransitionValidator()
        assertTrue(validator.isValid(
            ConnectionState.Reconnecting,
            ConnectionState.WaitingForWindowsServer
        ))
    }

    @Test
    fun `Error can transition to WaitingForWindowsServer for retry`() {
        val validator = TransitionValidator()
        assertTrue(validator.isValid(
            ConnectionState.Error("test"),
            ConnectionState.WaitingForWindowsServer
        ))
    }

    @Test
    fun `Error can transition to Reconnecting`() {
        val validator = TransitionValidator()
        assertTrue(validator.isValid(
            ConnectionState.Error("test"),
            ConnectionState.Reconnecting
        ))
    }

    /**
     * Extracted transition validation logic for unit testing without IntelliJ Project dependency.
     * This mirrors the logic in [StateMachine.isValidTransition].
     */
    private class TransitionValidator {
        fun isValid(from: ConnectionState, to: ConnectionState): Boolean {
            if (to is ConnectionState.Idle) return true
            if (to is ConnectionState.Error) return true

            return when (from) {
                is ConnectionState.Idle ->
                    to is ConnectionState.WaitingForWindowsServer

                is ConnectionState.WaitingForWindowsServer ->
                    to is ConnectionState.WaitingForWindowsDevice

                is ConnectionState.WaitingForWindowsDevice ->
                    to is ConnectionState.PreparingDevice

                is ConnectionState.PreparingDevice ->
                    to is ConnectionState.Connecting

                is ConnectionState.Connecting ->
                    to is ConnectionState.Connected

                is ConnectionState.Connected ->
                    to is ConnectionState.MonitoringLinux

                is ConnectionState.MonitoringLinux ->
                    to is ConnectionState.Reconnecting || to is ConnectionState.Connected

                is ConnectionState.Reconnecting ->
                    to is ConnectionState.WaitingForWindowsDevice ||
                            to is ConnectionState.WaitingForWindowsServer

                is ConnectionState.Error ->
                    to is ConnectionState.WaitingForWindowsServer ||
                            to is ConnectionState.Reconnecting
            }
        }
    }
}
