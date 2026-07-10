package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.DeviceState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeviceParserTest {

    // ──────────────────────────────────────────────────────────────────────
    // parse() tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `parse empty output returns empty list`() {
        assertEquals(emptyList<Any>(), DeviceParser.parse(""))
    }

    @Test
    fun `parse blank output returns empty list`() {
        assertEquals(emptyList<Any>(), DeviceParser.parse("   \n  \n  "))
    }

    @Test
    fun `parse header only returns empty list`() {
        val output = "List of devices attached\n\n"
        assertEquals(emptyList<Any>(), DeviceParser.parse(output))
    }

    @Test
    fun `parse single device`() {
        val output = """
            List of devices attached
            ABC123	device
        """.trimIndent()

        val devices = DeviceParser.parse(output)
        assertEquals(1, devices.size)
        assertEquals("ABC123", devices[0].serial)
        assertEquals(DeviceState.DEVICE, devices[0].state)
    }

    @Test
    fun `parse multiple devices`() {
        val output = """
            List of devices attached
            ABC123	device
            XYZ789	offline
            DEF456	unauthorized
        """.trimIndent()

        val devices = DeviceParser.parse(output)
        assertEquals(3, devices.size)
        assertEquals("ABC123", devices[0].serial)
        assertEquals(DeviceState.DEVICE, devices[0].state)
        assertEquals("XYZ789", devices[1].serial)
        assertEquals(DeviceState.OFFLINE, devices[1].state)
        assertEquals("DEF456", devices[2].serial)
        assertEquals(DeviceState.UNAUTHORIZED, devices[2].state)
    }

    @Test
    fun `parse TCP device`() {
        val output = """
            List of devices attached
            192.168.1.100:5555	device
        """.trimIndent()

        val devices = DeviceParser.parse(output)
        assertEquals(1, devices.size)
        assertEquals("192.168.1.100:5555", devices[0].serial)
        assertEquals(DeviceState.DEVICE, devices[0].state)
        assertTrue(devices[0].isTcpDevice)
    }

    @Test
    fun `parse with daemon messages`() {
        val output = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            ABC123	device
        """.trimIndent()

        val devices = DeviceParser.parse(output)
        assertEquals(1, devices.size)
        assertEquals("ABC123", devices[0].serial)
    }

    @Test
    fun `parse with spaces instead of tabs`() {
        val output = """
            List of devices attached
            ABC123    device
        """.trimIndent()

        val devices = DeviceParser.parse(output)
        assertEquals(1, devices.size)
        assertEquals("ABC123", devices[0].serial)
        assertEquals(DeviceState.DEVICE, devices[0].state)
    }

    @Test
    fun `parse unknown state`() {
        val output = """
            List of devices attached
            ABC123	someNewState
        """.trimIndent()

        val devices = DeviceParser.parse(output)
        assertEquals(1, devices.size)
        assertEquals(DeviceState.UNKNOWN, devices[0].state)
    }

    @Test
    fun `parse recovery state`() {
        val output = """
            List of devices attached
            ABC123	recovery
        """.trimIndent()

        val devices = DeviceParser.parse(output)
        assertEquals(1, devices.size)
        assertEquals(DeviceState.RECOVERY, devices[0].state)
    }

    @Test
    fun `parse connecting state`() {
        val output = """
            List of devices attached
            192.168.1.100:5555	connecting
        """.trimIndent()

        val devices = DeviceParser.parse(output)
        assertEquals(1, devices.size)
        assertEquals(DeviceState.CONNECTING, devices[0].state)
    }

    @Test
    fun `parse malformed line is skipped`() {
        val output = """
            List of devices attached
            this-is-not-valid
            ABC123	device
        """.trimIndent()

        val devices = DeviceParser.parse(output)
        assertEquals(1, devices.size)
        assertEquals("ABC123", devices[0].serial)
    }

    // ──────────────────────────────────────────────────────────────────────
    // parseLong() tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `parseLong extracts model name`() {
        val output = """
            List of devices attached
            ABC123	device usb:1-1 product:oriole model:Pixel_6 device:oriole transport_id:1
        """.trimIndent()

        val devices = DeviceParser.parseLong(output)
        assertEquals(1, devices.size)
        assertEquals("Pixel 6", devices[0].model) // underscores replaced with spaces
        assertEquals(DeviceState.DEVICE, devices[0].state)
    }

    @Test
    fun `parseLong without model`() {
        val output = """
            List of devices attached
            ABC123	device usb:1-1 product:oriole device:oriole transport_id:1
        """.trimIndent()

        val devices = DeviceParser.parseLong(output)
        assertEquals(1, devices.size)
        assertNull(devices[0].model)
    }

    @Test
    fun `parseLong empty output`() {
        assertEquals(emptyList<Any>(), DeviceParser.parseLong(""))
    }

    // ──────────────────────────────────────────────────────────────────────
    // parseDeviceIp() tests
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `parseDeviceIp from ip route output`() {
        val output = "192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.105"
        assertEquals("192.168.1.105", DeviceParser.parseDeviceIp(output))
    }

    @Test
    fun `parseDeviceIp prefers wlan interface`() {
        val output = """
            10.0.0.0/8 dev rmnet0 proto kernel scope link src 10.0.0.1
            192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.105
        """.trimIndent()

        assertEquals("192.168.1.105", DeviceParser.parseDeviceIp(output))
    }

    @Test
    fun `parseDeviceIp falls back to non-wlan`() {
        val output = "10.0.0.0/8 dev eth0 proto kernel scope link src 10.0.0.42"
        assertEquals("10.0.0.42", DeviceParser.parseDeviceIp(output))
    }

    @Test
    fun `parseDeviceIp blank output returns null`() {
        assertNull(DeviceParser.parseDeviceIp(""))
    }

    @Test
    fun `parseDeviceIp no src returns null`() {
        assertNull(DeviceParser.parseDeviceIp("some random output without ip"))
    }

    @Test
    fun `parseDeviceIp multi-line with wifi keyword`() {
        val output = """
            default via 192.168.1.1 dev wifi0
            192.168.1.0/24 dev wifi0 src 192.168.1.200
        """.trimIndent()

        assertEquals("192.168.1.200", DeviceParser.parseDeviceIp(output))
    }
}
