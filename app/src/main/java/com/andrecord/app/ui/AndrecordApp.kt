package com.andrecord.app.ui

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andrecord.app.AppContainer
import com.andrecord.app.recording.RecordingState
import com.andrecord.app.settings.ReopenBehavior
import com.andrecord.app.ui.detail.SessionDetailScreen
import com.andrecord.app.ui.detail.SessionDetailViewModel
import com.andrecord.app.ui.list.SessionListScreen
import com.andrecord.app.ui.list.SessionListViewModel
import com.andrecord.app.ui.recording.RecordingScreen
import com.andrecord.app.ui.recording.RecordingViewModel
import com.andrecord.app.ui.settings.SettingsScreen
import com.andrecord.app.ui.settings.SettingsViewModel

private enum class TopLevelDestination { LIST_DETAIL, RECORDING, SETTINGS }

// MainActivity declares no android:configChanges, so folding/unfolding this app's primary target
// foldable (or rotating) destroys and recreates the Activity/Composition. Without a Saver here,
// TopLevelDestination's plain `remember` would reset to LIST_DETAIL on every such recreation --
// bouncing the user back to the list mid-live-view or mid-Settings. Storing the enum's `name`
// keeps this a single line at the call site rather than switching every assignment in this file
// over to a raw String.
private val TopLevelDestinationSaver = Saver<TopLevelDestination, String>(
    save = { it.name },
    restore = { TopLevelDestination.valueOf(it) }
)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AndrecordApp(container: AppContainer) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>()
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }

    // If a recording is already running when the app opens (e.g. started via Quick Tap or the
    // volume-key hold while the app was closed), land on the live recording screen by default --
    // configurable in Settings for anyone who'd rather see the session list first instead.
    //
    // This init lambda only runs when there's no saved value to restore (a fresh instance), the
    // same as plain `remember` -- so a restored RECORDING/SETTINGS destination after a fold/rotate
    // is never silently overwritten by this recording-state check on recomposition.
    var destination by rememberSaveable(stateSaver = TopLevelDestinationSaver) {
        mutableStateOf(
            if (container.recordingController.currentState() == RecordingState.RECORDING &&
                container.appSettings.getReopenBehavior() == ReopenBehavior.LIVE_VIEW
            ) {
                TopLevelDestination.RECORDING
            } else {
                TopLevelDestination.LIST_DETAIL
            }
        )
    }

    val listViewModel = remember {
        SessionListViewModel(
            container.sessionRepository,
            container.recordingController,
            container.accessibilityServiceStatus,
            container.liveTranscriptState
        )
    }

    when (destination) {
        TopLevelDestination.RECORDING -> {
            val recordingViewModel = remember {
                RecordingViewModel(container.liveTranscriptState, container.recordingController)
            }
            RecordingScreen(
                viewModel = recordingViewModel,
                onBack = { destination = TopLevelDestination.LIST_DETAIL }
            )
        }
        TopLevelDestination.SETTINGS -> {
            val settingsViewModel = remember {
                SettingsViewModel(
                    container.appSettings,
                    container.calendarEventRepository,
                    container.exactAlarmPermissionStatus,
                    container.calendarAutoRecordScheduler
                )
            }
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { destination = TopLevelDestination.LIST_DETAIL }
            )
        }
        TopLevelDestination.LIST_DETAIL -> {
            NavigableListDetailPaneScaffold(
                navigator = navigator,
                listPane = {
                    AnimatedPane {
                        SessionListScreen(
                            viewModel = listViewModel,
                            onSessionClick = { id ->
                                selectedSessionId = id
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id)
                            },
                            onRecordingStarted = { destination = TopLevelDestination.RECORDING },
                            onReopenRecording = { destination = TopLevelDestination.RECORDING },
                            onOpenSettings = { destination = TopLevelDestination.SETTINGS }
                        )
                    }
                },
                detailPane = {
                    AnimatedPane {
                        val id = selectedSessionId
                        if (id != null) {
                            val detailViewModel = viewModel(key = id) { SessionDetailViewModel(container.sessionRepository, id) }
                            SessionDetailScreen(
                                viewModel = detailViewModel,
                                onDeleted = {
                                    selectedSessionId = null
                                    navigator.navigateBack()
                                }
                            )
                        }
                    }
                }
            )
        }
    }
}
