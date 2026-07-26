# Live Transcript View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a live-updating recording screen (elapsed timer, streaming transcript, stop control), a persistent recording bar on the session list, and a small Settings screen controlling what happens when the app is reopened mid-recording — closing the gap between the original design spec and the already-shipped Andrecord v1.

**Architecture:** A new `LiveTranscriptState` holder on `AppContainer` is fed directly by `RecordingService` as it processes ASR events during capture (in addition to its existing, unchanged periodic flush to Room); a new `RecordingScreen`/`RecordingViewModel` observes it. `AndrecordApp` gains a simple three-way top-level destination switch (list/detail, recording, settings) alongside the existing adaptive list-detail pane.

**Tech Stack:** Kotlin, Jetpack Compose, existing `AppContainer` manual-DI pattern, `SharedPreferences` (matching the existing `AccessibilityServiceStatus` pattern).

## Global Constraints

- Package: `com.andrecord.app`
- No speaker labels anywhere in the live view — diarization only runs after stop, unchanged from v1
- Partial ASR text always **replaces** in place; finalized text always **appends** permanently, never edited
- Elapsed time is computed client-side from a stored start time (no per-second push from the service)
- Custom-styled elements (recording bar, dimmed partial line) use `AndrecordColors`/`AndrecordTypography` (Task 13 tokens); standard chrome (Settings screen, TopAppBars) may use `MaterialTheme` defaults, matching the existing `SessionDetailScreen` precedent
- `TopAppBar` requires `@OptIn(ExperimentalMaterial3Api::class)` against this project's pinned Compose BOM (`2024.09.00`) — confirmed by Task 16's build failure without it
- This plan modifies existing, already-shipped files (`RecordingService.kt`, `AndrecordApplication.kt`, `SessionListViewModel.kt`, `SessionListScreen.kt`, `AndrecordApp.kt`) — each task shows the exact current content being changed, not a guess

---

## File Structure Overview

```
app/src/main/java/com/andrecord/app/
  recording/
    LiveTranscriptState.kt      # new: LiveTranscriptSnapshot + LiveTranscriptState
    RecordingService.kt         # modified: feeds LiveTranscriptState during capture
  settings/
    AppSettings.kt               # new: ReopenBehavior enum + AppSettings (SharedPreferences)
  AndrecordApplication.kt        # modified: AppContainer gains liveTranscriptState, appSettings
  ui/
    recording/
      RecordingViewModel.kt      # new
      RecordingScreen.kt         # new: formatElapsed() + the recording screen composable
    settings/
      SettingsViewModel.kt       # new
      SettingsScreen.kt          # new
    list/
      SessionListViewModel.kt    # modified: exposes liveTranscriptSnapshot
      SessionListScreen.kt       # modified: TopAppBar + settings icon, persistent recording bar
    AndrecordApp.kt               # modified: top-level destination switch
app/src/test/java/com/andrecord/app/
  recording/LiveTranscriptStateTest.kt   # new
  settings/AppSettingsTest.kt             # new
```

---

### Task 1: LiveTranscriptState

**Files:**
- Create: `app/src/main/java/com/andrecord/app/recording/LiveTranscriptState.kt`
- Test: `app/src/test/java/com/andrecord/app/recording/LiveTranscriptStateTest.kt`

**Interfaces:**
- Produces: `data class LiveTranscriptSnapshot(val sessionId: String? = null, val startTime: Long? = null, val finalLines: List<String> = emptyList(), val partialLine: String? = null)`; `class LiveTranscriptState` with `val snapshot: StateFlow<LiveTranscriptSnapshot>`, `fun start(sessionId: String, startTime: Long)`, `fun appendFinal(text: String)`, `fun updatePartial(text: String)`, `fun clear()`.

This is pure Kotlin with no Android framework dependency — `StateFlow.value` is directly readable in a plain JUnit test, no coroutines-test or Robolectric needed, following the same pattern as `LongPressDetector` and `AccessibilityServiceStatus`'s pure matching logic.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.andrecord.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTranscriptStateTest {

    @Test
    fun `start seeds sessionId and startTime with an empty transcript`() {
        val state = LiveTranscriptState()

        state.start("s1", 1000L)

        val snapshot = state.snapshot.value
        assertEquals("s1", snapshot.sessionId)
        assertEquals(1000L, snapshot.startTime)
        assertEquals(emptyList<String>(), snapshot.finalLines)
        assertNull(snapshot.partialLine)
    }

    @Test
    fun `appendFinal appends permanently and clears the partial`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)
        state.updatePartial("hello the")

        state.appendFinal("hello there")

        val snapshot = state.snapshot.value
        assertEquals(listOf("hello there"), snapshot.finalLines)
        assertNull(snapshot.partialLine)
    }

    @Test
    fun `appendFinal appends to existing lines rather than replacing them`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)
        state.appendFinal("first")

        state.appendFinal("second")

        assertEquals(listOf("first", "second"), state.snapshot.value.finalLines)
    }

    @Test
    fun `updatePartial replaces rather than appends`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)

        state.updatePartial("hello")
        state.updatePartial("hello there")

        assertEquals("hello there", state.snapshot.value.partialLine)
    }

    @Test
    fun `clear resets to an empty snapshot`() {
        val state = LiveTranscriptState()
        state.start("s1", 1000L)
        state.appendFinal("hi")
        state.updatePartial("more")

        state.clear()

        assertEquals(LiveTranscriptSnapshot(), state.snapshot.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.recording.LiveTranscriptStateTest"`
Expected: FAIL (compilation error — `LiveTranscriptState`/`LiveTranscriptSnapshot` don't exist yet)

- [ ] **Step 3: Implement `LiveTranscriptState.kt`**

```kotlin
package com.andrecord.app.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LiveTranscriptSnapshot(
    val sessionId: String? = null,
    val startTime: Long? = null,
    val finalLines: List<String> = emptyList(),
    val partialLine: String? = null
)

/**
 * Shared, process-local state for the live recording screen and the session list's persistent
 * recording bar, fed directly by [RecordingService] as it processes ASR events during capture.
 * This is entirely separate from the Room-backed transcript (which [RecordingService] continues
 * to flush periodically, unchanged) — this holder only exists to drive the in-progress UI while a
 * recording is active, and is cleared the moment it ends.
 */
class LiveTranscriptState {
    private val _snapshot = MutableStateFlow(LiveTranscriptSnapshot())
    val snapshot: StateFlow<LiveTranscriptSnapshot> = _snapshot

    fun start(sessionId: String, startTime: Long) {
        _snapshot.value = LiveTranscriptSnapshot(sessionId = sessionId, startTime = startTime)
    }

    fun appendFinal(text: String) {
        val current = _snapshot.value
        _snapshot.value = current.copy(finalLines = current.finalLines + text, partialLine = null)
    }

    fun updatePartial(text: String) {
        _snapshot.value = _snapshot.value.copy(partialLine = text)
    }

    fun clear() {
        _snapshot.value = LiveTranscriptSnapshot()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.recording.LiveTranscriptStateTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/recording/LiveTranscriptState.kt app/src/test/java/com/andrecord/app/recording/LiveTranscriptStateTest.kt
git commit -m "Add LiveTranscriptState for the upcoming live recording view"
```

---

### Task 2: Wire LiveTranscriptState into AppContainer and RecordingService

**Files:**
- Modify: `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`
- Modify: `app/src/main/java/com/andrecord/app/recording/RecordingService.kt`

**Interfaces:**
- Consumes: `LiveTranscriptState` (Task 1)
- Produces: `AppContainer.liveTranscriptState: LiveTranscriptState` (eager, like `accessibilityServiceStatus`)

- [ ] **Step 1: Add `liveTranscriptState` to `AppContainer`**

In `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`, the current `AppContainer` class is:

```kotlin
class AppContainer(app: Application) {
    private val database = AndrecordDatabase.build(app)

    val sessionRepository = SessionRepository(
        database.sessionDao(),
        database.transcriptSegmentDao()
    ) { path -> File(path).delete() }

    val accessibilityServiceStatus = AccessibilityServiceStatus(app)

    lateinit var streamingAsrEngine: StreamingAsrEngine
    lateinit var diarizationEngine: DiarizationEngine
    lateinit var recordingController: RecordingController
}
```

Add `val liveTranscriptState = LiveTranscriptState()` alongside `accessibilityServiceStatus`:

```kotlin
class AppContainer(app: Application) {
    private val database = AndrecordDatabase.build(app)

    val sessionRepository = SessionRepository(
        database.sessionDao(),
        database.transcriptSegmentDao()
    ) { path -> File(path).delete() }

    val accessibilityServiceStatus = AccessibilityServiceStatus(app)
    val liveTranscriptState = LiveTranscriptState()

    lateinit var streamingAsrEngine: StreamingAsrEngine
    lateinit var diarizationEngine: DiarizationEngine
    lateinit var recordingController: RecordingController
}
```

Add the import alongside the other `com.andrecord.app.recording.*` imports at the top of the file:

```kotlin
import com.andrecord.app.recording.LiveTranscriptState
```

- [ ] **Step 2: Verify it still compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Start the live state once capture actually begins**

In `app/src/main/java/com/andrecord/app/recording/RecordingService.kt`, `startRecording(id: String)` currently has this shape (abbreviated — the pre-flight checks between are unchanged):

```kotlin
    private fun startRecording(id: String) {
        sessionId = id
        startTime = System.currentTimeMillis()
        stopRequested = false
        stopping = false
        val container = (application as AndrecordApplication).container
        wavFile = File(filesDir, "audio/$id.wav").apply { parentFile?.mkdirs() }

        // ... pre-flight checks (mic permission, storage, AudioRecord construction) unchanged ...

        recordingJob = scope.launch {
```

Add `container.liveTranscriptState.start(id, startTime)` immediately before `recordingJob = scope.launch {` — this is the point where every pre-flight check has already passed and capture is genuinely about to begin, so the live view never shows a "recording" state for a session that immediately aborted:

```kotlin
        container.liveTranscriptState.start(id, startTime)
        recordingJob = scope.launch {
```

- [ ] **Step 4: Feed both ASR event types into the live state during capture**

`drainAsrEvents` currently only extracts `AsrEvent.Final` (discarding `AsrEvent.Partial` entirely — this is the "no live view exists yet" gap this plan closes):

```kotlin
    private fun drainAsrEvents(container: AppContainer, into: MutableList<AsrEvent.Final>) {
        var event = container.streamingAsrEngine.poll()
        while (event != null) {
            if (event is AsrEvent.Final) into.add(event)
            event = container.streamingAsrEngine.poll()
        }
    }
```

Replace it with:

```kotlin
    private fun drainAsrEvents(container: AppContainer, into: MutableList<AsrEvent.Final>) {
        var event = container.streamingAsrEngine.poll()
        while (event != null) {
            when (val e = event) {
                is AsrEvent.Final -> {
                    into.add(e)
                    container.liveTranscriptState.appendFinal(e.text)
                }
                is AsrEvent.Partial -> container.liveTranscriptState.updatePartial(e.text)
            }
            event = container.streamingAsrEngine.poll()
        }
    }
```

- [ ] **Step 5: Clear the live state whenever capture ends, success or failure**

The capture coroutine's `finally` block currently ends with:

```kotlin
                step("Stopping transcription") {
                    container.streamingAsrEngine.stop()
                    drainAsrEvents(container, pendingSegments)
                }
            }
```

Add one more `step` right after it, still inside the same `finally` block, so the live view is guaranteed to clear no matter how the capture loop exited:

```kotlin
                step("Stopping transcription") {
                    container.streamingAsrEngine.stop()
                    drainAsrEvents(container, pendingSegments)
                }
                step("Clearing the live transcript") { container.liveTranscriptState.clear() }
            }
```

- [ ] **Step 6: Run the full unit test suite and verify the app builds**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, all existing tests pass, no regressions

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/andrecord/app/AndrecordApplication.kt app/src/main/java/com/andrecord/app/recording/RecordingService.kt
git commit -m "Feed live ASR events into LiveTranscriptState during capture"
```

---

### Task 3: AppSettings (reopen-behavior persistence)

**Files:**
- Create: `app/src/main/java/com/andrecord/app/settings/AppSettings.kt`
- Test: `app/src/test/java/com/andrecord/app/settings/AppSettingsTest.kt`
- Modify: `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`

**Interfaces:**
- Produces: `enum class ReopenBehavior { LIVE_VIEW, SESSION_LIST }`; `class AppSettings(context: Context)` with `fun getReopenBehavior(): ReopenBehavior`, `fun setReopenBehavior(behavior: ReopenBehavior)`, and the testable `companion object` function `fun parseReopenBehavior(raw: String?): ReopenBehavior`; `AppContainer.appSettings: AppSettings`

This follows the exact same pure-logic-extraction pattern as `AccessibilityServiceStatus`: the `SharedPreferences`-dependent parts aren't unit-testable without Android, but the string-parsing they delegate to is.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.andrecord.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `parseReopenBehavior returns the matching enum value`() {
        assertEquals(ReopenBehavior.SESSION_LIST, AppSettings.parseReopenBehavior("SESSION_LIST"))
    }

    @Test
    fun `parseReopenBehavior defaults to LIVE_VIEW for null`() {
        assertEquals(ReopenBehavior.LIVE_VIEW, AppSettings.parseReopenBehavior(null))
    }

    @Test
    fun `parseReopenBehavior defaults to LIVE_VIEW for an unrecognized value`() {
        assertEquals(ReopenBehavior.LIVE_VIEW, AppSettings.parseReopenBehavior("garbage"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.settings.AppSettingsTest"`
Expected: FAIL (compilation error — `AppSettings`/`ReopenBehavior` don't exist yet)

- [ ] **Step 3: Implement `AppSettings.kt`**

```kotlin
package com.andrecord.app.settings

import android.content.Context

enum class ReopenBehavior { LIVE_VIEW, SESSION_LIST }

/**
 * Persists the one setting this app has: what happens when you open it while a recording is
 * already active (e.g. one started via Quick Tap or the volume-key hold while the app was
 * closed). Uses SharedPreferences directly -- a single boolean/enum flag doesn't need anything
 * heavier -- following the same pattern as [com.andrecord.app.accessibility.AccessibilityServiceStatus].
 */
class AppSettings(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun getReopenBehavior(): ReopenBehavior =
        parseReopenBehavior(prefs.getString(KEY_REOPEN_BEHAVIOR, null))

    fun setReopenBehavior(behavior: ReopenBehavior) {
        prefs.edit().putString(KEY_REOPEN_BEHAVIOR, behavior.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_REOPEN_BEHAVIOR = "reopen_behavior"

        /** Pure parsing logic, extracted so it's unit-testable without a real Context. */
        fun parseReopenBehavior(raw: String?): ReopenBehavior =
            ReopenBehavior.entries.find { it.name == raw } ?: ReopenBehavior.LIVE_VIEW
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.settings.AppSettingsTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Add `appSettings` to `AppContainer`**

In `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`, add `val appSettings = AppSettings(app)` alongside the other eager `AppContainer` fields, and add the import `import com.andrecord.app.settings.AppSettings`:

```kotlin
    val accessibilityServiceStatus = AccessibilityServiceStatus(app)
    val liveTranscriptState = LiveTranscriptState()
    val appSettings = AppSettings(app)
```

- [ ] **Step 6: Run the full unit test suite and verify the app builds**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, all tests pass

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/andrecord/app/settings/AppSettings.kt app/src/test/java/com/andrecord/app/settings/AppSettingsTest.kt app/src/main/java/com/andrecord/app/AndrecordApplication.kt
git commit -m "Add AppSettings for the reopen-during-recording preference"
```

---

### Task 4: RecordingViewModel + RecordingScreen

**Files:**
- Create: `app/src/main/java/com/andrecord/app/ui/recording/RecordingViewModel.kt`
- Create: `app/src/main/java/com/andrecord/app/ui/recording/RecordingScreen.kt`

**Interfaces:**
- Consumes: `LiveTranscriptState`, `LiveTranscriptSnapshot` (Task 1), `RecordingController` (existing)
- Produces: `RecordingViewModel(liveTranscriptState: LiveTranscriptState, recordingController: RecordingController)` with `val snapshot: StateFlow<LiveTranscriptSnapshot>`, `fun onStopClick()`; `fun formatElapsed(startMillis: Long, nowMillis: Long): String`; `@Composable fun RecordingScreen(viewModel: RecordingViewModel, onBack: () -> Unit)`

- [ ] **Step 1: Implement `RecordingViewModel.kt`**

```kotlin
package com.andrecord.app.ui.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andrecord.app.recording.LiveTranscriptSnapshot
import com.andrecord.app.recording.LiveTranscriptState
import com.andrecord.app.recording.RecordingController
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RecordingViewModel(
    liveTranscriptState: LiveTranscriptState,
    private val recordingController: RecordingController
) : ViewModel() {

    val snapshot: StateFlow<LiveTranscriptSnapshot> = liveTranscriptState.snapshot

    fun onStopClick() {
        viewModelScope.launch { recordingController.toggle() }
    }
}
```

- [ ] **Step 2: Write the failing test for the pure elapsed-time formatting**

```kotlin
package com.andrecord.app.ui.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingScreenTest {

    @Test
    fun `formatElapsed renders minutes and seconds with zero-padded seconds`() {
        assertEquals("0:00", formatElapsed(startMillis = 1000L, nowMillis = 1000L))
        assertEquals("0:05", formatElapsed(startMillis = 1000L, nowMillis = 6000L))
        assertEquals("1:00", formatElapsed(startMillis = 0L, nowMillis = 60_000L))
        assertEquals("12:34", formatElapsed(startMillis = 0L, nowMillis = 754_000L))
    }

    @Test
    fun `formatElapsed never goes negative`() {
        assertEquals("0:00", formatElapsed(startMillis = 5000L, nowMillis = 1000L))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.ui.recording.RecordingScreenTest"`
Expected: FAIL (compilation error — `formatElapsed` doesn't exist yet)

- [ ] **Step 4: Implement `RecordingScreen.kt`**

```kotlin
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
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

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
                            color = AndrecordColors.Ink600
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.ui.recording.RecordingScreenTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: Verify the app builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/andrecord/app/ui/recording
git commit -m "Add RecordingViewModel and the live recording screen"
```

---

### Task 5: SettingsViewModel + SettingsScreen

**Files:**
- Create: `app/src/main/java/com/andrecord/app/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/com/andrecord/app/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `AppSettings`, `ReopenBehavior` (Task 3)
- Produces: `SettingsViewModel(appSettings: AppSettings)` with `val reopenBehavior: StateFlow<ReopenBehavior>`, `fun onSelect(behavior: ReopenBehavior)`; `@Composable fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit)`

- [ ] **Step 1: Implement `SettingsViewModel.kt`**

```kotlin
package com.andrecord.app.ui.settings

import androidx.lifecycle.ViewModel
import com.andrecord.app.settings.AppSettings
import com.andrecord.app.settings.ReopenBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(private val appSettings: AppSettings) : ViewModel() {
    private val _reopenBehavior = MutableStateFlow(appSettings.getReopenBehavior())
    val reopenBehavior: StateFlow<ReopenBehavior> = _reopenBehavior

    fun onSelect(behavior: ReopenBehavior) {
        appSettings.setReopenBehavior(behavior)
        _reopenBehavior.value = behavior
    }
}
```

- [ ] **Step 2: Implement `SettingsScreen.kt`**

```kotlin
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
```

- [ ] **Step 3: Verify the app builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, all tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/ui/settings
git commit -m "Add SettingsViewModel and the Settings screen"
```

---

### Task 6: Persistent recording bar and settings entry point on the session list

**Files:**
- Modify: `app/src/main/java/com/andrecord/app/ui/list/SessionListViewModel.kt`
- Modify: `app/src/main/java/com/andrecord/app/ui/list/SessionListScreen.kt`

**Interfaces:**
- Consumes: `LiveTranscriptState` (Task 1), `formatElapsed` (Task 4)
- Produces: `SessionListViewModel(repository, recordingController, accessibilityServiceStatus, liveTranscriptState)` (new constructor param) exposing `val liveTranscriptSnapshot: StateFlow<LiveTranscriptSnapshot>`; `SessionListScreen(viewModel, onSessionClick, onRecordingStarted: () -> Unit, onReopenRecording: () -> Unit, onOpenSettings: () -> Unit)` (three new params)

- [ ] **Step 1: Add `liveTranscriptSnapshot` to `SessionListViewModel`**

The current constructor and imports are:

```kotlin
class SessionListViewModel(
    private val repository: SessionRepository,
    private val recordingController: RecordingController,
    private val accessibilityServiceStatus: AccessibilityServiceStatus
) : ViewModel() {
```

Change to:

```kotlin
class SessionListViewModel(
    private val repository: SessionRepository,
    private val recordingController: RecordingController,
    private val accessibilityServiceStatus: AccessibilityServiceStatus,
    liveTranscriptState: LiveTranscriptState
) : ViewModel() {

    val liveTranscriptSnapshot: StateFlow<LiveTranscriptSnapshot> = liveTranscriptState.snapshot
```

(add this new `val` right after the class header, before the existing `val sessions: StateFlow<...>` line). Add the imports:

```kotlin
import com.andrecord.app.recording.LiveTranscriptSnapshot
import com.andrecord.app.recording.LiveTranscriptState
```

- [ ] **Step 2: Add the persistent recording bar, TopAppBar with a settings icon, and the new navigation callbacks to `SessionListScreen`**

The current function signature and top of the composable body are:

```kotlin
@Composable
fun SessionListScreen(viewModel: SessionListViewModel, onSessionClick: (String) -> Unit) {
    val sessions by viewModel.sessions.collectAsState()
    val segmentsBySession by viewModel.segmentsBySession.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val showAccessibilityBanner by viewModel.showAccessibilityBanner.collectAsState()
    val context = LocalContext.current
```

Replace the signature and add the new state reads immediately after `val context = LocalContext.current`:

Also add `import androidx.compose.material3.ExperimentalMaterial3Api` (needed for the `@OptIn` below, since this screen is about to use `TopAppBar` for the first time) alongside the file's existing `androidx.compose.material3.*` imports.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onSessionClick: (String) -> Unit,
    onRecordingStarted: () -> Unit,
    onReopenRecording: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val segmentsBySession by viewModel.segmentsBySession.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val showAccessibilityBanner by viewModel.showAccessibilityBanner.collectAsState()
    val liveTranscriptSnapshot by viewModel.liveTranscriptSnapshot.collectAsState()
    val context = LocalContext.current

    // Whichever trigger started the recording -- the FAB, Quick Tap, or the volume-key hold --
    // this screen navigates to the live view the moment recordingState flips to RECORDING while
    // it's visible, so all three triggers land you in the same place.
    var previousRecordingState by remember { mutableStateOf(recordingState) }
    LaunchedEffect(recordingState) {
        if (previousRecordingState == RecordingState.IDLE && recordingState == RecordingState.RECORDING) {
            onRecordingStarted()
        }
        previousRecordingState = recordingState
    }
```

Add the corresponding new imports (`LaunchedEffect`, `mutableStateOf`, `remember`, `setValue`, `TopAppBar`, `delay`, and `formatElapsed` from Task 4 — check which aren't already imported in the file and add only the missing ones; `getValue`/`collectAsState`/`DisposableEffect`/`Icons`/`Row` etc. are already present):

```kotlin
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.andrecord.app.ui.recording.formatElapsed
import kotlinx.coroutines.delay
```

`Icons.Filled.Settings` needs no new import — it's the same `androidx.compose.material.icons.Icons` object already imported in this file for `Icons.Filled.Mic`/`Icons.Filled.Stop`.

Now wrap the existing `Scaffold(...)` (which currently has no `topBar` and starts directly with `floatingActionButton = { ... }`) with a `topBar`, and insert the `RecordingBar` above the existing accessibility-banner `item` inside the `LazyColumn`. The current `Scaffold` block is:

```kotlin
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
```

Replace it with:

```kotlin
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Andrecord") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
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
            if (recordingState == RecordingState.RECORDING) {
                item(key = "recording_bar") {
                    RecordingBar(startTime = liveTranscriptSnapshot.startTime, onClick = onReopenRecording)
                }
            }
            if (showAccessibilityBanner) {
                item(key = "accessibility_banner") {
                    AccessibilityBanner(
                        onOpenSettings = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onDismiss = { viewModel.dismissAccessibilityBanner() }
                    )
                }
            }
            items(sessions, key = { it.id }) { session ->
```

Add the new `RecordingBar` composable near `AccessibilityBanner` (after it, before `SessionRow`):

```kotlin
@Composable
private fun RecordingBar(startTime: Long?, onClick: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startTime) {
        while (startTime != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AndrecordColors.Brass500)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "Recording…", style = AndrecordTypography.bodyMedium, color = AndrecordColors.Ink900)
        Text(
            text = startTime?.let { formatElapsed(it, now) } ?: "0:00",
            style = AndrecordTypography.labelSmall,
            color = AndrecordColors.Ink900
        )
    }
}
```

- [ ] **Step 3: Verify the app builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, all tests pass (this task has no new pure logic to test — it's Compose wiring over already-tested `LiveTranscriptState`/`formatElapsed`, consistent with how Tasks 15/16 in the original plan verified UI-only changes)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/ui/list/SessionListViewModel.kt app/src/main/java/com/andrecord/app/ui/list/SessionListScreen.kt
git commit -m "Add persistent recording bar and Settings entry point to the session list"
```

---

### Task 7: Navigation wiring in AndrecordApp

**Files:**
- Modify: `app/src/main/java/com/andrecord/app/ui/AndrecordApp.kt`

**Interfaces:**
- Consumes: `RecordingScreen`, `RecordingViewModel` (Task 4), `SettingsScreen`, `SettingsViewModel` (Task 5), `SessionListScreen`'s new params (Task 6), `ReopenBehavior` (Task 3)
- Produces: the completed `AndrecordApp(container: AppContainer)` composable — this is the final integration task for this plan

This is the task that ties everything together: a simple top-level destination switch alongside the existing adaptive list-detail pane, deciding on first composition whether to land on the recording screen or the list based on whether a recording is already active and the user's Settings preference.

- [ ] **Step 1: Replace `AndrecordApp.kt` with the fully wired version**

The current file is:

```kotlin
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
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>()
    var selectedSessionId by remember { mutableStateOf<String?>(null) }

    val listViewModel = remember {
        SessionListViewModel(container.sessionRepository, container.recordingController, container.accessibilityServiceStatus)
    }

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
```

Replace the entire file with:

```kotlin
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

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AndrecordApp(container: AppContainer) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>()
    var selectedSessionId by remember { mutableStateOf<String?>(null) }

    // If a recording is already running when the app opens (e.g. started via Quick Tap or the
    // volume-key hold while the app was closed), land on the live recording screen by default --
    // configurable in Settings for anyone who'd rather see the session list first instead.
    var destination by remember {
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
            val settingsViewModel = remember { SettingsViewModel(container.appSettings) }
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
```

- [ ] **Step 2: Verify the app builds and launches**

Run: `./gradlew :app:installDebug`
Expected: `BUILD SUCCESSFUL`. If you have Android emulator/device access, manually verify:
1. Tap the record FAB on the list — you land on the recording screen, the elapsed timer ticks, spoken words appear (finalized lines solid, the current in-progress guess dimmed/italic beneath them).
2. Tap Stop on the recording screen — you return to the list, the session appears once diarization completes (unchanged from v1).
3. Start a recording, then press system back (or however your test harness triggers it) from the recording screen — you return to the list, and the persistent recording bar appears at the top showing a ticking elapsed time. Tapping it reopens the recording screen with the transcript exactly where it left off.
4. Force-stop the app while a recording is active (e.g. `adb shell am force-stop com.andrecord.app`), then relaunch — you land directly on the recording screen (default Settings behavior) showing the resumed elapsed time (the underlying recording continued the whole time; only the process needed relaunching, not the foreground service, if it survived — if the whole service+process is dead, this instead shows a fresh idle session list, which is the same "process really did die" case the existing `reconcileInterruptedSessions` sweep already handles).
5. Open Settings (gear icon on the list's TopAppBar), switch to "Show session list", relaunch while recording — you land on the list with the persistent bar instead.

- [ ] **Step 3: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, all tests pass

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/andrecord/app/ui/AndrecordApp.kt
git commit -m "Wire the live recording screen and Settings into top-level navigation"
```

---

## Post-plan manual verification checklist

1. Record a real ~30-second sentence or two, watch the live view update: does the partial line visibly settle into finalized text as you pause between sentences?
2. Confirm the "no volume change" / vibration feedback from the existing triggers (Quick Tap, volume-key) still works unaffected — this plan only adds a way to *watch* recording, it doesn't touch how triggers start/stop it.
3. Confirm the two-pane (unfolded) layout still works for the list/detail screens — this plan's new screens (recording, settings) are simple single-pane full-screen destinations, not part of the adaptive list-detail system, so they should look identical folded or unfolded; only the existing list/detail pane split is fold-aware.
