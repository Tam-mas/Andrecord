package com.andrecord.app.triggers

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.andrecord.app.AndrecordApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

class KeyTriggerAccessibilityService : AccessibilityService() {

    private val detector = LongPressDetector()

    // SupervisorJob (rather than a plain Job, as the brief's sketch had) so that an exception
    // from one toggle() call doesn't cancel the scope and silently break every subsequent
    // volume-key trigger for the remaining lifetime of the service. See the equivalent
    // reasoning on RecordingService.scope.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
