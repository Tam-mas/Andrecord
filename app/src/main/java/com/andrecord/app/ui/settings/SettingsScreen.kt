package com.andrecord.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andrecord.app.settings.ReopenBehavior

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val selected by viewModel.reopenBehavior.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp)) {
            Text(
                text = "When you open the app during an active recording",
                style = MaterialTheme.typography.titleMedium
            )
            listOf(
                ReopenBehavior.LIVE_VIEW to "Go to live view",
                ReopenBehavior.SESSION_LIST to "Show session list"
            ).forEach { (behavior, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected == behavior, onClick = { viewModel.onSelect(behavior) })
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(selected = selected == behavior, onClick = { viewModel.onSelect(behavior) })
                    Text(text = label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
