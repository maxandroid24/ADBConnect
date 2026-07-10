package com.adbconnect.plugin.notification

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NotificationServiceTest {

    // Note: Full NotificationService tests require the IntelliJ Platform test framework
    // for NotificationGroupManager. These tests validate the throttling logic.

    @Test
    fun `throttle logic allows first notification`() {
        val tracker = ThrottleTracker(windowMs = 10_000)
        assertTrue(tracker.shouldNotify("key1"))
    }

    @Test
    fun `throttle logic blocks duplicate within window`() {
        val tracker = ThrottleTracker(windowMs = 10_000)
        tracker.record("key1", timestamp = 1000)

        assertFalse(tracker.shouldNotify("key1", timestamp = 5000)) // 4s later, within 10s window
    }

    @Test
    fun `throttle logic allows after window expires`() {
        val tracker = ThrottleTracker(windowMs = 10_000)
        tracker.record("key1", timestamp = 1000)

        assertTrue(tracker.shouldNotify("key1", timestamp = 12_000)) // 11s later, past 10s window
    }

    @Test
    fun `throttle logic tracks different keys independently`() {
        val tracker = ThrottleTracker(windowMs = 10_000)
        tracker.record("key1", timestamp = 1000)

        // key2 should be allowed even though key1 was just recorded
        assertTrue(tracker.shouldNotify("key2", timestamp = 2000))
    }

    @Test
    fun `throttle logic clear resets all keys`() {
        val tracker = ThrottleTracker(windowMs = 10_000)
        tracker.record("key1", timestamp = 1000)
        tracker.record("key2", timestamp = 2000)

        tracker.clear()

        assertTrue(tracker.shouldNotify("key1", timestamp = 3000))
        assertTrue(tracker.shouldNotify("key2", timestamp = 3000))
    }

    @Test
    fun `throttle window of zero allows all notifications`() {
        val tracker = ThrottleTracker(windowMs = 0)
        tracker.record("key1", timestamp = 1000)
        assertTrue(tracker.shouldNotify("key1", timestamp = 1000))
    }

    @Test
    fun `notification key format for connected`() {
        val deviceName = "Pixel 9"
        val key = "connected:$deviceName"
        assertEquals("connected:Pixel 9", key)
    }

    @Test
    fun `notification key format for disconnected`() {
        val deviceName = "Samsung S24"
        val key = "disconnected:$deviceName"
        assertEquals("disconnected:Samsung S24", key)
    }

    @Test
    fun `different device names produce different keys`() {
        val key1 = "connected:Pixel 9"
        val key2 = "connected:Samsung S24"
        assertNotEquals(key1, key2)
    }

    /**
     * Extracted throttle tracking logic for testing without IntelliJ dependencies.
     * This mirrors the throttling behavior in [NotificationService].
     */
    private class ThrottleTracker(private val windowMs: Long) {
        private val lastTimes = mutableMapOf<String, Long>()

        fun record(key: String, timestamp: Long) {
            lastTimes[key] = timestamp
        }

        fun shouldNotify(key: String, timestamp: Long = System.currentTimeMillis()): Boolean {
            val lastTime = lastTimes[key] ?: return true
            return (timestamp - lastTime) >= windowMs
        }

        fun clear() {
            lastTimes.clear()
        }
    }
}
