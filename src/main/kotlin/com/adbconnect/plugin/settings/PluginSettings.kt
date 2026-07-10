package com.adbconnect.plugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings for the Remote ADB Connector plugin.
 *
 * Stored at application level so settings survive across projects.
 * Values are persisted to `remote-adb-connector.xml` in the IDE config directory.
 */
@Service(Service.Level.APP)
@State(
    name = "RemoteAdbConnectorSettings",
    storages = [Storage("remote-adb-connector.xml")]
)
class PluginSettings : SimplePersistentStateComponent<PluginSettingsState>(PluginSettingsState()) {

    companion object {
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}

/**
 * The serializable state for [PluginSettings].
 *
 * Uses [BaseState]'s delegated properties for automatic change tracking
 * and XML serialization.
 */
class PluginSettingsState : BaseState() {

    /** IP address or hostname of the Windows machine running the ADB server. */
    var windowsHost by string("")

    /** ADB server port on the Windows machine (default: 5037). */
    var adbPort by property(DEFAULT_ADB_PORT)

    /** TCP port to use when switching devices to TCP mode (default: 5555). */
    var deviceTcpPort by property(DEFAULT_DEVICE_TCP_PORT)

    /** How often to poll for device changes, in seconds (default: 5). */
    var pollingIntervalSeconds by property(DEFAULT_POLLING_INTERVAL)

    /** Whether to automatically connect on IDE startup. */
    var autoConnect by property(false)

    /** Whether to automatically detect devices on the Windows server. */
    var autoDetectDevices by property(true)

    /** Whether to automatically reconnect when a device is lost. */
    var autoReconnect by property(true)

    /** Explicit path to the ADB binary. Empty = auto-detect. */
    var adbPath by string("")

    /** Timeout for individual ADB commands, in seconds. */
    var commandTimeoutSeconds by property(DEFAULT_COMMAND_TIMEOUT)

    companion object {
        const val DEFAULT_ADB_PORT = 5037
        const val DEFAULT_DEVICE_TCP_PORT = 5555
        const val DEFAULT_POLLING_INTERVAL = 5
        const val DEFAULT_COMMAND_TIMEOUT = 30
    }
}
