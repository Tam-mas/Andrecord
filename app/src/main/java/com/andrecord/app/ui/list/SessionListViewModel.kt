package com.andrecord.app.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andrecord.app.accessibility.AccessibilityServiceStatus
import com.andrecord.app.data.Session
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.recording.LiveTranscriptSnapshot
import com.andrecord.app.recording.LiveTranscriptState
import com.andrecord.app.recording.RecordingController
import com.andrecord.app.recording.RecordingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionListViewModel(
    private val repository: SessionRepository,
    private val recordingController: RecordingController,
    private val accessibilityServiceStatus: AccessibilityServiceStatus,
    liveTranscriptState: LiveTranscriptState
) : ViewModel() {

    val liveTranscriptSnapshot: StateFlow<LiveTranscriptSnapshot> = liveTranscriptState.snapshot

    val sessions: StateFlow<List<Session>> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Re-derives the combined segments flow every time the *session list* changes (not just
    // when a session's own segments change). A naive `sessions.collect { combine(...).collect
    // { ... } } }` nests a non-completing inner collect inside the outer one: since Room flows
    // never complete, the outer collect lambda would never return, so it would never observe a
    // later emission from `sessions` (e.g. a new session being created). flatMapLatest cancels
    // the previous inner (combine) flow and resubscribes whenever `sessions` emits a new list.
    @OptIn(ExperimentalCoroutinesApi::class)
    val segmentsBySession: StateFlow<Map<String, List<TranscriptSegment>>> =
        sessions.flatMapLatest { sessionList ->
            if (sessionList.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(sessionList.map { s -> repository.observeSegments(s.id) }) { arrays ->
                    sessionList.mapIndexed { i, s -> s.id to arrays[i].toList() }.toMap()
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // RecordingController.state is already the single source of truth (a hot StateFlow kept
    // alive by the controller itself, not a cold/Room-backed flow), so this is exposed directly
    // rather than mirrored into a local var -- a local copy is exactly what used to go stale
    // whenever a recording started or stopped some way other than this screen's own FAB tap.
    val recordingState: StateFlow<RecordingState> = recordingController.state

    // Seeded eagerly from the current system/prefs state so the banner doesn't flash visible on
    // first composition when the service is already enabled or was already dismissed; refreshed
    // on resume (see SessionListScreen) since the user backgrounds the app to flip the setting
    // in Settings and the process stays alive when they come back.
    private val _showAccessibilityBanner = MutableStateFlow(accessibilityServiceStatus.shouldShowBanner())
    val showAccessibilityBanner: StateFlow<Boolean> = _showAccessibilityBanner

    fun onRecordButtonClick() {
        viewModelScope.launch {
            recordingController.toggle()
        }
    }

    fun refreshAccessibilityBannerState() {
        _showAccessibilityBanner.value = accessibilityServiceStatus.shouldShowBanner()
    }

    fun dismissAccessibilityBanner() {
        accessibilityServiceStatus.dismiss()
        _showAccessibilityBanner.value = accessibilityServiceStatus.shouldShowBanner()
    }
}
