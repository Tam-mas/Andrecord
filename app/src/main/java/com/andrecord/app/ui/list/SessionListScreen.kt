package com.andrecord.app.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andrecord.app.data.Session
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.recording.RecordingState
import com.andrecord.app.ui.components.SpeakerTimelineStrip

@Composable
fun SessionListScreen(viewModel: SessionListViewModel, onSessionClick: (String) -> Unit) {
    val sessions by viewModel.sessions.collectAsState()
    val segmentsBySession by viewModel.segmentsBySession.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.onRecordButtonClick() }) {
                Icon(
                    imageVector = if (recordingState == RecordingState.RECORDING) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (recordingState == RecordingState.RECORDING) "Stop recording" else "Start recording"
                )
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    segments = segmentsBySession[session.id].orEmpty(),
                    onClick = { onSessionClick(session.id) }
                )
            }
        }
    }
}

@Composable
private fun SessionRow(session: Session, segments: List<TranscriptSegment>, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(text = session.title, style = MaterialTheme.typography.titleMedium)
        session.speakerCount?.let {
            Text(text = "$it speaker${if (it == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall)
        }
        Box(modifier = Modifier.padding(top = 6.dp)) {
            SpeakerTimelineStrip(segments = segments)
        }
    }
}
