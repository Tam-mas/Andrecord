# Changelog

### [2026-07-26 21:10] Fixed

**Tech:** `RecordingService.flushSegments()`, `DiarizationWorker.runDiarization()`, `SessionRepository.replaceSegments()/getSegmentsOnce()` — transcript segments are written once and labeled in place, with Room (not an in-memory buffer) as the worker's source of truth
**Dev:** Three defects compounded into one. `flushPendingSegments()` re-read the whole accumulated `pendingAsrSegments[id]` list every 5 seconds and re-inserted all of it, so each utterance was written once per remaining flush tick; `DiarizationWorker` then appended a third, speaker-labeled copy of the same list without deleting the unlabeled rows (`TranscriptSegmentDao.deleteForSession` existed but was never called anywhere); and because `pendingAsrSegments` was a process-local `ConcurrentHashMap` on `AppContainer`, a process death between enqueue and execution — precisely what WorkManager is built to survive — left the worker diarizing against an empty list and silently finalizing a session with no transcript. Fixed by making the flush drain what it writes into a buffer local to the capture coroutine, adding a final flush after the capture loop (there was none: a clean stop previously dropped everything since the last 5-second tick), and having the worker read the already-flushed unlabeled rows back out of Room, align them, then `replaceSegments()` — delete-then-insert, which also makes retries idempotent. `pendingAsrSegments` is removed from `AppContainer` entirely; nothing needs it now. Covered by `RecordingServiceFlushTest` (multi-cycle flush, verified RED against the old draining-free implementation), three new `DiarizationWorkerLogicTest` cases, and a new on-device `TranscriptPipelineInstrumentedTest` that replays the real two-speaker speech fixture through the whole pipeline: 3 flush cycles produced 1 row, and diarization left 1 row (now `Speaker 1`), not 2 or 3.
**Plain:** A recorded meeting's transcript no longer repeats every sentence over and over — each thing said appears exactly once, with its speaker label.
**Why:** Any recording longer than a few seconds came back as a wall of the same sentences repeated dozens of times, which made the transcript useless to read and would have made the app feel fundamentally broken the first time anyone recorded a real meeting.

### [2026-07-26 21:10] Fixed

**Tech:** `SherpaOnnxStreamingAsrEngine.stop()` — drains the in-flight hypothesis as a final `AsrEvent.Final` before releasing the stream
**Dev:** A `Final` was only ever emitted when sherpa-onnx's endpoint rule fired (~1.4s of trailing silence), and `stop()` released the stream without draining, so the last sentence spoken before the user hit stop was decoded and then thrown away. `stop()` now decodes any remaining buffered input and queues the result; `RecordingService`'s capture coroutine polls once more after calling `stop()` (inside its teardown `finally`, the last point where anyone can still observe the queue) and includes it in the final flush.
**Plain:** The last thing said before you stop a recording now makes it into the transcript instead of being silently dropped.
**Why:** People finish their sentence and then hit stop — losing exactly that sentence, every single time, is the worst possible thing to lose.

### [2026-07-26 21:10] Fixed

**Tech:** `RecordingController.reportStartFailure()`, `RecordingService.abortStart()` — a failed start resets shared state instead of leaving the controller in `RECORDING`
**Dev:** `start()` set `state = RECORDING` unconditionally with no feedback path from the service, so when `RecordingService` hit a pre-flight failure (mic permission, storage) it called `markError()`+`stopSelf()` while the controller still believed a recording was live. The user's next trigger press then routed to `stop()`, which passed every guard, joined an already-completed job as a no-op, flipped the errored session back to `PROCESSING`, and enqueued diarization against a WAV that never existed. The service now calls back into the shared controller via `AppContainer`, `stopRecording()` bails out when `sessionId` is null (cleared on the failure paths), and the failure paths call `startForeground()` before `stopSelf()` to honour the `startForegroundService()` contract — best-effort, since a microphone-typed foreground service is itself refused when the missing permission is `RECORD_AUDIO`. Also hardened `AudioRecord` setup in the same pass: a negative `getMinBufferSize()` and a constructor that returns `STATE_UNINITIALIZED` (what happens when another app holds the mic) now route through the same abort path instead of throwing `IllegalStateException` onto the service thread. Verified on a Pixel 7 emulator within a single process: two trigger presses with the mic permission revoked produced two distinct errored sessions rather than one session flipped back to processing.
**Plain:** If a recording can't start — no microphone permission, no storage, or the mic is busy — the app now cleanly reports the failure and your next press starts a fresh recording, instead of getting stuck in a state where it thinks it's still recording.
**Why:** A single denied permission used to poison the next recording too, which is a horrible way to discover something went wrong.

### [2026-07-26 21:10] Fixed

**Tech:** `AndrecordApplication.onCreate()`, `SessionRepository.reconcileInterruptedSessions()`, `SessionDao.getByStatus()` — one-time startup sweep of sessions stranded in `RECORDING`
**Dev:** Nothing reconciled session state at startup, so a process killed mid-recording left its row in `RECORDING` permanently, with no path in the UI to clear it. A `CoroutineScope(Dispatchers.IO + SupervisorJob())` on the Application now marks any such row `ERROR` with "Interrupted — app was closed unexpectedly" once at startup; safe to repeat, since a reconciled session no longer matches. Verified on device: force-stopping mid-recording left the row `RECORDING`, and relaunching flipped it to the errored state.
**Plain:** A recording interrupted by the phone killing the app no longer shows as permanently "recording" — it's marked as interrupted next time you open the app.
**Why:** A row stuck on "recording" forever, with no way to dismiss it, looks like the app is broken and quietly eating your battery.

### [2026-07-26 21:10] Fixed

**Tech:** `SpeakerTimelineStrip` — `Modifier.weight(fraction)` instead of `Modifier.fillMaxWidth(fraction)`
**Dev:** `Row` measures an unweighted child against the *remaining* main-axis space, so fractional children compound-shrink (two 0.5 fractions render as 50% then 25% of what's left) and the strip never filled its width. `weight()` divides the full width proportionally and self-normalizes fractions that don't sum to 1.0 — as they don't here, since the silence between utterances isn't attributed to any speaker. Zero-length segments are filtered out because `weight()` rejects a non-positive weight. Rendering-only: `TimelineProportionsTest` tests the pure function and passes unchanged.
**Plain:** The colored speaker timeline under each session now fills the full width with correctly-sized bands, instead of trailing off into ever-smaller slivers.
**Why:** The timeline strip is the at-a-glance signature of each recording, and it was visibly wrong in a way that made every session look half-empty.

### [2026-07-26 21:10] Performance

**Tech:** `AndroidManifest.xml` (`android:largeHeap`, `dataSync` foreground service type), `DiarizationWorker.getForegroundInfo()` — headroom and unlimited execution time for diarizing long recordings
**Dev:** `SherpaOnnxDiarizationEngine.diarize()` materializes the entire recording as a `FloatArray` before ONNX inference (~64 MB/hour of samples at 16 kHz, on top of ~32 MB of model graphs), and the worker was additionally subject to WorkManager's ~10 minute execution ceiling — where a timeout or OOM burns a retry attempt and, once `MAX_ATTEMPTS` is exhausted, finalizes the session with zero speaker labels. Mitigated by `android:largeHeap="true"` and by promoting the worker with `setForeground()`/`getForegroundInfo()`, which removes the execution limit while a notification is shown. Since minSdk is 34, the typed `ForegroundInfo` overload is mandatory: the worker declares `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (the mic is already released by then, so `microphone` would be both wrong and permission-gated), which required declaring that type on WorkManager's own `SystemForegroundService` via `tools:node="merge"` plus the `FOREGROUND_SERVICE_DATA_SYNC` permission. The promotion is best-effort — wrapped in a try/catch so a refused promotion doesn't fail the work outright. Confirmed on device (`WM-Processor: Moving WorkSpec to the foreground`, worker `SUCCESS`). This is mitigation, not a fix: `OfflineSpeakerDiarization` has no streaming entry point, so genuinely bounding memory means chunked diarization with cross-window speaker re-clustering — documented in `SherpaOnnxDiarizationEngine`, and still unvalidated past short fixtures.
**Plain:** Long recordings now get more memory and unlimited processing time to finish their speaker breakdown, instead of being cut off partway through.
**Why:** The whole point of this app is recording actual hour-long meetings, and the processing step had only ever been tried on clips a few seconds long.

### [2026-07-26 20:55] Fixed

**Tech:** `app/src/main/java/com/andrecord/app/ui/AndrecordApp.kt` — clear `selectedSessionId` on delete
**Dev:** `onDeleted` previously called `navigator.navigateBack()` without also resetting `selectedSessionId` to `null`. Invisible in single-pane mode (the pane switch hides the stale composable), but in two-pane (unfolded) mode the detail pane would keep trying to render the just-deleted session's id until another session was selected.
**Plain:** Fixed a rare case where, on the unfolded/two-pane layout, deleting a session could leave a blank detail pane instead of clearing it.
**Why:** Foldable support is the whole point of this layout — didn't want the one navigation edge case that only shows up in two-pane mode to slip through untested.

### [2026-07-26 20:40] Added

**Tech:** `app/src/main/java/com/andrecord/app/ui/AndrecordApp.kt`, `app/src/main/java/com/andrecord/app/MainActivity.kt` — adaptive list-detail navigation root and app entry point, wiring together every screen and system built in Tasks 1-16
**Dev:** `MainActivity` requests `RECORD_AUDIO`+`POST_NOTIFICATIONS` via `registerForActivityResult(RequestMultiplePermissions())` on `onCreate()`, then sets content to `AndrecordTheme { AndrecordApp(container = (application as AndrecordApplication).container) }`. `AndrecordApp` uses `NavigableListDetailPaneScaffold` with a `rememberListDetailPaneScaffoldNavigator` to switch between single-pane (cover screen) and two-pane (unfolded) layouts; a separately-tracked `selectedSessionId` drives which `SessionDetailViewModel` (keyed per session id via `viewModel(key = id) { ... }`) is shown in the detail pane. Found and fixed a real API mismatch verifying the brief's sketch against the pinned `androidx.compose.material3.adaptive:*:1.0.0` libraries (confirmed via `javap` on the cached AARs' `classes.jar`, not assumption): `NavigableListDetailPaneScaffold`'s `navigator` parameter is hardcoded to `ThreePaneScaffoldNavigator<Any>` in this version (not left generic as `<T>` — verified by the absence of a `<T>` prefix in the decompiled signature, unlike the genuinely generic `rememberListDetailPaneScaffoldNavigator<T>()`), so the brief's `rememberListDetailPaneScaffoldNavigator<String>()` fails to type-check against it with an "expected `ThreePaneScaffoldNavigator<Any>`" error. Fixed by requesting `rememberListDetailPaneScaffoldNavigator<Any>()` instead and continuing to track the selected session id in the app's own `String?` state, since the navigator's destination payload type can't be `String` here anyway. Also found the brief's `viewModel(key = ...)` call needs `androidx.lifecycle:lifecycle-viewmodel-compose`, which was not present on the compile classpath (verified via `./gradlew :app:dependencies`) — neither `activity-compose` nor the adaptive-navigation libraries pull it in transitively — so added it explicitly to `app/build.gradle.kts` at the same `2.8.4` version already pinned for the rest of the lifecycle group. `ListDetailPaneScaffoldRole`, `ThreePaneScaffoldDestinationItem`, `AnimatedPane`, and `navigateTo`/`navigateBack` all checked out exactly as sketched once the navigator type was fixed. `./gradlew :app:assembleDebug` and the full unit test suite pass. Installed and launched on a running Pixel 7 emulator (no foldable AVD was available in this environment — only `Pixel_7` and `Medium_Phone_API_36.1`): confirmed both permission dialogs appear in order, the empty session list renders under the dark theme, tapping the record FAB starts `RecordingService` as a foreground service (confirmed via logcat) and a new session row appears immediately, tapping it again stops recording and `DiarizationWorker` completes successfully (logcat: `Worker result SUCCESS ... DiarizationWorker`), tapping the resulting session row navigates to the detail pane (single-pane mode replaces the list, as expected on a non-foldable device), and the system back button returns to the list. No crashes (`AndroidRuntime`/`FATAL` absent from logcat) throughout. The two-pane fold/unfold visual transition itself could not be verified in this environment since no foldable emulator profile or physical Fold device was available — this remains an item for the manual on-device checklist at the end of the plan.
**Plain:** The app is now fully wired up and launchable — this is the final piece that connects the recording engine, the session list, and the session detail screen into one real app that asks for microphone and notification permission on first launch and adapts its layout for folding phones.
**Why:** Every previous task built one piece in isolation; this is the task that makes Andrecord an actual app you can install and use end-to-end, and on-device testing confirmed the whole record-stop-view flow genuinely works, not just that each piece compiles.

### [2026-07-26 20:35] Added

**Tech:** `app/src/main/java/com/andrecord/app/ui/detail/{SessionDetailViewModel,SessionDetailScreen}.kt` — session detail screen with rename, delete, and share-as-text
**Dev:** `SessionDetailViewModel` derives `session`/`segments` as `StateFlow`s from `SessionRepository`, same `stateIn(viewModelScope, WhileSubscribed(5000), ...)` pattern as `SessionListViewModel`; `buildShareText()` reads `segments.value` synchronously and joins each segment as `[Speaker] text`. `SessionDetailScreen` renders a `TopAppBar` with a `MoreVert` overflow menu (Rename/Share as text/Delete), the `SpeakerTimelineStrip` from Task 14, and the segment-by-segment transcript in a `LazyColumn`. One real compile-time finding while verifying the brief's sketch against the actual Material3 API (BOM `2024.09.00`): `TopAppBar` is annotated `@ExperimentalMaterial3Api` in this BOM version and the file failed to compile without an opt-in — added `@OptIn(ExperimentalMaterial3Api::class)` on `SessionDetailScreen`, which the brief's code sample omitted. Everything else in the brief checked out on inspection: the rename dialog is a standard controlled-`TextField` pattern (`renameText` is set from `session?.title` at the moment the Rename menu item is clicked, before `showRenameDialog` flips true, so the dialog always pre-fills with the current title rather than a stale value); the `IconButton`+`DropdownMenu` pair as `TopAppBar` action siblings is the standard anchoring pattern (no wrapping `Box` needed); and the `Intent.ACTION_SEND` + `Intent.createChooser` share flow is correct as written since `LocalContext.current` inside a hosted Composable resolves to the Activity context, so no `FLAG_ACTIVITY_NEW_TASK` is needed. No new unit tests added — like Task 15's list screen, this task is Compose UI wiring over already-tested `SessionRepository`/`SpeakerTimelineStrip` logic with no new pure functions to isolate; full existing suite (14 tests) still passes and `:app:assembleDebug` succeeds.
**Plain:** You can now open a recorded session to see its full transcript, timeline strip, and speaker breakdown, and from there rename it, delete it, or share the transcript as plain text to any app.
**Why:** The list screen (Task 15) only shows an overview — this is the screen where you actually read what was said, fix a bad auto-generated title, get rid of a recording you don't want, or hand the transcript off to another app (like Notes or email) as plain text.

### [2026-07-26 20:20] Added

**Tech:** `app/src/main/java/com/andrecord/app/ui/list/{SessionListViewModel,SessionListScreen}.kt` — session list screen with record FAB and per-session speaker timeline strips
**Dev:** `segmentsBySession` uses `sessions.flatMapLatest { combine(sessionList.map { repository.observeSegments(it.id) }) { ... } }` rather than the design's original nested-`collect`/`channelFlow` sketch — that sketch had a real bug: nesting a non-completing inner `collect` inside an outer one means the outer lambda never returns, so a later emission from `sessions` (e.g. a newly created session) would never be observed. `flatMapLatest` correctly cancels and resubscribes the combined segments flow whenever the session list itself changes. Also fixed `SessionRow`'s root `Column` from `fillMaxSize()` to `fillMaxWidth()` — inside a `LazyColumn` item, `fillMaxSize()` would make each row greedily claim the full remaining scroll height instead of sizing to its content. Added `androidx.compose.material:material-icons-extended` (Icons.Filled.Mic/Stop live in the extended icon set, not the core one bundled with material3) and bumped Gradle's daemon heap (`org.gradle.jvmargs=-Xmx4096m`) after the default 512MB heap was thrashing/crashing the build on this larger dependency set.
**Plain:** You can now see your list of recorded sessions, each with a speaker timeline strip and a button to start or stop a new recording.
**Why:** This is the first screen you'll actually see and use — getting the list to correctly show new sessions as they're created (not just the ones that existed when the screen loaded) was worth fixing properly rather than shipping a subtly broken version.

### [2026-07-26 20:06] Added

**Tech:** `app/src/main/java/com/andrecord/app/ui/components/SpeakerTimelineStrip.kt` — pure `computeTimelineProportions()` function and `@Composable SpeakerTimelineStrip`; `app/src/test/java/com/andrecord/app/ui/components/TimelineProportionsTest.kt` — TDD unit tests
**Dev:** Built TDD (red: unresolved reference, green: 2/2 tests pass). `computeTimelineProportions()` merges adjacent same-speaker segments and computes each merged segment's fraction of total duration; handles empty input and any `TranscriptSegment` start/end timestamps by computing duration from first-to-last. `SpeakerTimelineStrip` renders as a horizontal 6dp strip with rounded corners, dividing space proportionally by speaker via `fillMaxWidth(fraction)`. Speaker color mapped via `speakerIndexFromLabel()` (strips "Speaker " prefix, converts to 0-indexed integer) and `speakerColorFor(index)`, falling back to `Ink600` for null/unparseable labels. Composed as Row of nested Rows to let each segment inherit its fraction-width constraint; tested via unit tests only (no Compose preview or instrumented test added).
**Plain:** The app now displays a visual timeline strip showing who spoke when in each meeting, with different colors for each speaker.
**Why:** A speaker timeline at a glance tells you the shape of the conversation — who dominated, where the handoffs were — without reading the whole transcript. It's the signature visual element that makes a speaker-aware recording feel like a real finished product.

### [2026-07-26 20:05] Added

**Tech:** `app/src/main/java/com/andrecord/app/ui/theme/{Color,Theme,Type}.kt`, `app/src/main/res/font/{space_grotesk,source_serif4,ibm_plex_mono}.ttf` — Compose `MaterialTheme` with bundled local font resources
**Dev:** An earlier attempt used Compose's downloadable Google Fonts provider (`GoogleFont.Provider` + a `font_certs.xml` array of Google's signing certificate hashes) so the font files themselves wouldn't need to ship in the APK. That was abandoned for two reasons: first, the cert byte values in that `font_certs.xml` would have had to be reproduced from memory with no way to verify them against the real thing, and a wrong value fails silently at runtime (fonts just don't load) rather than at build or lint time — exactly the kind of unverifiable fabrication that shouldn't ship. Second, and more fundamentally, the downloadable-fonts mechanism has Google Play Services fetch the font file over the network the first time it's used if it isn't already cached on-device — which would quietly reintroduce a network dependency into an app whose spec explicitly promises no `INTERNET` permission and no network access, ever (see the comment at `AndroidManifest.xml:9`). Replaced with the standard local-font-resource mechanism instead: three Regular-weight OFL-licensed `.ttf` files (Space Grotesk, Source Serif 4, IBM Plex Mono — all confirmed as `SIL Open Font License, Version 1.1` via each font's `OFL.txt`) downloaded directly from Google's `google/fonts` GitHub repo and placed under `res/font/`, referenced via plain `Font(R.font.xxx)` with no provider and no certs involved at all. Confirmed `ui-text-google-fonts` was never added to `app/build.gradle.kts`. Verified the three `.ttf` files are present inside the assembled APK (`res/font/*.ttf`) via `unzip -l`.
**Plain:** The app's visual style (a display font for headings, a serif for body text, a monospace for labels) now ships as font files bundled directly inside the app, instead of trying to download them from Google's servers the first time they're needed.
**Why:** Wanted the distinctive typography from the design spec without compromising the app's core privacy promise of truly never touching the network, and without needing to guess at security-sensitive certificate bytes that could silently fail.

### [2026-07-26 19:45] Added

**Tech:** `app/src/main/java/com/andrecord/app/triggers/KeyTriggerAccessibilityService.kt` (new `LongPressDetector` pure logic class + `KeyTriggerAccessibilityService`), `app/src/main/res/xml/accessibility_service_config.xml`, `AndroidManifest.xml`, `strings.xml` — system-wide long-press-Volume-Down trigger via `AccessibilityService.onKeyEvent()`
**Dev:** `LongPressDetector(holdMs, clock)` extracts the pure hold-duration math (`onKeyDown()`/`onKeyUp(): Boolean`) so the 1000ms threshold is unit-testable without Robolectric or a real service; built TDD (red: compile failure on missing class, green: 2/2 tests). The service only consumes (`return true`) the qualifying long-press `ACTION_UP`, so a normal tap's `ACTION_DOWN`/`ACTION_UP` pass through untouched and volume still adjusts normally — verified `android:canRequestFilterKeyEvents="true"` (→ `AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS`) and `onKeyEvent()` are the correct, non-deprecated mechanism for this. One deviation from the task brief's sketch: added `SupervisorJob()` to the service's `CoroutineScope` (matching the existing `RecordingService` pattern) plus an `onDestroy()` override that cancels it, so one failed `toggle()` call can't silently break every later volume-key trigger, and the coroutine scope doesn't outlive the service. Noted but left as specified: during a qualifying 1-second hold, the intermediate volume-key `ACTION_DOWN` repeats are not consumed, so the phone's volume may still audibly step down during the hold before the long-press is recognized and the final `ACTION_UP` is swallowed — a fully silent hold would require speculative consumption of every `ACTION_DOWN` plus manually replaying a normal single volume-step via `AudioManager` on a short tap, which is a materially larger change than the brief's scope and is left as a known limitation. Manual on-device verification (enabling Accessibility, physically holding Volume Down while locked) requires a physical Pixel 10 Pro Fold and could not be performed in this environment; no Android emulator/`adb` was available either, so only static verification (unit tests, `assembleDebug`, and confirming the service/config XML are correctly merged into the manifest and packaged into the APK) was possible.
**Plain:** You can now hold the Volume Down button for one second — even with the phone locked — to start or stop a recording, without needing to unlock the phone, tap the app, or use Quick Tap.
**Why:** Quick Tap (Task 11) only works on the small set of Pixel phones with that sensor; a volume-button hold works as a hardware-independent fallback trigger that's always within reach, including one-handed and through a pocket.

### [2026-07-26 19:30] Added

**Tech:** `app/src/main/res/xml/shortcuts.xml`, `app/src/main/java/com/andrecord/app/triggers/TriggerTrampolineActivity.kt`, `AndroidManifest.xml` — App Shortcut definition and invisible trampoline activity for Quick Tap gesture
**Dev:** `TriggerTrampolineActivity` extends `androidx.activity.ComponentActivity` (which implements `LifecycleOwner`, so `lifecycleScope` is available directly with no cast) with `setShowWhenLocked(true)`/`setTurnScreenOn(false)`, and calls `container.recordingController.toggle()` before finishing — never displays UI. Manifest registers it as `exported="true"` with `Theme.Translucent.NoTitleBar`, `excludeFromRecents="true"`, and `android:showOnLockScreen="true"`. Shortcut uses icon `@android:drawable/ic_btn_speak_now` with id `toggle_recording`.
**Plain:** You can now double-tap the back of a Pixel phone (via Quick Tap in Settings → Gestures) to instantly start/stop recording, even on the lock screen.
**Why:** Quick Tap is a powerful gesture on Pixel phones — integrating with it means recording can be triggered without unlocking the phone or opening the app, making it frictionless to capture ideas or meetings that pop up unexpectedly.

### [2026-07-26 19:15] Added

**Tech:** `app/src/main/java/com/andrecord/app/recording/RecordingService.kt` — foreground `Service` that captures mic audio via `AudioRecord`, streams it to `StreamingAsrEngine`, writes a WAV file, and enqueues `DiarizationWorker` on stop; `AndroidRecordingServiceStarter` added to `RecordingServiceStarter.kt`; `container.recordingController` assigned in `AndrecordApplication.onCreate()`, completing `AppContainer` wiring
**Dev:** Checks `RECORD_AUDIO` permission and free storage (50MB headroom) before starting capture, and marks the session `ERROR` (preserving whatever transcript was already flushed) rather than crashing on mid-recording mic-permission-revocation or storage exhaustion. Deviated from the plan's literal draft in one load-bearing way: the draft called `container.streamingAsrEngine.stop()` and released `AudioRecord` directly from `stopRecording()`'s caller thread while a separate `Dispatchers.Default` coroutine could still be mid-call on the same `AudioRecord`/sherpa-onnx `OnlineStream` objects — verified via an actual on-device instrumented run (`Pixel_7` AVD) to cause a real native SIGSEGV use-after-free crash inside `Java_com_k2fsa_sherpa_onnx_OnlineStream_acceptWaveform`, plus a second latent bug where `DiarizationWorker` could be enqueued before the WAV file was finalized/closed. Fixed by making the capture coroutine the sole owner of that teardown (signaled via a `@Volatile stopRequested` flag) and having `stopRecording()` join that coroutine before touching the file or enqueuing work. Also added the `androidx.test:rules` dependency (for `GrantPermissionRule`), not previously declared. Verified via `./gradlew :app:connectedDebugAndroidTest` on the same AVD across 3 runs post-fix: consistently clean pass, no crash, `DiarizationWorker` completes with `Result.success()`.
**Plain:** The app can now actually record a meeting: it captures your voice, transcribes it live, saves the audio, and hands it off for speaker detection when you stop — and it does this without crashing if you revoke mic permission or run out of storage mid-recording.
**Why:** This is the piece that makes recording real instead of just planned — and testing it for real (not just reading the code) caught a crash-on-stop bug that would have hit every single recording, so it was worth fixing now rather than shipping something that looked right on paper.

### [2026-07-26 18:55] Added

**Tech:** `app/src/main/java/com/andrecord/app/workers/DiarizationWorker.kt` — `CoroutineWorker` that aligns ASR transcript segments with speaker diarization results and posts a "transcript ready" notification
**Dev:** Extracts pure logic into `runDiarization()` companion function (testable without WorkManager boilerplate) that pulls pending ASR segments from `AppContainer`, diarizes the WAV via `DiarizationEngine`, aligns via `TranscriptAligner`, appends to repository, and finalizes session with speaker count. Notification includes duration in minutes and start time (e.g. "5-minute meeting transcript ready / Started 2:30 PM"). Retries up to 3 times; on exhaustion, finalizes soft-fail so session remains usable even if diarization fails.
**Plain:** When a recording stops, the app automatically runs speaker detection and combines it with the transcript in the background, then notifies you when it's ready.
**Why:** This is the final piece that turns recording into a complete, usable transcript with speakers labeled — wanted the notification to show useful info (length, start time) so you know which meeting it was without opening the app.

### [2026-07-26 18:50] Added

**Tech:** `app/src/main/java/com/andrecord/app/diarization/SherpaOnnxDiarizationEngine.kt` — implements `DiarizationEngine` using sherpa-onnx's `OfflineSpeakerDiarization` JNI API (pyannote segmentation-3.0 + ERes2Net English/VoxCeleb speaker embedding model); wired into `AndrecordApplication.onCreate()` via `container.diarizationEngine`
**Dev:** Real API differs from the original design sketch: `OfflineSpeakerDiarizationConfig` nests model paths three levels deep (`segmentation.pyannote.model`, `embedding.model`) rather than flat string fields, and `process()` takes only a sample array (no separate sample-rate arg) and returns the segment array directly rather than a wrapped result object. Models vendored to `app/src/main/assets/models/diarization/{segmentation,embedding}.onnx` (~31 MB). Verified on-device on a Pixel_7 AVD (arm64-v8a): a real two-speaker English test WAV (sherpa-onnx's own `1-two-speakers-en.wav` fixture) was correctly split into 4 segments across 2 distinct speakers.
**Plain:** The app can now tell apart who's speaking in a recording (Speaker 1 vs Speaker 2, etc.) using an on-device AI model, without needing internet access.
**Why:** A recording of a conversation is a lot less useful if you can't tell who said what — this is the piece that lets the transcript eventually get split up by speaker instead of being one undifferentiated wall of text.

### [2026-07-26 18:33] Added

**Tech:** `app/src/main/java/com/andrecord/app/asr/SherpaOnnxStreamingAsrEngine.kt` — implements `StreamingAsrEngine` using sherpa-onnx's `OnlineRecognizer` JNI API (streaming Zipformer2 English model); wired into `AndrecordApplication.onCreate()` via `container.streamingAsrEngine`
**Dev:** Real API required `OnlineModelConfig` wrapping the transducer config and carrying `tokens`/`modelType`, not the flat structure originally sketched. Vendored `app/libs/sherpa-onnx.aar` (v1.13.4) and the ASR model (~114MB total). Verified on a real Pixel_7 AVD (arm64-v8a): exact word-for-word match against sherpa-onnx's own reference transcript for a spoken test clip.
**Plain:** The app can now turn speech into live text as you record, entirely on the device.
**Why:** This is the core of the app — being able to see (and later save) what was said, without sending any audio off the phone.

### [2026-07-26 18:20] Fixed

**Tech:** `AndrecordApplication.kt` — implements `Configuration.Provider` for on-demand WorkManager initialization
**Dev:** A prior fix had wrapped `WorkManager.getInstance(this)` in a try/catch to survive Robolectric's ContentProvider-init race; that silently swallowed a real exception. Switching to on-demand init (the standard AndroidX pattern) fixes the root cause instead.
**Plain:** Fixed a hidden bug where background scheduling errors were being ignored instead of properly handled.
**Why:** Silently swallowed errors are the kind of thing that come back to bite you later — wanted this handled properly, not papered over.

### [2026-07-26 18:16] Added

**Tech:** `app/src/main/java/com/andrecord/app/workers/RetentionWorker.kt` — `CoroutineWorker` that deletes expired session audio via `SessionRepository.deleteExpiredAudio`; scheduled daily from `AndrecordApplication.onCreate()`
**Dev:** Uses `enqueueUniquePeriodicWork` with `ExistingPeriodicWorkPolicy.KEEP` so re-launching the app doesn't reset the schedule.
**Plain:** Meeting audio recordings now automatically get cleaned up after their retention window, without any manual action.
**Why:** Keeps old audio from piling up and eating storage, while still keeping the transcript forever.

### [2026-07-26 18:12] Added

**Tech:** `AndrecordApplication.kt` — introduces `AppContainer` (manual DI) with `sessionRepository`/`pendingAsrSegments` built eagerly and `streamingAsrEngine`/`diarizationEngine`/`recordingController` as `lateinit var`, assigned incrementally by later tasks
**Dev:** Registered `android:name=".AndrecordApplication"` in the manifest for the first time. This incremental-bootstrap shape exists specifically so every later task's code can compile against `(applicationContext as AndrecordApplication).container` from the moment it's written, instead of the whole module being uncompilable until one final wiring task.
**Plain:** Laid the foundation that lets all the app's pieces (recording, transcription, speaker detection) find and talk to each other.
**Why:** Wanted the app buildable and testable at every step along the way, not just at the very end.

### [2026-07-26 18:11] Added

**Tech:** `app/src/main/java/com/andrecord/app/recording/RecordingController.kt` — `IDLE`/`RECORDING` state machine with `toggle()`, used by every start/stop trigger
**Dev:** Single shared code path for in-app button, Quick Tap, and the volume-key trigger, so "what happens when you start/stop" only needs to be right once.
**Plain:** Added the shared on/off switch logic that every way of starting a recording will use.
**Why:** Wanted starting and stopping to behave identically no matter which trigger you use.

### [2026-07-26 18:08] Added

**Tech:** `app/src/main/java/com/andrecord/app/diarization/TranscriptAligner.kt` — merges ASR transcript segments with diarization speaker segments by timestamp overlap; also adds the `StreamingAsrEngine`/`DiarizationEngine` interfaces
**Dev:** Speaker label assigned by the segment with the greatest timestamp overlap (not first-match); no overlap yields a null label rather than a guess.
**Plain:** Added the logic that decides which spoken words belong to which speaker.
**Why:** This is what actually turns "a transcript" and "a list of speaker turns" into one coherent speaker-labeled document.

### [2026-07-26 18:05] Added

**Tech:** `app/src/main/java/com/andrecord/app/data/SessionRepository.kt` — wraps the Room DAOs with session-lifecycle operations (create, mark processing, finalize, error, rename, delete, retention cleanup)
**Dev:** Single source of truth for session state transitions, used by the recording service, the diarization worker, and the UI.
**Plain:** Added the central place that manages a meeting recording's lifecycle from start to finished transcript.
**Why:** Wanted one place responsible for "what happens to a session," so the recording, background processing, and screens all agree on its state.

### [2026-07-26 18:01] Fixed

**Tech:** `app/src/test/resources/robolectric.properties` — sets a module-wide Robolectric default SDK of 34
**Dev:** Robolectric 4.13 doesn't support this project's targetSdk 36; a per-test `@Config(sdk = [34])` workaround was replaced with this global default so future test classes don't need to repeat it.
**Plain:** Fixed the test setup so every future test automatically works around a testing-tool limitation, instead of needing a manual fix each time.
**Why:** Didn't want every future test to independently rediscover the same gotcha.

### [2026-07-26 17:57] Added

**Tech:** `app/src/main/java/com/andrecord/app/data/{Session,TranscriptSegment,SessionDao,TranscriptSegmentDao,AndrecordDatabase}.kt` — Room entities, DAOs, and database
**Dev:** `Session` tracks status (`RECORDING`/`PROCESSING`/`READY`/`ERROR`), speaker count, and audio retention deadline; `TranscriptSegment` cascades on session delete.
**Plain:** Added the local database that stores meeting sessions and their transcripts on the phone.
**Why:** Every saved meeting needs somewhere durable to live, with the right fields to support renaming, retention, and speaker counts later.

### [2026-07-26 17:51] Added

**Tech:** Gradle project scaffold — `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `AndroidManifest.xml`
**Dev:** `minSdk=34`/`targetSdk=36`, Compose enabled, Room/WorkManager/Material3-adaptive dependencies declared. Manifest deliberately has no `android:name` yet and no `INTERNET` permission (by design — this app is fully on-device).
**Plain:** Created the empty Andrecord Android project that everything else builds on top of.
**Why:** Every app needs a starting skeleton — this is that first buildable shell.
