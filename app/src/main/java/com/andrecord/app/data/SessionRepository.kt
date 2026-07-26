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

    suspend fun appendSegments(segments: List<TranscriptSegment>) {
        if (segments.isEmpty()) return
        transcriptSegmentDao.insertAll(segments)
    }

    /**
     * Swaps a session's transcript for [segments]. Used by DiarizationWorker to replace the
     * unlabeled segments RecordingService flushed while recording with their speaker-labeled
     * equivalents -- without this, the labeled copies would simply be appended alongside the
     * unlabeled originals and every utterance would appear twice.
     *
     * Deliberately delete-then-insert rather than an update-in-place: alignment can legitimately
     * change the number of segments, and re-deriving the whole set keeps the operation idempotent
     * across WorkManager retries. The two halves run in a single Room transaction (see
     * [TranscriptSegmentDao.replaceForSession]) so the emptied-but-not-yet-refilled state is never
     * observable, by a retry after a crash or by a Flow subscriber.
     */
    suspend fun replaceSegments(sessionId: String, segments: List<TranscriptSegment>) {
        transcriptSegmentDao.replaceForSession(sessionId, segments)
    }

    suspend fun getSegmentsOnce(sessionId: String): List<TranscriptSegment> =
        transcriptSegmentDao.getForSessionOnce(sessionId)

    /**
     * Clears sessions left stranded in [SessionStatus.RECORDING] by a process death mid-recording
     * (OS kill, crash, force-stop). Nothing else ever transitions such a row out of RECORDING, so
     * without this sweep at startup it would stay "recording" forever with no way to clear it from
     * the UI. Safe to run repeatedly: a reconciled session is in ERROR and no longer matches.
     */
    suspend fun reconcileInterruptedSessions() {
        for (session in sessionDao.getByStatus(SessionStatus.RECORDING)) {
            markError(session.id, INTERRUPTED_REASON)
        }
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

    companion object {
        const val INTERRUPTED_REASON = "Interrupted — app was closed unexpectedly"
    }
}
