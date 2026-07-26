package com.andrecord.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andrecord.app.data.Session
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.TranscriptSegment
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionDetailViewModel(
    private val repository: SessionRepository,
    private val sessionId: String
) : ViewModel() {

    val session: StateFlow<Session?> = repository.observeSessions()
        .map { list -> list.firstOrNull { it.id == sessionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val segments: StateFlow<List<TranscriptSegment>> = repository.observeSegments(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rename(newTitle: String) {
        viewModelScope.launch { repository.rename(sessionId, newTitle) }
    }

    fun delete() {
        viewModelScope.launch { repository.delete(sessionId) }
    }

    fun buildShareText(): String = buildShareText(segments.value)

    companion object {
        fun buildShareText(segments: List<TranscriptSegment>): String =
            segments.joinToString("\n\n") { seg ->
                "[${seg.speakerLabel ?: "Unknown"}] ${seg.text}"
            }
    }
}
