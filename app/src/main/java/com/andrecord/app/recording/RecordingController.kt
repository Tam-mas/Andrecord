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

    private suspend fun start(): RecordingState {
        val id = idGenerator()
        repository.createSession(id, clock())
        activeSessionId = id
        serviceStarter.startRecording(id)
        state = RecordingState.RECORDING
        return state
    }

    private suspend fun stop(): RecordingState {
        serviceStarter.stopRecording()
        activeSessionId = null
        state = RecordingState.IDLE
        return state
    }
}
