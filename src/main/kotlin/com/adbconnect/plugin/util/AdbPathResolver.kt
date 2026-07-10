package com.adbconnect.plugin.util

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Resolves the path to the `adb` executable.
 *
 * Resolution order:
 * 1. Explicit path from plugin settings (if configured).
 * 2. Android SDK `platform-tools` directory from common environment variables.
 * 3. System `PATH`.
 */
object AdbPathResolver {

    private val LOG = Logger.getInstance(AdbPathResolver::class.java)

    /** Common environment variables that point to the Android SDK root. */
    private val SDK_ENV_VARS = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_SDK")

    /** The ADB binary name (platform-dependent). */
    private val ADB_BINARY = if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb"

    /**
     * Resolves the ADB executable path.
     *
     * @param explicitPath An explicit path from plugin settings. If non-blank and valid, it is used directly.
     * @return The resolved path to the ADB binary, or `"adb"` as a fallback (relies on PATH).
     */
    fun resolve(explicitPath: String = ""): String {
        // 1. Explicit setting
        if (explicitPath.isNotBlank()) {
            val file = File(explicitPath)
            if (file.exists() && file.canExecute()) {
                LOG.info("Using explicit ADB path: $explicitPath")
                return explicitPath
            }
            LOG.warn("Explicit ADB path not found or not executable: $explicitPath")
        }

        // 2. Android SDK environment variables
        for (envVar in SDK_ENV_VARS) {
            val sdkRoot = System.getenv(envVar)
            if (!sdkRoot.isNullOrBlank()) {
                val adbFile = File(sdkRoot, "platform-tools${File.separator}$ADB_BINARY")
                if (adbFile.exists() && adbFile.canExecute()) {
                    LOG.info("Found ADB via $envVar: ${adbFile.absolutePath}")
                    return adbFile.absolutePath
                }
            }
        }

        // 3. Search PATH
        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            val adbFile = File(dir, ADB_BINARY)
            if (adbFile.exists() && adbFile.canExecute()) {
                LOG.info("Found ADB on PATH: ${adbFile.absolutePath}")
                return adbFile.absolutePath
            }
        }

        // 4. Fallback — let the OS resolve it
        LOG.warn("Could not locate ADB binary; falling back to '$ADB_BINARY' (requires PATH)")
        return ADB_BINARY
    }

    /**
     * Checks whether ADB is available and executable.
     *
     * @param explicitPath An explicit path from plugin settings.
     * @return `true` if the resolved ADB binary exists and is executable, or if bare `adb` can be invoked.
     */
    fun isAvailable(explicitPath: String = ""): Boolean {
        val resolved = resolve(explicitPath)
        if (resolved == ADB_BINARY) {
            // Try executing `adb version` to check availability
            return try {
                val process = ProcessBuilder(resolved, "version")
                    .redirectErrorStream(true)
                    .start()
                val exited = process.waitFor()
                exited == 0
            } catch (_: Exception) {
                false
            }
        }
        return File(resolved).let { it.exists() && it.canExecute() }
    }
}
