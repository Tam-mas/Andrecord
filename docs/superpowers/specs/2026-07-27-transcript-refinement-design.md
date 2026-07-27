# Offline transcript refinement design

## 1. Purpose

Andrecord's live transcript uses a small streaming zipformer2 transducer model trained on LibriSpeech (audiobook-style read speech, ~500 word-piece vocabulary, all-caps output, no punctuation). That model is fast enough for real-time captions, but it performs poorly on real conversational meeting audio — multiple speakers, cross-talk, filler words, room acoustics, accents — none of which resemble LibriSpeech's clean single-speaker narration.

This feature adds a second, much more accurate offline pass that runs after each recording ends, replacing the saved transcript with dramatically better output. It also upgrades the diarization pipeline's speaker embedding model and clustering tuning, since diarization accuracy matters just as much as transcription accuracy to the end result.

The live view during recording is explicitly out of scope and unchanged — the user has stated that live-view quality and processing speed both matter far less than the accuracy of the final saved transcript and speaker labels.

## 2. Current state (for context)

- `RecordingService` captures audio, feeds it to the streaming ASR engine for the live view, and flushes unlabeled `TranscriptSegment` rows to Room as it goes.
- On stop, the session is marked `PROCESSING` and a `DiarizationWorker` is enqueued with the session id, WAV path, duration, and start time.
- `DiarizationWorker.runDiarization()`: reads the flushed unlabeled segments back from Room (the source of truth, since it survives process death), runs `SherpaOnnxDiarizationEngine.diarize()` on the full WAV (pyannote segmentation + a mid-tier speaker embedding model, sherpa-onnx's default clustering config, untouched), then calls `TranscriptAligner.align()` to assign a speaker label to each existing ASR segment by best-overlap with a diarization segment. The result replaces the session's transcript segments (`SessionRepository.replaceSegments`, transactional delete+insert) and the session is marked `READY`.
- On failure, `DiarizationWorker` retries up to `MAX_ATTEMPTS` (3) times, then soft-fails: the session is still marked `READY`, just with a `null` speaker count.
- `SessionStatus` is `RECORDING | PROCESSING | READY | ERROR`. `ERROR` is currently only ever reached from `RECORDING` (a mid-capture failure or a process-death reconciliation sweep at startup) — every existing `ERROR` session has a `null` `audioFilePath`, since `markError()` is always called before `markProcessing()` would have set one.
- The WAV file is retained for 7 days after the recording ends (`audioDeleteAt`), then deleted by `RetentionWorker`, which also nulls out `audioFilePath` on the session.

## 3. Architecture

**Diarization-first chunking.** Diarization already produces accurate speaker-turn boundaries with timestamps. sherpa-onnx's offline Whisper recognizer processes audio in a fixed ~30-second window per call with no built-in long-form chunking — so instead of introducing a separate chunking mechanism (a VAD pass, or fixed-size windows), this design reuses diarization's own segment boundaries as the unit of transcription:

1. Diarization runs first, exactly as today (with the upgraded embedding model — see §5).
2. Each diarization segment becomes one (or more, if longer than ~30s) transcription chunk.
3. Whisper transcribes each chunk independently. Because each chunk is already a single, already-labeled speaker turn, the resulting text is already correctly attributed — no separate ASR-to-diarization alignment step is needed for the refined path.

This was chosen over two alternatives:
- **Independent VAD-based chunking**, aligning Whisper's output against diarization afterward: requires bundling and integrating a whole additional VAD model, and reintroduces an alignment step — the exact complexity this design avoids — for no clear accuracy benefit over diarization's own (already accurate) segmentation.
- **Fixed-size windowing** (e.g. sequential 25–30s chunks regardless of speaker boundaries): frequently cuts mid-sentence and mid-speaker-turn, which hurts both Whisper's transcription quality (less coherent context per chunk) and speaker attribution (a single window can span multiple speakers, forcing exactly the alignment heuristic this design avoids).

## 4. Components

**New:**
- `OfflineAsrEngine` — interface with a single responsibility: transcribe one chunk of audio samples (≤30s) to text. Mirrors the existing `DiarizationEngine` interface pattern.
- `SherpaOnnxWhisperAsrEngine` — implementation backed by sherpa-onnx's `OfflineRecognizer` + `OfflineWhisperModelConfig`, using the bundled Whisper-small model assets, language pinned to English (`language = "en"`, `task = "transcribe"`).
- `WhisperChunker` — pure object/function, no native dependencies. Input: the full sample array plus the diarization engine's `List<SpeakerSegment>`. Output: an ordered list of chunks to transcribe, each carrying `startMs`, `endMs`, and `speakerIndex`. Behavior:
  - One chunk per diarization segment, padded by a small margin (~200–300ms) on each side so words at segment boundaries aren't clipped (clamped to the recording's actual bounds).
  - Any segment whose padded duration exceeds Whisper's usable window is sub-split into multiple sequential ≤30s chunks, all carrying the same `speakerIndex`.
- `SessionRepository.markProcessingFailed(id: String)` — sets `status = ERROR` without mutating the title (unlike `markError()`, which appends a reason string to the title — appropriate for a one-time recording failure, wrong for a state the user may retry from repeatedly, since repeated retries would otherwise stack up multiple reason suffixes).
- A "Retry" action in the UI (session detail screen at minimum; list screen optional), shown whenever `status == ERROR && audioFilePath != null`. Tapping it sets `status = PROCESSING` and re-enqueues the same worker using the session's own persisted `audioFilePath`, `durationMs`, and `startTime` — no new storage needed, since `markProcessing()` already persists all three on the `Session` row.

**Modified:**
- `DiarizationWorker` is renamed to `TranscriptionWorker`, since it now owns transcription refinement as well as diarization. `runDiarization()` (renamed `runTranscription()`) changes from "diarize → align existing ASR segments" to "diarize → chunk → transcribe each chunk → build segments directly." On exhausting `MAX_ATTEMPTS`, it calls `markProcessingFailed()` instead of soft-succeeding to `READY`, and posts a "Transcript processing failed — tap to retry" notification (new text, same notification pattern as the existing "ready" notification).
- `SherpaOnnxDiarizationEngine` — swap the bundled embedding model asset for the larger one (see §5) and pass an explicit `FastClusteringConfig` (see §5) instead of relying on sherpa-onnx's untouched defaults.

**Removed:**
- `TranscriptAligner` and `TranscriptAlignerTest` — confirmed to have no callers outside `DiarizationWorker` and its own test. The refined pipeline no longer needs overlap-based alignment, since Whisper transcribes audio that is already speaker-labeled by construction.

**Assets:**
- Whisper-small (English) encoder/decoder/tokenizer, bundled under `assets/models/asr_offline/` (exact filenames depend on the sherpa-onnx release chosen during implementation).
- `assets/models/diarization/embedding.onnx` replaced with NeMo TitaNet-Large (~97MB, English-focused — the strongest English speaker-verification model in sherpa-onnx's model zoo), same config slot, no code-path changes beyond the asset path and any model-specific config it requires.

## 5. Model and tuning choices

- **Whisper size: small.** The user has explicitly prioritized transcription and diarization quality over app size and processing speed; `small` is a meaningfully better English-transcription model than `tiny`/`base` at a size and speed cost that's acceptable given that priority.
- **Language: English only**, pinned via `OfflineWhisperModelConfig.language = "en"` — faster and more accurate than auto-detection for the user's exclusively-English meetings, and avoids occasional misdetection on short/quiet chunks.
- **Speaker embedding: NeMo TitaNet-Large**, replacing the current mid-tier (~26MB) model. Expected to meaningfully improve speaker discrimination (fewer merged-together distinct speakers, fewer false splits of one speaker into two).
- **Clustering**: explicitly configure `FastClusteringConfig`'s `threshold` (currently left at sherpa-onnx's silent default) — exact value to be tuned empirically during implementation against real recordings, since the right threshold depends on the embedding model's actual score distribution.
- No hard ceiling on added processing time per session; the user has confirmed this is acceptable given the quality priority. The existing foreground-service promotion (already in place because diarization alone can exceed WorkManager's ~10 minute execution ceiling on long meetings) covers the additional Whisper decode time without new infrastructure.

## 6. Data flow

1. Recording ends → `markProcessing()` (unchanged) → worker enqueued (unchanged).
2. Worker promotes to a foreground service (unchanged).
3. Worker runs diarization on the full WAV using the upgraded embedding model and tuned clustering → `List<SpeakerSegment>`.
4. `WhisperChunker` turns those segments into a list of chunks (padded, sub-split as needed).
5. For each chunk, in order: slice the corresponding samples out of the already-in-memory float array (loaded once by diarization's `WaveReader` — not re-read from disk), transcribe via `SherpaOnnxWhisperAsrEngine`. If a chunk's transcription throws, log it, insert a `[transcription failed for this segment]` placeholder `TranscriptSegment` in its place (so the gap is visible rather than looking like nothing was said), and continue with the remaining chunks — see §7 for the reasoning.
6. Build the final `List<TranscriptSegment>` directly from each successfully-transcribed chunk's `startMs`/`endMs`/`speakerIndex`/text. Chunks whose Whisper output is blank (e.g. a diarization segment that was actually silence/noise) are dropped rather than inserted as empty rows.
7. `replaceSegments()` + `finalizeReady(speakerCount)` — unchanged.
8. If diarization itself throws, or every chunk fails (a systemic problem — model load failure, OOM — rather than a one-off bad chunk), the worker retries up to `MAX_ATTEMPTS` times via WorkManager's normal `Result.retry()` mechanism; on final exhaustion, `markProcessingFailed()` is called and a failure notification is posted instead of soft-succeeding to `READY`.
9. Manual retry (tapping "Retry" in the UI): sets `status = PROCESSING`, re-enqueues the same worker with the session's persisted `audioFilePath`/`durationMs`/`startTime`. The pipeline re-runs from scratch — `replaceSegments()`'s wholesale replace makes this naturally idempotent, same property the existing diarization retry path already relies on. No limit on manual retry attempts; each one is a deliberate user action, not an automated loop. Retry stops being offered once `audioFilePath` is `null` (either because the session never had one, or because `RetentionWorker` has since deleted the WAV — you cannot reprocess audio that no longer exists).

## 7. Error handling

- **Per-chunk failures are non-fatal.** If Whisper throws on one chunk out of many (a specific bad audio slice, a native-layer hiccup), the worker skips that chunk and continues — a transcript with one gap is better than losing an entire meeting's transcript to one bad segment. Only a failure at the diarization step, or a pattern indicating a systemic problem (e.g. every chunk failing), escalates to the worker-level retry/failure path in §6 step 8.
- **Terminal failure is a distinct, user-actionable state**, not a silent degradation. The current soft-fail-to-`READY`-with-null-speaker-count behavior is removed entirely by this design: any failure severe enough to exhaust `MAX_ATTEMPTS` (diarization throwing, or every chunk failing) now surfaces as `ERROR` with a clear retry path, rather than a `READY` session that silently has no or few transcript segments.
- **Reprocessing is bounded by audio retention.** The 7-day WAV retention window is unchanged; once the file is deleted, `audioFilePath` is `null` and retry is no longer offered, which is the correct behavior (there is nothing left to reprocess).

## 8. Testing

- `WhisperChunker`: fully unit-tested, no native dependencies. Covers one-segment-per-chunk, padding at segment edges (including clamping at the recording's start/end), sub-splitting a turn longer than the ~30s window, and adjacent-segment padding not overlapping into a neighboring speaker's audio.
- `SherpaOnnxWhisperAsrEngine`: not unit tested, same as the existing `SherpaOnnxDiarizationEngine`/`SherpaOnnxStreamingAsrEngine` — a thin wrapper around a native JNI call with no meaningful fake boundary; verified by on-device testing instead.
- Worker orchestration logic: extends the existing `DiarizationWorkerLogicTest` pattern (Robolectric + in-memory Room DB + a fake `OfflineAsrEngine`). New cases cover: chunks built and transcribed correctly end-to-end, a fake engine configured to throw on a specific chunk index resulting in that chunk skipped but the rest present, exhausting retries resulting in `markProcessingFailed()` rather than a soft `READY`, and a simulated manual retry re-running cleanly (idempotent, matching the existing re-run test for diarization).
- `SessionRepository.markProcessingFailed()`: tested the same way existing repository methods (`markError`, `markProcessing`) already are.

## 9. Out of scope

- No change to the live view, its ASR engine, or its decoding method (beam search was already implemented separately from this design).
- No new VAD engine or model.
- No support for languages other than English.
- No UI for granular per-chunk processing progress (e.g. "12 of 40 segments transcribed") — the existing `PROCESSING` status and "Processing meeting transcript…" notification are considered sufficient, given the user's stated priority is final quality over UX polish during processing.
- No automated limit on manual retry attempts.
