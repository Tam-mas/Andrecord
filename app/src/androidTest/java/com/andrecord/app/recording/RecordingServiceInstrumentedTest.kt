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
