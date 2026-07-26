# Andrecord v1 — Design Spec

**Date:** 2026-07-26
**Status:** Approved for planning
**Target device:** Google Pixel 10 Pro Fold (personal use, single device)

## 1. Purpose

A personal Android app that records meetings, transcribes them live, identifies who
spoke when (diarization), and saves each meeting as a dated, speaker-labeled
transcript. Must be startable/stoppable from the app itself, from Pixel's Quick Tap
gesture, or from a hardware key — including while the phone is locked.

Fully on-device: no network permission, no cloud processing. Appropriate given
meeting audio is inherently sensitive, and a real privacy guarantee worth stating
explicitly (see §7).

**v2 (future, separate spec):** on-device Gemma (via MediaPipe LLM Inference API)
auto-generates a title/label and summary per session. Out of scope for this spec;
v1's data model is designed so v2 can hook in without touching the recording/
diarization pipeline.

## 2. Architecture

```
RecordingService (foreground service)
  - captures mic audio (AudioRecord, 16kHz mono PCM)
  - feeds sherpa-onnx streaming ASR for live transcript
  - writes audio to WAV file in parallel
  - posts lock-screen notification with Stop action
  - flushes transcript segments to DB periodically (crash resilience)
        |
        | on stop: finalize WAV, mark Session PROCESSING,
        | enqueue DiarizationWorker(sessionId)
        v
DiarizationWorker (WorkManager, one-shot, retry w/ backoff)
  - runs sherpa-onnx offline diarization (VAD + speaker embeddings + clustering,
    auto speaker count) over the full WAV file
  - aligns diarization segments to existing ASR segments by timestamp overlap
  - writes speaker labels + speakerCount onto the session
  - marks Session READY (or READY-without-labels if diarization exhausts retries)
  - fires local notification: "<duration>-minute meeting transcript ready —
    started <start time>"

SessionRepository (Room DB + app-private files)
  - single source of truth, read/written by RecordingService, DiarizationWorker,
    and the UI (via Flow)

TriggerTrampolineActivity
  - invisible, showWhenLocked=true, turnScreenOn=false
  - launched by a Quick-Tap-bound Android App Shortcut
  - toggles RecordingController (start if idle, stop if recording), finishes immediately

KeyTriggerAccessibilityService
  - canRequestFilterKeyEvents, intercepts KEYCODE_VOLUME_DOWN system-wide incl. locked
  - requires a 1-second hold to avoid accidental triggers on normal volume use
  - toggles the same RecordingController as the trampoline activity

RecordingController
  - shared start/stop/toggle logic used by both trigger paths and the in-app UI button,
    so there is exactly one code path for "what happens when you start/stop"
```

Rationale for Service + WorkManager + Repository (over one monolithic service):
diarization must survive the app backgrounding or the OS killing the service before
it finishes — WorkManager persists independently of the app process and retries
automatically, which a single long-lived service does not get for free.

## 3. Recording pipeline detail

- Audio: 16kHz mono PCM via `AudioRecord`, matching sherpa-onnx's streaming model input.
- ASR: sherpa-onnx `OnlineRecognizer`, streaming Zipformer, English-only model.
  Built-in endpoint detection segments speech into finalized, timestamped utterances
  vs. an in-progress partial hypothesis (this is what makes the live transcript
  "settle" line by line).
- Diarization: sherpa-onnx offline speaker diarization pipeline (VAD → speaker
  embedding extraction → clustering), auto-detects speaker count — no fixed
  speaker count is assumed, since meeting size varies.
- Alignment: diarization segments are matched to ASR segments purely by timestamp
  overlap (both already carry timestamps) — no re-transcription needed.
- Live transcript shows plain text only (no speaker labels) while recording;
  speaker labels appear once diarization finishes post-stop.

## 4. Triggers & lock-screen behavior

- **Quick Tap → App Shortcut → `TriggerTrampolineActivity`**: standard, low-permission
  integration. Transparent activity, runs above the keyguard without prompting an
  unlock, toggles recording, finishes with no visible UI.
- **Long-press Volume Down (1 second) → `KeyTriggerAccessibilityService`**: works
  system-wide including screen-off/locked. Requires one-time manual Accessibility
  permission grant (Android disallows silently enabling this) — app shows a
  one-time setup screen with a deep link to that settings page.
- **Feedback:** single vibration pulse on start, double pulse on stop (via
  `Vibrator`) — works with the screen off, no sound leaks into the room.
- If the Accessibility service is disabled or killed by the OS, Quick Tap still
  works independently — always at least one working trigger.

## 5. Data & storage

**Room schema:**

`Session`
- `id`, `startTime`, `endTime`, `durationMs`
- `title` (defaults to formatted date/time, e.g. "Jul 26, 2026, 2:15 PM"; user-editable)
- `status`: `RECORDING` / `PROCESSING` / `READY` / `ERROR`
- `speakerCount` (nullable until diarization finishes)
- `audioFilePath` (nullable — cleared once auto-deleted)
- `audioDeleteAt` (`endTime` + 7 days)

`TranscriptSegment`
- `id`, `sessionId` (FK, cascade delete), `startMs`, `endMs`,
  `speakerLabel` (nullable until diarization finishes), `text`

**Files:** WAV audio in app-private storage (`filesDir/audio/{sessionId}.wav`) —
private app data, not shared media, so no MediaStore involvement.

**Retention:** daily `WorkManager` periodic job deletes audio past `audioDeleteAt`,
clears `audioFilePath`, leaves transcript + metadata intact indefinitely. Audio is
a 1-week convenience window (re-processing, listening back); the transcript is the
durable artifact.

**Session actions:** view, rename (edits `title`), delete (cascades segments +
audio file if present), share (plain-text export, `[Speaker N] text...` per
segment, via standard Android Share Sheet, `ACTION_SEND` / `text/plain`).

## 6. UI design

**Direction:** "voice ledger" — a private recording tool should feel like quiet
studio equipment, not a chat app. Dark UI, one warm analog accent, and a signature
element that is actually functional: diarization rendered as a colored timeline
strip.

**Tokens**
- Color: `ink-900 #14181F` (background), `ink-600 #3A4150` (dividers/secondary
  text on dark), `paper-50 #F6F3EC` (primary text/surfaces), `brass-500 #C89B3C`
  (single UI accent — record button, active states). Speaker colors (functional,
  not decorative — same hues used in the transcript and the timeline strip):
  `teal #4FA3A0`, `rose #C97064`, `periwinkle #7C87C9`, `moss #7C9A5C`, assigned in
  order as speakers are detected.
- Type: **Space Grotesk** (display — screen titles, session dates, used
  restrained), **Source Serif 4** (body — transcript text; long-form speech reads
  better as serif prose than UI-sans), **IBM Plex Mono** (utility — timestamps,
  duration, speaker counts/labels). Bundled Google Fonts, no network fetch.
- Signature: a thin **speaker timeline strip** under each session's title in the
  list, and full-width at the top of the detail view — a horizontal bar segmented
  by who was talking when, in speaker colors. Shows meeting dynamics ("mostly me"
  vs. "balanced") at a glance, before opening the transcript.

**Layout — foldable-adaptive:** built with `androidx.window` +
Material3 adaptive `ListDetailPaneScaffold`, driven by `WindowSizeClass` rather
than manual hinge detection (the standard approach for foldables — one
implementation naturally covers both states below).

- *Cover screen (compact width):* single-pane session list, FAB to start
  recording, persistent recording bar (elapsed time + stop) shown while active.
  Tapping a session pushes a full-screen detail view.
- *Unfolded (expanded width):* two-pane list-detail, both visible simultaneously.
  Unfolding mid-read promotes the currently open detail view into the right pane
  automatically.

## 7. Permissions

`RECORD_AUDIO`, `POST_NOTIFICATIONS`, foreground service type `microphone`
(required Android 14+), `BIND_ACCESSIBILITY_SERVICE` (user-enabled manually, one
time). **No network permission** — stated explicitly since it's a real privacy
guarantee: transcripts and audio never leave the device.

## 8. Error handling

- Mic permission revoked mid-recording → service stops gracefully, session marked
  `ERROR` with a clear reason; partial transcript up to that point is preserved.
- Diarization worker fails repeatedly → after `WorkManager` retry/backoff is
  exhausted, session becomes `READY` without speaker labels rather than stuck in
  `PROCESSING` forever — you still get a usable plain transcript.
- Storage full → checked before starting a recording; if space runs out mid-session,
  stop cleanly and keep what was captured rather than crashing.
- Accessibility service disabled/killed by OS → Quick Tap trigger still works
  independently.

## 9. Testing

- Unit tests: `SessionRepository` (CRUD, retention-deletion logic, speaker-count
  aggregation), and the diarization-to-transcript alignment logic (given fixed ASR
  + diarization segments, verify correct merge). Pure logic, no device needed.
- Manual/instrumented testing on the actual Pixel 10 Pro Fold for what can't be
  unit-tested: foreground service surviving Doze/backgrounding, the
  AccessibilityService key interception while locked, the Quick Tap trampoline
  activity, and the foldable layout transition itself.

## 10. Tech stack

Kotlin + Jetpack Compose. sherpa-onnx via its official Kotlin/Java bindings.
`androidx.window` + Material3 adaptive libraries for foldable layout. Room for
persistence. WorkManager for background finalization and retention cleanup.

## 11. v2 outlook (not building yet)

A `LabelingWorker` runs after diarization finalizes, feeding the transcript to an
on-device Gemma model via MediaPipe's LLM Inference API, writing a generated
title + short summary onto the `Session` row (additive — doesn't replace the
user's ability to manually rename). Slots into the existing schema/architecture
without changes to the v1 recording/diarization pipeline. Separate spec when
picked up.
