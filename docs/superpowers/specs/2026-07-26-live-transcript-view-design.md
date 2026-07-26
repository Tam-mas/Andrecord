# Live Transcript View — Design Spec (v1.1)

**Date:** 2026-07-26
**Status:** Approved for planning
**Builds on:** Andrecord v1 (already shipped — see `2026-07-26-andrecord-v1-design.md`)

## 1. Purpose

The original v1 design called for a live transcript view and a persistent recording indicator while recording (spec §3, §6), but the implementation plan never scheduled a task to build them — v1 only shows the transcript after you stop and diarization finishes. This spec closes that gap: a dedicated recording screen that streams transcript text live, plus a way to keep tabs on an in-progress recording from the session list.

## 2. Navigation flow

- Tapping the record FAB on the session list starts recording and navigates to a new **live recording screen**.
- From the recording screen, back navigation (system back or an explicit back control) returns to the session list; the recording keeps running in the background (the existing `RecordingService` foreground service is unaffected).
- While a recording is active, the session list shows a **persistent bar** (elapsed time + tap to reopen the live view) at the top, above the session rows.
- Stopping — from either the recording screen's stop control or the list's persistent bar — ends the recording and returns to the list, exactly as today's FAB stop behavior does.
- **Reopening the app while a recording is already active** (e.g. one started via Quick Tap or the volume-key hold while the app was closed) defaults to jumping straight to the live recording screen. A new **Settings screen** (see §6) lets this be changed to "show the session list instead" (with the persistent bar as the way back in).

## 3. Live transcript data flow

- `AppContainer` gains a small shared `LiveTranscriptState` — a `MutableStateFlow` carrying: the active session's finalized lines so far, the current in-progress partial line (if any, else null), and the recording's start time.
- `RecordingService` updates this state directly as it processes `AsrEvent.Partial`/`AsrEvent.Final` events during capture, in addition to its existing (unchanged) periodic flush of finalized lines to Room.
- **Partial text** replaces in place in the state (sherpa-onnx keeps revising it until its endpoint rule fires) — never appended.
- **Finalized lines** append permanently to the state's list, matching the same rows the existing flush already writes to Room.
- No speaker labels appear anywhere in the live view — diarization only runs after you stop, unchanged from v1's existing principle (spec §3 of the original design).
- **Elapsed time** is computed client-side from the stored start time via a simple ticking coroutine (once per second) wherever it's displayed (recording screen, persistent bar) — the service does not need to push per-second updates.
- When recording stops, `LiveTranscriptState` clears. The Room-backed finalized transcript (already durable throughout, per the recent duplication-bug fixes) is what the session detail screen shows afterward — entirely unchanged from today.
- This mirrors the existing pattern of shared state living on `AppContainer` (like `RecordingController`) rather than introducing a bound-service connection, which would be a larger structural change for no benefit here.

## 4. Recording screen

- A dedicated full-screen Compose destination, reached from the list's FAB and from the persistent bar's tap target.
- **Elapsed timer**: prominent, near the top, in the display font (Space Grotesk), e.g. "12:34".
- **Stop control**: directly below the timer, using the brass accent color, consistent with the app's existing "voice ledger" theme (`AndrecordColors`).
- **Transcript**: scrolls below the timer/stop area. Finalized lines render in the normal serif body style (`BodyFont`); the current partial line (if any) renders dimmed/italic beneath the finalized lines.
- Back navigation returns to the list without stopping the recording.

## 5. Persistent bar (session list)

- A compact card at the top of the session list's `LazyColumn` (same visual family as the existing accessibility-setup banner from the earlier v1.1 addition), shown only while a recording is active.
- Shows the elapsed time and is tappable to reopen the live recording screen.
- Coexists with the accessibility banner if both would otherwise show — the recording bar takes priority position (it's time-sensitive; the accessibility banner is not), i.e. recording bar first, accessibility banner below it if still applicable.

## 6. Settings screen

- A new, minimal screen with exactly one setting: **"When you open the app during an active recording"**, with two options — *Go to live view* (default) or *Show session list*.
- Persisted the same way the accessibility banner's dismissal state is (a small `SharedPreferences`-backed store) — reuse or closely mirror that existing mechanism rather than introducing a new persistence layer.
- Reached via a small settings icon on a new, lightweight `TopAppBar` added to the session list screen (the list screen currently has no top bar, only the FAB) — consistent with the overflow-menu pattern already used on the session detail screen (Task 16).

## 7. Testing

- `LiveTranscriptState`'s update logic (partial-replaces, final-appends) is pure and unit-testable independent of `RecordingService`, following this project's established pattern of extracting pure logic for testing (e.g. `TranscriptAligner`, `LongPressDetector`, `AccessibilityServiceStatus`'s component-matching logic).
- The elapsed-time-from-start-time computation is a pure function, testable the same way.
- The settings persistence (read/write the "reopen behavior" preference) follows the same testable-wrapper pattern as `AccessibilityServiceStatus`.
- Compose UI (recording screen layout, persistent bar, settings screen) is verified by build + on-device manual check, consistent with how Tasks 13-17 verified UI work in the original plan (no Robolectric Compose testing was used anywhere in this codebase).

## 8. Out of scope for this spec

- Editing the live transcript text while recording.
- Pausing (as distinct from stopping) a recording.
- Any change to how diarization, retention, or the existing triggers (Quick Tap, volume-key) work — this spec only adds a way to *watch* an already-working recording pipeline, not change how recording itself starts, stops, or gets processed.
