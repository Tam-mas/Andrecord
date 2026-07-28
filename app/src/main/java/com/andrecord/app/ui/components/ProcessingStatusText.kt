package com.andrecord.app.ui.components

/**
 * Human-readable transcript processing status, e.g. "42% · ~2 min remaining". Both
 * [progressPercent] and [etaMillis] are null until TranscriptionWorker's per-chunk loop has
 * finished at least one chunk (diarization runs first, with no progress signal of its own), so a
 * session that's just entered PROCESSING shows a plain "Processing…" until then.
 */
fun formatProcessingStatus(progressPercent: Int?, etaMillis: Long?): String {
    if (progressPercent == null) return "Processing…"
    val etaText = etaMillis?.let { formatRemaining(it) }
    return if (etaText != null) "$progressPercent% · $etaText remaining" else "$progressPercent%"
}

private fun formatRemaining(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "~$minutes min" else "~${seconds.coerceAtLeast(1)}s"
}
