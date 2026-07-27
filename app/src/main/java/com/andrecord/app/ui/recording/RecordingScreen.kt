package com.andrecord.app.ui.recording

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.andrecord.app.recording.RecordingState
import com.andrecord.app.ui.theme.AndrecordColors
import kotlinx.coroutines.delay

/** Pure formatting, extracted so it's unit-testable without Compose. */
fun formatElapsed(startMillis: Long, nowMillis: Long): String {
    val totalSeconds = ((nowMillis - startMillis) / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun RecordingScreen(viewModel: RecordingViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val snapshot by viewModel.snapshot.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // The recording can end via a path this screen never initiated -- a volume-key/Quick-Tap
    // stop, the recording notification's own Stop action, or a mid-recording failure -- so this
    // screen must not rely solely on its own Stop button to navigate away. Whenever the shared
    // controller state leaves RECORDING for any reason, bounce back to the list rather than keep
    // displaying a live view for a recording that's already over.
    LaunchedEffect(recordingState) {
        if (recordingState != RecordingState.RECORDING) {
            onBack()
        }
    }

    LaunchedEffect(snapshot.startTime) {
        while (snapshot.startTime != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = snapshot.startTime?.let { formatElapsed(it, now) } ?: "0:00",
                style = MaterialTheme.typography.titleLarge
            )
            Button(onClick = {
                viewModel.onStopClick()
                onBack()
            }) {
                Text("Stop")
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(snapshot.finalLines) { line ->
                    Text(text = line, style = MaterialTheme.typography.bodyLarge)
                }
                snapshot.partialLine?.let { partial ->
                    item {
                        Text(
                            text = partial,
                            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
