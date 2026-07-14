package com.adbconnect.plugin.service

import com.adbconnect.plugin.model.ConnectionState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Manages the finite state machine governing the remote ADB connection lifecycle.
 *
 * All UI and service components observe [state] to react to transitions.
 * Transitions are validated against the allowed transition table — invalid
 * transitions are logged and silently rejected.
 *
 * Thread-safe: [transition] can be called from any thread.
 */
@Service(Service.Level.PROJECT)
class StateMachine(private val project: Project) {

    private val log = Logger.getInstance(StateMachine::class.java)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    /** Observable state flow — collect from UI or service components. */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** The current state (snapshot). */
    val currentState: ConnectionState
        get() = _state.value

    /** Timestamp of the last state transition. */
    @Volatile
    var lastTransitionTime: Instant = Instant.now()
        private set

    /** History of recent state names for debugging (bounded size). */
    private val _history = mutableListOf<String>()
    val history: List<String>
        get() = synchronized(_history) { _history.toList() }

    /**
     * Attempts a state transition. If the transition is not valid according to
     * the transition table, it is rejected with a warning log.
     *
     * @param newState The target state.
     * @return `true` if the transition was applied, `false` if rejected.
     */
    fun transition(newState: ConnectionState): Boolean {
        val oldState = _state.value

        // Same-state transitions are no-ops (not errors)
        if (oldState::class == newState::class && oldState == newState) {
            log.debug("Ignoring no-op transition: ${oldState.displayName()}")
            return true
        }

        if (!isValidTransition(oldState, newState)) {
            log.warn(
                "Invalid state transition rejected: ${oldState.displayName()} → ${newState.displayName()}"
            )
            return false
        }

        log.info("State transition: ${oldState.displayName()} → ${newState.displayName()}")
        lastTransitionTime = Instant.now()

        synchronized(_history) {
            _history.add("${oldState.displayName()} → ${newState.displayName()}")
            if (_history.size > MAX_HISTORY) _history.removeAt(0)
        }

        _state.value = newState
        return true
    }

    /**
     * Forces a transition to [ConnectionState.Idle] regardless of current state.
     * Used during disconnect or cleanup.
     */
    fun reset() {
        log.info("State machine reset to Idle from ${_state.value.displayName()}")
        lastTransitionTime = Instant.now()
        _state.value = ConnectionState.Idle
    }

    /**
     * Validates whether a transition from [from] to [to] is allowed.
     *
     * The transition table encodes the FSM edges. Any state can transition
     * to [ConnectionState.Idle] (disconnect) or [ConnectionState.Error].
     */
    private fun isValidTransition(from: ConnectionState, to: ConnectionState): Boolean {
        // Any state can go to Idle (user-initiated disconnect)
        if (to is ConnectionState.Idle) return true

        // Any state can go to Error
        if (to is ConnectionState.Error) return true

        return when (from) {
            is ConnectionState.Idle ->
                to is ConnectionState.WaitingForWindowsServer

            is ConnectionState.WaitingForWindowsServer ->
                to is ConnectionState.WaitingForWindowsDevice || to is ConnectionState.PreparingDevice

            is ConnectionState.WaitingForWindowsDevice ->
                to is ConnectionState.PreparingDevice

            is ConnectionState.PreparingDevice ->
                to is ConnectionState.Connecting || to is ConnectionState.Connected || to is ConnectionState.MonitoringLinux

            is ConnectionState.Connecting ->
                to is ConnectionState.Connected || to is ConnectionState.MonitoringLinux

            is ConnectionState.Connected ->
                to is ConnectionState.MonitoringLinux || to is ConnectionState.PreparingDevice

            is ConnectionState.MonitoringLinux ->
                to is ConnectionState.Reconnecting || to is ConnectionState.Connected || to is ConnectionState.PreparingDevice

            is ConnectionState.Reconnecting ->
                to is ConnectionState.WaitingForWindowsDevice ||
                        to is ConnectionState.WaitingForWindowsServer

            is ConnectionState.Error ->
                to is ConnectionState.WaitingForWindowsServer ||
                        to is ConnectionState.Reconnecting
        }
    }

    companion object {
        private const val MAX_HISTORY = 50

        fun getInstance(project: Project): StateMachine = project.service()
    }
}
