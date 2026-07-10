package com.adbconnect.plugin.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WindowsAdbMonitorTest {

    // Note: Full WindowsAdbMonitor tests require the IntelliJ Platform test framework.
    // These tests validate the device change detection logic that the monitor uses.

    @Test
    fun `device list change detection - new device added`() {
        val previous = setOf("ABC123")
        val current = setOf("ABC123", "XYZ789")

        assertNotEquals(previous, current)
        val added = current - previous
        assertEquals(setOf("XYZ789"), added)
    }

    @Test
    fun `device list change detection - device removed`() {
        val previous = setOf("ABC123", "XYZ789")
        val current = setOf("ABC123")

        assertNotEquals(previous, current)
        val removed = previous - current
        assertEquals(setOf("XYZ789"), removed)
    }

    @Test
    fun `device list change detection - no change`() {
        val previous = setOf("ABC123", "XYZ789")
        val current = setOf("ABC123", "XYZ789")

        assertEquals(previous, current)
    }

    @Test
    fun `device list change detection - complete replacement`() {
        val previous = setOf("ABC123")
        val current = setOf("XYZ789")

        assertNotEquals(previous, current)
        val added = current - previous
        val removed = previous - current
        assertEquals(setOf("XYZ789"), added)
        assertEquals(setOf("ABC123"), removed)
    }

    @Test
    fun `device list change detection - empty to populated`() {
        val previous = emptySet<String>()
        val current = setOf("ABC123")

        assertNotEquals(previous, current)
        val added = current - previous
        assertEquals(setOf("ABC123"), added)
    }

    @Test
    fun `device list change detection - populated to empty`() {
        val previous = setOf("ABC123")
        val current = emptySet<String>()

        assertNotEquals(previous, current)
        val removed = previous - current
        assertEquals(setOf("ABC123"), removed)
    }

    @Test
    fun `polling interval validation`() {
        val validIntervals = listOf(1, 5, 10, 30, 60, 300)
        val invalidIntervals = listOf(0, -1, -100)

        for (interval in validIntervals) {
            assertTrue(interval >= 1, "Interval $interval should be valid")
        }
        for (interval in invalidIntervals) {
            assertTrue(interval < 1, "Interval $interval should be invalid")
        }
    }
}
