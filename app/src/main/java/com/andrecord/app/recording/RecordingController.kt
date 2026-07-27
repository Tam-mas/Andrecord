package com.andrecord.app.recording

import com.andrecord.app.data.SessionRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RecordingController(
    private val repository: SessionRepository,
    private val serviceStarter: RecordingServiceStarter,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val _state = MutableStateFlow(RecordingState.IDLE)

    /**
     * Single source of truth for "is a recording currently active", observed directly by
     * SessionListViewModel (persistent bar + FAB) and RecordingViewModel (live view) so every
     * path that starts/stops a recording -- this class's own toggle(), a volume-key/Quick-Tap
     * trigger, the recording notification's Stop action, or a mid-recording failure -- is
     * reflected everywhere at once instead of leaving other observers on a stale local copy.
     */
    val state: StateFlow<RecordingState> = _state

    private var activeSessionId: String? = null

    fun currentState(): RecordingState = _state.value

    suspend fun toggle(): RecordingState {
        return if (_state.value == RecordingState.IDLE) start() else stop()
    }

    /**
     * Called by RecordingService whenever a recording ends without the user having asked it to:
     * a start that never got off the ground (mic permission denied, no storage, mic held by
     * another app), or a failure part-way through the capture loop (ran out of storage, permission
     * revoked, mic died). Both cases mean the same thing to the controller — there is no live
     * recording any more — which is why this is named for the outcome rather than for the
     * failed-start case it was originally added for.
     *
     * Without it the controller stays in RECORDING forever: the user's next trigger press would
     * route to stop(), which would flip the just-errored session back to PROCESSING and enqueue
     * diarization against a recording that was never finished.
     */
    fun reportRecordingEnded() {
        activeSessionId = null
        _state.value = RecordingState.IDLE
    }

    /**
     * Session-scoped counterpart to [reportRecordingEnded]: a no-op unless [sessionId] still
     * matches the session this controller currently considers active.
     *
     * A recording's teardown (WAV finalize + ASR stop + trailing decode + Room flush) runs on its
     * own coroutine and can take anywhere from a few hundred milliseconds to over a second. If the
     * user starts a NEW recording before that coroutine finishes, its eventual call to the
     * unguarded [reportRecordingEnded] would flip this controller back to IDLE out from under the
     * new, still-running recording -- desyncing the FAB/persistent bar and kicking the user out of
     * the live view via RecordingScreen's auto-navigate-back effect. Callers that identify the
     * specific session they're tearing down (e.g. RecordingService's notification-Stop path)
     * should use this instead of the unguarded overload.
     */
    fun reportRecordingEnded(sessionId: String) {
        if (activeSessionId == sessionId) reportRecordingEnded()
    }

    private suspend fun start(): RecordingState {
        val id = idGenerator()
        repository.createSession(id, clock())
        activeSessionId = id
        // Set before handing off to the service: the service can report a start failure back to
        // us (see reportRecordingEnded) as soon as it processes the start intent, and that reset
        // must not be clobbered by a late assignment here.
        _state.value = RecordingState.RECORDING
        serviceStarter.startRecording(id)
        return _state.value
    }

    private suspend fun stop(): RecordingState {
        serviceStarter.stopRecording()
        activeSessionId = null
        _state.value = RecordingState.IDLE
        return _state.value
    }
}
