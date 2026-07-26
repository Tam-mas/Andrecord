package com.andrecord.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.ui.theme.AndrecordColors
import com.andrecord.app.ui.theme.speakerColorFor

fun computeTimelineProportions(segments: List<TranscriptSegment>): List<Pair<String?, Float>> {
    if (segments.isEmpty()) return emptyList()
    val totalMs = (segments.last().endMs - segments.first().startMs).coerceAtLeast(1)

    val merged = mutableListOf<Pair<String?, Long>>()
    for (segment in segments) {
        val duration = segment.endMs - segment.startMs
        val last = merged.lastOrNull()
        if (last != null && last.first == segment.speakerLabel) {
            merged[merged.lastIndex] = last.first to (last.second + duration)
        } else {
            merged.add(segment.speakerLabel to duration)
        }
    }
    return merged.map { (label, duration) -> label to (duration.toFloat() / totalMs) }
}

private fun speakerIndexFromLabel(label: String?): Int =
    label?.removePrefix("Speaker ")?.trim()?.toIntOrNull()?.minus(1) ?: -1

@Composable
fun SpeakerTimelineStrip(segments: List<TranscriptSegment>, modifier: Modifier = Modifier) {
    val proportions = computeTimelineProportions(segments)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
    ) {
        // weight(), not fillMaxWidth(fraction): Row measures an unweighted child against the
        // *remaining* main-axis space, so fractional children compound-shrink (two 0.5s render as
        // 50% then 25% of what's left) and the strip never fills its width. weight() divides the
        // full width proportionally, which also self-normalizes fractions that don't sum to 1.0
        // -- as they don't here, since silence between utterances isn't attributed to anyone.
        // Zero-length segments are dropped because weight() rejects a non-positive weight.
        proportions.filter { (_, fraction) -> fraction > 0f }.forEach { (label, fraction) ->
            val index = speakerIndexFromLabel(label)
            val color = if (index >= 0) speakerColorFor(index) else AndrecordColors.Ink600
            Row(modifier = Modifier.weight(fraction).height(6.dp).background(color)) {}
        }
    }
}
