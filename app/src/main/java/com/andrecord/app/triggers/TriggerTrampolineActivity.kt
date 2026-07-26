package com.andrecord.app.triggers

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.andrecord.app.AndrecordApplication
import kotlinx.coroutines.launch

class TriggerTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(false)

        val controller = (application as AndrecordApplication).container.recordingController
        (this as LifecycleOwner).lifecycleScope.launch {
            controller.toggle()
            finish()
        }
    }
}
