package com.andrecord.app.ui.list

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andrecord.app.data.Session
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.recording.RecordingState
import com.andrecord.app.ui.components.SpeakerTimelineStrip
import com.andrecord.app.ui.theme.AndrecordColors
import com.andrecord.app.ui.theme.AndrecordTypography

@Composable
fun SessionListScreen(viewModel: SessionListViewModel, onSessionClick: (String) -> Unit) {
    val sessions by viewModel.sessions.collectAsState()
    val segmentsBySession by viewModel.segmentsBySession.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val showAccessibilityBanner by viewModel.showAccessibilityBanner.collectAsState()
    val context = LocalContext.current

    // The user's path here is: see the banner, background the app, flip the setting in
    // Android's Accessibility settings, then return -- without the process being killed. Only
    // re-checking on ON_RESUME (rather than e.g. once per composition) catches that return trip
    // without polling.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityBannerState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            if (showAccessibilityBanner) {
                item(key = "accessibility_banner") {
                    AccessibilityBanner(
                        onOpenSettings = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onDismiss = { viewModel.dismissAccessibilityBanner() }
                    )
                }
            }
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
private fun AccessibilityBanner(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AndrecordColors.Ink600)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "Enable long-press Volume Down as a way to start or stop recording, even when your phone is locked.",
                style = AndrecordTypography.bodyMedium,
                color = AndrecordColors.Paper50,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = AndrecordColors.Paper50
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onOpenSettings) {
            Text(
                text = "Open Accessibility Settings",
                style = AndrecordTypography.labelSmall,
                color = AndrecordColors.Brass500
            )
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
