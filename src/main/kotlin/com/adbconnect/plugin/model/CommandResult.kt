package com.adbconnect.plugin.model

/**
 * Encapsulates the result of executing an ADB command via ProcessBuilder.
 *
 * @property exitCode The process exit code (0 = success).
 * @property stdout The captured standard output.
 * @property stderr The captured standard error.
 * @property durationMs How long the command took to execute in milliseconds.
 */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long
) {
    /** Whether the command completed successfully (exit code 0). */
    val isSuccess: Boolean
        get() = exitCode == 0

    /** Whether the command failed (non-zero exit code). */
    val isFailure: Boolean
        get() = !isSuccess

    /** Combined stdout and stderr for logging purposes. */
    val fullOutput: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(stderr)
            }
        }

    companion object {
        /**
         * Creates a [CommandResult] representing a timeout failure.
         */
        fun timeout(timeoutMs: Long): CommandResult = CommandResult(
            exitCode = -1,
            stdout = "",
            stderr = "Command timed out after ${timeoutMs}ms",
            durationMs = timeoutMs
        )

        /**
         * Creates a [CommandResult] representing an execution failure.
         */
        fun failure(error: String, durationMs: Long = 0): CommandResult = CommandResult(
            exitCode = -1,
            stdout = "",
            stderr = error,
            durationMs = durationMs
        )
    }
}
