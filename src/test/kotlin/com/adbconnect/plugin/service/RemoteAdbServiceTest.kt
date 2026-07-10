package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.ConnectionState
import com.adbconnect.plugin.model.Device
import com.adbconnect.plugin.model.DeviceState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemoteAdbServiceTest {

    // Note: Full RemoteAdbService tests require the IntelliJ Platform test framework.
    // These tests validate the settings validation logic extracted from the service.

    @Test
    fun `validation passes with valid settings`() {
        val error = validateSettings(
            host = "192.168.1.100",
            adbPort = 5037,
            deviceTcpPort = 5555,
            pollingInterval = 5
        )
        assertNull(error)
    }

    @Test
    fun `validation fails with blank host`() {
        val error = validateSettings(
            host = "",
            adbPort = 5037,
            deviceTcpPort = 5555,
            pollingInterval = 5
        )
        assertNotNull(error)
        assertTrue(error!!.contains("required", ignoreCase = true))
    }

    @Test
    fun `validation fails with invalid host`() {
        val error = validateSettings(
            host = "---invalid---",
            adbPort = 5037,
            deviceTcpPort = 5555,
            pollingInterval = 5
        )
        assertNotNull(error)
        assertTrue(error!!.contains("Invalid", ignoreCase = true))
    }

    @Test
    fun `validation fails with invalid adb port`() {
        val error = validateSettings(
            host = "192.168.1.100",
            adbPort = 0,
            deviceTcpPort = 5555,
            pollingInterval = 5
        )
        assertNotNull(error)
        assertTrue(error!!.contains("port", ignoreCase = true))
    }

    @Test
    fun `validation fails with invalid device tcp port`() {
        val error = validateSettings(
            host = "192.168.1.100",
            adbPort = 5037,
            deviceTcpPort = 70000,
            pollingInterval = 5
        )
        assertNotNull(error)
        assertTrue(error!!.contains("port", ignoreCase = true))
    }

    @Test
    fun `validation fails with zero polling interval`() {
        val error = validateSettings(
            host = "192.168.1.100",
            adbPort = 5037,
            deviceTcpPort = 5555,
            pollingInterval = 0
        )
        assertNotNull(error)
        assertTrue(error!!.contains("interval", ignoreCase = true))
    }

    @Test
    fun `validation passes with hostname`() {
        val error = validateSettings(
            host = "my-windows-pc",
            adbPort = 5037,
            deviceTcpPort = 5555,
            pollingInterval = 5
        )
        assertNull(error)
    }

    @Test
    fun `validation passes with minimum valid values`() {
        val error = validateSettings(
            host = "a",
            adbPort = 1,
            deviceTcpPort = 1,
            pollingInterval = 1
        )
        assertNull(error)
    }

    @Test
    fun `validation passes with maximum valid port`() {
        val error = validateSettings(
            host = "192.168.1.1",
            adbPort = 65535,
            deviceTcpPort = 65535,
            pollingInterval = 5
        )
        assertNull(error)
    }

    /**
     * Extracted validation logic from [RemoteAdbService.validateSettings]
     * for testing without IntelliJ dependencies.
     */
    private fun validateSettings(
        host: String,
        adbPort: Int,
        deviceTcpPort: Int,
        pollingInterval: Int
    ): String? {
        if (host.isBlank()) {
            return "Windows host address is required."
        }

        val hostError = com.adbconnect.plugin.util.IpValidator.getValidationError(host)
        if (hostError != null) return hostError

        if (!com.adbconnect.plugin.util.IpValidator.isValidPort(adbPort)) {
            return "ADB port must be between 1 and 65535."
        }

        if (!com.adbconnect.plugin.util.IpValidator.isValidPort(deviceTcpPort)) {
            return "Device TCP port must be between 1 and 65535."
        }

        if (pollingInterval < 1) {
            return "Polling interval must be at least 1 second."
        }

        return null
    }
}
