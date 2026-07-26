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
        proportions.forEach { (label, fraction) ->
            val index = speakerIndexFromLabel(label)
            val color = if (index >= 0) speakerColorFor(index) else AndrecordColors.Ink600
            Row(modifier = Modifier.fillMaxWidth(fraction).height(6.dp).background(color)) {}
        }
    }
}
