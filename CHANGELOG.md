# Changelog

### [2026-07-26 18:50] Added

**Tech:** `app/src/main/java/com/andrecord/app/diarization/SherpaOnnxDiarizationEngine.kt` — implements `DiarizationEngine` using sherpa-onnx's `OfflineSpeakerDiarization` JNI API (pyannote segmentation-3.0 + ERes2Net English/VoxCeleb speaker embedding model); wired into `AndrecordApplication.onCreate()` via `container.diarizationEngine`
**Dev:** Real API differs from the original design sketch: `OfflineSpeakerDiarizationConfig` nests model paths three levels deep (`segmentation.pyannote.model`, `embedding.model`) rather than flat string fields, and `process()` takes only a sample array (no separate sample-rate arg) and returns the segment array directly rather than a wrapped result object. Models vendored to `app/src/main/assets/models/diarization/{segmentation,embedding}.onnx` (~31 MB). Verified on-device on a Pixel_7 AVD (arm64-v8a): a real two-speaker English test WAV (sherpa-onnx's own `1-two-speakers-en.wav` fixture) was correctly split into 4 segments across 2 distinct speakers.
**Plain:** The app can now tell apart who's speaking in a recording (Speaker 1 vs Speaker 2, etc.) using an on-device AI model, without needing internet access.
**Why:** A recording of a conversation is a lot less useful if you can't tell who said what — this is the piece that lets the transcript eventually get split up by speaker instead of being one undifferentiated wall of text.
