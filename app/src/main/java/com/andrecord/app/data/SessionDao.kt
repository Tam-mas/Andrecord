package com.andrecord.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: Session)

    @Update
    suspend fun update(session: Session)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): Session?

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun getAll(): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    suspend fun getAllOnce(): List<Session>

    @Query("SELECT * FROM sessions WHERE status = :status")
    suspend fun getByStatus(status: SessionStatus): List<Session>

    @Query("SELECT * FROM sessions WHERE audioFilePath IS NOT NULL AND audioDeleteAt IS NOT NULL AND audioDeleteAt < :now")
    suspend fun getSessionsWithExpiredAudio(now: Long): List<Session>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}
