package com.andrecord.app.ui.detail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andrecord.app.ui.components.SpeakerTimelineStrip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(viewModel: SessionDetailViewModel, onDeleted: () -> Unit) {
    val session by viewModel.session.collectAsState()
    val segments by viewModel.segments.collectAsState()
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(renameText)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.title.orEmpty()) },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = {
                            menuExpanded = false
                            renameText = session?.title.orEmpty()
                            showRenameDialog = true
                        })
                        DropdownMenuItem(text = { Text("Share as text") }, onClick = {
                            menuExpanded = false
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, viewModel.buildShareText())
                            }
                            context.startActivity(Intent.createChooser(intent, "Share transcript"))
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = {
                            menuExpanded = false
                            viewModel.delete()
                            onDeleted()
                        })
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            session?.let { s ->
                Text(text = "${s.durationMs?.div(60000) ?: 0} min · ${s.speakerCount ?: 0} speakers",
                    style = MaterialTheme.typography.labelSmall)
                SpeakerTimelineStrip(segments = segments, modifier = Modifier.padding(vertical = 8.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(segments) { segment ->
                    Column {
                        Text(text = segment.speakerLabel ?: "Unknown", style = MaterialTheme.typography.labelSmall)
                        Text(text = segment.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
