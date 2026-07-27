# Offline Transcript Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the saved transcript for every recording with a much more accurate offline pass (Whisper-small, English, run per diarization segment), and upgrade the diarization pipeline's speaker embedding model and clustering tuning, per `docs/superpowers/specs/2026-07-27-transcript-refinement-design.md`.

**Architecture:** Diarization runs first (as today, with an upgraded embedding model). Its speaker-turn segments become the chunk boundaries for a new offline Whisper pass — no separate VAD or alignment step needed, since each chunk is transcribed from audio that's already speaker-labeled by construction. `DiarizationWorker` is renamed `TranscriptionWorker` and its core logic is rewritten around this new flow. A new `ERROR`-with-retry path replaces the old soft-fail-to-`READY` behavior.

**Tech Stack:** Kotlin, sherpa-onnx (JNI, verified via `javap` against `app/libs/sherpa-onnx.aar`), Room, WorkManager (`CoroutineWorker`), Jetpack Compose, Robolectric + JUnit for tests.

## Global Constraints

- `TopAppBar` and other Material3 experimental APIs require `@OptIn(ExperimentalMaterial3Api::class)` (Compose BOM pinned to 2024.09.00).
- `NavigableListDetailPaneScaffold` is hardcoded to `ThreePaneScaffoldNavigator<Any>` in this project's `adaptive-navigation:1.0.0` — not relevant to this plan's screens, but do not "fix" it if encountered.
- Do NOT run `git add -A` or `git add .` when committing. A past incident committed 350MB+ stray `.hprof` files this way. Stage only the exact file paths named in each task.
- `gradle.properties` already sets `org.gradle.jvmargs=-Xmx4096m` to avoid Gradle daemon OOM with these bundled ONNX assets — do not lower it.
- Model binaries are committed directly into the git repo as regular files (the existing convention for `assets/models/asr/*.onnx` and `assets/models/diarization/*.onnx` — no Git LFS, no download script). Follow the same convention for new model assets.
- When wrapping a sherpa-onnx class, verify its real Kotlin API via `javap -p -classpath ~/.gradle/caches/8.9/transforms/3aa1593c1197a1a885bc1d4a520b11bd/transformed/sherpa-onnx-api.jar com.k2fsa.sherpa.onnx.<ClassName>` (or read the AAR's Kotlin sources if available) before writing code against it — never assume a field/constructor shape. This project's existing `SherpaOnnxDiarizationEngine.kt` and `SherpaOnnxStreamingAsrEngine.kt` document this verification in their own file header comments; match that citation style for any new sherpa-onnx wrapper class.

---

### Task 1: Upgrade diarization quality (embedding model + clustering tuning)

**Files:**
- Modify: `app/src/main/assets/models/diarization/embedding.onnx` (replaced wholesale)
- Modify: `app/src/main/java/com/andrecord/app/diarization/SherpaOnnxDiarizationEngine.kt`

**Interfaces:**
- Consumes: nothing new — `DiarizationEngine.diarize(wavFilePath: String): List<SpeakerSegment>` is unchanged in this task.
- Produces: no interface change. `SherpaOnnxDiarizationEngine` now uses a stronger embedding model and an explicit clustering config, verified via the confirmed `FastClusteringConfig(numClusters: Int, threshold: Float)` constructor (checked via `javap` — see Global Constraints).

This task swaps the diarization pipeline's speaker embedding model for a larger, higher-quality one, and explicitly configures clustering instead of leaving it at sherpa-onnx's silent default. No code outside this one file changes.

- [ ] **Step 1: Download and verify the NeMo TitaNet-Large embedding model**

Run:
```bash
curl -L -o /tmp/nemo_en_titanet_large.onnx "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_large.onnx"
ls -la /tmp/nemo_en_titanet_large.onnx
```
Expected: the file downloads successfully and is approximately 101,405,493 bytes (~97 MB). If the size is wildly different (e.g. a few KB — usually means the URL returned an HTML error page instead of the file), stop and investigate before proceeding; do not commit a bad file.

- [ ] **Step 2: Replace the embedding model asset**

```bash
cp /tmp/nemo_en_titanet_large.onnx app/src/main/assets/models/diarization/embedding.onnx
```

- [ ] **Step 3: Add explicit clustering config to `SherpaOnnxDiarizationEngine.kt`**

Current relevant section (inside the `diarizer` lazy property):
```kotlin
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = assetPath("models/diarization/segmentation.onnx"),
                ),
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = assetPath("models/diarization/embedding.onnx"),
                numThreads = 2,
            ),
        )
```

Replace with:
```kotlin
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = assetPath("models/diarization/segmentation.onnx"),
                ),
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                // Upgraded from a ~26 MB mid-tier embedding model to NeMo TitaNet-Large (~97 MB,
                // one of the strongest English speaker-verification models in sherpa-onnx's model
                // zoo), for better speaker discrimination. English-only, matching this app's scope.
                model = assetPath("models/diarization/embedding.onnx"),
                numThreads = 2,
            ),
            // Previously left unset, so this class relied on whichever default FastClusteringConfig's
            // own no-arg constructor uses internally (not independently verified here -- javap shows
            // constructor signatures, not default field values baked into bytecode). Passing this
            // explicitly removes that uncertainty going forward regardless of what the old default
            // was. -1 tells the clusterer to pick the speaker count automatically via the threshold
            // rather than assuming a fixed number, since Andrecord has no way to know how many people
            // are in a meeting ahead of time. 0.6 is a starting point intended to require a fairly
            // high similarity before merging two segments into one speaker, reducing false-merges of
            // two distinct (but similar-sounding) speakers now that the embedding model itself is more
            // discriminative -- treat as a starting point, not a verified-optimal value, and adjust
            // based on real-recording testing in Step 4.
            clustering = FastClusteringConfig(
                numClusters = -1,
                threshold = 0.6f,
            ),
        )
```

Add the import:
```kotlin
import com.k2fsa.sherpa.onnx.FastClusteringConfig
```

- [ ] **Step 4: Build and manually verify on-device**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

`SherpaOnnxDiarizationEngine` has no unit tests (a native JNI wrapper with no meaningful fake boundary — same as this project's existing convention for `SherpaOnnxStreamingAsrEngine`). Verify by installing on-device and recording a short multi-speaker test conversation once Task 6 (below) has wired the full pipeline together — there's no way to observe diarization's output in isolation before then, since nothing in the app surfaces raw `SpeakerSegment`s directly. Note this as a deferred verification: come back to this step's on-device check after Task 6 is done, since that's the first point the diarization output actually reaches the saved transcript.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/models/diarization/embedding.onnx app/src/main/java/com/andrecord/app/diarization/SherpaOnnxDiarizationEngine.kt
git commit -m "Upgrade diarization to NeMo TitaNet-Large embeddings with tuned clustering"
```

---

### Task 2: Vendor the Whisper-small.en (fp32) model assets

**Files:**
- Create: `app/src/main/assets/models/asr_offline/small.en-encoder.onnx`
- Create: `app/src/main/assets/models/asr_offline/small.en-decoder.onnx`
- Create: `app/src/main/assets/models/asr_offline/small.en-tokens.txt`

**Interfaces:**
- Consumes: nothing.
- Produces: asset paths `models/asr_offline/small.en-encoder.onnx`, `models/asr_offline/small.en-decoder.onnx`, `models/asr_offline/small.en-tokens.txt`, consumed by Task 4's `SherpaOnnxWhisperAsrEngine`.

Full precision (fp32), not int8-quantized, per the user's explicit priority on transcription quality over app size. This is a large download (~606 MB compressed) — expect it to take a few minutes depending on connection speed.

- [ ] **Step 1: Download and extract the Whisper-small.en release archive**

```bash
curl -L -o /tmp/sherpa-onnx-whisper-small.en.tar.bz2 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.en.tar.bz2"
ls -la /tmp/sherpa-onnx-whisper-small.en.tar.bz2
```
Expected: the file downloads successfully and is approximately 635,693,775 bytes (~606 MB). If the size is wildly different, stop and investigate before proceeding.

```bash
mkdir -p /tmp/whisper-small-en
tar xjf /tmp/sherpa-onnx-whisper-small.en.tar.bz2 -C /tmp/whisper-small-en
find /tmp/whisper-small-en -name "*.onnx" -o -name "*tokens*"
```
Expected output includes (exact directory name may include a version-numbered subfolder):
```
.../small.en-encoder.onnx
.../small.en-decoder.onnx
.../small.en-encoder.int8.onnx
.../small.en-decoder.int8.onnx
.../small.en-tokens.txt
```
Use the **non-int8** (full precision) `small.en-encoder.onnx` and `small.en-decoder.onnx` files, plus `small.en-tokens.txt`, in the next step. Ignore the `.int8.onnx` variants and the `test_wavs` directory — they are not needed.

- [ ] **Step 2: Copy the fp32 model files into the app's assets**

```bash
mkdir -p app/src/main/assets/models/asr_offline
cp /tmp/whisper-small-en/*/small.en-encoder.onnx app/src/main/assets/models/asr_offline/
cp /tmp/whisper-small-en/*/small.en-decoder.onnx app/src/main/assets/models/asr_offline/
cp /tmp/whisper-small-en/*/small.en-tokens.txt app/src/main/assets/models/asr_offline/
ls -la app/src/main/assets/models/asr_offline/
```
Expected: three files present, with `small.en-encoder.onnx` and `small.en-decoder.onnx` together accounting for the bulk of the ~606 MB (the int8 variants and test wavs in the extracted tarball are excluded, so the copied files should be noticeably less than the full tarball size, but still large — encoder is the larger of the two).

- [ ] **Step 3: Build to confirm the new assets don't break packaging**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (No code references these assets yet — this just confirms AAPT/the packaging step handles the new, large asset files without issue.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/models/asr_offline/small.en-encoder.onnx app/src/main/assets/models/asr_offline/small.en-decoder.onnx app/src/main/assets/models/asr_offline/small.en-tokens.txt
git commit -m "Vendor Whisper-small.en (fp32) model assets for offline transcript refinement"
```

---

### Task 3: `WhisperChunker`

**Files:**
- Create: `app/src/main/java/com/andrecord/app/asr/WhisperChunker.kt`
- Test: `app/src/test/java/com/andrecord/app/asr/WhisperChunkerTest.kt`

**Interfaces:**
- Consumes: `com.andrecord.app.diarization.SpeakerSegment` (existing: `data class SpeakerSegment(val startMs: Long, val endMs: Long, val speakerIndex: Int)`).
- Produces: `WhisperChunker.Chunk(startMs: Long, endMs: Long, speakerIndex: Int)` and `WhisperChunker.chunk(totalDurationMs: Long, segments: List<SpeakerSegment>): List<Chunk>`, consumed by Task 6's `TranscriptionWorker`.

Pure logic, no native dependencies — fully unit-testable. Turns diarization's speaker-turn segments into a list of chunks to feed to Whisper one at a time, padding each segment so words at its edges aren't clipped (clamped against the recording's bounds and against neighboring segments, so padding never eats into an adjacent speaker's audio), and sub-splitting any segment whose padded span exceeds Whisper's ~30-second usable window.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/andrecord/app/asr/WhisperChunkerTest.kt`:
```kotlin
package com.andrecord.app.asr

import com.andrecord.app.diarization.SpeakerSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperChunkerTest {

    @Test
    fun `one chunk per segment, padded on both sides`() {
        val segments = listOf(SpeakerSegment(startMs = 5000, endMs = 8000, speakerIndex = 0))

        val chunks = WhisperChunker.chunk(totalDurationMs = 20_000, segments = segments)

        assertEquals(1, chunks.size)
        assertEquals(WhisperChunker.Chunk(startMs = 4750, endMs = 8250, speakerIndex = 0), chunks[0])
    }

    @Test
    fun `padding clamps at the recording's start and end`() {
        val segments = listOf(SpeakerSegment(startMs = 0, endMs = 100, speakerIndex = 0))

        val chunks = WhisperChunker.chunk(totalDurationMs = 150, segments = segments)

        assertEquals(1, chunks.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(150L, chunks[0].endMs)
    }

    @Test
    fun `padding does not overlap into an adjacent segment's audio`() {
        // Two segments only 300ms apart -- less than double the 250ms padding margin, so naive
        // padding on both sides would overlap by 200ms if not clamped against the neighbor.
        val segments = listOf(
            SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0),
            SpeakerSegment(startMs = 5300, endMs = 10_000, speakerIndex = 1)
        )

        val chunks = WhisperChunker.chunk(totalDurationMs = 10_000, segments = segments)

        assertEquals(2, chunks.size)
        assertEquals(5150L, chunks[0].endMs)
        assertEquals(5150L, chunks[1].startMs)
    }

    @Test
    fun `a segment longer than the whisper window is sub-split into multiple chunks`() {
        // 35 seconds, padded to 35.25s -- longer than MAX_CHUNK_MS (28s), so it must split into two.
        val segments = listOf(SpeakerSegment(startMs = 0, endMs = 35_000, speakerIndex = 2))

        val chunks = WhisperChunker.chunk(totalDurationMs = 40_000, segments = segments)

        assertEquals(2, chunks.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(28_000L, chunks[0].endMs)
        assertEquals(2, chunks[0].speakerIndex)
        assertEquals(28_000L, chunks[1].startMs)
        assertEquals(35_250L, chunks[1].endMs)
        assertEquals(2, chunks[1].speakerIndex)
    }

    @Test
    fun `empty segment list produces no chunks`() {
        val chunks = WhisperChunker.chunk(totalDurationMs = 10_000, segments = emptyList())

        assertEquals(emptyList<WhisperChunker.Chunk>(), chunks)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*WhisperChunkerTest*'`
Expected: compilation failure (`WhisperChunker` does not exist yet).

- [ ] **Step 3: Implement `WhisperChunker`**

Create `app/src/main/java/com/andrecord/app/asr/WhisperChunker.kt`:
```kotlin
package com.andrecord.app.asr

import com.andrecord.app.diarization.SpeakerSegment

/**
 * Turns diarization's speaker-turn segments into the chunks fed to Whisper one at a time.
 *
 * sherpa-onnx's offline Whisper recognizer processes audio in a fixed ~30-second window per call
 * with no built-in long-form chunking (verified via `javap` against `OfflineWhisperModelConfig` /
 * `OfflineRecognizer` -- there is no streaming or automatic-windowing entry point). Rather than add
 * a separate VAD pass to find chunk boundaries, this reuses diarization's own segment boundaries:
 * each speaker turn becomes one chunk (sub-split if it's too long), so the resulting text is already
 * correctly speaker-attributed by construction -- no separate ASR-to-diarization alignment needed.
 */
object WhisperChunker {

    /** Padding added on each side of a segment so words at its edges aren't clipped. */
    const val PADDING_MS = 250L

    /** Kept comfortably under Whisper's ~30s window to leave headroom for the model's own
     *  internal padding/positional-embedding behavior. */
    const val MAX_CHUNK_MS = 28_000L

    data class Chunk(val startMs: Long, val endMs: Long, val speakerIndex: Int)

    fun chunk(totalDurationMs: Long, segments: List<SpeakerSegment>): List<Chunk> {
        val sorted = segments.sortedBy { it.startMs }
        val chunks = mutableListOf<Chunk>()

        for ((index, segment) in sorted.withIndex()) {
            val prev = sorted.getOrNull(index - 1)
            val next = sorted.getOrNull(index + 1)

            // Clamp against the MIDPOINT of the gap to each neighbor, not the neighbor's raw
            // start/end. Clamping against the raw endpoint only stops this segment's padding from
            // entering the neighbor's actual speech -- it does nothing to stop both segments'
            // padding from claiming the same territory when the gap between them is smaller than
            // 2x PADDING_MS. The midpoint is the furthest either side can go without the two
            // padded chunks overlapping each other.
            val leftBound = if (prev != null) (prev.endMs + segment.startMs) / 2 else 0L
            val rightBound = if (next != null) (segment.endMs + next.startMs) / 2 else totalDurationMs

            val paddedStart = (segment.startMs - PADDING_MS).coerceAtLeast(leftBound).coerceAtLeast(0L)
            val paddedEnd = (segment.endMs + PADDING_MS).coerceAtMost(rightBound).coerceAtMost(totalDurationMs)

            if (paddedStart >= paddedEnd) {
                // Padding was fully squeezed out by an adjacent segment or the recording's own
                // bounds -- fall back to the segment's own unpadded span rather than emit a
                // zero-or-negative-length chunk.
                chunks.add(Chunk(segment.startMs, segment.endMs, segment.speakerIndex))
                continue
            }

            var cursor = paddedStart
            while (cursor < paddedEnd) {
                val end = (cursor + MAX_CHUNK_MS).coerceAtMost(paddedEnd)
                chunks.add(Chunk(cursor, end, segment.speakerIndex))
                cursor = end
            }
        }

        return chunks
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*WhisperChunkerTest*'`
Expected: `BUILD SUCCESSFUL`, 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/asr/WhisperChunker.kt app/src/test/java/com/andrecord/app/asr/WhisperChunkerTest.kt
git commit -m "Add WhisperChunker to turn diarization segments into Whisper transcription chunks"
```

---

### Task 4: `OfflineAsrEngine` interface + `SherpaOnnxWhisperAsrEngine` + `WavFileReader`

**Files:**
- Create: `app/src/main/java/com/andrecord/app/asr/OfflineAsrEngine.kt`
- Create: `app/src/main/java/com/andrecord/app/asr/SherpaOnnxWhisperAsrEngine.kt`
- Create: `app/src/main/java/com/andrecord/app/asr/WavFileReader.kt`

**Interfaces:**
- Consumes: sherpa-onnx's `OfflineRecognizer`, `OfflineRecognizerConfig`, `OfflineModelConfig`, `OfflineWhisperModelConfig`, `FeatureConfig`, `OfflineStream`, `OfflineRecognizerResult`, `WaveReader`, `WaveData` (all verified via `javap` against `com.k2fsa.sherpa.onnx.*` — see field lists below). Asset paths from Task 2: `models/asr_offline/small.en-encoder.onnx`, `models/asr_offline/small.en-decoder.onnx`, `models/asr_offline/small.en-tokens.txt`.
- Produces: `OfflineAsrEngine.transcribe(samples: FloatArray, sampleRate: Int): String`, `WavFileReader.read(wavFilePath: String): WavSamples` and `data class WavSamples(val samples: FloatArray, val sampleRate: Int)`, `SherpaOnnxWavFileReader` (the real implementation) — all consumed by Task 6's `TranscriptionWorker`. `AppContainer.whisperAsrEngine: OfflineAsrEngine` (wired in Task 7).

`WavFileReader` exists specifically so `TranscriptionWorker`'s orchestration logic can be unit tested without touching native code. sherpa-onnx's `WaveReader.readWaveFromFile()` is a native JNI call (verified via `javap`: declared `native`) — it cannot run inside a plain-JVM Robolectric unit test (no Android device/emulator, and Robolectric does not provide a working native `.so` for arbitrary third-party JNI libraries). `SherpaOnnxDiarizationEngine` and `SherpaOnnxStreamingAsrEngine` never hit this problem because their native calls are already fully contained behind the `DiarizationEngine`/`StreamingAsrEngine` interfaces, which tests fake outright. `TranscriptionWorker` (Task 6) needs the raw decoded samples itself — not just an engine's derived output — in order to slice per-chunk audio for Whisper, so without this seam its test would need real native code to even construct fixture data.

Verified real API shapes (via `javap -p -classpath ~/.gradle/caches/8.9/transforms/3aa1593c1197a1a885bc1d4a520b11bd/transformed/sherpa-onnx-api.jar com.k2fsa.sherpa.onnx.<Name>`, cross-checked against this project's existing `SherpaOnnxStreamingAsrEngine.kt`'s citation of the same AAR):
- `OfflineWhisperModelConfig(encoder: String, decoder: String, language: String, task: String, tailPaddings: Int, enableTokenTimestamps: Boolean, enableSegmentTimestamps: Boolean)` — a secondary constructor with a `DefaultConstructorMarker` confirms all fields have defaults except (by Kotlin convention for this codebase's existing sherpa-onnx wrapper classes) the ones this task passes explicitly: `encoder`, `decoder`, `language`, `task`.
- `OfflineModelConfig` has a `whisper: OfflineWhisperModelConfig` field (alongside `transducer`, `paraformer`, and many other model-type fields this app doesn't use), plus `tokens: String`, `numThreads: Int`, `modelType: String`.
- `OfflineRecognizerConfig(featConfig: FeatureConfig, modelConfig: OfflineModelConfig, hr: HomophoneReplacerConfig, decodingMethod: String, maxActivePaths: Int, hotwordsFile: String, hotwordsScore: Float, ruleFsts: String, ruleFars: String, blankPenalty: Float)`.
- `OfflineRecognizer(assetManager: AssetManager, config: OfflineRecognizerConfig)` with `createStream(): OfflineStream`, `decode(stream: OfflineStream)`, `getResult(stream: OfflineStream): OfflineRecognizerResult`.
- `OfflineStream.acceptWaveform(samples: FloatArray, sampleRate: Int)`, `.release()`.
- `OfflineRecognizerResult.text: String`.
- `FeatureConfig(sampleRate: Int, featureDim: Int)` — same class already used by `SherpaOnnxStreamingAsrEngine`. Whisper models expect 80 mel-frequency bins, matching `FEATURE_DIM` already used for the streaming engine.
- `WaveReader.Companion.readWaveFromFile(path: String): WaveData` — declared `native`, confirming it cannot run outside a real Android runtime. Same class already used by `SherpaOnnxDiarizationEngine`.
- `WaveData(samples: FloatArray, sampleRate: Int)`.

- [ ] **Step 1: Create the `OfflineAsrEngine` interface**

Create `app/src/main/java/com/andrecord/app/asr/OfflineAsrEngine.kt`:
```kotlin
package com.andrecord.app.asr

interface OfflineAsrEngine {
    /** Transcribes one chunk of audio (expected to be no longer than Whisper's ~30s window --
     *  see [WhisperChunker]) to text. */
    fun transcribe(samples: FloatArray, sampleRate: Int): String
}
```

- [ ] **Step 2: Implement `SherpaOnnxWhisperAsrEngine`**

Create `app/src/main/java/com/andrecord/app/asr/SherpaOnnxWhisperAsrEngine.kt`:
```kotlin
package com.andrecord.app.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Offline ASR refinement engine backed by sherpa-onnx's real Kotlin/JNI API (package
 * com.k2fsa.sherpa.onnx, same AAR vendored for the streaming ASR and diarization engines,
 * verified via `javap -p` against sherpa-onnx-api.jar -- see this file's plan task for the full
 * verified field list). English-only (`language = "en"`), matching this app's scope; `task =
 * "transcribe"` (not "translate", which would convert non-English speech to English text --
 * irrelevant here, but the field is required regardless of language).
 */
class SherpaOnnxWhisperAsrEngine(private val context: Context) : OfflineAsrEngine {

    // Deferred: loading the Whisper encoder + decoder graphs is expensive, and this engine has no
    // start()/stop() lifecycle of its own -- same reasoning as SherpaOnnxDiarizationEngine's lazy
    // `diarizer` property.
    private val recognizer: OfflineRecognizer by lazy {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM,
            ),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = assetPath("models/asr_offline/small.en-encoder.onnx"),
                    decoder = assetPath("models/asr_offline/small.en-decoder.onnx"),
                    language = "en",
                    task = "transcribe",
                ),
                tokens = assetPath("models/asr_offline/small.en-tokens.txt"),
                modelType = "whisper",
                // Higher than the streaming engine's numThreads=2: this runs in the background
                // during post-recording processing (not competing with live capture for CPU), and
                // the user has prioritized transcription quality/thoroughness over processing
                // speed, so there's no reason to leave cores idle here.
                numThreads = 4,
            ),
        )
        OfflineRecognizer(assetManager = context.assets, config = config)
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /** sherpa-onnx resolves model paths relative to the AssetManager root, so the
     *  path is used as-is (no leading slash, no "assets/" prefix). */
    private fun assetPath(path: String) = path

    companion object {
        const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
    }
}
```

- [ ] **Step 3: Create `WavFileReader` and its real implementation**

Create `app/src/main/java/com/andrecord/app/asr/WavFileReader.kt`:
```kotlin
package com.andrecord.app.asr

import com.k2fsa.sherpa.onnx.WaveReader

data class WavSamples(val samples: FloatArray, val sampleRate: Int)

/**
 * Reads an entire WAV file's samples into memory. Exists as a thin, fakeable seam around
 * sherpa-onnx's [WaveReader] (a native JNI call -- verified via `javap` as `native`, so it cannot
 * run inside a plain-JVM Robolectric unit test) so [com.andrecord.app.workers.TranscriptionWorker]'s
 * chunk-slicing orchestration logic can be unit tested without touching native code, the same way
 * [com.andrecord.app.diarization.DiarizationEngine] and [OfflineAsrEngine] already are.
 */
fun interface WavFileReader {
    fun read(wavFilePath: String): WavSamples
}

class SherpaOnnxWavFileReader : WavFileReader {
    override fun read(wavFilePath: String): WavSamples {
        val wave = WaveReader.readWaveFromFile(wavFilePath)
        return WavSamples(wave.samples, wave.sampleRate)
    }
}
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

`SherpaOnnxWhisperAsrEngine` and `SherpaOnnxWavFileReader` are not unit tested — same as `SherpaOnnxDiarizationEngine`/`SherpaOnnxStreamingAsrEngine`, thin wrappers around native JNI calls with no meaningful fake boundary. `WavFileReader` the *interface* is what Task 6's tests fake — verified on-device once Task 6 wires the real implementations into the full pipeline.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/asr/OfflineAsrEngine.kt app/src/main/java/com/andrecord/app/asr/SherpaOnnxWhisperAsrEngine.kt app/src/main/java/com/andrecord/app/asr/WavFileReader.kt
git commit -m "Add OfflineAsrEngine, SherpaOnnxWhisperAsrEngine, and WavFileReader for offline transcript refinement"
```

---

### Task 5: `SessionRepository.markProcessingFailed()` and `retryProcessing()`

**Files:**
- Modify: `app/src/main/java/com/andrecord/app/data/SessionRepository.kt`
- Test: `app/src/test/java/com/andrecord/app/data/SessionRepositoryTest.kt`

**Interfaces:**
- Consumes: existing `Session` fields (`status`, `audioFilePath`, `durationMs`, `startTime`).
- Produces: `SessionRepository.markProcessingFailed(id: String)`, `SessionRepository.retryProcessing(id: String)`, both consumed by Task 6 (worker calls `markProcessingFailed`) and Task 8 (UI calls `retryProcessing`). A new mutable property `SessionRepository.transcriptionEnqueuer: (sessionId: String, wavFilePath: String, durationMs: Long, startTime: Long) -> Unit`, defaulted to a no-op — **not** a constructor parameter, deliberately: `SessionRepository`'s constructor is used with Kotlin's trailing-lambda syntax at 7 existing call sites (e.g. `SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { path -> ... }`, where the trailing `{ }` binds to `audioFileDeleter`). Trailing-lambda syntax always binds to the literal last constructor parameter, so adding any new parameter after `audioFileDeleter` — defaulted or not — would break every one of those 7 call sites by causing the trailing lambda to bind to the new parameter instead, leaving `audioFileDeleter` unfilled. A settable property, assigned after construction, avoids this entirely and matches this same file's own existing pattern for `AppContainer`'s `lateinit var diarizationEngine`/`recordingController` (constructed separately from `AppContainer` itself, then assigned once framework dependencies are available).

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/java/com/andrecord/app/data/SessionRepositoryTest.kt` (inside the `SessionRepositoryTest` class, alongside the existing tests):
```kotlin
    @Test
    fun `markProcessingFailed sets ERROR without touching the title`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 999_999L)
        val titleBefore = db.sessionDao().getById("s1")?.title

        repo.markProcessingFailed("s1")

        val session = db.sessionDao().getById("s1")
        assertEquals(SessionStatus.ERROR, session?.status)
        assertEquals(titleBefore, session?.title)
        db.close()
    }

    @Test
    fun `retryProcessing moves an errored session back to PROCESSING and re-enqueues`() = runTest {
        val enqueued = mutableListOf<Quadruple>()
        val (repo, db) = buildRepo()
        repo.transcriptionEnqueuer = { sessionId, wavFilePath, durationMs, startTime ->
            enqueued.add(Quadruple(sessionId, wavFilePath, durationMs, startTime))
        }
        repo.createSession("s1", startTime = 1000L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 999_999L)
        repo.markProcessingFailed("s1")

        repo.retryProcessing("s1")

        assertEquals(SessionStatus.PROCESSING, db.sessionDao().getById("s1")?.status)
        assertEquals(1, enqueued.size)
        assertEquals("s1", enqueued[0].sessionId)
        assertEquals("/audio/s1.wav", enqueued[0].wavFilePath)
        assertEquals(4000L, enqueued[0].durationMs)
        assertEquals(1000L, enqueued[0].startTime)
        db.close()
    }

    @Test
    fun `retryProcessing does nothing when the session has no audio file`() = runTest {
        val enqueued = mutableListOf<Quadruple>()
        val (repo, db) = buildRepo()
        repo.transcriptionEnqueuer = { sessionId, wavFilePath, durationMs, startTime ->
            enqueued.add(Quadruple(sessionId, wavFilePath, durationMs, startTime))
        }
        repo.createSession("s1", startTime = 1000L)
        // Never reached markProcessing, so audioFilePath/durationMs are still null -- mirrors a
        // session whose recording itself failed, which markProcessingFailed/retryProcessing should
        // never apply to since there is no audio to reprocess.
        repo.markError("s1", "some reason")

        repo.retryProcessing("s1")

        assertEquals(SessionStatus.ERROR, db.sessionDao().getById("s1")?.status)
        assertEquals(0, enqueued.size)
        db.close()
    }

    private data class Quadruple(val sessionId: String, val wavFilePath: String, val durationMs: Long, val startTime: Long)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SessionRepositoryTest*'`
Expected: compilation failure (`markProcessingFailed`/`retryProcessing`/`transcriptionEnqueuer` don't exist yet).

- [ ] **Step 3: Implement `markProcessingFailed` and `retryProcessing`**

In `app/src/main/java/com/andrecord/app/data/SessionRepository.kt`, the constructor itself is **unchanged** — add a new property instead, right after the class header:
```kotlin
class SessionRepository(
    private val sessionDao: SessionDao,
    private val transcriptSegmentDao: TranscriptSegmentDao,
    private val audioFileDeleter: (String) -> Unit
) {
    private val titleFormat = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US)

    /**
     * Re-enqueues transcription refinement for a retried session (see [retryProcessing]).
     * Deliberately a settable property, not a constructor parameter: this class's constructor is
     * called with Kotlin's trailing-lambda syntax at several existing call sites (e.g.
     * `SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { path -> ... }`, where the
     * trailing lambda binds to [audioFileDeleter]). Trailing-lambda syntax always binds to the
     * literal last constructor parameter, so adding any new parameter after [audioFileDeleter] --
     * defaulted or not -- would break every one of those call sites. A property assigned after
     * construction avoids that, matching how `AppContainer` already assigns
     * `diarizationEngine`/`recordingController` after constructing itself, once the Android
     * `Context` those need is available.
     */
    var transcriptionEnqueuer: (sessionId: String, wavFilePath: String, durationMs: Long, startTime: Long) -> Unit =
        { _, _, _, _ -> }
```

(The `titleFormat` line above is already present in the file — shown here only to anchor exactly where the new property goes, right after it. Do not duplicate it.)

Add these two methods (near `markError`, since they're all status-transition methods):
```kotlin
    /**
     * Sets [SessionStatus.ERROR] without mutating the title, unlike [markError]. [markError] is
     * only ever called on a session that never left [SessionStatus.RECORDING] (a mid-capture
     * failure or a process-death reconciliation sweep) -- a terminal, one-time failure with no
     * retry path, where baking a reason into the title makes sense. This method is for a session
     * that DID record successfully and has a real [Session.audioFilePath], but whose transcription
     * refinement failed after exhausting retries -- a state the user can retry from repeatedly via
     * [retryProcessing], so mutating the title here would stack up multiple reason suffixes on
     * every retry attempt that fails again.
     */
    suspend fun markProcessingFailed(id: String) {
        val session = sessionDao.getById(id) ?: return
        sessionDao.update(session.copy(status = SessionStatus.ERROR))
    }

    /**
     * Re-enqueues transcription refinement for a session left in [SessionStatus.ERROR] by
     * [markProcessingFailed]. A no-op if the session has no [Session.audioFilePath] or
     * [Session.durationMs] -- either it never recorded successfully in the first place (the
     * [markError] case above), or [audioFileDeleter] has since deleted the WAV after the 7-day
     * retention window, in which case there is nothing left to reprocess.
     */
    suspend fun retryProcessing(id: String) {
        val session = sessionDao.getById(id) ?: return
        val wavFilePath = session.audioFilePath ?: return
        val durationMs = session.durationMs ?: return
        sessionDao.update(session.copy(status = SessionStatus.PROCESSING))
        transcriptionEnqueuer(id, wavFilePath, durationMs, session.startTime)
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SessionRepositoryTest*'`
Expected: `BUILD SUCCESSFUL`, all tests passing (existing tests plus the 3 new ones).

- [ ] **Step 5: Run the full test suite to confirm no other call site broke**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: `BUILD SUCCESSFUL`. The constructor itself didn't change, so the other 6 test files and `AndrecordApplication.kt` that construct `SessionRepository` are unaffected — `transcriptionEnqueuer` simply keeps its no-op default for all of them.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/andrecord/app/data/SessionRepository.kt app/src/test/java/com/andrecord/app/data/SessionRepositoryTest.kt
git commit -m "Add SessionRepository.markProcessingFailed and retryProcessing"
```

---

### Task 6: Rewrite `DiarizationWorker` as `TranscriptionWorker`

**Files:**
- Delete: `app/src/main/java/com/andrecord/app/workers/DiarizationWorker.kt`
- Create: `app/src/main/java/com/andrecord/app/workers/TranscriptionWorker.kt`
- Delete: `app/src/test/java/com/andrecord/app/workers/DiarizationWorkerLogicTest.kt`
- Create: `app/src/test/java/com/andrecord/app/workers/TranscriptionWorkerLogicTest.kt`

**Interfaces:**
- Consumes: `DiarizationEngine.diarize(wavFilePath: String): List<SpeakerSegment>` (unchanged), `OfflineAsrEngine.transcribe(samples: FloatArray, sampleRate: Int): String` (Task 4), `WhisperChunker.chunk(totalDurationMs: Long, segments: List<SpeakerSegment>): List<WhisperChunker.Chunk>` (Task 3), `SessionRepository.replaceSegments`/`finalizeReady`/`markProcessingFailed` (existing + Task 5), `WavFileReader.read(wavFilePath: String): WavSamples` and `SherpaOnnxWavFileReader` (Task 4) — **not** sherpa-onnx's `WaveReader` directly, since that's a native call this task's tests cannot exercise (see Task 4's rationale for `WavFileReader`).
- Produces: `TranscriptionWorker.runTranscription(repository, diarizationEngine, asrEngine, wavFileReader, sessionId, wavFilePath): Int?` (companion function, consumed by the new test file), `TranscriptionWorker.KEY_SESSION_ID`/`KEY_WAV_PATH`/`KEY_DURATION_MS`/`KEY_START_TIME` (same names as `DiarizationWorker`'s, consumed by Task 7's enqueue helper), `TranscriptionWorker.enqueue(context: Context, sessionId: String, wavFilePath: String, durationMs: Long, startTime: Long)` (companion function, consumed by Task 7 and by `retryProcessing`'s wiring).

This is the core rewrite. The worker no longer reads RecordingService's flushed live-ASR segments as its transcription source (they're superseded entirely) — it diarizes the WAV, chunks the result via `WhisperChunker`, transcribes each chunk independently, and builds the final transcript directly from chunk boundaries + speaker index + Whisper text. A chunk whose transcription throws gets a placeholder segment instead of aborting the whole session; only diarization throwing, or every chunk failing, is treated as a worker-level failure.

RecordingService's periodic + final flush of unlabeled segments to Room during recording is **unchanged and out of scope** — those rows still serve as a live preview if a session's detail screen is opened while `PROCESSING`, and `replaceSegments()` still wholesale-replaces them once this worker finishes, exactly as before. This task only changes what produces the *replacement* segments.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/andrecord/app/workers/TranscriptionWorkerLogicTest.kt`:
```kotlin
package com.andrecord.app.workers

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.SessionStatus
import com.andrecord.app.asr.OfflineAsrEngine
import com.andrecord.app.asr.WavFileReader
import com.andrecord.app.asr.WavSamples
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.diarization.SpeakerSegment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranscriptionWorkerLogicTest {

    private class FakeDiarizationEngine(private val segments: List<SpeakerSegment>) : DiarizationEngine {
        var diarizeCalls = 0
        override fun diarize(wavFilePath: String): List<SpeakerSegment> {
            diarizeCalls++
            return segments
        }
    }

    /** Returns text keyed by call order (0-indexed); throws for any index in [throwOnCallIndex]. */
    private class FakeAsrEngine(
        private val textByCallIndex: List<String>,
        private val throwOnCallIndex: Set<Int> = emptySet()
    ) : OfflineAsrEngine {
        var callCount = 0
        override fun transcribe(samples: FloatArray, sampleRate: Int): String {
            val index = callCount
            callCount++
            if (index in throwOnCallIndex) throw RuntimeException("simulated transcription failure")
            return textByCallIndex[index]
        }
    }

    /** Synthetic silent samples of the given duration -- no real file I/O and no native code,
     *  since diarization/ASR are both faked and never actually run on the sample values. */
    private class FakeWavFileReader(private val durationMs: Long, private val sampleRate: Int = 16000) : WavFileReader {
        override fun read(wavFilePath: String): WavSamples =
            WavSamples(FloatArray(((durationMs * sampleRate) / 1000L).toInt()), sampleRate)
    }

    private fun buildDb(): AndrecordDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AndrecordDatabase::class.java
    ).allowMainThreadQueries().build()

    @Test
    fun `runTranscription builds segments directly from diarization and whisper output`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val diarization = FakeDiarizationEngine(listOf(SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0)))
        val asr = FakeAsrEngine(listOf("hello there"))

        val speakerCount = TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(5000L), "s1", "/audio/s1.wav")

        assertEquals(1, speakerCount)
        assertEquals(SessionStatus.READY, db.sessionDao().getById("s1")?.status)
        val segments = db.transcriptSegmentDao().getForSession("s1").first()
        assertEquals(1, segments.size)
        assertEquals("hello there", segments[0].text)
        assertEquals("Speaker 1", segments[0].speakerLabel)
        db.close()
    }

    @Test
    fun `a chunk that throws gets a placeholder and the rest still succeed`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 9000L, durationMs = 9000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        // Two segments far enough apart that padding produces exactly one chunk each.
        val diarization = FakeDiarizationEngine(
            listOf(
                SpeakerSegment(startMs = 0, endMs = 3000, speakerIndex = 0),
                SpeakerSegment(startMs = 4000, endMs = 9000, speakerIndex = 1)
            )
        )
        val asr = FakeAsrEngine(textByCallIndex = listOf("", "good to see you"), throwOnCallIndex = setOf(0))

        TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(9000L), "s1", "/audio/s1.wav")

        val segments = db.transcriptSegmentDao().getForSession("s1").first()
        assertEquals(2, segments.size)
        assertEquals("[transcription failed for this segment]", segments[0].text)
        assertEquals("Speaker 1", segments[0].speakerLabel)
        assertEquals("good to see you", segments[1].text)
        assertEquals("Speaker 2", segments[1].speakerLabel)
        assertEquals(SessionStatus.READY, db.sessionDao().getById("s1")?.status)
        db.close()
    }

    @Test
    fun `a blank whisper result is dropped rather than inserted as an empty segment`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val diarization = FakeDiarizationEngine(listOf(SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0)))
        val asr = FakeAsrEngine(listOf(""))

        TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(5000L), "s1", "/audio/s1.wav")

        assertTrue(db.transcriptSegmentDao().getForSession("s1").first().isEmpty())
        assertEquals(SessionStatus.READY, db.sessionDao().getById("s1")?.status)
        db.close()
    }

    @Test(expected = IllegalStateException::class)
    fun `when every chunk fails, runTranscription throws instead of finalizing`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val diarization = FakeDiarizationEngine(listOf(SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0)))
        val asr = FakeAsrEngine(textByCallIndex = listOf(""), throwOnCallIndex = setOf(0))

        try {
            TranscriptionWorker.runTranscription(repo, diarization, asr, FakeWavFileReader(5000L), "s1", "/audio/s1.wav")
        } finally {
            db.close()
        }
    }

    @Test
    fun `re-running runTranscription is idempotent and does not duplicate segments`() = runTest {
        val db = buildDb()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val diarization = FakeDiarizationEngine(listOf(SpeakerSegment(startMs = 0, endMs = 5000, speakerIndex = 0)))
        val wavFileReader = FakeWavFileReader(5000L)

        TranscriptionWorker.runTranscription(repo, diarization, FakeAsrEngine(listOf("first pass")), wavFileReader, "s1", "/audio/s1.wav")
        val afterFirstRun = db.transcriptSegmentDao().getForSession("s1").first()

        // Simulates a manual retry (Task 8) or a WorkManager retry re-running the same worker.
        TranscriptionWorker.runTranscription(repo, diarization, FakeAsrEngine(listOf("second pass")), wavFileReader, "s1", "/audio/s1.wav")
        val afterSecondRun = db.transcriptSegmentDao().getForSession("s1").first()

        assertEquals(1, afterFirstRun.size)
        assertEquals(1, afterSecondRun.size)
        assertEquals("second pass", afterSecondRun[0].text)
        assertEquals(2, diarization.diarizeCalls)
        db.close()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*TranscriptionWorkerLogicTest*'`
Expected: compilation failure (`TranscriptionWorker` doesn't exist yet).

- [ ] **Step 3: Delete the old worker and its test**

```bash
git rm app/src/main/java/com/andrecord/app/workers/DiarizationWorker.kt
git rm app/src/test/java/com/andrecord/app/workers/DiarizationWorkerLogicTest.kt
```

- [ ] **Step 4: Implement `TranscriptionWorker`**

Create `app/src/main/java/com/andrecord/app/workers/TranscriptionWorker.kt`:
```kotlin
package com.andrecord.app.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.asr.OfflineAsrEngine
import com.andrecord.app.asr.SherpaOnnxWavFileReader
import com.andrecord.app.asr.WavFileReader
import com.andrecord.app.asr.WhisperChunker
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.diarization.DiarizationEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val wavFilePath = inputData.getString(KEY_WAV_PATH) ?: return Result.failure()

        // Promote to a foreground-service-backed worker: diarization + per-chunk Whisper decoding
        // of a long meeting can run well past WorkManager's standard ~10 minute execution ceiling,
        // and being killed at that ceiling burns a retry attempt for no reason. Best-effort: if the
        // platform refuses the promotion, still attempt the work rather than failing outright.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            // Intentionally continue unpromoted; see above.
        }

        val container = (applicationContext as AndrecordApplication).container

        val speakerCount = try {
            runTranscription(
                container.sessionRepository,
                container.diarizationEngine,
                container.whisperAsrEngine,
                SherpaOnnxWavFileReader(),
                sessionId,
                wavFilePath
            )
        } catch (e: Exception) {
            null
        }

        if (speakerCount == null) {
            if (runAttemptCount >= MAX_ATTEMPTS) {
                container.sessionRepository.markProcessingFailed(sessionId)
                notifyFailed(sessionId)
                return Result.success()
            }
            return Result.retry()
        }

        notifyReady(sessionId)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL_ID, "Transcript processing", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Processing meeting transcript…")
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            PROGRESS_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun notifyReady(sessionId: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transcript ready", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val minutes = (inputData.getLong(KEY_DURATION_MS, 0L) / 60000L).toInt()
        val startedAt = SimpleDateFormat("h:mm a", Locale.US).format(Date(inputData.getLong(KEY_START_TIME, 0L)))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$minutes-minute meeting transcript ready")
            .setContentText("Started $startedAt")
            .setAutoCancel(true)
            .build()
        manager.notify(sessionId.hashCode(), notification)
    }

    private fun notifyFailed(sessionId: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transcript ready", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Transcript processing failed")
            .setContentText("Tap the session to retry")
            .setAutoCancel(true)
            .build()
        manager.notify(sessionId.hashCode(), notification)
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_WAV_PATH = "wav_path"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_START_TIME = "start_time"
        private const val CHANNEL_ID = "transcript_ready"
        private const val PROGRESS_CHANNEL_ID = "transcript_processing"
        private const val PROGRESS_NOTIFICATION_ID = 2
        private const val MAX_ATTEMPTS = 3
        private const val FAILURE_PLACEHOLDER = "[transcription failed for this segment]"

        /**
         * Diarizes the WAV, chunks the result via [WhisperChunker], transcribes each chunk
         * independently, and builds the session's transcript directly from chunk boundaries +
         * speaker index + Whisper text -- no separate ASR-to-diarization alignment step, since
         * each chunk is transcribed from audio that's already speaker-labeled by construction.
         *
         * A chunk whose transcription throws gets a [FAILURE_PLACEHOLDER] segment rather than
         * aborting the whole session -- a transcript with one visible gap is better than losing an
         * entire meeting's transcript to one bad audio slice. If every chunk fails, that's treated
         * as a systemic problem (not a one-off bad chunk) and this function throws, so the caller's
         * retry/[SessionRepository.markProcessingFailed] logic in [doWork] applies.
         *
         * `replaceSegments()`'s wholesale replace makes re-running this idempotent across
         * WorkManager retries and manual reprocessing alike.
         */
        suspend fun runTranscription(
            repository: SessionRepository,
            diarizationEngine: DiarizationEngine,
            asrEngine: OfflineAsrEngine,
            wavFileReader: WavFileReader,
            sessionId: String,
            wavFilePath: String
        ): Int? {
            val speakerSegments = diarizationEngine.diarize(wavFilePath)
            val speakerCount = speakerSegments.map { it.speakerIndex }.distinct().size

            val wave = wavFileReader.read(wavFilePath)
            val totalDurationMs = (wave.samples.size.toLong() * 1000L) / wave.sampleRate
            val chunks = WhisperChunker.chunk(totalDurationMs, speakerSegments)

            var successCount = 0
            val transcriptSegments = mutableListOf<TranscriptSegment>()
            for (chunk in chunks) {
                val chunkSamples = sliceSamples(wave.samples, wave.sampleRate, chunk.startMs, chunk.endMs)
                val text = try {
                    asrEngine.transcribe(chunkSamples, wave.sampleRate).also { successCount++ }
                } catch (e: Exception) {
                    FAILURE_PLACEHOLDER
                }
                if (text.isNotBlank()) {
                    transcriptSegments.add(
                        TranscriptSegment(
                            sessionId = sessionId,
                            startMs = chunk.startMs,
                            endMs = chunk.endMs,
                            speakerLabel = "Speaker ${chunk.speakerIndex + 1}",
                            text = text
                        )
                    )
                }
            }

            check(chunks.isEmpty() || successCount > 0) {
                "All ${chunks.size} chunks failed to transcribe for session $sessionId"
            }

            repository.replaceSegments(sessionId, transcriptSegments)
            repository.finalizeReady(sessionId, speakerCount)
            return speakerCount
        }

        private fun sliceSamples(samples: FloatArray, sampleRate: Int, startMs: Long, endMs: Long): FloatArray {
            val startIdx = ((startMs * sampleRate) / 1000L).toInt().coerceIn(0, samples.size)
            val endIdx = ((endMs * sampleRate) / 1000L).toInt().coerceIn(startIdx, samples.size)
            return samples.copyOfRange(startIdx, endIdx)
        }

        /** Builds and enqueues the [TranscriptionWorker] request. Shared by RecordingService's
         *  normal post-recording enqueue and by [SessionRepository.retryProcessing], so the
         *  WorkManager request-building code exists in exactly one place. */
        fun enqueue(context: Context, sessionId: String, wavFilePath: String, durationMs: Long, startTime: Long) {
            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_SESSION_ID, sessionId)
                        .putString(KEY_WAV_PATH, wavFilePath)
                        .putLong(KEY_DURATION_MS, durationMs)
                        .putLong(KEY_START_TIME, startTime)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*TranscriptionWorkerLogicTest*'`
Expected: `BUILD SUCCESSFUL`, all 5 tests passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/andrecord/app/workers/TranscriptionWorker.kt app/src/test/java/com/andrecord/app/workers/TranscriptionWorkerLogicTest.kt
git commit -m "Replace DiarizationWorker with TranscriptionWorker (diarization + per-chunk Whisper refinement)"
```

Note: this commit includes the `git rm` staged in Step 3 — `TranscriptionWorker.kt`/`TranscriptionWorkerLogicTest.kt` being new files and `DiarizationWorker.kt`/`DiarizationWorkerLogicTest.kt` being deleted are part of the same logical change and should land together.

---

### Task 7: Wire `AppContainer`, `AndrecordApplication`, and `RecordingService` to the new worker and engine

**Files:**
- Modify: `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`
- Modify: `app/src/main/java/com/andrecord/app/recording/RecordingService.kt`

**Interfaces:**
- Consumes: `SherpaOnnxWhisperAsrEngine` (Task 4), `TranscriptionWorker.enqueue`/`KEY_*` constants (Task 6), `SessionRepository.transcriptionEnqueuer` property (Task 5).
- Produces: `AppContainer.whisperAsrEngine: OfflineAsrEngine` (consumed by `TranscriptionWorker.doWork()`, already written in Task 6 to read `container.whisperAsrEngine`); a real `sessionRepository.transcriptionEnqueuer` wired to `TranscriptionWorker.enqueue`, which is what makes Task 8's `retryProcessing()` call actually re-enqueue work once that task is done.

No new tests — this task is pure wiring (constructor calls, one changed import, one changed enqueue call site). Verified by the build and by the full test suite still passing.

- [ ] **Step 1: Add `whisperAsrEngine` to `AppContainer` and wire the `transcriptionEnqueuer`**

In `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`, change the imports:
```kotlin
import com.andrecord.app.accessibility.AccessibilityServiceStatus
import com.andrecord.app.asr.OfflineAsrEngine
import com.andrecord.app.asr.SherpaOnnxStreamingAsrEngine
import com.andrecord.app.asr.SherpaOnnxWhisperAsrEngine
import com.andrecord.app.asr.StreamingAsrEngine
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.diarization.SherpaOnnxDiarizationEngine
import com.andrecord.app.recording.AndroidRecordingServiceStarter
import com.andrecord.app.recording.LiveTranscriptState
import com.andrecord.app.recording.RecordingController
import com.andrecord.app.settings.AppSettings
import com.andrecord.app.workers.RetentionWorker
import com.andrecord.app.workers.TranscriptionWorker
```

Change `AppContainer` — only the `lateinit var` list changes; `sessionRepository`'s construction is untouched (its constructor didn't change in Task 5):
```kotlin
class AppContainer(app: Application) {
    private val database = AndrecordDatabase.build(app)

    val sessionRepository = SessionRepository(
        database.sessionDao(),
        database.transcriptSegmentDao()
    ) { path -> File(path).delete() }

    val accessibilityServiceStatus = AccessibilityServiceStatus(app)
    val liveTranscriptState = LiveTranscriptState()
    val appSettings = AppSettings(app)

    lateinit var streamingAsrEngine: StreamingAsrEngine
    lateinit var diarizationEngine: DiarizationEngine
    lateinit var whisperAsrEngine: OfflineAsrEngine
    lateinit var recordingController: RecordingController
}
```

Change `AndrecordApplication.onCreate()` — adds the new engine assignment and, right after it, sets `sessionRepository.transcriptionEnqueuer` (the property Task 5 added), exactly the same way `diarizationEngine`/`recordingController` are assigned here rather than passed into `AppContainer`'s constructor:
```kotlin
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.streamingAsrEngine = SherpaOnnxStreamingAsrEngine(this)
        container.diarizationEngine = SherpaOnnxDiarizationEngine(this)
        container.whisperAsrEngine = SherpaOnnxWhisperAsrEngine(this)
        container.recordingController = RecordingController(container.sessionRepository, AndroidRecordingServiceStarter(this))
        container.sessionRepository.transcriptionEnqueuer = { sessionId, wavFilePath, durationMs, startTime ->
            TranscriptionWorker.enqueue(this, sessionId, wavFilePath, durationMs, startTime)
        }
        reconcileInterruptedSessions()
        scheduleRetention()
    }
```

- [ ] **Step 2: Point `RecordingService`'s post-recording enqueue at `TranscriptionWorker`**

In `app/src/main/java/com/andrecord/app/recording/RecordingService.kt`, change the import:
```kotlin
import com.andrecord.app.workers.TranscriptionWorker
```
(replacing `import com.andrecord.app.workers.DiarizationWorker`)

Find this block (the tail end of `stopRecording()`'s coroutine, right after `markProcessing`):
```kotlin
            val request = OneTimeWorkRequestBuilder<DiarizationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(DiarizationWorker.KEY_SESSION_ID, id)
                        .putString(DiarizationWorker.KEY_WAV_PATH, file.absolutePath)
                        .putLong(DiarizationWorker.KEY_DURATION_MS, durationMs)
                        .putLong(DiarizationWorker.KEY_START_TIME, startTime)
                        .build()
                )
                .build()
            WorkManager.getInstance(applicationContext).enqueue(request)
```

Replace with:
```kotlin
            TranscriptionWorker.enqueue(applicationContext, id, file.absolutePath, durationMs, startTime)
```

This now removes the need for `RecordingService.kt`'s own `OneTimeWorkRequestBuilder`/`Data`/`WorkManager` imports if they're not used elsewhere in the file — check with:
```bash
grep -n "OneTimeWorkRequestBuilder\|WorkManager\|Data\.Builder\|^import androidx.work" app/src/main/java/com/andrecord/app/recording/RecordingService.kt
```
If `OneTimeWorkRequestBuilder`, `Data`, and `WorkManager` (as bare identifiers, not just in imports) no longer appear anywhere else in the file, remove their now-unused imports (`androidx.work.Data`, `androidx.work.OneTimeWorkRequestBuilder`, `androidx.work.WorkManager`).

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/AndrecordApplication.kt app/src/main/java/com/andrecord/app/recording/RecordingService.kt
git commit -m "Wire AppContainer and RecordingService to TranscriptionWorker and the Whisper engine"
```

---

### Task 8: Add a "Retry" action for failed sessions

**Files:**
- Modify: `app/src/main/java/com/andrecord/app/ui/detail/SessionDetailViewModel.kt`
- Modify: `app/src/main/java/com/andrecord/app/ui/detail/SessionDetailScreen.kt`

**Interfaces:**
- Consumes: `SessionRepository.retryProcessing(id: String)` (Task 5), `Session.status`/`Session.audioFilePath` (existing).
- Produces: `SessionDetailViewModel.retry()`, called from the new UI affordance.

Shown whenever `status == SessionStatus.ERROR && audioFilePath != null` — the exact condition from the design spec that distinguishes "processing failed, audio exists, retry is possible" from "recording itself failed, nothing to retry."

- [ ] **Step 1: Add `retry()` to `SessionDetailViewModel`**

In `app/src/main/java/com/andrecord/app/ui/detail/SessionDetailViewModel.kt`, add this method (alongside `rename`/`delete`):
```kotlin
    fun retry() {
        viewModelScope.launch { repository.retryProcessing(sessionId) }
    }
```

- [ ] **Step 2: Add the "Retry" UI to `SessionDetailScreen`**

In `app/src/main/java/com/andrecord/app/ui/detail/SessionDetailScreen.kt`, add the import:
```kotlin
import androidx.compose.material3.Button
import com.andrecord.app.data.SessionStatus
```

Find this block (right after the `TopAppBar` in the `Scaffold`, at the top of the content `Column`):
```kotlin
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            session?.let { s ->
                Text(text = "${s.durationMs?.div(60000) ?: 0} min · ${s.speakerCount ?: 0} speakers",
                    style = MaterialTheme.typography.labelSmall)
                SpeakerTimelineStrip(segments = segments, modifier = Modifier.padding(vertical = 8.dp))
            }
```

Replace with:
```kotlin
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            session?.let { s ->
                if (s.status == SessionStatus.ERROR && s.audioFilePath != null) {
                    Text(
                        text = "Transcript processing failed",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(onClick = { viewModel.retry() }, modifier = Modifier.padding(vertical = 8.dp)) {
                        Text("Retry")
                    }
                } else {
                    Text(text = "${s.durationMs?.div(60000) ?: 0} min · ${s.speakerCount ?: 0} speakers",
                        style = MaterialTheme.typography.labelSmall)
                    SpeakerTimelineStrip(segments = segments, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

Not unit tested — this is a small, purely presentational Compose branch reading fields already covered by `SessionRepositoryTest`'s `retryProcessing` tests (Task 5) and `SessionDetailViewModel` has no other test file in this codebase to extend. Verified via on-device testing in Task 9's manual checklist.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/andrecord/app/ui/detail/SessionDetailViewModel.kt app/src/main/java/com/andrecord/app/ui/detail/SessionDetailScreen.kt
git commit -m "Add a Retry action to the session detail screen for failed transcript processing"
```

---

### Task 9: Remove `TranscriptAligner` and verify the full pipeline

**Files:**
- Delete: `app/src/main/java/com/andrecord/app/diarization/TranscriptAligner.kt`
- Delete: `app/src/test/java/com/andrecord/app/diarization/TranscriptAlignerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing — this is cleanup only.

`TranscriptAligner` has no callers left after Task 6 removed its only use in `DiarizationWorker`/`TranscriptionWorker`. Confirm before deleting.

- [ ] **Step 1: Confirm `TranscriptAligner` has no remaining callers**

Run:
```bash
grep -rln "TranscriptAligner" app/src/main app/src/test
```
Expected: only `app/src/main/java/com/andrecord/app/diarization/TranscriptAligner.kt` and `app/src/test/java/com/andrecord/app/diarization/TranscriptAlignerTest.kt` themselves — no other file references it. If anything else does, stop and investigate before deleting (do not delete a class still in use).

- [ ] **Step 2: Delete the file and its test**

```bash
git rm app/src/main/java/com/andrecord/app/diarization/TranscriptAligner.kt
git rm app/src/test/java/com/andrecord/app/diarization/TranscriptAlignerTest.kt
```

- [ ] **Step 3: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: `BUILD SUCCESSFUL`, all tests passing.

- [ ] **Step 4: Build the full debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git commit -m "Remove TranscriptAligner, superseded by per-chunk Whisper transcription"
```

(The `git rm` calls in Step 2 already staged the deletions — no `git add` needed here.)

- [ ] **Step 6: Manual on-device verification checklist**

This step has no automated test — record it as done once verified on a real device:

1. Record a real multi-speaker conversation of at least a couple of minutes (long enough to exercise `WhisperChunker`'s sub-splitting of a turn longer than 28s, if anyone talks that long uninterrupted — otherwise just confirms the normal per-turn chunking path).
2. Wait for the session to move from `PROCESSING` to `READY`. Confirm the transcript text is meaningfully more accurate than before this plan (punctuated, cased, fewer misheard words) and that speaker labels look right.
3. Force a failure to test the retry path: temporarily rename or corrupt the session's WAV file after it's marked `PROCESSING` but before the worker runs (e.g. `adb shell mv <path> <path>.bak` right after starting a stop), or otherwise induce 3 failed attempts. Confirm the session ends up `ERROR` with a "Transcript processing failed" notification, and that opening the session detail screen shows the "Retry" button. Restore the WAV, tap Retry, and confirm it successfully completes on the next attempt.
4. Confirm a session whose audio has already been deleted (older than 7 days, or manually null out `audioFilePath` for a test session) does **not** show a Retry button if it's in `ERROR` state.
5. Confirm the live view during recording is unaffected — this plan doesn't touch it.
