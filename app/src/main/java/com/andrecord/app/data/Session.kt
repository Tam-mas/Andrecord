package com.andrecord.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SessionStatus { RECORDING, PROCESSING, READY, ERROR }

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long?,
    val durationMs: Long?,
    val title: String,
    val status: SessionStatus,
    val speakerCount: Int?,
    val audioFilePath: String?,
    val audioDeleteAt: Long?,
    val processingProgressPercent: Int? = null,
    val processingEtaMillis: Long? = null
)
