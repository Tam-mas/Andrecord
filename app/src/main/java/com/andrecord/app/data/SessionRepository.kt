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

    /**
     * Re-enqueues transcription refinement for a retried session (see [retryProcessing]).
     * Deliberately a settable property, not a constructor parameter: this class's constructor is
     * called with Kotlin's trailing-lambda syntax at several existing call sites (e.g.
     * `SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { path -> ... }`, where the
     * trailing lambda binds to [audioFileDeleter]). Trailing-lambda syntax always binds to the
     * literal last constructor parameter, so adding any new parameter after [audioFileDeleter] --
     * defaulted or not -- would break every one of those call sites. A property assigned after
     * construction avoids that, matching how `AppContainer` already assigns
     * `diarizationEngine`/`recordingController` after constructing itself, once the Android
     * `Context` those need is available.
     */
    var transcriptionEnqueuer: (sessionId: String, wavFilePath: String, durationMs: Long, startTime: Long) -> Unit =
        { _, _, _, _ -> }

    /**
     * [calendarName], when present, means this session was started by the calendar auto-record
     * feature (see CalendarAlarmReceiver) rather than a manual trigger; it's appended to the
     * title so a calendar-triggered session is distinguishable in the session list.
     */
    suspend fun createSession(id: String, startTime: Long, calendarName: String? = null): Session {
        val baseTitle = titleFormat.format(Date(startTime))
        val session = Session(
            id = id,
            startTime = startTime,
            endTime = null,
            durationMs = null,
            title = if (calendarName != null) "$baseTitle — $calendarName" else baseTitle,
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
     * Swaps a session's transcript for [segments]. Used by TranscriptionWorker to replace the
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
                audioDeleteAt = audioDeleteAt,
                processingProgressPercent = null,
                processingEtaMillis = null
            )
        )
    }

    /**
     * Live progress updates from [com.andrecord.app.workers.TranscriptionWorker]'s per-chunk
     * transcription loop -- both are rough estimates (percent complete by chunk count, and an
     * ETA extrapolated from the average time per chunk so far), not precise measurements. A no-op
     * if the session has since been deleted or otherwise raced past PROCESSING.
     */
    suspend fun updateProcessingProgress(id: String, progressPercent: Int, etaMillis: Long?) {
        val session = sessionDao.getById(id) ?: return
        sessionDao.update(
            session.copy(processingProgressPercent = progressPercent, processingEtaMillis = etaMillis)
        )
    }

    suspend fun finalizeReady(id: String, speakerCount: Int?) {
        val session = sessionDao.getById(id) ?: return
        sessionDao.update(
            session.copy(
                status = SessionStatus.READY,
                speakerCount = speakerCount,
                processingProgressPercent = null,
                processingEtaMillis = null
            )
        )
    }

    suspend fun markError(id: String, reason: String) {
        val session = sessionDao.getById(id) ?: return
        sessionDao.update(session.copy(status = SessionStatus.ERROR, title = "${session.title} (${reason})"))
    }

    /**
     * Sets [SessionStatus.ERROR] without mutating the title, unlike [markError]. [markError] is
     * only ever called on a session that never left [SessionStatus.RECORDING] (a mid-capture
     * failure or a process-death reconciliation sweep) -- a terminal, one-time failure with no
     * retry path, where baking a reason into the title makes sense. This method is for a session
     * that DID record successfully and has a real [Session.audioFilePath], but whose transcription
     * refinement failed after exhausting retries -- a state the user can retry from repeatedly via
     * [retryProcessing], so mutating the title here would stack up multiple reason suffixes on
     * every retry attempt that fails again.
     */
    suspend fun markProcessingFailed(id: String) {
        val session = sessionDao.getById(id) ?: return
        sessionDao.update(
            session.copy(status = SessionStatus.ERROR, processingProgressPercent = null, processingEtaMillis = null)
        )
    }

    /**
     * Re-enqueues transcription refinement for a session left in [SessionStatus.ERROR] by
     * [markProcessingFailed]. A no-op if the session has no [Session.audioFilePath] or
     * [Session.durationMs] -- either it never recorded successfully in the first place (the
     * [markError] case above), or [audioFileDeleter] has since deleted the WAV after the 7-day
     * retention window, in which case there is nothing left to reprocess.
     */
    suspend fun retryProcessing(id: String) {
        val session = sessionDao.getById(id) ?: return
        val wavFilePath = session.audioFilePath ?: return
        val durationMs = session.durationMs ?: return
        sessionDao.update(
            session.copy(status = SessionStatus.PROCESSING, processingProgressPercent = null, processingEtaMillis = null)
        )
        transcriptionEnqueuer(id, wavFilePath, durationMs, session.startTime)
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
