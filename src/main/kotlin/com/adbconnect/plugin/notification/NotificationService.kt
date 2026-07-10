package com.adbconnect.plugin.notification

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized notification service for the Remote ADB Connector plugin.
 *
 * Uses IntelliJ's [NotificationGroupManager] API and provides throttling
 * to prevent duplicate notifications within a configurable window.
 */
@Service(Service.Level.PROJECT)
class NotificationService(private val project: Project) {

    private val log = Logger.getInstance(NotificationService::class.java)

    /** Tracks last notification time per message key to prevent duplicates. */
    private val lastNotificationTimes = ConcurrentHashMap<String, Long>()

    /** Minimum interval between identical notifications in milliseconds. */
    private var throttleWindowMs: Long = DEFAULT_THROTTLE_WINDOW_MS

    // ──────────────────────────────────────────────────────────────────────
    // Public notification methods
    // ──────────────────────────────────────────────────────────────────────

    /** Notify that a device connection was established successfully. */
    fun notifyConnected(deviceName: String) {
        notify(
            key = "connected:$deviceName",
            title = "Device Connected",
            content = "$deviceName is now connected via remote ADB.",
            type = NotificationType.INFORMATION
        )
    }

    /** Notify that a device was disconnected. */
    fun notifyDisconnected(deviceName: String) {
        notify(
            key = "disconnected:$deviceName",
            title = "Device Disconnected",
            content = "$deviceName has been disconnected.",
            type = NotificationType.WARNING
        )
    }

    /** Notify that a reconnection attempt is in progress. */
    fun notifyRetrying(attempt: Int = 0) {
        val suffix = if (attempt > 0) " (attempt $attempt)" else ""
        notify(
            key = "retrying",
            title = "Reconnecting",
            content = "Attempting to re-establish ADB connection$suffix...",
            type = NotificationType.INFORMATION
        )
    }

    /** Notify that a connection attempt failed. */
    fun notifyConnectionFailed(reason: String) {
        notify(
            key = "connection_failed",
            title = "Connection Failed",
            content = reason,
            type = NotificationType.ERROR
        )
    }

    /** Notify that a new device was discovered on the Windows server. */
    fun notifyNewDevice(deviceName: String) {
        notify(
            key = "new_device:$deviceName",
            title = "New Device Detected",
            content = "Found $deviceName on the remote Windows ADB server.",
            type = NotificationType.INFORMATION
        )
    }

    /** Notify that a previously connected device was lost. */
    fun notifyDeviceLost(deviceName: String) {
        notify(
            key = "device_lost:$deviceName",
            title = "Device Lost",
            content = "$deviceName is no longer available.",
            type = NotificationType.WARNING
        )
    }

    /** Notify that the Windows ADB server is unreachable. */
    fun notifyServerUnreachable(host: String) {
        notify(
            key = "server_unreachable",
            title = "Server Unreachable",
            content = "Cannot reach ADB server at $host.",
            type = NotificationType.ERROR
        )
    }

    /** Notify a generic error. */
    fun notifyError(title: String, content: String) {
        notify(
            key = "error:$title",
            title = title,
            content = content,
            type = NotificationType.ERROR
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Sends a notification if the same key hasn't been notified within the throttle window.
     */
    private fun notify(
        key: String,
        title: String,
        content: String,
        type: NotificationType
    ) {
        val now = System.currentTimeMillis()
        val lastTime = lastNotificationTimes[key]

        if (lastTime != null && (now - lastTime) < throttleWindowMs) {
            log.debug("Throttled notification: $key (${now - lastTime}ms since last)")
            return
        }

        lastNotificationTimes[key] = now

        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, content, type)
                .notify(project)

            log.info("Notification sent: [$type] $title — $content")
        } catch (e: Exception) {
            log.error("Failed to send notification: $title", e)
        }
    }

    /**
     * Clears the throttle cache. Useful for testing or when
     * the user explicitly triggers a new connection attempt.
     */
    fun clearThrottleCache() {
        lastNotificationTimes.clear()
    }

    /**
     * Updates the throttle window. Primarily for testing.
     */
    fun setThrottleWindow(windowMs: Long) {
        throttleWindowMs = windowMs
    }

    companion object {
        /** Must match the id in plugin.xml `<notificationGroup>` */
        const val NOTIFICATION_GROUP_ID = "Remote ADB Connector"

        /** Default: 10 seconds between identical notifications. */
        private const val DEFAULT_THROTTLE_WINDOW_MS = 10_000L

        fun getInstance(project: Project): NotificationService = project.service()
    }
}
