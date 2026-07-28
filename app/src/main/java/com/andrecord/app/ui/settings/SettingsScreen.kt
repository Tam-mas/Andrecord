package com.andrecord.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape as RoundedCorner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andrecord.app.settings.ReopenBehavior
import com.andrecord.app.ui.theme.AndrecordColors
import com.andrecord.app.ui.theme.AndrecordTypography

/** Reads the live, revocable-at-any-time READ_CALENDAR grant state directly -- this is a
 *  standard runtime permission with no wrapper class (unlike [ExactAlarmPermissionStatus]),
 *  so it's checked inline here rather than through a settings-package helper. */
private fun isCalendarPermissionGranted(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val selected by viewModel.reopenBehavior.collectAsState()
    val calendarAutoRecordEnabled by viewModel.calendarAutoRecordEnabled.collectAsState()
    val availableCalendars by viewModel.availableCalendars.collectAsState()
    val watchedCalendarIds by viewModel.watchedCalendarIds.collectAsState()
    val showExactAlarmBanner by viewModel.showExactAlarmBanner.collectAsState()
    val context = LocalContext.current
    var calendarPermissionGranted by remember { mutableStateOf(isCalendarPermissionGranted(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshCalendarState()
                calendarPermissionGranted = isCalendarPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { viewModel.refreshCalendarState() }

    val requestCalendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarPermissionGranted = granted
        if (granted) viewModel.setCalendarAutoRecordEnabled(true)
    }

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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
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

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "Calendar auto-record", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Automatically record meetings with multiple participants",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = calendarAutoRecordEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            requestCalendarPermission.launch(Manifest.permission.READ_CALENDAR)
                        } else {
                            viewModel.setCalendarAutoRecordEnabled(false)
                        }
                    }
                )
            }

            if (calendarAutoRecordEnabled) {
                // READ_CALENDAR is revocable at any time via system Settings, same as
                // SCHEDULE_EXACT_ALARM below -- surfaced the same way rather than silently
                // finding zero events forever (design spec §8).
                if (!calendarPermissionGranted) {
                    CalendarPermissionBanner(
                        onGrant = { requestCalendarPermission.launch(Manifest.permission.READ_CALENDAR) }
                    )
                }
                if (showExactAlarmBanner) {
                    ExactAlarmBanner(
                        onOpenSettings = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        },
                        onDismiss = { viewModel.dismissExactAlarmBanner() }
                    )
                }
                if (calendarPermissionGranted) {
                    Text(
                        text = "Watched calendars",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    availableCalendars.forEach { calendar ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = calendar.id in watchedCalendarIds,
                                    onClick = { viewModel.toggleWatchedCalendar(calendar.id) }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = calendar.id in watchedCalendarIds,
                                onCheckedChange = { viewModel.toggleWatchedCalendar(calendar.id) }
                            )
                            Text(text = calendar.displayName, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarPermissionBanner(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCorner(12.dp))
            .background(AndrecordColors.Ink600)
            .padding(16.dp)
    ) {
        Text(
            text = "Calendar permission is needed for auto-record to see your meetings.",
            style = AndrecordTypography.bodyMedium,
            color = AndrecordColors.Paper50
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onGrant) {
            Text(text = "Grant Calendar Permission", style = AndrecordTypography.labelSmall, color = AndrecordColors.Brass500)
        }
    }
}

@Composable
private fun ExactAlarmBanner(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCorner(12.dp))
            .background(AndrecordColors.Ink600)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "Exact alarm permission is needed to start recordings precisely when meetings begin.",
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
                text = "Open Alarm Settings",
                style = AndrecordTypography.labelSmall,
                color = AndrecordColors.Brass500
            )
        }
    }
}
