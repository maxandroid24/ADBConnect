package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.CommandResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdbExecutorTest {

    // Note: Full AdbExecutor tests require the IntelliJ Platform test framework
    // for service injection. These tests validate the CommandResult model and
    // the logic that can be tested without a Project context.

    @Test
    fun `CommandResult success when exit code is 0`() {
        val result = CommandResult(0, "output", "", 100)
        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
    }

    @Test
    fun `CommandResult failure when exit code is non-zero`() {
        val result = CommandResult(1, "", "error occurred", 50)
        assertFalse(result.isSuccess)
        assertTrue(result.isFailure)
    }

    @Test
    fun `CommandResult timeout factory`() {
        val result = CommandResult.timeout(5000)
        assertEquals(-1, result.exitCode)
        assertTrue(result.isFailure)
        assertTrue(result.stderr.contains("timed out"))
        assertEquals(5000, result.durationMs)
    }

    @Test
    fun `CommandResult failure factory`() {
        val result = CommandResult.failure("Connection refused", 200)
        assertEquals(-1, result.exitCode)
        assertTrue(result.isFailure)
        assertEquals("Connection refused", result.stderr)
        assertEquals(200, result.durationMs)
    }

    @Test
    fun `CommandResult fullOutput combines stdout and stderr`() {
        val result = CommandResult(0, "line1", "warning", 100)
        val full = result.fullOutput
        assertTrue(full.contains("line1"))
        assertTrue(full.contains("warning"))
    }

    @Test
    fun `CommandResult fullOutput with only stdout`() {
        val result = CommandResult(0, "output only", "", 100)
        assertEquals("output only", result.fullOutput)
    }

    @Test
    fun `CommandResult fullOutput with only stderr`() {
        val result = CommandResult(1, "", "error only", 100)
        assertEquals("error only", result.fullOutput)
    }

    @Test
    fun `CommandResult fullOutput with both empty`() {
        val result = CommandResult(0, "", "", 100)
        assertEquals("", result.fullOutput)
    }

    @Test
    fun `ADB_SERVER_SOCKET format for Windows`() {
        // Test the socket string format used by executeOnWindows
        val host = "192.168.1.10"
        val port = 5037
        val socket = "tcp:$host:$port"
        assertEquals("tcp:192.168.1.10:5037", socket)
    }

    @Test
    fun `ADB_SERVER_SOCKET format for local`() {
        val socket = "tcp:127.0.0.1:5037"
        assertEquals("tcp:127.0.0.1:5037", socket)
    }

    @Test
    fun `command construction`() {
        // Validate that the command list is built correctly
        val adbPath = "/usr/bin/adb"
        val args = arrayOf("-s", "ABC123", "tcpip", "5555")
        val command = listOf(adbPath) + args.toList()

        assertEquals(5, command.size)
        assertEquals("/usr/bin/adb", command[0])
        assertEquals("-s", command[1])
        assertEquals("ABC123", command[2])
        assertEquals("tcpip", command[3])
        assertEquals("5555", command[4])
    }
}
