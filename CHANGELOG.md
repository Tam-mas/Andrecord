# Changelog

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
