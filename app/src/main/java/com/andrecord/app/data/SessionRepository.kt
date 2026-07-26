package com.andrecord.app.data

import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionRepository(
    private val sessionDao: SessionDao,
    private val transcriptSegmentDao: TranscriptSegmentDao,
    private val audioFileDeleter: (String) -> Unit
) {
    private val titleFormat = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US)

    suspend fun createSession(id: String, startTime: Long): Session {
        val session = Session(
            id = id,
            startTime = startTime,
            endTime = null,
            durationMs = null,
            title = titleFormat.format(Date(startTime)),
            status = SessionStatus.RECORDING,
            speakerCount = null,
            audioFilePath = null,
            audioDeleteAt = null
        )
        sessionDao.insert(session)
        return session
    }

    suspend fun appendSegment(segment: TranscriptSegment) {
        transcriptSegmentDao.insertAll(listOf(segment))
    }

    suspend fun markProcessing(id: String, endTime: Long, durationMs: Long, audioFilePath: String, audioDeleteAt: Long) {
        val session = sessionDao.getById(id) ?: return
        sessionDao.update(
            session.copy(
                endTime = endTime,
                durationMs = durationMs,
                status = SessionStatus.PROCESSING,
                audioFilePath = audioFilePath,
                audioDeleteAt = audioDeleteAt
            )
        )
    }

    suspend fun finalizeReady(id: String, speakerCount: Int?) {
        val session = sessionDao.getById(id) ?: return
        sessionDao.update(session.copy(status = SessionStatus.READY, speakerCount = speakerCount))
    }

    suspend fun markError(id: String, reason: String) {
        val session = sessionDao.getById(id) ?: return
        sessionDao.update(session.copy(status = SessionStatus.ERROR, title = "${session.title} (${reason})"))
    }

    suspend fun rename(id: String, newTitle: String) {
        val session = sessionDao.getById(id) ?: return
        sessionDao.update(session.copy(title = newTitle))
    }

    suspend fun delete(id: String) {
        val session = sessionDao.getById(id) ?: return
        session.audioFilePath?.let(audioFileDeleter)
        sessionDao.deleteById(id)
    }

    suspend fun deleteExpiredAudio(now: Long) {
        for (session in sessionDao.getSessionsWithExpiredAudio(now)) {
            session.audioFilePath?.let(audioFileDeleter)
            sessionDao.update(session.copy(audioFilePath = null))
        }
    }

    fun observeSessions(): Flow<List<Session>> = sessionDao.getAll()

    fun observeSegments(sessionId: String): Flow<List<TranscriptSegment>> = transcriptSegmentDao.getForSession(sessionId)
}
