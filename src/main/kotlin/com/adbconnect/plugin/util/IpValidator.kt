package com.adbconnect.plugin.util

/**
 * Validates IPv4 addresses and hostnames for the Windows host configuration.
 */
object IpValidator {

    private val IPV4_PATTERN = Regex(
        """^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$"""
    )

    private val HOSTNAME_PATTERN = Regex(
        """^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)*[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?$"""
    )

    /**
     * Validates that the given string is a valid IPv4 address or hostname.
     *
     * @param host The host string to validate.
     * @return `true` if the host is a valid IPv4 address or hostname.
     */
    fun isValid(host: String): Boolean {
        if (host.isBlank()) return false
        return isValidIpv4(host) || isValidHostname(host)
    }

    /**
     * Validates that the given string is a valid IPv4 address.
     *
     * @param ip The string to validate.
     * @return `true` if the string is a valid IPv4 address.
     */
    fun isValidIpv4(ip: String): Boolean {
        return IPV4_PATTERN.matches(ip.trim())
    }

    /**
     * Validates that the given string is a valid hostname.
     *
     * @param hostname The string to validate.
     * @return `true` if the string is a valid hostname.
     */
    fun isValidHostname(hostname: String): Boolean {
        val trimmed = hostname.trim()
        if (trimmed.isEmpty() || trimmed.length > 253) return false
        return HOSTNAME_PATTERN.matches(trimmed)
    }

    /**
     * Validates that the given port number is within the valid TCP port range.
     *
     * @param port The port number to validate.
     * @return `true` if the port is between 1 and 65535 inclusive.
     */
    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }

    /**
     * Returns a validation error message, or null if the host is valid.
     */
    fun getValidationError(host: String): String? {
        if (host.isBlank()) return "Host cannot be empty"
        if (!isValid(host)) return "Invalid IP address or hostname: $host"
        return null
    }
}
