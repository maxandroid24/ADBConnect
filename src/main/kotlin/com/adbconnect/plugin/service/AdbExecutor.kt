package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.CommandResult
import com.adbconnect.plugin.settings.PluginSettings
import com.adbconnect.plugin.util.AdbPathResolver
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit

/**
 * Executes ADB commands via [ProcessBuilder], managing per-process environment
 * variables so the IDE's global environment is never modified.
 *
 * Each invocation sets `ADB_SERVER_SOCKET` on the child process only, allowing
 * seamless switching between the remote Windows ADB server and the local Linux
 * ADB server without side effects.
 *
 * This service is the **only** place in the plugin that spawns ADB processes.
 */
@Service(Service.Level.PROJECT)
class AdbExecutor(private val project: Project) {

    private val log = Logger.getInstance(AdbExecutor::class.java)

    /**
     * Executes an ADB command with optional server socket override.
     *
     * @param args        ADB command arguments (e.g., `"devices"`, `"-s"`, `"ABC123"`, `"tcpip"`, `"5555"`).
     * @param serverSocket Optional `ADB_SERVER_SOCKET` value (e.g., `"tcp:192.168.1.10:5037"`).
     *                     If null, the process inherits the system environment.
     * @param timeoutSeconds Maximum time to wait for the process to complete.
     * @return A [CommandResult] capturing exit code, stdout, stderr, and duration.
     */
    fun execute(
        vararg args: String,
        serverSocket: String? = null,
        timeoutSeconds: Long = 30
    ): CommandResult {
        val settings = PluginSettings.getInstance()
        val adbPath = AdbPathResolver.resolve(settings.state.adbPath.orEmpty())
        val command = listOf(adbPath) + args.toList()

        log.info("Executing: ${command.joinToString(" ")}${serverSocket?.let { " [ADB_SERVER_SOCKET=$it]" } ?: ""}")

        val startTime = System.currentTimeMillis()

        return try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(false)

            // Set ADB_SERVER_SOCKET per-process — never mutate the IDE environment
            if (serverSocket != null) {
                processBuilder.environment()["ADB_SERVER_SOCKET"] = serverSocket
            }

            val process = processBuilder.start()

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val durationMs = System.currentTimeMillis() - startTime

            if (!completed) {
                process.destroyForcibly()
                log.warn("Command timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}")
                return CommandResult.timeout(durationMs)
            }

            val exitCode = process.exitValue()
            val result = CommandResult(exitCode, stdout.trim(), stderr.trim(), durationMs)

            if (result.isSuccess) {
                log.info("Command succeeded in ${durationMs}ms (exit=$exitCode)")
                log.debug("stdout: ${result.stdout}")
            } else {
                log.warn("Command failed in ${durationMs}ms (exit=$exitCode): ${result.stderr}")
            }

            result
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            log.error("Failed to execute: ${command.joinToString(" ")}", e)
            CommandResult.failure("${e.javaClass.simpleName}: ${e.message}", durationMs)
        }
    }

    /**
     * Executes an ADB command targeting the **remote Windows ADB server**.
     *
     * Sets `ADB_SERVER_SOCKET=tcp:<windowsHost>:<adbPort>` on the child process.
     */
    fun executeOnWindows(vararg args: String, timeoutSeconds: Long = 30): CommandResult {
        val settings = PluginSettings.getInstance()
        val host = settings.state.windowsHost.orEmpty()
        val port = settings.state.adbPort
        val socket = "tcp:$host:$port"
        return execute(*args, serverSocket = socket, timeoutSeconds = timeoutSeconds)
    }

    /**
     * Executes an ADB command targeting the **local Linux ADB server**.
     *
     * Sets `ADB_SERVER_SOCKET=tcp:127.0.0.1:5037` on the child process to ensure
     * the command always talks to the local server regardless of any global
     * `ADB_SERVER_SOCKET` that might be set.
     */
    fun executeOnLocal(vararg args: String, timeoutSeconds: Long = 30): CommandResult {
        return execute(*args, serverSocket = "tcp:127.0.0.1:5037", timeoutSeconds = timeoutSeconds)
    }

    companion object {
        fun getInstance(project: Project): AdbExecutor = project.service()
    }
}
