package com.andrecord.app.triggers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.andrecord.app.AndrecordApplication
import kotlinx.coroutines.launch

class TriggerTrampolineActivity : ComponentActivity() {
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
