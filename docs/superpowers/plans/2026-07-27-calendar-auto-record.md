# Calendar Auto-Record Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fourth recording trigger — Android's `CalendarContract` provider — that automatically starts and stops a recording when a calendar event with 2+ attendees begins on a calendar the user has chosen to watch, per `docs/superpowers/specs/2026-07-27-calendar-auto-record-design.md`.

**Architecture:** A new `CalendarAutoRecordScheduler` re-scans the user's watched calendars and schedules exactly one exact `AlarmManager` alarm for the next qualifying event's start time. A `BroadcastReceiver` re-verifies the event when that alarm fires, starts a recording through `RecordingController` (tagging the session with the calendar name), schedules a tagged stop alarm, and re-scans for the next event. Rescans are also triggered by calendar-data changes (`ContentObserver`), device reboot, and an infrequent WorkManager safety net.

**Tech Stack:** Kotlin, `CalendarContract` (Calendars/Instances/Attendees), `AlarmManager` exact alarms, `BroadcastReceiver`, `ContentObserver`, WorkManager (`androidx.work:work-runtime-ktx:2.9.1`, already a dependency), Jetpack Compose (Material3, `compose-bom:2024.09.00`), Room, Robolectric + JUnit4 for unit tests.

## Global Constraints

- `compileSdk = 36`, `minSdk = 34`, `targetSdk = 36` — every API used in this plan (`AlarmManager.canScheduleExactAlarms()`, `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, both API 31+) is unconditionally available; no `Build.VERSION.SDK_INT` guards are needed anywhere in this plan.
- `@OptIn(ExperimentalMaterial3Api::class)` is required on any Composable using `TopAppBar`/`Scaffold` from `androidx.compose.material3` (Compose BOM pinned to `2024.09.00`). `Switch` and `Checkbox` are stable APIs and need no opt-in.
- `NavigableListDetailPaneScaffold` is hardcoded to `ThreePaneScaffoldNavigator<Any>` in `AndrecordApp.kt` — not touched by this plan, do not modify it.
- Do **not** run `git add -A` or `git add .` when committing — stage only the explicit file paths named in each task (a past incident committed 350MB+ of stray files this way).
- `gradle.properties` already sets `org.gradle.jvmargs=-Xmx4096m` for the Gradle daemon — do not lower it.
- This app deliberately requests no `INTERNET` permission. Nothing in this plan needs one: `CalendarContract` and `AlarmManager` are both local, on-device APIs.
- Commits must **not** include a `Co-Authored-By` trailer — use a plain commit message with no co-author attribution.
- **Placement decision (resolving an ambiguity in the spec):** the spec's §5 says the exact-alarm permission banner should match "the existing accessibility-service banner pattern exactly." The existing `AccessibilityBanner` lives on `SessionListScreen` (the app's first screen), because a user needs to discover the volume-key feature before they know Settings has anything to do with it. The calendar feature's toggle, calendar picker, and this exact-alarm permission all live together in the Settings screen already, per spec §5 — so this plan puts the new banner **inside `SettingsScreen`**, visible only when the feature is enabled and the permission is missing, matching the *visual style* of `AccessibilityBanner` exactly (same colors, same dismiss-button/deep-link-button layout) rather than its screen location. This keeps the permission ask next to the setting that needs it instead of on an unrelated screen.
- Every new class in this plan lives under a new package, `com.andrecord.app.calendar`, except `SessionRepository`, `RecordingController`, `RecordingServiceStarter`, `RecordingService`, `AppSettings`, `SettingsScreen.kt`/`SettingsViewModel.kt`, `AndrecordApplication.kt`/`AppContainer`, and `AndroidManifest.xml`, which are modified in place.
- Real `CalendarContract`/`AlarmManager` queries against a live ContentProvider/AlarmManager are not unit-testable (same category as this app's native ASR/diarization engines) — each task below says explicitly what is and isn't covered by an automated test, and the final task covers on-device manual verification.

---

### Task 1: `AppSettings` — calendar auto-record enabled flag and watched calendar IDs

**Files:**
- Modify: `app/src/main/java/com/andrecord/app/settings/AppSettings.kt`
- Test: `app/src/test/java/com/andrecord/app/settings/AppSettingsTest.kt`

**Interfaces:**
- Produces: `AppSettings.isCalendarAutoRecordEnabled(): Boolean`, `AppSettings.setCalendarAutoRecordEnabled(enabled: Boolean)`, `AppSettings.getWatchedCalendarIds(): Set<Long>`, `AppSettings.setWatchedCalendarIds(ids: Set<Long>)`, and the pure companion function `AppSettings.parseWatchedCalendarIds(raw: Set<String>?): Set<Long>`.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/java/com/andrecord/app/settings/AppSettingsTest.kt` (existing file — add these test functions inside the existing `AppSettingsTest` class, alongside the existing `parseReopenBehavior` tests):

```kotlin
    @Test
    fun `parseWatchedCalendarIds converts string set to longs`() {
        assertEquals(setOf(1L, 2L, 3L), AppSettings.parseWatchedCalendarIds(setOf("1", "2", "3")))
    }

    @Test
    fun `parseWatchedCalendarIds defaults to empty set for null`() {
        assertEquals(emptySet<Long>(), AppSettings.parseWatchedCalendarIds(null))
    }

    @Test
    fun `parseWatchedCalendarIds drops entries that are not valid longs`() {
        assertEquals(setOf(1L), AppSettings.parseWatchedCalendarIds(setOf("1", "garbage")))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.andrecord.app.settings.AppSettingsTest"`
Expected: FAIL — `parseWatchedCalendarIds` is unresolved.

- [ ] **Step 3: Implement**

Replace the full contents of `app/src/main/java/com/andrecord/app/settings/AppSettings.kt` with:

```kotlin
package com.andrecord.app.settings

import android.content.Context

enum class ReopenBehavior { LIVE_VIEW, SESSION_LIST }

/**
 * Persists this app's settings via SharedPreferences directly -- small, direct flags don't need
 * anything heavier -- following the same pattern as
 * [com.andrecord.app.accessibility.AccessibilityServiceStatus].
 */
class AppSettings(private val context: Context) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun getReopenBehavior(): ReopenBehavior =
        parseReopenBehavior(prefs.getString(KEY_REOPEN_BEHAVIOR, null))

    fun setReopenBehavior(behavior: ReopenBehavior) {
        prefs.edit().putString(KEY_REOPEN_BEHAVIOR, behavior.name).apply()
    }

    fun isCalendarAutoRecordEnabled(): Boolean =
        prefs.getBoolean(KEY_CALENDAR_AUTO_RECORD_ENABLED, false)

    fun setCalendarAutoRecordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_AUTO_RECORD_ENABLED, enabled).apply()
    }

    fun getWatchedCalendarIds(): Set<Long> =
        parseWatchedCalendarIds(prefs.getStringSet(KEY_WATCHED_CALENDAR_IDS, null))

    fun setWatchedCalendarIds(ids: Set<Long>) {
        prefs.edit().putStringSet(KEY_WATCHED_CALENDAR_IDS, ids.map { it.toString() }.toSet()).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_REOPEN_BEHAVIOR = "reopen_behavior"
        private const val KEY_CALENDAR_AUTO_RECORD_ENABLED = "calendar_auto_record_enabled"
        private const val KEY_WATCHED_CALENDAR_IDS = "watched_calendar_ids"

        /** Pure parsing logic, extracted so it's unit-testable without a real Context. */
        fun parseReopenBehavior(raw: String?): ReopenBehavior =
            ReopenBehavior.entries.find { it.name == raw } ?: ReopenBehavior.LIVE_VIEW

        /** Pure parsing logic, extracted so it's unit-testable without a real Context. Drops any
         *  entry that isn't a valid [Long] rather than throwing -- SharedPreferences string sets
         *  are just strings on disk, and a corrupted or manually-edited entry shouldn't crash the
         *  whole read. */
        fun parseWatchedCalendarIds(raw: Set<String>?): Set<Long> =
            raw?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.andrecord.app.settings.AppSettingsTest"`
Expected: PASS (6 tests: 3 existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/settings/AppSettings.kt app/src/test/java/com/andrecord/app/settings/AppSettingsTest.kt
git commit -m "Add calendar auto-record settings to AppSettings"
```

---

### Task 2: `SessionRepository.createSession` — optional calendar name

**Files:**
- Modify: `app/src/main/java/com/andrecord/app/data/SessionRepository.kt`
- Test: `app/src/test/java/com/andrecord/app/data/SessionRepositoryTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `SessionRepository.createSession(id: String, startTime: Long, calendarName: String? = null): Session` — the existing two-argument call sites (`repository.createSession(id, clock())` in `RecordingController`, `repository.createSession(sessionId, startTime = 0L)` in the instrumented test) are unaffected by the new defaulted third parameter, since neither uses trailing-lambda syntax.

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/com/andrecord/app/data/SessionRepositoryTest.kt` (existing file — add inside the existing `SessionRepositoryTest` class):

```kotlin
    @Test
    fun `createSession with a calendar name appends it to the title`() = runTest {
        val (repo, db) = buildRepo()

        repo.createSession("s1", startTime = 1000L, calendarName = "Work")

        val title = db.sessionDao().getById("s1")?.title
        assertTrue("Expected title to end with the calendar name, was: $title", title!!.endsWith("— Work"))
        db.close()
    }

    @Test
    fun `createSession without a calendar name keeps the plain title`() = runTest {
        val (repo, db) = buildRepo()

        repo.createSession("s1", startTime = 1000L)

        val title = db.sessionDao().getById("s1")?.title
        assertEquals(false, title!!.contains("—"))
        db.close()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.andrecord.app.data.SessionRepositoryTest"`
Expected: FAIL — `createSession` has no `calendarName` parameter.

- [ ] **Step 3: Implement**

In `app/src/main/java/com/andrecord/app/data/SessionRepository.kt`, replace:

```kotlin
    suspend fun createSession(id: String, startTime: Long): Session {
        val session = Session(
            id = id,
            startTime = startTime,
            endTime = null,
            durationMs = null,
            title = titleFormat.format(Date(startTime)),
            status = SessionStatus.RECORDING,
            speakerCount = null,
            audioFilePath = null,
            audioDeleteAt = null
        )
        sessionDao.insert(session)
        return session
    }
```

with:

```kotlin
    /**
     * [calendarName], when present, means this session was started by the calendar auto-record
     * feature (see CalendarAlarmReceiver) rather than a manual trigger; it's appended to the
     * title so a calendar-triggered session is distinguishable in the session list.
     */
    suspend fun createSession(id: String, startTime: Long, calendarName: String? = null): Session {
        val baseTitle = titleFormat.format(Date(startTime))
        val session = Session(
            id = id,
            startTime = startTime,
            endTime = null,
            durationMs = null,
            title = if (calendarName != null) "$baseTitle — $calendarName" else baseTitle,
            status = SessionStatus.RECORDING,
            speakerCount = null,
            audioFilePath = null,
            audioDeleteAt = null
        )
        sessionDao.insert(session)
        return session
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.andrecord.app.data.SessionRepositoryTest"`
Expected: PASS (all tests in the file, including the two new ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/data/SessionRepository.kt app/src/test/java/com/andrecord/app/data/SessionRepositoryTest.kt
git commit -m "Add optional calendar name to SessionRepository.createSession"
```

---

### Task 3: `RecordingController` — calendar-triggered start/stop, calendar name in the notification

**Files:**
- Modify: `app/src/main/java/com/andrecord/app/recording/RecordingServiceStarter.kt`
- Modify: `app/src/main/java/com/andrecord/app/recording/RecordingService.kt`
- Modify: `app/src/main/java/com/andrecord/app/recording/RecordingController.kt`
- Test: `app/src/test/java/com/andrecord/app/recording/RecordingControllerTest.kt`

**Interfaces:**
- Consumes: `SessionRepository.createSession(id, startTime, calendarName)` from Task 2.
- Produces: `RecordingController.startForCalendarEvent(calendarName: String): String?` (returns the new session id, or `null` if a recording was already active — a quiet no-op), `RecordingController.stopIfActive(sessionId: String): Boolean` (returns whether it actually stopped anything). Both are consumed by `CalendarAlarmReceiver` in Task 7.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/java/com/andrecord/app/recording/RecordingControllerTest.kt` (existing file). First update `FakeServiceStarter` (it must match the new interface signature or the file won't compile):

```kotlin
    private class FakeServiceStarter : RecordingServiceStarter {
        var startedSessionId: String? = null
        var startedCalendarName: String? = null
        var stopCalled = false
        override fun startRecording(sessionId: String, calendarName: String?) {
            startedSessionId = sessionId
            startedCalendarName = calendarName
        }
        override fun stopRecording() { stopCalled = true }
    }
```

Then add these new test functions inside the existing `RecordingControllerTest` class:

```kotlin
    @Test
    fun `startForCalendarEvent starts a session with the calendar name`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter)

        val sessionId = controller.startForCalendarEvent("Work")

        assertEquals("fixed-id", sessionId)
        assertEquals(RecordingState.RECORDING, controller.currentState())
        assertEquals("Work", starter.startedCalendarName)
        val title = db.sessionDao().getById("fixed-id")?.title
        assertEquals(true, title!!.endsWith("— Work"))
        db.close()
    }

    @Test
    fun `startForCalendarEvent returns null and does not interrupt an already-active recording`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter, ids = listOf("manual-id"))
        controller.toggle()
        assertEquals(RecordingState.RECORDING, controller.currentState())

        val sessionId = controller.startForCalendarEvent("Work")

        assertEquals(null, sessionId)
        assertEquals(RecordingState.RECORDING, controller.currentState())
        assertEquals(false, starter.stopCalled)
        assertEquals("manual-id", starter.startedSessionId)
        db.close()
    }

    @Test
    fun `stopIfActive stops the matching session`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter, ids = listOf("cal-id"))
        val sessionId = controller.startForCalendarEvent("Work")!!

        val stopped = controller.stopIfActive(sessionId)

        assertEquals(true, stopped)
        assertEquals(RecordingState.IDLE, controller.currentState())
        assertEquals(true, starter.stopCalled)
        db.close()
    }

    @Test
    fun `stopIfActive is a no-op for a stale session id`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter, ids = listOf("cal-id"))
        controller.startForCalendarEvent("Work")

        val stopped = controller.stopIfActive("some-other-id")

        assertEquals(false, stopped)
        assertEquals(RecordingState.RECORDING, controller.currentState())
        assertEquals(false, starter.stopCalled)
        db.close()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.andrecord.app.recording.RecordingControllerTest"`
Expected: FAIL to compile — `RecordingServiceStarter.startRecording` still takes one argument, and `startForCalendarEvent`/`stopIfActive` don't exist yet.

- [ ] **Step 3: Implement**

Replace the full contents of `app/src/main/java/com/andrecord/app/recording/RecordingServiceStarter.kt` with:

```kotlin
package com.andrecord.app.recording

interface RecordingServiceStarter {
    fun startRecording(sessionId: String, calendarName: String?)
    fun stopRecording()
}

class AndroidRecordingServiceStarter(private val context: android.content.Context) : RecordingServiceStarter {
    override fun startRecording(sessionId: String, calendarName: String?) {
        val intent = android.content.Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_SESSION_ID, sessionId)
            .putExtra(RecordingService.EXTRA_CALENDAR_NAME, calendarName)
        context.startForegroundService(intent)
    }

    override fun stopRecording() {
        val intent = android.content.Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_STOP)
        context.startService(intent)
    }
}
```

In `app/src/main/java/com/andrecord/app/recording/RecordingService.kt`, replace:

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_SESSION_ID)!!)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(id: String) {
```

with:

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(
                intent.getStringExtra(EXTRA_SESSION_ID)!!,
                intent.getStringExtra(EXTRA_CALENDAR_NAME)
            )
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(id: String, calendarName: String?) {
```

Still in `startRecording`, replace the notification line:

```kotlin
        val notification = buildNotification("Recording…")
```

with:

```kotlin
        val notification = buildNotification(
            if (calendarName != null) "Recording… ($calendarName)" else "Recording…"
        )
```

In the same file's companion object, add the new extra key alongside the existing ones:

```kotlin
        const val ACTION_START = "com.andrecord.app.action.START"
        const val ACTION_STOP = "com.andrecord.app.action.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CALENDAR_NAME = "calendar_name"
```

Replace the full contents of `app/src/main/java/com/andrecord/app/recording/RecordingController.kt` with:

```kotlin
package com.andrecord.app.recording

import com.andrecord.app.data.SessionRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RecordingController(
    private val repository: SessionRepository,
    private val serviceStarter: RecordingServiceStarter,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val _state = MutableStateFlow(RecordingState.IDLE)

    /**
     * Single source of truth for "is a recording currently active", observed directly by
     * SessionListViewModel (persistent bar + FAB) and RecordingViewModel (live view) so every
     * path that starts/stops a recording -- this class's own toggle(), a volume-key/Quick-Tap
     * trigger, a calendar-triggered auto-record, the recording notification's Stop action, or a
     * mid-recording failure -- is reflected everywhere at once instead of leaving other observers
     * on a stale local copy.
     */
    val state: StateFlow<RecordingState> = _state

    // Volatile: written from Main (toggle()/start()/stop()) and from Dispatchers.Default (the
    // notification-Stop teardown coroutine in RecordingService, and the capture loop's failure
    // path), with no other synchronization between those threads.
    @Volatile
    private var activeSessionId: String? = null

    fun currentState(): RecordingState = _state.value

    suspend fun toggle(): RecordingState {
        return if (_state.value == RecordingState.IDLE) start() else stop()
    }

    /**
     * Calendar-triggered start (see CalendarAlarmReceiver): only starts if currently idle --
     * unlike [toggle], it never stops an active recording. Returns the new session's id so the
     * caller can tag a later stop alarm to it, or `null` if a recording was already active, which
     * the caller treats as a quiet no-op rather than interrupting whatever is already running.
     */
    suspend fun startForCalendarEvent(calendarName: String): String? {
        if (_state.value != RecordingState.IDLE) return null
        start(calendarName)
        return activeSessionId
    }

    /**
     * Session-scoped stop, mirroring [reportRecordingEnded]'s session-scoped overload but
     * actually stopping the recording rather than just resetting state: a no-op unless
     * [sessionId] still matches the session this controller currently considers active. Used by
     * CalendarAlarmReceiver's stop alarm, which must never stop a different recording (a manual
     * one, or a later calendar event's) that happens to be active when it fires. Returns whether
     * it actually stopped anything.
     */
    suspend fun stopIfActive(sessionId: String): Boolean {
        if (activeSessionId != sessionId) return false
        stop()
        return true
    }

    /**
     * Called by RecordingService whenever a recording ends without the user having asked it to:
     * a start that never got off the ground (mic permission denied, no storage, mic held by
     * another app), or a failure part-way through the capture loop (ran out of storage, permission
     * revoked, mic died). Both cases mean the same thing to the controller — there is no live
     * recording any more — which is why this is named for the outcome rather than for the
     * failed-start case it was originally added for.
     *
     * Without it the controller stays in RECORDING forever: the user's next trigger press would
     * route to stop(), which would flip the just-errored session back to PROCESSING and enqueue
     * diarization against a recording that was never finished.
     */
    fun reportRecordingEnded() {
        activeSessionId = null
        _state.value = RecordingState.IDLE
    }

    /**
     * Session-scoped counterpart to [reportRecordingEnded]: a no-op unless [sessionId] still
     * matches the session this controller currently considers active.
     *
     * A recording's teardown (WAV finalize + ASR stop + trailing decode + Room flush) runs on its
     * own coroutine and can take anywhere from a few hundred milliseconds to over a second. If the
     * user starts a NEW recording before that coroutine finishes, its eventual call to the
     * unguarded [reportRecordingEnded] would flip this controller back to IDLE out from under the
     * new, still-running recording -- desyncing the FAB/persistent bar and kicking the user out of
     * the live view via RecordingScreen's auto-navigate-back effect. Callers that identify the
     * specific session they're tearing down (e.g. RecordingService's notification-Stop path)
     * should use this instead of the unguarded overload.
     */
    fun reportRecordingEnded(sessionId: String) {
        if (activeSessionId == sessionId) reportRecordingEnded()
    }

    private suspend fun start(calendarName: String? = null): RecordingState {
        val id = idGenerator()
        repository.createSession(id, clock(), calendarName)
        activeSessionId = id
        // Set before handing off to the service: the service can report a start failure back to
        // us (see reportRecordingEnded) as soon as it processes the start intent, and that reset
        // must not be clobbered by a late assignment here.
        _state.value = RecordingState.RECORDING
        serviceStarter.startRecording(id, calendarName)
        return _state.value
    }

    private suspend fun stop(): RecordingState {
        serviceStarter.stopRecording()
        activeSessionId = null
        _state.value = RecordingState.IDLE
        return _state.value
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.andrecord.app.recording.RecordingControllerTest"`
Expected: PASS (all tests in the file, including the four new ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/recording/RecordingServiceStarter.kt app/src/main/java/com/andrecord/app/recording/RecordingService.kt app/src/main/java/com/andrecord/app/recording/RecordingController.kt app/src/test/java/com/andrecord/app/recording/RecordingControllerTest.kt
git commit -m "Add calendar-triggered start/stop to RecordingController"
```

---

### Task 4: `CalendarEventRepository` — interface, data classes, and real `CalendarContract` implementation

**Files:**
- Create: `app/src/main/java/com/andrecord/app/calendar/CalendarEventRepository.kt`
- Create: `app/src/main/java/com/andrecord/app/calendar/AndroidCalendarEventRepository.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `data class CalendarInfo(val id: Long, val displayName: String)`, `data class CalendarEvent(val eventId: Long, val calendarId: Long, val calendarName: String, val startTimeMillis: Long, val endTimeMillis: Long, val isAllDay: Boolean, val attendeeCount: Int)`, `interface CalendarEventRepository { fun getWatchableCalendars(): List<CalendarInfo>; fun getEventsInWindow(windowStartMillis: Long, windowEndMillis: Long): List<CalendarEvent> }`, and `class AndroidCalendarEventRepository(context: Context) : CalendarEventRepository`. Consumed by `CalendarQualification` (Task 5), `CalendarAutoRecordScheduler`/`CalendarAlarmReceiver` (Task 7), and the Settings UI (Task 9).

No automated test in this task: both methods issue real `ContentResolver` queries against `CalendarContract`, which requires a live calendar provider with real data and isn't unit-testable — the same category as this app's native `SherpaOnnxDiarizationEngine`/`SherpaOnnxWhisperAsrEngine`, which also have no direct unit test. Task 5's `CalendarQualification` covers all the actual filtering logic with plain unit tests against the `CalendarEvent` data class directly. This repository is verified on-device in Task 9's manual test pass.

- [ ] **Step 1: Create the interface and data classes**

Create `app/src/main/java/com/andrecord/app/calendar/CalendarEventRepository.kt`:

```kotlin
package com.andrecord.app.calendar

data class CalendarInfo(val id: Long, val displayName: String)

data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val isAllDay: Boolean,
    val attendeeCount: Int
)

interface CalendarEventRepository {
    /** Every calendar synced on the device, for the Settings multi-select list. */
    fun getWatchableCalendars(): List<CalendarInfo>

    /** Every event instance (recurrence-expanded) starting or ending within
     *  [windowStartMillis, windowEndMillis), across all calendars -- not pre-filtered by
     *  watched-calendar selection or qualification rules; see [CalendarQualification] for that. */
    fun getEventsInWindow(windowStartMillis: Long, windowEndMillis: Long): List<CalendarEvent>
}
```

- [ ] **Step 2: Implement the real Android-backed repository**

Create `app/src/main/java/com/andrecord/app/calendar/AndroidCalendarEventRepository.kt`:

```kotlin
package com.andrecord.app.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract

/**
 * Real `CalendarContract` implementation. `CalendarContract.Instances` (not `Events`) is
 * queried so recurring events are already expanded into concrete start/end-time occurrences
 * within the window -- `Events` alone would return one row per recurring series, not per
 * occurrence. Querying `Instances` with a time window is the documented pattern: build the URI
 * via `Instances.CONTENT_URI.buildUpon()` with the window's start/end appended as path segments
 * via `ContentUris.appendId`.
 *
 * `SecurityException` is caught in both methods (returning an empty result) rather than
 * propagated: `READ_CALENDAR` is a revocable runtime permission, and this repository is queried
 * from a `BroadcastReceiver` (see CalendarAlarmReceiver) where an uncaught exception would crash
 * the whole receiver. A missing/revoked permission is surfaced separately as a Settings state,
 * not as a crash here.
 */
class AndroidCalendarEventRepository(private val context: Context) : CalendarEventRepository {

    override fun getWatchableCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
        )
        val calendars = mutableListOf<CalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    calendars.add(CalendarInfo(cursor.getLong(idIndex), cursor.getString(nameIndex) ?: "Calendar"))
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return calendars
    }

    override fun getEventsInWindow(windowStartMillis: Long, windowEndMillis: Long): List<CalendarEvent> {
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, windowStartMillis)
        ContentUris.appendId(uriBuilder, windowEndMillis)
        val uri = uriBuilder.build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
        val calendarNames = getWatchableCalendars().associate { it.id to it.displayName }
        val events = mutableListOf<CalendarEvent>()
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val calendarIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(eventIdIndex)
                    val calendarId = cursor.getLong(calendarIdIndex)
                    events.add(
                        CalendarEvent(
                            eventId = eventId,
                            calendarId = calendarId,
                            calendarName = calendarNames[calendarId] ?: "Calendar",
                            startTimeMillis = cursor.getLong(beginIndex),
                            endTimeMillis = cursor.getLong(endIndex),
                            isAllDay = cursor.getInt(allDayIndex) != 0,
                            attendeeCount = getAttendeeCount(eventId)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return events
    }

    private fun getAttendeeCount(eventId: Long): Int {
        val projection = arrayOf(CalendarContract.Attendees._ID)
        val selection = "${CalendarContract.Attendees.EVENT_ID} = ?"
        return try {
            context.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI, projection, selection, arrayOf(eventId.toString()), null
            )?.use { cursor -> cursor.count } ?: 0
        } catch (e: SecurityException) {
            0
        }
    }
}
```

- [ ] **Step 3: Declare the `READ_CALENDAR` permission**

In `app/src/main/AndroidManifest.xml`, add this line to the permissions block at the top of the file (after the existing `VIBRATE` permission, before the "Deliberately no INTERNET permission" comment):

```xml
    <uses-permission android:name="android.permission.READ_CALENDAR" />
```

- [ ] **Step 4: Verify the project compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL — no test to run for this task (see rationale above), so this compile check is the only automated gate.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/calendar/CalendarEventRepository.kt app/src/main/java/com/andrecord/app/calendar/AndroidCalendarEventRepository.kt app/src/main/AndroidManifest.xml
git commit -m "Add CalendarEventRepository backed by CalendarContract"
```

---

### Task 5: `CalendarQualification` — pure qualifying-event and stop-time logic

**Files:**
- Create: `app/src/main/java/com/andrecord/app/calendar/CalendarQualification.kt`
- Test: `app/src/test/java/com/andrecord/app/calendar/CalendarQualificationTest.kt`

**Interfaces:**
- Consumes: `CalendarEvent` from Task 4.
- Produces: `CalendarQualification.isQualifying(event: CalendarEvent, watchedCalendarIds: Set<Long>): Boolean`, `CalendarQualification.findNextQualifyingEvent(events: List<CalendarEvent>, watchedCalendarIds: Set<Long>, nowMillis: Long): CalendarEvent?`, `CalendarQualification.computeStopTimeMillis(event: CalendarEvent, allEvents: List<CalendarEvent>, watchedCalendarIds: Set<Long>): Long`, and `CalendarQualification.GRACE_PERIOD_MILLIS`. Consumed by `CalendarAlarmReceiver` and `CalendarAutoRecordScheduler` in Task 7.

This is pure Kotlin logic against plain data classes -- no Android framework dependency, no Robolectric needed, plain JUnit.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/andrecord/app/calendar/CalendarQualificationTest.kt`:

```kotlin
package com.andrecord.app.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarQualificationTest {

    private fun event(
        eventId: Long,
        calendarId: Long = 1L,
        start: Long,
        end: Long,
        isAllDay: Boolean = false,
        attendeeCount: Int = 2
    ) = CalendarEvent(
        eventId = eventId,
        calendarId = calendarId,
        calendarName = "Work",
        startTimeMillis = start,
        endTimeMillis = end,
        isAllDay = isAllDay,
        attendeeCount = attendeeCount
    )

    @Test
    fun `isQualifying requires a watched calendar, non-all-day, and 2+ attendees`() {
        val watched = setOf(1L)
        assertEquals(true, CalendarQualification.isQualifying(event(1, start = 0, end = 1000), watched))
        assertEquals(false, CalendarQualification.isQualifying(event(1, calendarId = 2L, start = 0, end = 1000), watched))
        assertEquals(false, CalendarQualification.isQualifying(event(1, start = 0, end = 1000, isAllDay = true), watched))
        assertEquals(false, CalendarQualification.isQualifying(event(1, start = 0, end = 1000, attendeeCount = 1), watched))
    }

    @Test
    fun `findNextQualifyingEvent picks the soonest future qualifying event`() {
        val watched = setOf(1L)
        val events = listOf(
            event(1, start = 5000, end = 6000),
            event(2, start = 3000, end = 4000),
            event(3, calendarId = 2L, start = 1000, end = 2000) // not on a watched calendar
        )

        val next = CalendarQualification.findNextQualifyingEvent(events, watched, nowMillis = 2000)

        assertEquals(2L, next?.eventId)
    }

    @Test
    fun `findNextQualifyingEvent ignores events that already started`() {
        val watched = setOf(1L)
        val events = listOf(event(1, start = 1000, end = 2000))

        val next = CalendarQualification.findNextQualifyingEvent(events, watched, nowMillis = 1500)

        assertNull(next)
    }

    @Test
    fun `computeStopTimeMillis defaults to end time plus the grace period`() {
        val watched = setOf(1L)
        val target = event(1, start = 0, end = 60_000)

        val stop = CalendarQualification.computeStopTimeMillis(target, listOf(target), watched)

        assertEquals(60_000L + CalendarQualification.GRACE_PERIOD_MILLIS, stop)
    }

    @Test
    fun `computeStopTimeMillis brings the stop forward to a back-to-back qualifying event`() {
        val watched = setOf(1L)
        val target = event(1, start = 0, end = 60_000)
        val backToBack = event(2, start = 61_000, end = 120_000) // starts inside the 5-min grace window

        val stop = CalendarQualification.computeStopTimeMillis(target, listOf(target, backToBack), watched)

        assertEquals(61_000L, stop)
    }

    @Test
    fun `computeStopTimeMillis ignores a nearby event that does not qualify`() {
        val watched = setOf(1L)
        val target = event(1, start = 0, end = 60_000)
        val nonQualifying = event(2, start = 61_000, end = 120_000, attendeeCount = 1)

        val stop = CalendarQualification.computeStopTimeMillis(target, listOf(target, nonQualifying), watched)

        assertEquals(60_000L + CalendarQualification.GRACE_PERIOD_MILLIS, stop)
    }

    @Test
    fun `computeStopTimeMillis ignores a qualifying event that starts after the grace window`() {
        val watched = setOf(1L)
        val target = event(1, start = 0, end = 60_000)
        val farAway = event(2, start = 600_000, end = 700_000)

        val stop = CalendarQualification.computeStopTimeMillis(target, listOf(target, farAway), watched)

        assertEquals(60_000L + CalendarQualification.GRACE_PERIOD_MILLIS, stop)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.andrecord.app.calendar.CalendarQualificationTest"`
Expected: FAIL to compile — `CalendarQualification` doesn't exist yet.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/andrecord/app/calendar/CalendarQualification.kt`:

```kotlin
package com.andrecord.app.calendar

/**
 * Pure "does this event qualify for auto-record, and when should it stop" logic -- no Android
 * dependency, fully unit-testable against plain [CalendarEvent] values. See
 * docs/superpowers/specs/2026-07-27-calendar-auto-record-design.md §3-4 for the rules this
 * implements.
 */
object CalendarQualification {

    /** Default stop time is the event's end time plus this grace period, absorbing a meeting
     *  that runs slightly long while staying fully automatic (see the design spec §4). */
    const val GRACE_PERIOD_MILLIS = 5 * 60 * 1000L

    fun isQualifying(event: CalendarEvent, watchedCalendarIds: Set<Long>): Boolean =
        event.calendarId in watchedCalendarIds && !event.isAllDay && event.attendeeCount >= 2

    /** The soonest qualifying event starting at or after [nowMillis], or `null` if none qualify. */
    fun findNextQualifyingEvent(
        events: List<CalendarEvent>,
        watchedCalendarIds: Set<Long>,
        nowMillis: Long
    ): CalendarEvent? =
        events
            .filter { isQualifying(it, watchedCalendarIds) && it.startTimeMillis >= nowMillis }
            .minByOrNull { it.startTimeMillis }

    /**
     * The default stop time is [event]'s end time plus [GRACE_PERIOD_MILLIS]. If another
     * qualifying event on a watched calendar starts within that grace window, the stop time is
     * brought forward to that next event's start time instead (or [event]'s own end time,
     * whichever is later) -- so a back-to-back meeting gets its own clean recording rather than
     * one continuous session bleeding across both.
     */
    fun computeStopTimeMillis(
        event: CalendarEvent,
        allEvents: List<CalendarEvent>,
        watchedCalendarIds: Set<Long>
    ): Long {
        val defaultStop = event.endTimeMillis + GRACE_PERIOD_MILLIS
        val nextQualifying = allEvents
            .filter { it.eventId != event.eventId }
            .filter { isQualifying(it, watchedCalendarIds) }
            .filter { it.startTimeMillis > event.startTimeMillis && it.startTimeMillis < defaultStop }
            .minByOrNull { it.startTimeMillis }
        return if (nextQualifying != null) maxOf(nextQualifying.startTimeMillis, event.endTimeMillis) else defaultStop
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.andrecord.app.calendar.CalendarQualificationTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/calendar/CalendarQualification.kt app/src/test/java/com/andrecord/app/calendar/CalendarQualificationTest.kt
git commit -m "Add CalendarQualification pure qualifying-event and stop-time logic"
```

---

### Task 6: `ExactAlarmPermissionStatus`

**Files:**
- Create: `app/src/main/java/com/andrecord/app/calendar/ExactAlarmPermissionStatus.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `class ExactAlarmPermissionStatus(context: Context) { fun isGranted(): Boolean; fun isDismissed(): Boolean; fun dismiss(); fun shouldShowBanner(): Boolean }`. Consumed by the Settings UI in Task 9.

No automated test: `isGranted()` calls the real `AlarmManager.canScheduleExactAlarms()`, which needs a real (or Robolectric-simulated) system service and isn't meaningfully testable as pure logic — unlike `AccessibilityServiceStatus.isServiceEnabled()`, there's no string-parsing sub-step to extract, since `canScheduleExactAlarms()` is already a direct boolean. This mirrors `AccessibilityServiceStatus.isServiceEnabled()` itself having no direct unit test either (only its `isServiceInEnabledList` companion function does).

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/andrecord/app/calendar/ExactAlarmPermissionStatus.kt`:

```kotlin
package com.andrecord.app.calendar

import android.app.AlarmManager
import android.content.Context

/**
 * Tracks whether this app can schedule exact alarms (needed to start a recording precisely when
 * a calendar event begins), and whether the user has dismissed the in-app prompt to grant it.
 *
 * `SCHEDULE_EXACT_ALARM` has no runtime-permission dialog -- the user must flip it on manually in
 * Settings -- so the UI (see SettingsScreen's banner) instead surfaces a one-tap deep link there
 * via `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, mirroring
 * [com.andrecord.app.accessibility.AccessibilityServiceStatus]'s exact pattern for the volume-key
 * trigger's accessibility-service permission.
 */
class ExactAlarmPermissionStatus(private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun isGranted(): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return alarmManager?.canScheduleExactAlarms() ?: false
    }

    fun isDismissed(): Boolean = prefs.getBoolean(KEY_DISMISSED, false)

    fun dismiss() {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    /** Never shown once the permission is actually granted (dismissal is then moot), and never
     * shown again once the user has dismissed it while still ungranted. */
    fun shouldShowBanner(): Boolean = !isGranted() && !isDismissed()

    companion object {
        private const val PREFS_NAME = "exact_alarm_permission_status"
        private const val KEY_DISMISSED = "banner_dismissed"
    }
}
```

- [ ] **Step 2: Declare the `SCHEDULE_EXACT_ALARM` permission**

In `app/src/main/AndroidManifest.xml`, add this line directly below the `READ_CALENDAR` permission added in Task 4:

```xml
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

- [ ] **Step 3: Verify the project compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/andrecord/app/calendar/ExactAlarmPermissionStatus.kt app/src/main/AndroidManifest.xml
git commit -m "Add ExactAlarmPermissionStatus"
```

---

### Task 7: `CalendarAutoRecordScheduler`, `CalendarAlarmReceiver`, `CalendarBootReceiver`

**Files:**
- Create: `app/src/main/java/com/andrecord/app/calendar/CalendarAutoRecordScheduler.kt`
- Create: `app/src/main/java/com/andrecord/app/calendar/CalendarAlarmReceiver.kt`
- Create: `app/src/main/java/com/andrecord/app/calendar/CalendarBootReceiver.kt`
- Modify: `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `AppSettings` (Task 1), `RecordingController.startForCalendarEvent`/`stopIfActive` (Task 3), `CalendarEventRepository` (Task 4), `CalendarQualification` (Task 5).
- Produces: `AppContainer.calendarEventRepository: CalendarEventRepository`, `AppContainer.calendarAutoRecordScheduler: CalendarAutoRecordScheduler` — both consumed by Task 8 (periodic rescan + `ContentObserver`) and Task 9 (Settings UI).

No automated test for these three classes: they are thin `AlarmManager`/`BroadcastReceiver` glue around real Android framework calls (`AlarmManager.setExactAndAllowWhileIdle`, `PendingIntent`, `ContentResolver` via `CalendarEventRepository`), exactly the same category as `RecordingService`'s own `startRecording`/`stopRecording`, which also have no direct unit test — all the logic they delegate to (`CalendarQualification`) is already covered by Task 5's tests. This matches the spec's own testing section (§9): "the thin `AlarmManager`/`BroadcastReceiver` glue isn't unit tested directly, but the logic it delegates to is." Verified on-device in Task 9's manual test pass.

- [ ] **Step 1: Implement `CalendarAutoRecordScheduler`**

Create `app/src/main/java/com/andrecord/app/calendar/CalendarAutoRecordScheduler.kt`:

```kotlin
package com.andrecord.app.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.andrecord.app.settings.AppSettings

/**
 * Schedules exactly one exact `AlarmManager` alarm for the next qualifying calendar event's
 * start time, rather than a recurring poll -- see the design spec §2 for why (WorkManager's
 * 15-minute periodic minimum is too imprecise for a meeting's actual start time; a
 * continuously-running poll would drain battery for a feature that should be invisible until a
 * meeting happens).
 */
class CalendarAutoRecordScheduler(
    private val context: Context,
    private val eventRepository: CalendarEventRepository,
    private val appSettings: AppSettings
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /**
     * Re-scans the watched calendars for the next qualifying event and (re)schedules exactly one
     * exact start alarm for it, replacing any previously scheduled one. Called whenever the "what
     * comes next" answer might have changed: this scheduler's own alarm firing (via
     * CalendarAlarmReceiver), a calendar data change, device boot, or the periodic safety-net
     * check (see Task 8). A no-op (with any existing start alarm cancelled) if the feature is
     * disabled, no calendars are watched, or the exact-alarm permission isn't granted.
     */
    fun rescan() {
        cancelStartAlarm()
        if (!appSettings.isCalendarAutoRecordEnabled()) return
        if (alarmManager?.canScheduleExactAlarms() != true) return
        val watchedIds = appSettings.getWatchedCalendarIds()
        if (watchedIds.isEmpty()) return

        val now = System.currentTimeMillis()
        val events = eventRepository.getEventsInWindow(now, now + LOOKAHEAD_WINDOW_MILLIS)
        val next = CalendarQualification.findNextQualifyingEvent(events, watchedIds, now) ?: return
        scheduleStartAlarm(next)
    }

    /** Schedules the tagged stop alarm for [sessionId] at [stopTimeMillis] (see the design spec
     *  §4 for how the stop time itself is computed). */
    fun scheduleStopAlarm(sessionId: String, stopTimeMillis: Long) {
        val intent = Intent(context, CalendarAlarmReceiver::class.java).apply {
            action = CalendarAlarmReceiver.ACTION_STOP
            putExtra(CalendarAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_STOP, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopTimeMillis, pendingIntent)
    }

    private fun scheduleStartAlarm(event: CalendarEvent) {
        val intent = Intent(context, CalendarAlarmReceiver::class.java).apply {
            action = CalendarAlarmReceiver.ACTION_START
            putExtra(CalendarAlarmReceiver.EXTRA_EVENT_ID, event.eventId)
            putExtra(CalendarAlarmReceiver.EXTRA_EVENT_START, event.startTimeMillis)
            putExtra(CalendarAlarmReceiver.EXTRA_CALENDAR_NAME, event.calendarName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_START, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.startTimeMillis, pendingIntent)
    }

    private fun cancelStartAlarm() {
        val intent = Intent(context, CalendarAlarmReceiver::class.java).apply { action = CalendarAlarmReceiver.ACTION_START }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_START, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager?.cancel(it) }
    }

    companion object {
        /** How far ahead a rescan looks for the next qualifying event (design spec §7). */
        const val LOOKAHEAD_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val REQUEST_CODE_START = 1001
        private const val REQUEST_CODE_STOP = 1002
    }
}
```

- [ ] **Step 2: Implement `CalendarAlarmReceiver`**

Create `app/src/main/java/com/andrecord/app/calendar/CalendarAlarmReceiver.kt`:

```kotlin
package com.andrecord.app.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.recording.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles both the start and stop alarms scheduled by [CalendarAutoRecordScheduler]. Registered
 * with `android:exported="false"` in the manifest -- these alarms only ever come from this app's
 * own `PendingIntent`s, never from another app.
 *
 * `goAsync()` is required because both branches call suspend functions on
 * [com.andrecord.app.recording.RecordingController] (`startForCalendarEvent`/`stopIfActive`):
 * without it, the receiver's process could be killed the moment `onReceive` returns, before the
 * launched coroutine finishes.
 */
class CalendarAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                when (intent.action) {
                    ACTION_START -> handleStart(context, intent)
                    ACTION_STOP -> handleStop(context, intent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Re-verifies the event this alarm was scheduled for is still valid (still exists, same
     * start time, still qualifies) before starting a recording -- see the design spec §7-8: the
     * event may have been deleted, moved, or edited below the attendee threshold since this alarm
     * was scheduled. If a recording is already active, this is a quiet no-op rather than
     * interrupting it. Either way, rescans afterward so the *next* qualifying event still gets
     * scheduled.
     */
    private suspend fun handleStart(context: Context, intent: Intent) {
        val container = (context.applicationContext as AndrecordApplication).container
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val expectedStart = intent.getLongExtra(EXTRA_EVENT_START, -1L)
        val calendarName = intent.getStringExtra(EXTRA_CALENDAR_NAME)

        if (calendarName != null && container.recordingController.currentState() == RecordingState.IDLE) {
            val watchedIds = container.appSettings.getWatchedCalendarIds()
            val windowStart = expectedStart - VERIFICATION_LOOKBEHIND_MILLIS
            val windowEnd = expectedStart + CalendarAutoRecordScheduler.LOOKAHEAD_WINDOW_MILLIS
            val events = container.calendarEventRepository.getEventsInWindow(windowStart, windowEnd)
            val event = events.find { it.eventId == eventId && it.startTimeMillis == expectedStart }

            if (event != null && CalendarQualification.isQualifying(event, watchedIds)) {
                val sessionId = container.recordingController.startForCalendarEvent(calendarName)
                if (sessionId != null) {
                    val stopTime = CalendarQualification.computeStopTimeMillis(event, events, watchedIds)
                    container.calendarAutoRecordScheduler.scheduleStopAlarm(sessionId, stopTime)
                }
            }
        }
        container.calendarAutoRecordScheduler.rescan()
    }

    private suspend fun handleStop(context: Context, intent: Intent) {
        val container = (context.applicationContext as AndrecordApplication).container
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        container.recordingController.stopIfActive(sessionId)
    }

    companion object {
        const val ACTION_START = "com.andrecord.app.calendar.action.START"
        const val ACTION_STOP = "com.andrecord.app.calendar.action.STOP"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_START = "event_start"
        const val EXTRA_CALENDAR_NAME = "calendar_name"
        const val EXTRA_SESSION_ID = "session_id"

        /** Window padding around the expected start time when re-querying the specific event by
         *  id, to tolerate any minor clock/provider rounding rather than requiring an exact
         *  millisecond match on the query bounds themselves (the exact match is still enforced
         *  afterward, against [EXTRA_EVENT_START]). */
        private const val VERIFICATION_LOOKBEHIND_MILLIS = 60_000L
    }
}
```

- [ ] **Step 3: Implement `CalendarBootReceiver`**

Create `app/src/main/java/com/andrecord/app/calendar/CalendarBootReceiver.kt`:

```kotlin
package com.andrecord.app.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrecord.app.AndrecordApplication

/**
 * `AlarmManager` alarms don't survive a device restart, so this re-runs the scheduler from
 * scratch immediately on boot (design spec §8). A meeting starting in the narrow window between
 * boot and this receiver running could theoretically be missed -- an accepted, rare limitation.
 */
class CalendarBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as AndrecordApplication).container
        container.calendarAutoRecordScheduler.rescan()
    }
}
```

- [ ] **Step 4: Wire the new classes into `AppContainer`**

In `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`, add these imports alongside the existing ones:

```kotlin
import com.andrecord.app.calendar.AndroidCalendarEventRepository
import com.andrecord.app.calendar.CalendarAutoRecordScheduler
import com.andrecord.app.calendar.CalendarEventRepository
```

Then add these two lines to `AppContainer`, directly below the existing `val appSettings = AppSettings(app)` line:

```kotlin
    val calendarEventRepository: CalendarEventRepository = AndroidCalendarEventRepository(app)
    val calendarAutoRecordScheduler = CalendarAutoRecordScheduler(app, calendarEventRepository, appSettings)
```

- [ ] **Step 5: Declare the new permission and receivers in the manifest**

In `app/src/main/AndroidManifest.xml`, add this permission directly below the `SCHEDULE_EXACT_ALARM` permission added in Task 6:

```xml
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

Then add these two `<receiver>` declarations inside `<application>`, directly after the existing `KeyTriggerAccessibilityService` `<service>` block (before the closing `</application>` tag):

```xml
        <receiver
            android:name=".calendar.CalendarAlarmReceiver"
            android:exported="false" />

        <receiver
            android:name=".calendar.CalendarBootReceiver"
            android:exported="true"
            android:permission="android.permission.RECEIVE_BOOT_COMPLETED">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 6: Verify the project compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/andrecord/app/calendar/CalendarAutoRecordScheduler.kt app/src/main/java/com/andrecord/app/calendar/CalendarAlarmReceiver.kt app/src/main/java/com/andrecord/app/calendar/CalendarBootReceiver.kt app/src/main/java/com/andrecord/app/AndrecordApplication.kt app/src/main/AndroidManifest.xml
git commit -m "Add CalendarAutoRecordScheduler and its alarm/boot receivers"
```

---

### Task 8: Periodic safety-net rescan and calendar-change `ContentObserver`

**Files:**
- Create: `app/src/main/java/com/andrecord/app/calendar/CalendarRescanWorker.kt`
- Modify: `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`

**Interfaces:**
- Consumes: `AppContainer.calendarAutoRecordScheduler` from Task 7.

No automated test: `CalendarRescanWorker.doWork()` and the `ContentObserver` registration are both thin real-Android-framework glue with nothing left to unit test once `rescan()` itself is excluded (as established in Task 7) — this mirrors `RetentionWorker`, which also has no test of its own `doWork()`.

- [ ] **Step 1: Implement the periodic safety-net worker**

Create `app/src/main/java/com/andrecord/app/calendar/CalendarRescanWorker.kt`:

```kotlin
package com.andrecord.app.calendar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andrecord.app.AndrecordApplication

/**
 * Infrequent backstop, not the precision mechanism (design spec §6): if a calendar change is
 * missed while the app process wasn't running to observe it via the ContentObserver in
 * AndrecordApplication, this eventually picks it up anyway.
 */
class CalendarRescanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as AndrecordApplication).container.calendarAutoRecordScheduler.rescan()
        return Result.success()
    }
}
```

- [ ] **Step 2: Schedule the periodic worker and register the calendar `ContentObserver`**

In `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`, add these imports alongside the existing ones:

```kotlin
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.andrecord.app.calendar.CalendarRescanWorker
```

Add a call to `registerCalendarObserver()` and `scheduleCalendarRescan()` inside `onCreate()`, directly after the existing `scheduleRetention()` call:

```kotlin
        reconcileInterruptedSessions()
        scheduleRetention()
        scheduleCalendarRescan()
        registerCalendarObserver()
```

Then add these two new private methods to `AndrecordApplication`, directly after the existing `scheduleRetention()` method:

```kotlin
    private fun scheduleCalendarRescan() {
        val request = PeriodicWorkRequestBuilder<CalendarRescanWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "calendar_rescan", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /** Promptly re-scans when the calendar's underlying data changes (an event added, edited, or
     *  deleted) rather than waiting for the next periodic safety-net run, up to 6 hours away. */
    private fun registerCalendarObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                container.calendarAutoRecordScheduler.rescan()
            }
        }
        contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
    }
```

- [ ] **Step 3: Verify the project compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/andrecord/app/calendar/CalendarRescanWorker.kt app/src/main/java/com/andrecord/app/AndrecordApplication.kt
git commit -m "Add periodic calendar rescan worker and ContentObserver"
```

---

### Task 9: Settings UI — enable toggle, calendar multi-select, exact-alarm banner

**Files:**
- Modify: `app/src/main/java/com/andrecord/app/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/andrecord/app/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/andrecord/app/ui/AndrecordApp.kt`

**Interfaces:**
- Consumes: `AppSettings` (Task 1), `CalendarEventRepository.getWatchableCalendars()`/`CalendarInfo` (Task 4), `ExactAlarmPermissionStatus` (Task 6), `CalendarAutoRecordScheduler.rescan()` (Task 7).

No automated test: this is a Compose UI wiring task with no new pure logic to extract (the toggle/checkbox handlers are one-line pass-throughs to already-tested `AppSettings` methods). Verified manually on-device in Step 5 below, alongside the rest of this plan's on-device verification.

- [ ] **Step 1: Update `SettingsViewModel`**

Replace the full contents of `app/src/main/java/com/andrecord/app/ui/settings/SettingsViewModel.kt` with:

```kotlin
package com.andrecord.app.ui.settings

import androidx.lifecycle.ViewModel
import com.andrecord.app.calendar.CalendarAutoRecordScheduler
import com.andrecord.app.calendar.CalendarEventRepository
import com.andrecord.app.calendar.CalendarInfo
import com.andrecord.app.calendar.ExactAlarmPermissionStatus
import com.andrecord.app.settings.AppSettings
import com.andrecord.app.settings.ReopenBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val appSettings: AppSettings,
    private val calendarEventRepository: CalendarEventRepository,
    private val exactAlarmPermissionStatus: ExactAlarmPermissionStatus,
    private val calendarAutoRecordScheduler: CalendarAutoRecordScheduler
) : ViewModel() {
    private val _reopenBehavior = MutableStateFlow(appSettings.getReopenBehavior())
    val reopenBehavior: StateFlow<ReopenBehavior> = _reopenBehavior

    private val _calendarAutoRecordEnabled = MutableStateFlow(appSettings.isCalendarAutoRecordEnabled())
    val calendarAutoRecordEnabled: StateFlow<Boolean> = _calendarAutoRecordEnabled

    private val _availableCalendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val availableCalendars: StateFlow<List<CalendarInfo>> = _availableCalendars

    private val _watchedCalendarIds = MutableStateFlow(appSettings.getWatchedCalendarIds())
    val watchedCalendarIds: StateFlow<Set<Long>> = _watchedCalendarIds

    private val _showExactAlarmBanner = MutableStateFlow(false)
    val showExactAlarmBanner: StateFlow<Boolean> = _showExactAlarmBanner

    fun onSelect(behavior: ReopenBehavior) {
        appSettings.setReopenBehavior(behavior)
        _reopenBehavior.value = behavior
    }

    /** Re-reads live state that can change outside this ViewModel: the device's synced calendar
     *  list, and whether the exact-alarm permission is currently granted. Call on resume, since
     *  the user's path here is granting the permission in system Settings and returning. */
    fun refreshCalendarState() {
        if (_calendarAutoRecordEnabled.value) {
            _availableCalendars.value = calendarEventRepository.getWatchableCalendars()
        }
        _showExactAlarmBanner.value = _calendarAutoRecordEnabled.value && exactAlarmPermissionStatus.shouldShowBanner()
    }

    /** Called once READ_CALENDAR has been granted (or immediately, when turning the feature
     *  off) -- see SettingsScreen's permission-request launcher. */
    fun setCalendarAutoRecordEnabled(enabled: Boolean) {
        appSettings.setCalendarAutoRecordEnabled(enabled)
        _calendarAutoRecordEnabled.value = enabled
        refreshCalendarState()
        calendarAutoRecordScheduler.rescan()
    }

    fun toggleWatchedCalendar(calendarId: Long) {
        val current = _watchedCalendarIds.value
        val updated = if (calendarId in current) current - calendarId else current + calendarId
        appSettings.setWatchedCalendarIds(updated)
        _watchedCalendarIds.value = updated
        calendarAutoRecordScheduler.rescan()
    }

    fun dismissExactAlarmBanner() {
        exactAlarmPermissionStatus.dismiss()
        _showExactAlarmBanner.value = _calendarAutoRecordEnabled.value && exactAlarmPermissionStatus.shouldShowBanner()
    }
}
```

- [ ] **Step 2: Update `SettingsScreen`**

Replace the full contents of `app/src/main/java/com/andrecord/app/ui/settings/SettingsScreen.kt` with:

```kotlin
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
```

- [ ] **Step 3: Update `AndrecordApp.kt`'s `SettingsViewModel` construction**

In `app/src/main/java/com/andrecord/app/ui/AndrecordApp.kt`, replace:

```kotlin
        TopLevelDestination.SETTINGS -> {
            val settingsViewModel = remember { SettingsViewModel(container.appSettings) }
```

with:

```kotlin
        TopLevelDestination.SETTINGS -> {
            val settingsViewModel = remember {
                SettingsViewModel(
                    container.appSettings,
                    container.calendarEventRepository,
                    container.exactAlarmPermissionStatus,
                    container.calendarAutoRecordScheduler
                )
            }
```

This references `container.exactAlarmPermissionStatus`, which does not exist on `AppContainer` yet. Add it now: in `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`, add the import `import com.andrecord.app.calendar.ExactAlarmPermissionStatus`, and add this line to `AppContainer` directly below the `calendarAutoRecordScheduler` line added in Task 7:

```kotlin
    val exactAlarmPermissionStatus = ExactAlarmPermissionStatus(app)
```

- [ ] **Step 4: Verify the project compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (existing suite plus every test added in Tasks 1, 2, 3, and 5).

- [ ] **Step 6: On-device manual verification**

This step exercises everything Tasks 4, 6, 7, 8, and 9 couldn't cover with an automated test: real `CalendarContract` data, a real granted `SCHEDULE_EXACT_ALARM` permission, and a real alarm actually firing. Install the app (`./gradlew installDebug`, or `ANDROID_SERIAL=<serial> ./gradlew installDebug` if more than one device is connected) and verify:

1. Open Settings, turn on "Calendar auto-record" — the READ_CALENDAR system permission dialog appears; grant it.
2. The watched-calendars list shows the device's real synced calendars (not empty, not hardcoded names).
3. Check at least one calendar. If the exact-alarm banner appears, tap "Open Alarm Settings", grant the permission in the system screen that opens, and return — the banner should disappear on resume.
4. In the watched calendar, create a test event starting ~2 minutes from now with at least one other attendee (a concrete time, not all-day) — confirm a recording starts automatically at that time (the foreground notification reads "Recording… (<calendar name>)"), and that the resulting session's title in the session list ends with "— <calendar name>".
5. Let the meeting's scheduled end time pass with no further meeting immediately after — confirm the recording auto-stops ~5 minutes after the event's end time.
6. Create two back-to-back qualifying events (the second starting within 5 minutes of the first's end) — confirm the first recording stops at (or very near) the second event's start time rather than running the full grace period.
7. Turn off "Calendar auto-record" — confirm no further auto-recordings start for events after that point.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/andrecord/app/ui/settings/SettingsViewModel.kt app/src/main/java/com/andrecord/app/ui/settings/SettingsScreen.kt app/src/main/java/com/andrecord/app/ui/AndrecordApp.kt app/src/main/java/com/andrecord/app/AndrecordApplication.kt
git commit -m "Add calendar auto-record settings UI"
```
