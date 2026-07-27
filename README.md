# Andrecord

A personal, fully offline meeting recorder for Android — records, transcribes, and diarizes
speech entirely on-device. Built for a Google Pixel 10 Pro Fold, but works on any device meeting
the requirements below.

No internet permission is requested anywhere in this app, on purpose. Every model — speech
recognition, speaker diarization, transcript refinement — runs locally via
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). Nothing you record ever leaves the device.

## What it does

- **Record** a meeting or conversation with one tap, from the app, a Quick Tap (back-tap)
  gesture, or a long-press on Volume Down — including while the phone is locked (see
  [Known limitations](#known-limitations) for where that last one currently doesn't work).
- **Live transcript**: while recording, see a real-time (if rough) transcript build on screen,
  with a persistent bar to jump back into it if you navigate away.
- **After the recording ends**, the app automatically:
  1. Runs speaker diarization on the full recording (who spoke, and when).
  2. Runs each speaker's turn through a much more accurate offline Whisper pass, replacing the
     rough live transcript with a properly punctuated, cased, per-speaker transcript.
- **Session list**: every recording is saved with a date/time title, duration, and speaker count.
  Rename, delete, or share a session's transcript as plain text.
- **Retry**: if processing fails for any reason, the session shows a clear "Retry" action instead
  of silently finalizing with an empty or partial transcript.
- **Foldable-aware UI**: the list/detail view adapts to a two-pane layout when the Fold is open,
  and to a single pane on the cover screen or a non-foldable phone.
- **7-day audio retention**: the raw WAV recording is kept for 7 days after a session ends (long
  enough to retry processing or fix mistakes), then automatically deleted. The transcript itself
  is kept forever.
- **v2 (not yet built)**: on-device Gemma-based auto-labeling/summarization of transcripts.

## Architecture

```
RecordingService (foreground service)
  ├─ AudioRecord capture (16kHz mono PCM)
  ├─ live streaming ASR  ──────────────► LiveTranscriptState (shown during recording)
  └─ periodic flush of live transcript segments to Room (durability / in-progress preview)
        │
        ▼  (on stop)
  TranscriptionWorker (WorkManager, foreground-promoted, serialized one-at-a-time)
  ├─ 1. Diarization on the full WAV (who spoke, when)
  ├─ 2. WhisperChunker turns each speaker turn into a Whisper-sized chunk
  │      (padded so words at edges aren't clipped; sub-split if a turn is longer than ~30s)
  ├─ 3. Each chunk transcribed independently via offline Whisper
  │      (a chunk that fails gets a visible placeholder, not a silently missing gap)
  └─ 4. Final transcript replaces the rough live version; session marked READY (or ERROR
         with a Retry option, if the whole thing failed)
```

The key design decision: diarization runs *first*, and its speaker-turn boundaries become the
chunk boundaries fed to Whisper. This means each chunk is already correctly speaker-labeled by
construction — there's no separate "guess which speaker said this ASR text" alignment step, and
no need for a voice-activity-detection pass to find chunk boundaries independently.

### Models used (all via sherpa-onnx, all bundled in the APK)

| Purpose | Model | Size |
|---|---|---|
| Live streaming transcript | zipformer2 transducer (LibriSpeech-trained, English) | ~70MB |
| Speaker diarization — segmentation | pyannote segmentation-3.0 | ~6MB |
| Speaker diarization — speaker embedding | NeMo TitaNet-Large (English) | ~97MB |
| Offline transcript refinement | Whisper-small.en, **int8-quantized** | ~112MB (encoder) + ~262MB (decoder) |

The live model is intentionally small and fast (low latency matters more than accuracy for a
live caption). It's also trained on LibriSpeech (clean audiobook narration), which is a poor
match for real multi-speaker meeting audio — that mismatch is exactly why the offline Whisper
refinement pass exists: it replaces the live model's rough output with something trained on
real-world conversational audio.

Whisper is int8-quantized rather than full precision specifically to keep native memory use and
APK size manageable — see [Known limitations](#known-limitations) for the numbers and reasoning.

### Key source packages

| Package | Responsibility |
|---|---|
| `recording/` | `RecordingService` (the foreground capture service), `RecordingController` (start/stop state machine), `LiveTranscriptState` |
| `asr/` | Streaming ASR engine (live), offline Whisper ASR engine (refinement), `WhisperChunker` |
| `diarization/` | Offline speaker diarization engine |
| `workers/` | `TranscriptionWorker` (post-recording processing), `RetentionWorker` (7-day WAV cleanup) |
| `triggers/` | Quick Tap shortcut trampoline, volume-key `AccessibilityService` |
| `data/` | Room entities/DAOs, `SessionRepository` |
| `ui/` | Compose screens: session list, session detail, live recording view, settings |

## Building and running

### Requirements

- Android Studio or the command-line Android SDK (`platform-tools`, an SDK platform matching
  `compileSdk`/`targetSdk` below).
- A device or emulator running **Android 14 (API 34) or later** (`minSdk = 34`).
- ~1GB of free disk space for the build (see [model files](#model-files-not-in-git) below).

```
compileSdk = 36
minSdk     = 34
targetSdk  = 36
```

### Model files (not in git)

Two of the bundled model files exceed GitHub's 100MB per-file push limit even after
int8-quantization, so they are **not tracked in this repository** — `.gitignore` explicitly
excludes them:

- `app/src/main/assets/models/asr_offline/small.en-encoder.onnx` (~112MB)
- `app/src/main/assets/models/asr_offline/small.en-decoder.onnx` (~262MB)

**The app will not build without these two files present.** On a fresh clone, you need to obtain
them yourself before building:

```bash
curl -L -o /tmp/sherpa-onnx-whisper-small.en.tar.bz2 \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.en.tar.bz2"
mkdir -p /tmp/whisper-small-en
tar xjf /tmp/sherpa-onnx-whisper-small.en.tar.bz2 -C /tmp/whisper-small-en

cp /tmp/whisper-small-en/*/small.en-encoder.int8.onnx \
   app/src/main/assets/models/asr_offline/small.en-encoder.onnx
cp /tmp/whisper-small-en/*/small.en-decoder.int8.onnx \
   app/src/main/assets/models/asr_offline/small.en-decoder.onnx
```

(Note the `.int8.onnx` source files map to plain `.onnx` destination filenames — the app's code
expects those exact names. `small.en-tokens.txt`, the third file the offline ASR engine needs,
**is** tracked in git and needs no action.)

Everything else — the sherpa-onnx `.aar`, the streaming ASR model, and both diarization models —
is small enough to be committed normally and needs no setup.

### Build and install

```bash
./gradlew installDebug
```

Or target a specific device explicitly if you have more than one connected:

```bash
ANDROID_SERIAL=<device-serial> ./gradlew installDebug
```

`adb devices` lists connected device serials. The Android SDK's `platform-tools` (containing
`adb`) needs to be on your `PATH`, or referenced by full path
(`$HOME/Library/Android/sdk/platform-tools/adb` on macOS with a default Android Studio install).

### First-run setup on-device

1. Grant the microphone permission when prompted on first launch.
2. Notifications need to be allowed (Android 13+) for the "recording in progress" and
   "transcript ready" notifications to show.
3. **For the volume-key trigger** specifically: Settings → Accessibility → find "Andrecord" under
   downloaded apps → enable it. The app has an in-app banner that deep-links here. (See
   [Known limitations](#known-limitations) — this may not actually work on your Android version.)
4. **For Quick Tap**: Settings → System → Gestures → Quick Tap (exact path varies by device) →
   assign Andrecord's "Start/stop recording" shortcut.

### Running tests

```bash
# Unit tests (JVM, Robolectric — no device needed)
./gradlew testDebugUnitTest

# Instrumented tests (need a real device or emulator — exercises the real native
# sherpa-onnx engines end-to-end, not fakes)
./gradlew connectedDebugAndroidTest
```

The instrumented suite includes an end-to-end test that runs the real streaming ASR, diarization,
and Whisper engines together against a bundled two-speaker fixture clip, confirming a
recording's transcript is produced correctly and that re-running processing (as a retry would)
reproduces the same result rather than duplicating it.

## Known limitations

**Hardware triggers don't work while the phone is locked, on this device's Android version.**
Both intended "start recording without unlocking" mechanisms are blocked by the platform on
Android 17 (this project's target device):

- **Volume-key long-press**: `AccessibilityService.onKeyEvent()` never fires, for any key,
  despite the service being correctly configured and bound (confirmed via extensive on-device
  diagnostics — this isn't a bug in this app's code). Root cause undetermined; several plausible
  platform-level restrictions were ruled out (restricted-settings app-ops, the OS's own
  volume-key accessibility shortcut) without finding the actual mechanism.
- **Quick Tap**: works normally while unlocked, but Android's own gesture service
  (`Columbus`) explicitly disables its sensor while the keyguard is showing
  (`Gated by KeyguardProximity`) — confirmed via logcat, not a guess.

Both trigger implementations are left in the app (they're harmless, and may work correctly on
other Android versions or a future OS update) — but on this specific device, **you need to
unlock the phone before either trigger will start a recording.**

**The debug APK is large (~700MB).** This is a deliberate tradeoff: Whisper's model weights are
stored uncompressed in the APK (`noCompress` for `.onnx` assets) so the OS never has to fully
decompress ~360MB into RAM before the ONNX runtime can read it. The alternative (deflate
compression) would produce a smaller APK but risk running out of memory at the moment a
transcription actually starts. Not an issue for a personal, sideloaded app; would need
reconsideration (e.g. Play Asset Delivery) before any kind of public distribution.

**Diarization has no chunking for very long recordings.** The offline diarization engine loads
the entire recording into memory as a single array (`android:largeHeap="true"` is set to
accommodate this). Multi-hour recordings are the practical ceiling and haven't been stress-tested.

**This repository's `origin` remote may lag behind the working tree.** The two large Whisper
model files were, at one point, briefly committed to git history and then removed via a full
history rewrite (`git filter-repo`) after they turned out to exceed GitHub's push size limit. If
you're working with a stale clone from before that rewrite, you'll need to re-clone rather than
pull — the commit hashes for everything on `master` changed.

## Design notes

- **"Voice ledger" visual style**: a dark, editorial aesthetic (`Ink900` background, `Brass500`
  accent, warm paper-white text) with locally-bundled OFL-licensed fonts (Space Grotesk for
  display, Source Serif 4 for transcript body text, IBM Plex Mono for timestamps/utility text) —
  chosen specifically to avoid Android's downloadable-fonts mechanism, which would otherwise
  require a network dependency this app deliberately doesn't have.
- **Speaker colors** are assigned cyclically from a fixed 4-color palette (teal, rose,
  periwinkle, moss) rather than generated per-session, so a given speaker index always reads the
  same color across the whole app.
- **Failure handling is deliberately visible, not silent.** Earlier iterations of the processing
  pipeline could finalize a session as "done" with an empty or partial transcript if something
  went wrong internally. Every such path was found and closed during development — a failed
  session now always surfaces as `ERROR` with a Retry action, never as a silently-incomplete
  `READY` session.
- **Subagent-driven development**: this app was built almost entirely through Claude Code's
  subagent-driven-development workflow — a fresh implementer subagent per task, followed by an
  independent reviewer subagent, with fix-and-re-review loops for anything found. Design specs
  and implementation plans for each feature are kept in `docs/superpowers/` for reference.

## License / provenance

Personal project, not published or licensed for redistribution. Built for one person's own use
on their own device. Uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0) and
its published pretrained models (Whisper, NeMo TitaNet, pyannote segmentation — see sherpa-onnx's
own documentation for each model's original license and provenance).
