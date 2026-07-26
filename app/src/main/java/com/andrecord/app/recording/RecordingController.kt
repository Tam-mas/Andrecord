package com.andrecord.app.recording

import com.andrecord.app.data.SessionRepository
import java.util.UUID

class RecordingController(
    private val repository: SessionRepository,
    private val serviceStarter: RecordingServiceStarter,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var state: RecordingState = RecordingState.IDLE
    private var activeSessionId: String? = null

    fun currentState(): RecordingState = state

    suspend fun toggle(): RecordingState {
        return if (state == RecordingState.IDLE) start() else stop()
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
        state = RecordingState.IDLE
    }

    private suspend fun start(): RecordingState {
        val id = idGenerator()
        repository.createSession(id, clock())
        activeSessionId = id
        // Set before handing off to the service: the service can report a start failure back to
        // us (see reportRecordingEnded) as soon as it processes the start intent, and that reset
        // must not be clobbered by a late assignment here.
        state = RecordingState.RECORDING
        serviceStarter.startRecording(id)
        return state
    }

    private suspend fun stop(): RecordingState {
        serviceStarter.stopRecording()
        activeSessionId = null
        state = RecordingState.IDLE
        return state
    }
}
