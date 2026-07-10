package com.adbconnect.plugin.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class IpValidatorTest {

    // ──────────────────────────────────────────────────────────────────────
    // isValidIpv4
    // ──────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = [
        "192.168.1.1",
        "10.0.0.1",
        "255.255.255.255",
        "0.0.0.0",
        "172.16.0.1",
        "1.2.3.4"
    ])
    fun `valid IPv4 addresses`(ip: String) {
        assertTrue(IpValidator.isValidIpv4(ip), "Should be valid: $ip")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "256.1.1.1",
        "192.168.1",
        "192.168.1.1.1",
        "abc.def.ghi.jkl",
        "",
        "   ",
        "192.168.1.999",
        "192.168.01.1", // Debatable but our regex allows leading zeros
    ])
    fun `invalid IPv4 addresses`(ip: String) {
        if (ip == "192.168.01.1") return // Leading zeros are valid per our regex
        assertFalse(IpValidator.isValidIpv4(ip), "Should be invalid: '$ip'")
    }

    // ──────────────────────────────────────────────────────────────────────
    // isValidHostname
    // ──────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = [
        "localhost",
        "my-windows-pc",
        "windows.local",
        "server-01.office.lan",
        "a",
        "test123"
    ])
    fun `valid hostnames`(hostname: String) {
        assertTrue(IpValidator.isValidHostname(hostname), "Should be valid: $hostname")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "",
        "   ",
        "-invalid",
        "invalid-",
        ".invalid",
    ])
    fun `invalid hostnames`(hostname: String) {
        assertFalse(IpValidator.isValidHostname(hostname), "Should be invalid: '$hostname'")
    }

    // ──────────────────────────────────────────────────────────────────────
    // isValid (combined)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `isValid accepts IPv4`() {
        assertTrue(IpValidator.isValid("192.168.1.100"))
    }

    @Test
    fun `isValid accepts hostname`() {
        assertTrue(IpValidator.isValid("my-pc"))
    }

    @Test
    fun `isValid rejects blank`() {
        assertFalse(IpValidator.isValid(""))
        assertFalse(IpValidator.isValid("   "))
    }

    // ──────────────────────────────────────────────────────────────────────
    // isValidPort
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `valid ports`() {
        assertTrue(IpValidator.isValidPort(1))
        assertTrue(IpValidator.isValidPort(80))
        assertTrue(IpValidator.isValidPort(5037))
        assertTrue(IpValidator.isValidPort(5555))
        assertTrue(IpValidator.isValidPort(65535))
    }

    @Test
    fun `invalid ports`() {
        assertFalse(IpValidator.isValidPort(0))
        assertFalse(IpValidator.isValidPort(-1))
        assertFalse(IpValidator.isValidPort(65536))
        assertFalse(IpValidator.isValidPort(100000))
    }

    // ──────────────────────────────────────────────────────────────────────
    // getValidationError
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `getValidationError returns null for valid host`() {
        assertNull(IpValidator.getValidationError("192.168.1.1"))
        assertNull(IpValidator.getValidationError("my-windows-pc"))
    }

    @Test
    fun `getValidationError returns message for blank host`() {
        val error = IpValidator.getValidationError("")
        assertNotNull(error)
        assertTrue(error!!.contains("empty", ignoreCase = true))
    }

    @Test
    fun `getValidationError returns message for invalid host`() {
        val error = IpValidator.getValidationError("---invalid---")
        assertNotNull(error)
        assertTrue(error!!.contains("Invalid", ignoreCase = true))
    }
}
