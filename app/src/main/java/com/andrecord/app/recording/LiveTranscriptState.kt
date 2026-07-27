package com.andrecord.app.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LiveTranscriptSnapshot(
    val sessionId: String? = null,
    val startTime: Long? = null,
    val finalLines: List<String> = emptyList(),
    val partialLine: String? = null
)

/**
 * Shared, process-local state for the live recording screen and the session list's persistent
 * recording bar, fed directly by [RecordingService] as it processes ASR events during capture.
 * This is entirely separate from the Room-backed transcript (which [RecordingService] continues
 * to flush periodically, unchanged) — this holder only exists to drive the in-progress UI while a
 * recording is active, and is cleared the moment it ends.
 */
class LiveTranscriptState {
    private val _snapshot = MutableStateFlow(LiveTranscriptSnapshot())
    val snapshot: StateFlow<LiveTranscriptSnapshot> = _snapshot

    fun start(sessionId: String, startTime: Long) {
        _snapshot.value = LiveTranscriptSnapshot(sessionId = sessionId, startTime = startTime)
    }

    fun appendFinal(text: String) {
        val current = _snapshot.value
        _snapshot.value = current.copy(finalLines = current.finalLines + text, partialLine = null)
    }

    fun updatePartial(text: String) {
        _snapshot.value = _snapshot.value.copy(partialLine = text)
    }

    fun clear() {
        _snapshot.value = LiveTranscriptSnapshot()
    }

    /**
     * Session-scoped counterpart to [appendFinal]: a no-op unless [sessionId] still matches the
     * session currently held in the snapshot. See [clearIf] for the race this guards against.
     */
    fun appendFinalIf(sessionId: String, text: String) {
        if (_snapshot.value.sessionId == sessionId) appendFinal(text)
    }

    /** Session-scoped counterpart to [updatePartial]; see [clearIf]. */
    fun updatePartialIf(sessionId: String, text: String) {
        if (_snapshot.value.sessionId == sessionId) updatePartial(text)
    }

    /**
     * Session-scoped counterpart to [clear]: a no-op unless [sessionId] still matches the session
     * currently held in the snapshot. Mirrors the "a new recording may already have been started
     * on this same service instance" guard RecordingService applies elsewhere (see
     * `stillCurrent`/`failedSessionId` in RecordingService.kt).
     *
     * Without this, a stop-then-immediate-restart can let the old recording's teardown -- which
     * runs on its own coroutine and can be delayed by file/mic teardown work -- call clear() AFTER
     * the new recording's start() has already run, permanently wiping the new recording's
     * startTime/transcript for the rest of its duration, since start() is the only thing that ever
     * sets startTime again.
     */
    fun clearIf(sessionId: String) {
        if (_snapshot.value.sessionId == sessionId) clear()
    }
}
