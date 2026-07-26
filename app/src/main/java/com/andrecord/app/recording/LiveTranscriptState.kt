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
}
