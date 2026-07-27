package com.andrecord.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptSegmentDao {
    @Insert
    suspend fun insertAll(segments: List<TranscriptSegment>)

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY startMs ASC")
    fun getForSession(sessionId: String): Flow<List<TranscriptSegment>>

    /**
     * One-shot counterpart of [getForSession], for callers that need a snapshot rather than a
     * subscription -- notably TranscriptionWorker, which reads the segments RecordingService
     * already flushed to disk and cannot rely on a Flow it would have to collect-and-cancel.
     */
    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY startMs ASC")
    suspend fun getForSessionOnce(sessionId: String): List<TranscriptSegment>

    @Query("DELETE FROM transcript_segments WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    /**
     * Swaps a session's transcript for [segments] atomically.
     *
     * The delete and the insert must commit or roll back together. Separately they leave two ways
     * to lose a transcript with no error anywhere: a process death between them (exactly the
     * WorkManager-retry scenario this pipeline is built around) leaves the session with zero rows,
     * and the retry then "successfully" diarizes nothing and finalizes an empty session as READY;
     * and [getForSession] is a live Flow, so even without a crash a UI observer sees a transient
     * empty-list emission in the gap. Room's @Transaction wraps both calls in one SQLite
     * transaction, so neither is observable on its own.
     */
    @Transaction
    suspend fun replaceForSession(sessionId: String, segments: List<TranscriptSegment>) {
        deleteForSession(sessionId)
        if (segments.isNotEmpty()) insertAll(segments)
    }
}
