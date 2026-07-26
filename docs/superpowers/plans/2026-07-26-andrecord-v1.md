# Andrecord v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a personal Android app that records meetings, transcribes them live via sherpa-onnx streaming ASR, diarizes speakers as a post-process, saves each meeting as a dated speaker-labeled session, and can be started/stopped from the app, Quick Tap, or a long-press volume key — including while locked.

**Architecture:** A `RecordingService` (foreground service) captures audio and streams live ASR text; on stop it hands off to a `DiarizationWorker` (WorkManager) that aligns speaker segments onto the transcript. A `SessionRepository` (Room + app-private files) is the single source of truth for the Compose UI, which adapts between a single-pane phone-style layout and a two-pane list-detail layout depending on fold state.

**Tech Stack:** Kotlin, Jetpack Compose, Material3 adaptive (`androidx.window`), Room, WorkManager, sherpa-onnx (Android AAR + JNI), Robolectric (JVM-level Room/unit tests).

## Global Constraints

- Package/applicationId: `com.andrecord.app`
- `minSdk = 34`, `targetSdk` = latest installed platform (Android 14+ is required for foreground service type `microphone`)
- No network permission anywhere in the manifest — this app is fully on-device (spec §7)
- English-only ASR for v1 (spec §3)
- Speaker count is always auto-detected, never fixed/configured (spec §3)
- Audio retention: 7 days from `endTime`, then delete the WAV but keep the transcript forever (spec §5)
- Long-press hold duration for the volume-key trigger is exactly 1000ms (spec §4)
- Vibration feedback: single pulse on start, double pulse on stop (spec §4)
- Colors/type/layout must follow the tokens in spec §6 exactly (hex values, font names, adaptive `ListDetailPaneScaffold` approach)

---

## File Structure Overview

```
app/
  build.gradle.kts
  src/main/
    AndroidManifest.xml
    assets/models/asr/{encoder.onnx,decoder.onnx,joiner.onnx,tokens.txt}
    assets/models/diarization/{segmentation.onnx,embedding.onnx}
    java/com/andrecord/app/
      AndrecordApplication.kt          # AppContainer wiring (manual DI)
      data/
        Session.kt                    # Room entity
        TranscriptSegment.kt          # Room entity
        SessionDao.kt
        TranscriptSegmentDao.kt
        AndrecordDatabase.kt
        SessionRepository.kt
      asr/
        StreamingAsrEngine.kt         # interface + AsrEvent
        SherpaOnnxStreamingAsrEngine.kt
      diarization/
        DiarizationEngine.kt          # interface + SpeakerSegment
        SherpaOnnxDiarizationEngine.kt
        TranscriptAligner.kt
      recording/
        RecordingState.kt
        RecordingServiceStarter.kt    # interface, real impl starts/stops the Android Service
        RecordingController.kt
        RecordingService.kt
      workers/
        DiarizationWorker.kt
        RetentionWorker.kt
      triggers/
        TriggerTrampolineActivity.kt
        KeyTriggerAccessibilityService.kt
        shortcuts.xml
        accessibility_service_config.xml
      ui/
        theme/{Color.kt,Type.kt,Theme.kt}
        components/SpeakerTimelineStrip.kt
        list/{SessionListScreen.kt,SessionListViewModel.kt}
        detail/{SessionDetailScreen.kt,SessionDetailViewModel.kt}
        AndrecordApp.kt               # adaptive nav host
      MainActivity.kt
  src/test/java/com/andrecord/app/    # JVM/Robolectric unit tests, mirrors main tree
  src/androidTest/java/com/andrecord/app/  # instrumented tests (accessibility/service, manual-driven)
```

---

### Task 1: Project scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `app/build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`

**Interfaces:** none (no prior tasks) — produces the Gradle module that every later task builds into.

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Andrecord"
include(":app")
```

- [ ] **Step 2: Create root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
```

- [ ] **Step 3: Create `gradle.properties`**

```properties
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 4: Create `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.andrecord.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andrecord.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3.adaptive:adaptive:1.0.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.0.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.0.0")
    implementation("androidx.window:window:1.3.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
```

Add the KSP plugin next to the other two plugins in root `build.gradle.kts`:

```kotlin
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
```

and in `app/build.gradle.kts` plugins block:

```kotlin
    id("com.google.devtools.ksp")
```

- [ ] **Step 5: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <!-- Deliberately no INTERNET permission: this app is fully on-device. -->

    <application
        android:name=".AndrecordApplication"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>
```

- [ ] **Step 6: Create `app/src/main/res/values/strings.xml`**

```xml
<resources>
    <string name="app_name">Andrecord</string>
</resources>
```

- [ ] **Step 7: Verify the empty project builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "Scaffold Andrecord Android project"
```

---

### Task 2: Room data layer — entities, DAOs, database

**Files:**
- Create: `app/src/main/java/com/andrecord/app/data/Session.kt`
- Create: `app/src/main/java/com/andrecord/app/data/TranscriptSegment.kt`
- Create: `app/src/main/java/com/andrecord/app/data/SessionDao.kt`
- Create: `app/src/main/java/com/andrecord/app/data/TranscriptSegmentDao.kt`
- Create: `app/src/main/java/com/andrecord/app/data/AndrecordDatabase.kt`
- Test: `app/src/test/java/com/andrecord/app/data/SessionDaoTest.kt`

**Interfaces:**
- Produces: `Session(id: String, startTime: Long, endTime: Long?, durationMs: Long?, title: String, status: SessionStatus, speakerCount: Int?, audioFilePath: String?, audioDeleteAt: Long?)`, `SessionStatus` enum (`RECORDING`, `PROCESSING`, `READY`, `ERROR`), `TranscriptSegment(id: Long, sessionId: String, startMs: Long, endMs: Long, speakerLabel: String?, text: String)`, `SessionDao`, `TranscriptSegmentDao`, `AndrecordDatabase.build(context): AndrecordDatabase`.

- [ ] **Step 1: Write the failing DAO test**

```kotlin
package com.andrecord.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionDaoTest {

    private fun buildDb() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AndrecordDatabase::class.java
    ).allowMainThreadQueries().build()

    @Test
    fun `insert and read back a session`() = runTest {
        val db = buildDb()
        val session = Session(
            id = "s1",
            startTime = 1000L,
            endTime = null,
            durationMs = null,
            title = "Jul 26, 2026, 2:15 PM",
            status = SessionStatus.RECORDING,
            speakerCount = null,
            audioFilePath = "/data/audio/s1.wav",
            audioDeleteAt = null
        )
        db.sessionDao().insert(session)

        val loaded = db.sessionDao().getById("s1")

        assertEquals("Jul 26, 2026, 2:15 PM", loaded?.title)
        assertEquals(SessionStatus.RECORDING, loaded?.status)
        db.close()
    }

    @Test
    fun `sessions ordered newest first`() = runTest {
        val db = buildDb()
        db.sessionDao().insert(sessionAt("s1", 1000L))
        db.sessionDao().insert(sessionAt("s2", 2000L))

        val all = db.sessionDao().getAllOnce()

        assertEquals(listOf("s2", "s1"), all.map { it.id })
        db.close()
    }

    private fun sessionAt(id: String, startTime: Long) = Session(
        id = id, startTime = startTime, endTime = null, durationMs = null,
        title = id, status = SessionStatus.READY, speakerCount = null,
        audioFilePath = null, audioDeleteAt = null
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.data.SessionDaoTest"`
Expected: FAIL (compilation error — `Session`, `SessionStatus`, `AndrecordDatabase` don't exist yet)

- [ ] **Step 3: Create `Session.kt`**

```kotlin
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
    val audioDeleteAt: Long?
)
```

- [ ] **Step 4: Create `TranscriptSegment.kt`**

```kotlin
package com.andrecord.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(
        entity = Session::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class TranscriptSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    val speakerLabel: String?,
    val text: String
)
```

- [ ] **Step 5: Create `SessionDao.kt`**

```kotlin
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

    @Query("SELECT * FROM sessions WHERE audioFilePath IS NOT NULL AND audioDeleteAt IS NOT NULL AND audioDeleteAt < :now")
    suspend fun getSessionsWithExpiredAudio(now: Long): List<Session>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}
```

- [ ] **Step 6: Create `TranscriptSegmentDao.kt`**

```kotlin
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
```

- [ ] **Step 7: Create `AndrecordDatabase.kt`**

```kotlin
package com.andrecord.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Session::class, TranscriptSegment::class], version = 1)
abstract class AndrecordDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao

    companion object {
        fun build(context: Context): AndrecordDatabase =
            Room.databaseBuilder(context, AndrecordDatabase::class.java, "andrecord.db").build()
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.data.SessionDaoTest"`
Expected: PASS (2 tests)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/andrecord/app/data app/src/test/java/com/andrecord/app/data
git commit -m "Add Room entities, DAOs, and database"
```

---

### Task 3: SessionRepository

**Files:**
- Create: `app/src/main/java/com/andrecord/app/data/SessionRepository.kt`
- Test: `app/src/test/java/com/andrecord/app/data/SessionRepositoryTest.kt`

**Interfaces:**
- Consumes: `SessionDao`, `TranscriptSegmentDao`, `Session`, `TranscriptSegment`, `SessionStatus` (Task 2)
- Produces: `SessionRepository(sessionDao, transcriptSegmentDao, audioFileDeleter: (String) -> Unit)` with methods: `suspend fun createSession(id: String, startTime: Long): Session`, `suspend fun appendSegment(segment: TranscriptSegment)`, `suspend fun markProcessing(id: String, endTime: Long, durationMs: Long, audioFilePath: String, audioDeleteAt: Long)`, `suspend fun finalizeReady(id: String, speakerCount: Int?)`, `suspend fun markError(id: String, reason: String)`, `suspend fun rename(id: String, newTitle: String)`, `suspend fun delete(id: String)`, `suspend fun deleteExpiredAudio(now: Long)`, `fun observeSessions(): Flow<List<Session>>`, `fun observeSegments(sessionId: String): Flow<List<TranscriptSegment>>`.

- [ ] **Step 1: Write the failing repository test**

```kotlin
package com.andrecord.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {

    private fun buildRepo(deletedPaths: MutableList<String> = mutableListOf()): Pair<SessionRepository, AndrecordDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndrecordDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { path -> deletedPaths.add(path) }
        return repo to db
    }

    @Test
    fun `createSession then markProcessing then finalizeReady moves through statuses`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)

        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 999_999L)
        assertEquals(SessionStatus.PROCESSING, db.sessionDao().getById("s1")?.status)

        repo.finalizeReady("s1", speakerCount = 3)
        val finalSession = db.sessionDao().getById("s1")
        assertEquals(SessionStatus.READY, finalSession?.status)
        assertEquals(3, finalSession?.speakerCount)
        db.close()
    }

    @Test
    fun `deleteExpiredAudio removes file and clears path only for expired sessions`() = runTest {
        val deleted = mutableListOf<String>()
        val (repo, db) = buildRepo(deleted)
        repo.createSession("s1", startTime = 1000L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 2000L)
        repo.createSession("s2", startTime = 1000L)
        repo.markProcessing("s2", endTime = 5000L, durationMs = 4000L, audioFilePath = "/audio/s2.wav", audioDeleteAt = 9_000_000L)

        repo.deleteExpiredAudio(now = 3000L)

        assertEquals(listOf("/audio/s1.wav"), deleted)
        assertNull(db.sessionDao().getById("s1")?.audioFilePath)
        assertTrue(db.sessionDao().getById("s2")?.audioFilePath == "/audio/s2.wav")
        db.close()
    }

    @Test
    fun `rename updates title`() = runTest {
        val (repo, db) = buildRepo()
        repo.createSession("s1", startTime = 1000L)

        repo.rename("s1", "Budget sync")

        assertEquals("Budget sync", db.sessionDao().getById("s1")?.title)
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.data.SessionRepositoryTest"`
Expected: FAIL (compilation error — `SessionRepository` doesn't exist)

- [ ] **Step 3: Implement `SessionRepository.kt`**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.data.SessionRepositoryTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/data/SessionRepository.kt app/src/test/java/com/andrecord/app/data/SessionRepositoryTest.kt
git commit -m "Add SessionRepository"
```

---

### Task 4: Transcript/diarization alignment logic

**Files:**
- Create: `app/src/main/java/com/andrecord/app/diarization/DiarizationEngine.kt`
- Create: `app/src/main/java/com/andrecord/app/asr/StreamingAsrEngine.kt`
- Create: `app/src/main/java/com/andrecord/app/diarization/TranscriptAligner.kt`
- Test: `app/src/test/java/com/andrecord/app/diarization/TranscriptAlignerTest.kt`

**Interfaces:**
- Produces: `AsrEvent` sealed class (`Partial(text: String)`, `Final(startMs: Long, endMs: Long, text: String)`) in `StreamingAsrEngine.kt`; `SpeakerSegment(startMs: Long, endMs: Long, speakerIndex: Int)` and `DiarizationEngine` interface in `DiarizationEngine.kt`; `TranscriptAligner.align(sessionId: String, asrSegments: List<AsrEvent.Final>, speakerSegments: List<SpeakerSegment>): List<TranscriptSegment>` (uses `TranscriptSegment` from Task 2) and `TranscriptAligner.speakerCount(speakerSegments: List<SpeakerSegment>): Int`.

- [ ] **Step 1: Write the failing alignment test**

```kotlin
package com.andrecord.app.diarization

import com.andrecord.app.asr.AsrEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAlignerTest {

    @Test
    fun `assigns speaker label by max timestamp overlap`() {
        val asrSegments = listOf(
            AsrEvent.Final(startMs = 0, endMs = 2000, text = "hello there"),
            AsrEvent.Final(startMs = 2000, endMs = 5000, text = "how are you")
        )
        val speakerSegments = listOf(
            SpeakerSegment(startMs = 0, endMs = 2200, speakerIndex = 0),
            SpeakerSegment(startMs = 2200, endMs = 6000, speakerIndex = 1)
        )

        val result = TranscriptAligner.align("s1", asrSegments, speakerSegments)

        assertEquals("Speaker 1", result[0].speakerLabel)
        assertEquals("Speaker 2", result[1].speakerLabel)
        assertEquals("hello there", result[0].text)
        assertEquals("s1", result[0].sessionId)
    }

    @Test
    fun `asr segment with no overlapping speaker segment gets null label`() {
        val asrSegments = listOf(AsrEvent.Final(startMs = 10_000, endMs = 12_000, text = "orphaned"))
        val speakerSegments = listOf(SpeakerSegment(startMs = 0, endMs = 1000, speakerIndex = 0))

        val result = TranscriptAligner.align("s1", asrSegments, speakerSegments)

        assertEquals(null, result[0].speakerLabel)
    }

    @Test
    fun `speakerCount returns distinct speaker index count`() {
        val speakerSegments = listOf(
            SpeakerSegment(0, 1000, speakerIndex = 0),
            SpeakerSegment(1000, 2000, speakerIndex = 1),
            SpeakerSegment(2000, 3000, speakerIndex = 0)
        )

        assertEquals(2, TranscriptAligner.speakerCount(speakerSegments))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.diarization.TranscriptAlignerTest"`
Expected: FAIL (compilation error — nothing exists yet)

- [ ] **Step 3: Create `StreamingAsrEngine.kt`**

```kotlin
package com.andrecord.app.asr

sealed class AsrEvent {
    data class Partial(val text: String) : AsrEvent()
    data class Final(val startMs: Long, val endMs: Long, val text: String) : AsrEvent()
}

interface StreamingAsrEngine {
    fun start()
    /** samples are 16kHz mono PCM, normalized to [-1.0, 1.0] */
    fun acceptWaveform(samples: FloatArray)
    /** Returns the next available event, or null if none is ready yet. Call after every acceptWaveform. */
    fun poll(): AsrEvent?
    fun stop()
}
```

- [ ] **Step 4: Create `DiarizationEngine.kt`**

```kotlin
package com.andrecord.app.diarization

data class SpeakerSegment(val startMs: Long, val endMs: Long, val speakerIndex: Int)

interface DiarizationEngine {
    /** Runs the full offline diarization pipeline over a WAV file and returns speaker-labeled segments. */
    fun diarize(wavFilePath: String): List<SpeakerSegment>
}
```

- [ ] **Step 5: Implement `TranscriptAligner.kt`**

```kotlin
package com.andrecord.app.diarization

import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.TranscriptSegment

object TranscriptAligner {

    fun align(
        sessionId: String,
        asrSegments: List<AsrEvent.Final>,
        speakerSegments: List<SpeakerSegment>
    ): List<TranscriptSegment> = asrSegments.map { asr ->
        val label = bestSpeakerLabel(asr, speakerSegments)
        TranscriptSegment(
            sessionId = sessionId,
            startMs = asr.startMs,
            endMs = asr.endMs,
            speakerLabel = label,
            text = asr.text
        )
    }

    fun speakerCount(speakerSegments: List<SpeakerSegment>): Int =
        speakerSegments.map { it.speakerIndex }.distinct().size

    private fun bestSpeakerLabel(asr: AsrEvent.Final, speakerSegments: List<SpeakerSegment>): String? {
        var bestOverlap = 0L
        var bestIndex: Int? = null
        for (seg in speakerSegments) {
            val overlap = overlapMs(asr.startMs, asr.endMs, seg.startMs, seg.endMs)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestIndex = seg.speakerIndex
            }
        }
        return bestIndex?.let { "Speaker ${it + 1}" }
    }

    private fun overlapMs(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
        (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L)
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.diarization.TranscriptAlignerTest"`
Expected: PASS (3 tests)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/andrecord/app/asr app/src/main/java/com/andrecord/app/diarization app/src/test/java/com/andrecord/app/diarization
git commit -m "Add ASR/diarization interfaces and transcript alignment logic"
```

---

### Task 5: RecordingController (state machine)

**Files:**
- Create: `app/src/main/java/com/andrecord/app/recording/RecordingState.kt`
- Create: `app/src/main/java/com/andrecord/app/recording/RecordingServiceStarter.kt`
- Create: `app/src/main/java/com/andrecord/app/recording/RecordingController.kt`
- Test: `app/src/test/java/com/andrecord/app/recording/RecordingControllerTest.kt`

**Interfaces:**
- Consumes: `SessionRepository` (Task 3)
- Produces: `RecordingState` enum (`IDLE`, `RECORDING`), `RecordingServiceStarter` interface (`fun startRecording(sessionId: String)`, `fun stopRecording()`), `RecordingController(repository: SessionRepository, serviceStarter: RecordingServiceStarter, idGenerator: () -> String = { java.util.UUID.randomUUID().toString() }, clock: () -> Long = { System.currentTimeMillis() })` with `suspend fun toggle(): RecordingState`, `fun currentState(): RecordingState`.

- [ ] **Step 1: Write the failing controller test**

```kotlin
package com.andrecord.app.recording

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.SessionStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordingControllerTest {

    private class FakeServiceStarter : RecordingServiceStarter {
        var startedSessionId: String? = null
        var stopCalled = false
        override fun startRecording(sessionId: String) { startedSessionId = sessionId }
        override fun stopRecording() { stopCalled = true }
    }

    private fun buildController(starter: FakeServiceStarter): Pair<RecordingController, AndrecordDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndrecordDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        val controller = RecordingController(
            repository = repo,
            serviceStarter = starter,
            idGenerator = { "fixed-id" },
            clock = { 42_000L }
        )
        return controller to db
    }

    @Test
    fun `toggle from idle starts a new session`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter)

        val state = controller.toggle()

        assertEquals(RecordingState.RECORDING, state)
        assertEquals("fixed-id", starter.startedSessionId)
        assertEquals(SessionStatus.RECORDING, db.sessionDao().getById("fixed-id")?.status)
        db.close()
    }

    @Test
    fun `toggle again while recording stops it`() = runTest {
        val starter = FakeServiceStarter()
        val (controller, db) = buildController(starter)
        controller.toggle()

        val state = controller.toggle()

        assertEquals(RecordingState.IDLE, state)
        assertEquals(true, starter.stopCalled)
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.recording.RecordingControllerTest"`
Expected: FAIL (compilation error — nothing exists yet)

- [ ] **Step 3: Create `RecordingState.kt`**

```kotlin
package com.andrecord.app.recording

enum class RecordingState { IDLE, RECORDING }
```

- [ ] **Step 4: Create `RecordingServiceStarter.kt`**

```kotlin
package com.andrecord.app.recording

interface RecordingServiceStarter {
    fun startRecording(sessionId: String)
    fun stopRecording()
}
```

- [ ] **Step 5: Implement `RecordingController.kt`**

```kotlin
package com.andrecord.app.recording

import com.andrecord.app.data.SessionRepository
import java.util.UUID

class RecordingController(
    private val repository: SessionRepository,
    private val serviceStarter: RecordingServiceStarter,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var state: RecordingState = RecordingState.IDLE
    private var activeSessionId: String? = null

    fun currentState(): RecordingState = state

    suspend fun toggle(): RecordingState {
        return if (state == RecordingState.IDLE) start() else stop()
    }

    private suspend fun start(): RecordingState {
        val id = idGenerator()
        repository.createSession(id, clock())
        activeSessionId = id
        serviceStarter.startRecording(id)
        state = RecordingState.RECORDING
        return state
    }

    private suspend fun stop(): RecordingState {
        serviceStarter.stopRecording()
        activeSessionId = null
        state = RecordingState.IDLE
        return state
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.recording.RecordingControllerTest"`
Expected: PASS (2 tests)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/andrecord/app/recording/RecordingState.kt app/src/main/java/com/andrecord/app/recording/RecordingServiceStarter.kt app/src/main/java/com/andrecord/app/recording/RecordingController.kt app/src/test/java/com/andrecord/app/recording/RecordingControllerTest.kt
git commit -m "Add RecordingController state machine"
```

---

### Task 6: RetentionWorker

**Files:**
- Create: `app/src/main/java/com/andrecord/app/workers/RetentionWorker.kt`
- Test: `app/src/test/java/com/andrecord/app/workers/RetentionWorkerLogicTest.kt`

**Interfaces:**
- Consumes: `SessionRepository.deleteExpiredAudio(now: Long)` (Task 3)
- Produces: `RetentionWorker(context, params)` extends `CoroutineWorker`; the pure scheduling/logic is exposed via a standalone testable function `RetentionWorker.Companion.runRetention(repository: SessionRepository, now: Long)` so the test doesn't need `WorkerParameters` boilerplate.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.andrecord.app.workers

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RetentionWorkerLogicTest {

    @Test
    fun `runRetention clears expired audio paths`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndrecordDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 1000L, durationMs = 1000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 5000L)

        RetentionWorker.runRetention(repo, now = 6000L)

        assertNull(db.sessionDao().getById("s1")?.audioFilePath)
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.workers.RetentionWorkerLogicTest"`
Expected: FAIL (compilation error — `RetentionWorker` doesn't exist)

- [ ] **Step 3: Implement `RetentionWorker.kt`**

```kotlin
package com.andrecord.app.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.data.SessionRepository

class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = (applicationContext as AndrecordApplication).container.sessionRepository
        runRetention(repository, System.currentTimeMillis())
        return Result.success()
    }

    companion object {
        suspend fun runRetention(repository: SessionRepository, now: Long) {
            repository.deleteExpiredAudio(now)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.workers.RetentionWorkerLogicTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/workers/RetentionWorker.kt app/src/test/java/com/andrecord/app/workers/RetentionWorkerLogicTest.kt
git commit -m "Add RetentionWorker with testable retention logic"
```

*(Note: this task references `AndrecordApplication.container` — implemented in Task 12. The worker class compiles once Task 12 lands; the logic test above only depends on `SessionRepository` and passes independently.)*

---

### Task 7: DiarizationWorker

**Files:**
- Create: `app/src/main/java/com/andrecord/app/workers/DiarizationWorker.kt`
- Test: `app/src/test/java/com/andrecord/app/workers/DiarizationWorkerLogicTest.kt`

**Interfaces:**
- Consumes: `DiarizationEngine`, `TranscriptAligner` (Task 4), `SessionRepository` (Task 3)
- Produces: `DiarizationWorker.Companion.runDiarization(repository: SessionRepository, engine: DiarizationEngine, sessionId: String, wavFilePath: String, pendingAsrSegments: List<AsrEvent.Final>): Int?` (returns speaker count, or null if diarization failed) — extracted so it's testable without `WorkerParameters`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.andrecord.app.workers

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.SessionStatus
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.diarization.SpeakerSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiarizationWorkerLogicTest {

    private class FakeDiarizationEngine(private val segments: List<SpeakerSegment>) : DiarizationEngine {
        override fun diarize(wavFilePath: String): List<SpeakerSegment> = segments
    }

    @Test
    fun `runDiarization aligns segments, writes transcript, and finalizes session`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AndrecordDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = SessionRepository(db.sessionDao(), db.transcriptSegmentDao()) { }
        repo.createSession("s1", startTime = 0L)
        repo.markProcessing("s1", endTime = 5000L, durationMs = 5000L, audioFilePath = "/audio/s1.wav", audioDeleteAt = 99_999L)
        val engine = FakeDiarizationEngine(listOf(SpeakerSegment(0, 5000, speakerIndex = 0)))
        val asrSegments = listOf(AsrEvent.Final(startMs = 0, endMs = 2000, text = "hi there"))

        val speakerCount = DiarizationWorker.runDiarization(repo, engine, "s1", "/audio/s1.wav", asrSegments)

        assertEquals(1, speakerCount)
        assertEquals(SessionStatus.READY, db.sessionDao().getById("s1")?.status)
        assertEquals(1, db.sessionDao().getById("s1")?.speakerCount)
        val segments = db.transcriptSegmentDao().getForSession("s1")
        db.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.workers.DiarizationWorkerLogicTest"`
Expected: FAIL (compilation error — `DiarizationWorker` doesn't exist)

- [ ] **Step 3: Implement `DiarizationWorker.kt`**

```kotlin
package com.andrecord.app.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.diarization.TranscriptAligner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class DiarizationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val wavFilePath = inputData.getString(KEY_WAV_PATH) ?: return Result.failure()

        val container = (applicationContext as AndrecordApplication).container
        val pendingAsrSegments = container.pendingAsrSegments.remove(sessionId).orEmpty()

        val speakerCount = try {
            runDiarization(container.sessionRepository, container.diarizationEngine, sessionId, wavFilePath, pendingAsrSegments)
        } catch (e: Exception) {
            null
        }

        if (speakerCount == null) {
            // Exhausted usefulness of retrying at the call-site policy (WorkManager retries this
            // whole doWork() on RETRY); if we already retried, fail soft so the session is still usable.
            if (runAttemptCount >= MAX_ATTEMPTS) {
                container.sessionRepository.finalizeReady(sessionId, speakerCount = null)
                notifyReady(sessionId)
                return Result.success()
            }
            return Result.retry()
        }

        notifyReady(sessionId)
        return Result.success()
    }

    private fun notifyReady(sessionId: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transcript ready", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val minutes = (inputData.getLong(KEY_DURATION_MS, 0L) / 60000L).toInt()
        val startedAt = SimpleDateFormat("h:mm a", Locale.US).format(Date(inputData.getLong(KEY_START_TIME, 0L)))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$minutes-minute meeting transcript ready")
            .setContentText("Started $startedAt")
            .setAutoCancel(true)
            .build()
        manager.notify(sessionId.hashCode(), notification)
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_WAV_PATH = "wav_path"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_START_TIME = "start_time"
        private const val CHANNEL_ID = "transcript_ready"
        private const val MAX_ATTEMPTS = 3

        suspend fun runDiarization(
            repository: SessionRepository,
            engine: DiarizationEngine,
            sessionId: String,
            wavFilePath: String,
            pendingAsrSegments: List<AsrEvent.Final>
        ): Int? {
            val speakerSegments = engine.diarize(wavFilePath)
            val speakerCount = TranscriptAligner.speakerCount(speakerSegments)
            val transcriptSegments = TranscriptAligner.align(sessionId, pendingAsrSegments, speakerSegments)
            transcriptSegments.forEach { repository.appendSegment(it) }
            repository.finalizeReady(sessionId, speakerCount)
            return speakerCount
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.workers.DiarizationWorkerLogicTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/workers/DiarizationWorker.kt app/src/test/java/com/andrecord/app/workers/DiarizationWorkerLogicTest.kt
git commit -m "Add DiarizationWorker with speaker-count and duration in the ready notification"
```

*(Note: like Task 6, the class references `AndrecordApplication.container`, which lands in Task 12 — the `runDiarization` logic under test has no such dependency and passes on its own.)*

---

### Task 8: sherpa-onnx streaming ASR integration

**Files:**
- Create: `app/libs/` (drop location for the vendored AAR)
- Create: `app/src/main/java/com/andrecord/app/asr/SherpaOnnxStreamingAsrEngine.kt`
- Create: `app/src/androidTest/java/com/andrecord/app/asr/SherpaOnnxStreamingAsrEngineSmokeTest.kt`

**Interfaces:**
- Consumes: `StreamingAsrEngine`, `AsrEvent` (Task 4)
- Produces: `SherpaOnnxStreamingAsrEngine(context: Context) : StreamingAsrEngine`

This task integrates a real third-party native library, so it is verified with an on-device smoke test rather than a red/green unit test — sherpa-onnx's JNI code cannot run under Robolectric.

- [ ] **Step 1: Vendor the sherpa-onnx Android AAR**

Go to https://github.com/k2-fsa/sherpa-onnx/releases and download the latest Android release asset (look for a filename containing `android` — it bundles `sherpa-onnx.aar` with the JNI `.so` files for `arm64-v8a`, which is what the Pixel 10 Pro Fold uses). Extract `sherpa-onnx.aar` into `app/libs/sherpa-onnx.aar`. The dependency wiring for `app/libs/*.aar` is already in `app/build.gradle.kts` from Task 1 Step 4.

- [ ] **Step 2: Download and bundle the streaming ASR model**

From the same repo's model documentation, get a streaming Zipformer English transducer model (encoder/decoder/joiner ONNX files + `tokens.txt`). Rename and place them at:
- `app/src/main/assets/models/asr/encoder.onnx`
- `app/src/main/assets/models/asr/decoder.onnx`
- `app/src/main/assets/models/asr/joiner.onnx`
- `app/src/main/assets/models/asr/tokens.txt`

- [ ] **Step 3: Implement `SherpaOnnxStreamingAsrEngine.kt`**

```kotlin
package com.andrecord.app.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.ConcurrentLinkedQueue

class SherpaOnnxStreamingAsrEngine(private val context: Context) : StreamingAsrEngine {

    private lateinit var recognizer: OnlineRecognizer
    private lateinit var stream: OnlineStream
    private val pendingEvents = ConcurrentLinkedQueue<AsrEvent>()
    private var samplesProcessed = 0L
    private var lastEmittedText = ""
    private var segmentStartMs = 0L

    override fun start() {
        val config = OnlineRecognizerConfig(
            modelConfig = OnlineTransducerModelConfig(
                encoder = assetPath("models/asr/encoder.onnx"),
                decoder = assetPath("models/asr/decoder.onnx"),
                joiner = assetPath("models/asr/joiner.onnx"),
            ),
            tokens = assetPath("models/asr/tokens.txt"),
        )
        recognizer = OnlineRecognizer(assetManager = context.assets, config = config)
        stream = recognizer.createStream()
        samplesProcessed = 0L
        lastEmittedText = ""
        segmentStartMs = 0L
    }

    override fun acceptWaveform(samples: FloatArray) {
        stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
        samplesProcessed += samples.size
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        val text = recognizer.getResult(stream).text
        val nowMs = (samplesProcessed * 1000L) / SAMPLE_RATE

        if (recognizer.isEndpoint(stream)) {
            if (text.isNotBlank()) {
                pendingEvents.add(AsrEvent.Final(startMs = segmentStartMs, endMs = nowMs, text = text))
            }
            recognizer.reset(stream)
            segmentStartMs = nowMs
            lastEmittedText = ""
        } else if (text != lastEmittedText) {
            lastEmittedText = text
            pendingEvents.add(AsrEvent.Partial(text))
        }
    }

    override fun poll(): AsrEvent? = pendingEvents.poll()

    override fun stop() {
        stream.release()
        recognizer.release()
    }

    private fun assetPath(path: String) = path

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
```

- [ ] **Step 4: Write an on-device smoke test**

```kotlin
package com.andrecord.app.asr

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SherpaOnnxStreamingAsrEngineSmokeTest {

    @Test
    fun `recognizes non-empty text from a short spoken test clip`() {
        // Record a 3-5 second WAV of yourself saying a short sentence and place it at
        // app/src/androidTest/assets/test_clip_16k_mono.wav before running this test.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = SherpaOnnxStreamingAsrEngine(context)
        engine.start()

        val samples = readTestClipAsFloatPcm(context)
        val chunkSize = 1600 // 100ms chunks at 16kHz
        val collected = StringBuilder()
        for (i in samples.indices step chunkSize) {
            val chunk = samples.copyOfRange(i, minOf(i + chunkSize, samples.size))
            engine.acceptWaveform(chunk)
            var event = engine.poll()
            while (event != null) {
                if (event is AsrEvent.Final) collected.append(event.text).append(" ")
                event = engine.poll()
            }
        }
        engine.stop()

        assertTrue("Expected non-empty transcript, got: '$collected'", collected.isNotBlank())
    }

    private fun readTestClipAsFloatPcm(context: android.content.Context): FloatArray {
        context.assets.open("test_clip_16k_mono.wav").use { input ->
            val bytes = input.readBytes()
            val pcmBytes = bytes.copyOfRange(44, bytes.size) // skip the 44-byte WAV header
            val samples = FloatArray(pcmBytes.size / 2)
            for (i in samples.indices) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                val sample = (hi shl 8) or lo
                samples[i] = sample / 32768.0f
            }
            return samples
        }
    }
}
```

- [ ] **Step 5: Run the smoke test on the Pixel 10 Pro Fold**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.andrecord.app.asr.SherpaOnnxStreamingAsrEngineSmokeTest"`
Expected: PASS, with the printed/collected transcript roughly matching what was said in the test clip

- [ ] **Step 6: Commit**

```bash
git add app/libs/sherpa-onnx.aar app/src/main/assets/models/asr app/src/main/java/com/andrecord/app/asr/SherpaOnnxStreamingAsrEngine.kt app/src/androidTest/java/com/andrecord/app/asr app/src/androidTest/assets/test_clip_16k_mono.wav
git commit -m "Integrate sherpa-onnx streaming ASR engine"
```

---

### Task 9: sherpa-onnx offline diarization integration

**Files:**
- Create: `app/src/main/java/com/andrecord/app/diarization/SherpaOnnxDiarizationEngine.kt`
- Create: `app/src/androidTest/java/com/andrecord/app/diarization/SherpaOnnxDiarizationEngineSmokeTest.kt`

**Interfaces:**
- Consumes: `DiarizationEngine`, `SpeakerSegment` (Task 4)
- Produces: `SherpaOnnxDiarizationEngine(context: Context) : DiarizationEngine`

- [ ] **Step 1: Download and bundle the diarization models**

From the sherpa-onnx offline speaker diarization documentation, get a speaker segmentation model and a speaker embedding model. Place them at:
- `app/src/main/assets/models/diarization/segmentation.onnx`
- `app/src/main/assets/models/diarization/embedding.onnx`

- [ ] **Step 2: Implement `SherpaOnnxDiarizationEngine.kt`**

```kotlin
package com.andrecord.app.diarization

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.WaveReader

class SherpaOnnxDiarizationEngine(private val context: Context) : DiarizationEngine {

    override fun diarize(wavFilePath: String): List<SpeakerSegment> {
        val config = OfflineSpeakerDiarizationConfig(
            segmentationModel = "models/diarization/segmentation.onnx",
            embeddingModel = "models/diarization/embedding.onnx",
        )
        val diarizer = OfflineSpeakerDiarization(assetManager = context.assets, config = config)
        val wave = WaveReader.readWaveFromFile(wavFilePath)
        val result = diarizer.process(samples = wave.samples, sampleRate = wave.sampleRate)
        return result.segments.map {
            SpeakerSegment(
                startMs = (it.start * 1000).toLong(),
                endMs = (it.end * 1000).toLong(),
                speakerIndex = it.speaker
            )
        }
    }
}
```

- [ ] **Step 3: Write an on-device smoke test**

```kotlin
package com.andrecord.app.diarization

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SherpaOnnxDiarizationEngineSmokeTest {

    @Test
    fun `diarizes a two-speaker test clip into at least two speaker segments`() {
        // Record a short (~20s) two-person conversation WAV (16kHz mono) and place it at
        // /sdcard/Download/two_speaker_test.wav on the device before running this test.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = SherpaOnnxDiarizationEngine(context)

        val segments = engine.diarize("/sdcard/Download/two_speaker_test.wav")

        assertTrue("Expected at least 2 distinct speakers, got: ${TranscriptAligner.speakerCount(segments)}",
            TranscriptAligner.speakerCount(segments) >= 2)
    }
}
```

- [ ] **Step 4: Run the smoke test on the Pixel 10 Pro Fold**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.andrecord.app.diarization.SherpaOnnxDiarizationEngineSmokeTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/models/diarization app/src/main/java/com/andrecord/app/diarization/SherpaOnnxDiarizationEngine.kt app/src/androidTest/java/com/andrecord/app/diarization
git commit -m "Integrate sherpa-onnx offline speaker diarization engine"
```

---

### Task 10: RecordingService (foreground service)

**Files:**
- Create: `app/src/main/java/com/andrecord/app/recording/RecordingService.kt`
- Modify: `app/src/main/AndroidManifest.xml` (register the service)
- Create: `app/src/androidTest/java/com/andrecord/app/recording/RecordingServiceInstrumentedTest.kt`

**Interfaces:**
- Consumes: `StreamingAsrEngine`, `AsrEvent` (Task 4/8), `SessionRepository` (Task 3), `TranscriptSegment` (Task 2), enqueues `DiarizationWorker` (Task 7)
- Produces: `RecordingService`, started via `Intent` actions `ACTION_START` (extra `EXTRA_SESSION_ID: String`) and `ACTION_STOP`.

- [ ] **Step 1: Implement `RecordingService.kt`**

```kotlin
package com.andrecord.app.recording

import android.Manifest
import android.app.Notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.andrecord.app.AndrecordApplication
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.workers.DiarizationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class RecordingService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var audioRecord: AudioRecord? = null
    private var sessionId: String? = null
    private var startTime: Long = 0L
    private var wavFile: File? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_SESSION_ID)!!)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(id: String) {
        sessionId = id
        startTime = System.currentTimeMillis()
        val container = (application as AndrecordApplication).container
        wavFile = File(filesDir, "audio/$id.wav").apply { parentFile?.mkdirs() }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            scope.launch { container.sessionRepository.markError(id, "Microphone permission not granted") }
            stopSelf()
            return
        }
        if (!hasEnoughStorage()) {
            scope.launch { container.sessionRepository.markError(id, "Not enough storage to record") }
            stopSelf()
            return
        }

        val notification = buildNotification("Recording…")
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        vibrate(longArrayOf(0, 150))

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2
        )
        audioRecord = record
        container.streamingAsrEngine.start()
        record.startRecording()

        scope.launch {
            val pcmFile = RandomAccessFile(wavFile, "rw")
            writeWavPlaceholderHeader(pcmFile)
            val buffer = ShortArray(minBufferSize)
            var lastFlush = System.currentTimeMillis()
            var failureReason: String? = null

            while (audioRecord != null) {
                if (!hasEnoughStorage()) {
                    failureReason = "Ran out of storage"
                    break
                }
                val read = try {
                    record.read(buffer, 0, buffer.size)
                } catch (e: SecurityException) {
                    failureReason = "Microphone permission was revoked"
                    break
                }
                if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
                    failureReason = "Microphone became unavailable"
                    break
                }
                if (read <= 0) continue
                val floatSamples = FloatArray(read) { buffer[it] / 32768.0f }
                pcmFile.write(shortArrayToBytes(buffer, read))

                container.streamingAsrEngine.acceptWaveform(floatSamples)
                var event = container.streamingAsrEngine.poll()
                while (event != null) {
                    if (event is AsrEvent.Final) {
                        container.pendingAsrSegments.getOrPut(id) { mutableListOf() }.add(event)
                    }
                    event = container.streamingAsrEngine.poll()
                }

                if (System.currentTimeMillis() - lastFlush > FLUSH_INTERVAL_MS) {
                    flushPendingSegments(id)
                    lastFlush = System.currentTimeMillis()
                }
            }
            finalizeWavHeader(pcmFile)
            pcmFile.close()

            if (failureReason != null) {
                // Preserve whatever was already flushed to the DB rather than losing the session.
                flushPendingSegments(id)
                audioRecord?.release()
                audioRecord = null
                container.streamingAsrEngine.stop()
                container.sessionRepository.markError(id, failureReason)
                ServiceCompat.stopForeground(this@RecordingService, Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun hasEnoughStorage(): Boolean {
        val stat = android.os.StatFs(filesDir.path)
        return stat.availableBytes > MIN_FREE_BYTES
    }

    private suspend fun flushPendingSegments(id: String) {
        val container = (application as AndrecordApplication).container
        val pending = container.pendingAsrSegments[id].orEmpty().toList()
        for (segment in pending) {
            container.sessionRepository.appendSegment(
                TranscriptSegment(sessionId = id, startMs = segment.startMs, endMs = segment.endMs, speakerLabel = null, text = segment.text)
            )
        }
    }

    private fun stopRecording() {
        val id = sessionId ?: return
        val container = (application as AndrecordApplication).container
        val endTime = System.currentTimeMillis()
        val durationMs = endTime - startTime

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        container.streamingAsrEngine.stop()
        vibrate(longArrayOf(0, 100, 100, 100))

        scope.launch {
            container.sessionRepository.markProcessing(
                id, endTime, durationMs,
                audioFilePath = wavFile!!.absolutePath,
                audioDeleteAt = endTime + TimeUnit.DAYS.toMillis(7)
            )
            val request = OneTimeWorkRequestBuilder<DiarizationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(DiarizationWorker.KEY_SESSION_ID, id)
                        .putString(DiarizationWorker.KEY_WAV_PATH, wavFile!!.absolutePath)
                        .putLong(DiarizationWorker.KEY_DURATION_MS, durationMs)
                        .putLong(DiarizationWorker.KEY_START_TIME, startTime)
                        .build()
                )
                .build()
            WorkManager.getInstance(applicationContext).enqueue(request)
            ServiceCompat.stopForeground(this@RecordingService, Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(text)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun writeWavPlaceholderHeader(file: RandomAccessFile) {
        file.write(ByteArray(44)) // rewritten with real sizes in finalizeWavHeader
    }

    private fun finalizeWavHeader(file: RandomAccessFile) {
        val dataSize = file.length() - 44
        file.seek(0)
        file.write(buildWavHeader(dataSize.toInt(), SAMPLE_RATE))
    }

    private fun buildWavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val totalSize = 36 + dataSize
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(totalSize); header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()); header.putInt(16); header.putShort(1); header.putShort(1)
        header.putInt(sampleRate); header.putInt(sampleRate * 2); header.putShort(2); header.putShort(16)
        header.put("data".toByteArray()); header.putInt(dataSize)
        return header.array()
    }

    private fun shortArrayToBytes(samples: ShortArray, count: Int): ByteArray {
        val bytes = java.nio.ByteBuffer.allocate(count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        return bytes.array()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_START = "com.andrecord.app.action.START"
        const val ACTION_STOP = "com.andrecord.app.action.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "recording"
        private const val SAMPLE_RATE = 16000
        private const val FLUSH_INTERVAL_MS = 5000L
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024 // 50MB headroom
    }
}
```

`markError` (added to `SessionRepository` in Task 3) already handles the "partial transcript preserved" requirement automatically — segments flushed before the failure remain in the DB untouched, since `markError` only updates the `Session` row, not `TranscriptSegment` rows.

- [ ] **Step 2: Register the service in `AndroidManifest.xml`**

Add inside `<application>`, after the `<activity>` block:

```xml
        <service
            android:name=".recording.RecordingService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
```

- [ ] **Step 3: Implement the real `RecordingServiceStarter`**

Add to the bottom of `RecordingServiceStarter.kt` (from Task 5):

```kotlin
class AndroidRecordingServiceStarter(private val context: android.content.Context) : RecordingServiceStarter {
    override fun startRecording(sessionId: String) {
        val intent = android.content.Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_SESSION_ID, sessionId)
        context.startForegroundService(intent)
    }

    override fun stopRecording() {
        val intent = android.content.Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_STOP)
        context.startService(intent)
    }
}
```

- [ ] **Step 4: Write an instrumented smoke test**

```kotlin
package com.andrecord.app.recording

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingServiceInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    @Test
    fun `service starts and stops without crashing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val starter = AndroidRecordingServiceStarter(context)

        starter.startRecording("smoke-test-session")
        Thread.sleep(3000)
        starter.stopRecording()
        Thread.sleep(1000)
        // Manual verification: check logcat for no crash, and confirm
        // filesDir/audio/smoke-test-session.wav exists and is non-empty.
    }
}
```

- [ ] **Step 5: Run on the Pixel 10 Pro Fold and manually verify**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.andrecord.app.recording.RecordingServiceInstrumentedTest"`
Expected: test completes without crash; manually confirm `audio/smoke-test-session.wav` exists in app-private storage and the lock-screen notification appeared with a working Stop action while the test ran.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/andrecord/app/recording/RecordingService.kt app/src/main/java/com/andrecord/app/recording/RecordingServiceStarter.kt app/src/main/AndroidManifest.xml app/src/androidTest/java/com/andrecord/app/recording
git commit -m "Add RecordingService foreground service with live ASR and WAV capture"
```

---

### Task 11: Quick Tap trigger (App Shortcut + trampoline activity)

**Files:**
- Create: `app/src/main/res/xml/shortcuts.xml`
- Create: `app/src/main/java/com/andrecord/app/triggers/TriggerTrampolineActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `RecordingController` (Task 5, provided by `AndrecordApplication.container`, wired in Task 12)
- Produces: an app shortcut with id `toggle_recording` that Quick Tap can be bound to.

- [ ] **Step 1: Create `app/src/main/res/xml/shortcuts.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
    <shortcut
        android:shortcutId="toggle_recording"
        android:enabled="true"
        android:icon="@android:drawable/ic_btn_speak_now"
        android:shortcutShortLabel="@string/shortcut_toggle_recording">
        <intent
            android:action="android.intent.action.VIEW"
            android:targetPackage="com.andrecord.app"
            android:targetClass="com.andrecord.app.triggers.TriggerTrampolineActivity" />
    </shortcut>
</shortcuts>
```

Add the string to `app/src/main/res/values/strings.xml`:

```xml
    <string name="shortcut_toggle_recording">Start/stop recording</string>
```

- [ ] **Step 2: Implement `TriggerTrampolineActivity.kt`**

```kotlin
package com.andrecord.app.triggers

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.andrecord.app.AndrecordApplication
import kotlinx.coroutines.launch

class TriggerTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(false)

        val controller = (application as AndrecordApplication).container.recordingController
        lifecycleScope.launch {
            controller.toggle()
            finish()
        }
    }
}
```

- [ ] **Step 3: Register in `AndroidManifest.xml`**

Add inside `<application>`, after the `RecordingService` entry:

```xml
        <activity
            android:name=".triggers.TriggerTrampolineActivity"
            android:exported="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar"
            android:excludeFromRecents="true"
            android:showOnLockScreen="true" />
```

Add the shortcuts meta-data to the existing `MainActivity` entry (inside its `<activity>` tag, after the `<intent-filter>` closes):

```xml
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/shortcuts" />
```

- [ ] **Step 4: Manual verification on device**

1. Install the app, open Settings → System → Gestures → Quick Tap, choose "Open app shortcut," pick Andrecord's "Start/stop recording" shortcut.
2. Lock the phone. Double-tap the back of the phone.
3. Expected: a single vibration pulse, the lock-screen recording notification appears, no screen turns on or unlock is required.
4. Double-tap again: double vibration pulse, notification disappears, "Meeting transcript ready" notification appears shortly after (once diarization finishes).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/xml/shortcuts.xml app/src/main/res/values/strings.xml app/src/main/java/com/andrecord/app/triggers/TriggerTrampolineActivity.kt app/src/main/AndroidManifest.xml
git commit -m "Add Quick Tap app shortcut and lock-screen trampoline activity"
```

---

### Task 12: AndrecordApplication (AppContainer wiring)

**Files:**
- Create: `app/src/main/java/com/andrecord/app/AndrecordApplication.kt`

**Interfaces:**
- Consumes: everything from Tasks 2–9 and 11
- Produces: `AndrecordApplication.container: AppContainer` with fields `sessionRepository: SessionRepository`, `streamingAsrEngine: StreamingAsrEngine`, `diarizationEngine: DiarizationEngine`, `recordingController: RecordingController`, `pendingAsrSegments: MutableMap<String, MutableList<AsrEvent.Final>>`

This wires together every prior task's real (non-fake) implementation for the first time — this is where the app actually becomes runnable end-to-end.

- [ ] **Step 1: Implement `AndrecordApplication.kt`**

```kotlin
package com.andrecord.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andrecord.app.asr.AsrEvent
import com.andrecord.app.asr.SherpaOnnxStreamingAsrEngine
import com.andrecord.app.asr.StreamingAsrEngine
import com.andrecord.app.data.AndrecordDatabase
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.diarization.DiarizationEngine
import com.andrecord.app.diarization.SherpaOnnxDiarizationEngine
import com.andrecord.app.recording.AndroidRecordingServiceStarter
import com.andrecord.app.recording.RecordingController
import com.andrecord.app.workers.RetentionWorker
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    private val database = AndrecordDatabase.build(app)

    val sessionRepository = SessionRepository(
        database.sessionDao(),
        database.transcriptSegmentDao()
    ) { path -> File(path).delete() }

    val streamingAsrEngine: StreamingAsrEngine = SherpaOnnxStreamingAsrEngine(app)
    val diarizationEngine: DiarizationEngine = SherpaOnnxDiarizationEngine(app)
    val recordingController = RecordingController(sessionRepository, AndroidRecordingServiceStarter(app))
    val pendingAsrSegments = ConcurrentHashMap<String, MutableList<AsrEvent.Final>>()
}

class AndrecordApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scheduleRetention()
    }

    private fun scheduleRetention() {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "audio_retention", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}
```

- [ ] **Step 2: Verify the full app compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Re-run the full unit test suite to confirm Tasks 6/7's deferred references now compile clean**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/andrecord/app/AndrecordApplication.kt
git commit -m "Wire AppContainer: real engines, repository, controller, and retention scheduling"
```

---

### Task 13: Volume-key trigger (AccessibilityService)

**Files:**
- Create: `app/src/main/res/xml/accessibility_service_config.xml`
- Create: `app/src/main/java/com/andrecord/app/triggers/KeyTriggerAccessibilityService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/andrecord/app/triggers/LongPressDetectorTest.kt`

**Interfaces:**
- Consumes: `RecordingController` (Task 5/12)
- Produces: `LongPressDetector(holdMs: Long = 1000L, clock: () -> Long)` — pure hold-duration logic extracted from the service so it's unit-testable — with `fun onKeyDown()`, `fun onKeyUp(): Boolean` (returns true if the hold qualified as a long-press)

- [ ] **Step 1: Write the failing test for the pure long-press logic**

```kotlin
package com.andrecord.app.triggers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressDetectorTest {

    @Test
    fun `holding for 1000ms or more counts as a long press`() {
        var now = 0L
        val detector = LongPressDetector(holdMs = 1000L, clock = { now })

        detector.onKeyDown()
        now = 1000L
        val result = detector.onKeyUp()

        assertTrue(result)
    }

    @Test
    fun `holding for less than 1000ms does not count`() {
        var now = 0L
        val detector = LongPressDetector(holdMs = 1000L, clock = { now })

        detector.onKeyDown()
        now = 500L
        val result = detector.onKeyUp()

        assertFalse(result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.triggers.LongPressDetectorTest"`
Expected: FAIL (compilation error — `LongPressDetector` doesn't exist)

- [ ] **Step 3: Implement `LongPressDetector` (add to a new file `KeyTriggerAccessibilityService.kt`, defined above the service class)**

```kotlin
package com.andrecord.app.triggers

class LongPressDetector(private val holdMs: Long = 1000L, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var downAt: Long? = null

    fun onKeyDown() {
        if (downAt == null) downAt = clock()
    }

    fun onKeyUp(): Boolean {
        val start = downAt ?: return false
        downAt = null
        return (clock() - start) >= holdMs
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.triggers.LongPressDetectorTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Implement `KeyTriggerAccessibilityService` in the same file**

```kotlin
package com.andrecord.app.triggers

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.andrecord.app.AndrecordApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class KeyTriggerAccessibilityService : AccessibilityService() {

    private val detector = LongPressDetector()
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> detector.onKeyDown()
            KeyEvent.ACTION_UP -> {
                if (detector.onKeyUp()) {
                    val controller = (application as AndrecordApplication).container.recordingController
                    scope.launch { controller.toggle() }
                    return true // consume the event so volume doesn't also change
                }
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
```

- [ ] **Step 6: Create `app/src/main/res/xml/accessibility_service_config.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRequestFilterKeyEvents="true"
    android:notificationTimeout="0"
    android:description="@string/accessibility_service_description" />
```

- [ ] **Step 7: Add strings and manifest entry**

Add to `strings.xml`:

```xml
    <string name="accessibility_service_description">Lets Andrecord start/stop recording when you long-press Volume Down, even while the phone is locked.</string>
```

Add inside `<application>` in `AndroidManifest.xml`, after `TriggerTrampolineActivity`:

```xml
        <service
            android:name=".triggers.KeyTriggerAccessibilityService"
            android:exported="false"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
```

- [ ] **Step 8: Manual verification on device**

1. Install the app, go to Settings → Accessibility → Andrecord, enable it.
2. Lock the phone.
3. Hold Volume Down for 1 second.
4. Expected: single vibration, recording notification appears, screen stays off/locked, no volume change happened.
5. Hold Volume Down for 1 second again: double vibration, recording stops.
6. Tap Volume Down briefly (normal use): confirm volume changes normally and recording is NOT triggered.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/xml/accessibility_service_config.xml app/src/main/java/com/andrecord/app/triggers/KeyTriggerAccessibilityService.kt app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/test/java/com/andrecord/app/triggers/LongPressDetectorTest.kt
git commit -m "Add long-press Volume Down trigger via AccessibilityService"
```

---

### Task 14: Compose theme (colors, typography)

**Files:**
- Create: `app/src/main/java/com/andrecord/app/ui/theme/Color.kt`
- Create: `app/src/main/java/com/andrecord/app/ui/theme/Type.kt`
- Create: `app/src/main/java/com/andrecord/app/ui/theme/Theme.kt`
- Modify: `app/build.gradle.kts` (add Google Fonts provider dependency)

**Interfaces:**
- Produces: `AndrecordColors` object (all spec §6 hex tokens), `SpeakerColors` list, `AndrecordTypography` (`displayFont`, `bodyFont`, `monoFont` as `FontFamily`), `@Composable fun AndrecordTheme(content: @Composable () -> Unit)`

- [ ] **Step 1: Add the Google Fonts dependency**

Add to `app/build.gradle.kts` dependencies block:

```kotlin
    implementation("androidx.compose.ui:ui-text-google-fonts:1.7.0")
```

- [ ] **Step 2: Implement `Color.kt`**

```kotlin
package com.andrecord.app.ui.theme

import androidx.compose.ui.graphics.Color

object AndrecordColors {
    val Ink900 = Color(0xFF14181F)
    val Ink600 = Color(0xFF3A4150)
    val Paper50 = Color(0xFFF6F3EC)
    val Brass500 = Color(0xFFC89B3C)
}

val SpeakerColors = listOf(
    Color(0xFF4FA3A0), // teal
    Color(0xFFC97064), // rose
    Color(0xFF7C87C9), // periwinkle
    Color(0xFF7C9A5C), // moss
)

fun speakerColorFor(speakerIndex: Int): Color = SpeakerColors[speakerIndex % SpeakerColors.size]
```

- [ ] **Step 3: Implement `Type.kt`**

```kotlin
package com.andrecord.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.GoogleFont
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.andrecord.app.R

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val DisplayFont = FontFamily(Font(GoogleFont("Space Grotesk"), provider))
val BodyFont = FontFamily(Font(GoogleFont("Source Serif 4"), provider))
val MonoFont = FontFamily(Font(GoogleFont("IBM Plex Mono"), provider))

val AndrecordTypography = Typography(
    titleLarge = TextStyle(fontFamily = DisplayFont, fontSize = 24.sp),
    titleMedium = TextStyle(fontFamily = DisplayFont, fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 15.sp, lineHeight = 22.sp),
    labelSmall = TextStyle(fontFamily = MonoFont, fontSize = 12.sp),
)
```

*(Note: `R.array.com_google_android_gms_fonts_certs` requires the standard Google Fonts certs resource — add `app/src/main/res/values/font_certs.xml` from the [androidx Google Fonts setup guide](https://developer.android.com/develop/ui/compose/text/fonts#downloadable-fonts) if it isn't already present in the compose starter template.)*

- [ ] **Step 4: Implement `Theme.kt`**

```kotlin
package com.andrecord.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AndrecordDarkScheme = darkColorScheme(
    background = AndrecordColors.Ink900,
    surface = AndrecordColors.Ink900,
    onBackground = AndrecordColors.Paper50,
    onSurface = AndrecordColors.Paper50,
    primary = AndrecordColors.Brass500,
    onPrimary = AndrecordColors.Ink900,
    outline = AndrecordColors.Ink600,
)

@Composable
fun AndrecordTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AndrecordDarkScheme,
        typography = AndrecordTypography,
        content = content
    )
}
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/andrecord/app/ui/theme
git commit -m "Add Andrecord voice-ledger theme: colors, typography"
```

---

### Task 15: Speaker timeline strip component

**Files:**
- Create: `app/src/main/java/com/andrecord/app/ui/components/SpeakerTimelineStrip.kt`
- Test: `app/src/test/java/com/andrecord/app/ui/components/TimelineProportionsTest.kt`

**Interfaces:**
- Consumes: `TranscriptSegment` (Task 2), `speakerColorFor` (Task 14)
- Produces: `computeTimelineProportions(segments: List<TranscriptSegment>): List<Pair<String?, Float>>` (speaker label to fraction-of-total-duration, in chronological order, adjacent-same-speaker runs merged) and `@Composable fun SpeakerTimelineStrip(segments: List<TranscriptSegment>, modifier: Modifier = Modifier)`

- [ ] **Step 1: Write the failing test for the pure proportions logic**

```kotlin
package com.andrecord.app.ui.components

import com.andrecord.app.data.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineProportionsTest {

    @Test
    fun `merges adjacent same-speaker segments and computes fractions`() {
        val segments = listOf(
            TranscriptSegment(sessionId = "s1", startMs = 0, endMs = 1000, speakerLabel = "Speaker 1", text = "a"),
            TranscriptSegment(sessionId = "s1", startMs = 1000, endMs = 2000, speakerLabel = "Speaker 1", text = "b"),
            TranscriptSegment(sessionId = "s1", startMs = 2000, endMs = 4000, speakerLabel = "Speaker 2", text = "c"),
        )

        val result = computeTimelineProportions(segments)

        assertEquals(listOf("Speaker 1" to 0.5f, "Speaker 2" to 0.5f), result)
    }

    @Test
    fun `empty segments produces empty proportions`() {
        assertEquals(emptyList<Pair<String?, Float>>(), computeTimelineProportions(emptyList()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.ui.components.TimelineProportionsTest"`
Expected: FAIL (compilation error — function doesn't exist)

- [ ] **Step 3: Implement `computeTimelineProportions` and the composable in `SpeakerTimelineStrip.kt`**

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.andrecord.app.ui.components.TimelineProportionsTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/ui/components/SpeakerTimelineStrip.kt app/src/test/java/com/andrecord/app/ui/components/TimelineProportionsTest.kt
git commit -m "Add speaker timeline strip signature component"
```

---

### Task 16: Session list screen

**Files:**
- Create: `app/src/main/java/com/andrecord/app/ui/list/SessionListViewModel.kt`
- Create: `app/src/main/java/com/andrecord/app/ui/list/SessionListScreen.kt`

**Interfaces:**
- Consumes: `SessionRepository.observeSessions()` / `observeSegments()` (Task 3), `RecordingController` (Task 5), `SpeakerTimelineStrip` (Task 15)
- Produces: `SessionListViewModel(repository, recordingController)` exposing `val sessions: StateFlow<List<Session>>`, `val segmentsBySession: StateFlow<Map<String, List<TranscriptSegment>>>`, `val recordingState: StateFlow<RecordingState>`, `fun onRecordButtonClick()`; `@Composable fun SessionListScreen(viewModel: SessionListViewModel, onSessionClick: (String) -> Unit)`

- [ ] **Step 1: Implement `SessionListViewModel.kt`**

```kotlin
package com.andrecord.app.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andrecord.app.data.Session
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.recording.RecordingController
import com.andrecord.app.recording.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionListViewModel(
    private val repository: SessionRepository,
    private val recordingController: RecordingController
) : ViewModel() {

    val sessions: StateFlow<List<Session>> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val segmentsBySession: StateFlow<Map<String, List<TranscriptSegment>>> =
        channelFlow {
            sessions.collect { sessionList ->
                val flows = sessionList.map { s -> repository.observeSegments(s.id) }
                if (flows.isEmpty()) {
                    send(emptyMap())
                } else {
                    combine(flows) { arrays ->
                        sessionList.mapIndexed { i, s -> s.id to arrays[i].toList() }.toMap()
                    }.collect { send(it) }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _recordingState = MutableStateFlow(recordingController.currentState())
    val recordingState: StateFlow<RecordingState> = _recordingState

    fun onRecordButtonClick() {
        viewModelScope.launch {
            _recordingState.value = recordingController.toggle()
        }
    }
}
```

- [ ] **Step 2: Implement `SessionListScreen.kt`**

```kotlin
package com.andrecord.app.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andrecord.app.data.Session
import com.andrecord.app.data.TranscriptSegment
import com.andrecord.app.recording.RecordingState
import com.andrecord.app.ui.components.SpeakerTimelineStrip

@Composable
fun SessionListScreen(viewModel: SessionListViewModel, onSessionClick: (String) -> Unit) {
    val sessions by viewModel.sessions.collectAsState()
    val segmentsBySession by viewModel.segmentsBySession.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.onRecordButtonClick() }) {
                Icon(
                    imageVector = if (recordingState == RecordingState.RECORDING) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (recordingState == RecordingState.RECORDING) "Stop recording" else "Start recording"
                )
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    segments = segmentsBySession[session.id].orEmpty(),
                    onClick = { onSessionClick(session.id) }
                )
            }
        }
    }
}

@Composable
private fun SessionRow(session: Session, segments: List<TranscriptSegment>, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().clickable(onClick = onClick)) {
        Text(text = session.title, style = MaterialTheme.typography.titleMedium)
        session.speakerCount?.let {
            Text(text = "$it speaker${if (it == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall)
        }
        Box(modifier = Modifier.padding(top = 6.dp)) {
            SpeakerTimelineStrip(segments = segments)
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/andrecord/app/ui/list
git commit -m "Add session list screen with record FAB and per-session timeline strips"
```

---

### Task 17: Session detail screen

**Files:**
- Create: `app/src/main/java/com/andrecord/app/ui/detail/SessionDetailViewModel.kt`
- Create: `app/src/main/java/com/andrecord/app/ui/detail/SessionDetailScreen.kt`

**Interfaces:**
- Consumes: `SessionRepository` (Task 3), `SpeakerTimelineStrip` (Task 15)
- Produces: `SessionDetailViewModel(repository, sessionId)` exposing `val session: StateFlow<Session?>`, `val segments: StateFlow<List<TranscriptSegment>>`, `fun rename(newTitle: String)`, `fun delete()`, `fun buildShareText(): String`; `@Composable fun SessionDetailScreen(viewModel: SessionDetailViewModel, onDeleted: () -> Unit)`

- [ ] **Step 1: Implement `SessionDetailViewModel.kt`**

```kotlin
package com.andrecord.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andrecord.app.data.Session
import com.andrecord.app.data.SessionRepository
import com.andrecord.app.data.TranscriptSegment
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SessionDetailViewModel(
    private val repository: SessionRepository,
    private val sessionId: String
) : ViewModel() {

    val session: StateFlow<Session?> = repository.observeSessions()
        .map { list -> list.firstOrNull { it.id == sessionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val segments: StateFlow<List<TranscriptSegment>> = repository.observeSegments(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rename(newTitle: String) {
        viewModelScope.launch { repository.rename(sessionId, newTitle) }
    }

    fun delete() {
        viewModelScope.launch { repository.delete(sessionId) }
    }

    fun buildShareText(): String =
        segments.value.joinToString("\n\n") { seg ->
            "[${seg.speakerLabel ?: "Unknown"}] ${seg.text}"
        }
}
```

- [ ] **Step 2: Implement `SessionDetailScreen.kt`**

```kotlin
package com.andrecord.app.ui.detail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andrecord.app.ui.components.SpeakerTimelineStrip

@Composable
fun SessionDetailScreen(viewModel: SessionDetailViewModel, onDeleted: () -> Unit) {
    val session by viewModel.session.collectAsState()
    val segments by viewModel.segments.collectAsState()
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(renameText)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.title.orEmpty()) },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = {
                            menuExpanded = false
                            renameText = session?.title.orEmpty()
                            showRenameDialog = true
                        })
                        DropdownMenuItem(text = { Text("Share as text") }, onClick = {
                            menuExpanded = false
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, viewModel.buildShareText())
                            }
                            context.startActivity(Intent.createChooser(intent, "Share transcript"))
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = {
                            menuExpanded = false
                            viewModel.delete()
                            onDeleted()
                        })
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            session?.let { s ->
                Text(text = "${s.durationMs?.div(60000) ?: 0} min · ${s.speakerCount ?: 0} speakers",
                    style = MaterialTheme.typography.labelSmall)
                SpeakerTimelineStrip(segments = segments, modifier = Modifier.padding(vertical = 8.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(segments) { segment ->
                    Column {
                        Text(text = segment.speakerLabel ?: "Unknown", style = MaterialTheme.typography.labelSmall)
                        Text(text = segment.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/andrecord/app/ui/detail
git commit -m "Add session detail screen with rename, delete, and share"
```

---

### Task 18: Adaptive navigation host + MainActivity

**Files:**
- Create: `app/src/main/java/com/andrecord/app/ui/AndrecordApp.kt`
- Create: `app/src/main/java/com/andrecord/app/MainActivity.kt`

**Interfaces:**
- Consumes: `SessionListScreen`, `SessionListViewModel` (Task 16), `SessionDetailScreen`, `SessionDetailViewModel` (Task 17), `AndrecordTheme` (Task 14), `AndrecordApplication.container` (Task 12)
- Produces: `@Composable fun AndrecordApp(container: com.andrecord.app.AppContainer)` — the adaptive list-detail root; `MainActivity`

- [ ] **Step 1: Implement `AndrecordApp.kt`**

```kotlin
package com.andrecord.app.ui

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.andrecord.app.AppContainer
import com.andrecord.app.ui.detail.SessionDetailScreen
import com.andrecord.app.ui.detail.SessionDetailViewModel
import com.andrecord.app.ui.list.SessionListScreen
import com.andrecord.app.ui.list.SessionListViewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AndrecordApp(container: AppContainer) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    var selectedSessionId by remember { mutableStateOf<String?>(null) }

    val listViewModel = remember { SessionListViewModel(container.sessionRepository, container.recordingController) }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                SessionListScreen(
                    viewModel = listViewModel,
                    onSessionClick = { id ->
                        selectedSessionId = id
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id)
                    }
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val id = selectedSessionId
                if (id != null) {
                    val detailViewModel = viewModel(key = id) { SessionDetailViewModel(container.sessionRepository, id) }
                    SessionDetailScreen(
                        viewModel = detailViewModel,
                        onDeleted = { navigator.navigateBack() }
                    )
                }
            }
        }
    )
}
```

- [ ] **Step 2: Implement `MainActivity.kt`**

```kotlin
package com.andrecord.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.andrecord.app.ui.AndrecordApp
import com.andrecord.app.ui.theme.AndrecordTheme

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))

        setContent {
            AndrecordTheme {
                AndrecordApp(container = (application as AndrecordApplication).container)
            }
        }
    }
}
```

- [ ] **Step 3: Verify it builds and launches**

Run: `./gradlew :app:installDebug`
Expected: `BUILD SUCCESSFUL`; manually launch on the Pixel 10 Pro Fold, confirm the session list renders (empty state is fine), the record FAB starts/stops a session end-to-end, and tapping a completed session opens the detail view.

- [ ] **Step 4: Manually verify the foldable layout**

With the phone folded (cover screen), confirm the single-pane list renders. Unfold it, confirm the layout promotes to the two-pane list-detail view described in spec §6, with the currently selected session (if any) shown in the right pane.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/andrecord/app/ui/AndrecordApp.kt app/src/main/java/com/andrecord/app/MainActivity.kt
git commit -m "Wire adaptive list-detail navigation and launch MainActivity"
```

---

## Post-plan manual verification checklist

Run through this on the actual Pixel 10 Pro Fold once all tasks are complete — this exercises the parts no unit or instrumented test fully covers:

1. Start a recording from the in-app FAB, speak for ~1 minute with two voices, stop from the FAB. Confirm live text appeared while recording, and the finished session shows correct speaker labels and a plausible speaker count.
2. Lock the phone, trigger via Quick Tap, confirm vibration + notification + no screen wake, then stop the same way.
3. Lock the phone, trigger via 1-second Volume Down hold, confirm the same, and confirm a quick tap on Volume Down still just changes volume.
4. Kill the app from Recents mid-diarization (right after stopping a recording) and confirm the "transcript ready" notification still arrives once WorkManager resumes the job.
5. Wait (or manipulate `audioDeleteAt` via `adb shell` sqlite access) to confirm a session's audio is deleted after 7 days while its transcript remains.
6. Fold/unfold the phone while a session's detail view is open, confirm the layout transition behaves as designed.
