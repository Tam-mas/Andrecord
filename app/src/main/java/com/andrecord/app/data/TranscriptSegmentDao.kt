package com.andrecord.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptSegmentDao {
    @Insert
    suspend fun insertAll(segments: List<TranscriptSegment>)

    @Query("SELECT * FROM transcript_segments WHERE sessionId = :sessionId ORDER BY startMs ASC")
    fun getForSession(sessionId: String): Flow<List<TranscriptSegment>>

    @Query("DELETE FROM transcript_segments WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}
