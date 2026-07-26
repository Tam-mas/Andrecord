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
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andrecord.app.AppContainer
import com.andrecord.app.ui.detail.SessionDetailScreen
import com.andrecord.app.ui.detail.SessionDetailViewModel
import com.andrecord.app.ui.list.SessionListScreen
import com.andrecord.app.ui.list.SessionListViewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AndrecordApp(container: AppContainer) {
    // `NavigableListDetailPaneScaffold` in adaptive-navigation:1.0.0 is hardcoded to accept a
    // `ThreePaneScaffoldNavigator<Any>` (its generic type parameter is erased/fixed at the
    // call-site overload used here, not left open as `<T>`), so requesting a
    // `ThreePaneScaffoldNavigator<String>` navigator fails to type-check against it. We track the
    // selected session id ourselves in `selectedSessionId` instead of relying on the navigator's
    // destination payload type.
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>()
    var selectedSessionId by remember { mutableStateOf<String?>(null) }

    val listViewModel = remember { SessionListViewModel(container.sessionRepository, container.recordingController) }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                SessionListScreen(
                    viewModel = listViewModel,
                    onSessionClick = { id ->
                        selectedSessionId = id
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id)
                    }
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
