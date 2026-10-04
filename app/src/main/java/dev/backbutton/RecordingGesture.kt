package dev.backbutton

/** Classifies one press. Android's timer decides when hold() runs; this owns no microphone. */
class RecordingGesture {
    enum class Action { NONE, TAP, START_HOLD, FINISH_HOLD, CANCEL_HOLD }
    private enum class State { IDLE, ARMED, TAP_PENDING, HELD, IGNORED }
    private var state = State.IDLE

    /** Returns true only when a long-press timer should be scheduled. */
    fun down(recording: Boolean, enabled: Boolean): Boolean {
        if (state != State.IDLE) return false
        state = when {
            !enabled -> State.IGNORED
            recording -> State.TAP_PENDING
            else -> State.ARMED
        }
        return state == State.ARMED
    }

    fun hold(): Action {
        if (state != State.ARMED) return Action.NONE
        state = State.HELD
        return Action.START_HOLD
    }

    /** A failed microphone start must not become a new tap when released. */
    fun rejectHold() {
        if (state == State.HELD) state = State.IGNORED
    }

    fun release(): Action {
        val old = state
        reset()
        return when (old) {
            State.ARMED, State.TAP_PENDING -> Action.TAP
            State.HELD -> Action.FINISH_HOLD
            else -> Action.NONE
        }
    }

    fun cancel(): Action {
        val held = state == State.HELD
        reset()
        return if (held) Action.CANCEL_HOLD else Action.NONE
    }

    /** The session already stopped, so a stale release/timer must have no further effect. */
    fun reset() { state = State.IDLE }
}
