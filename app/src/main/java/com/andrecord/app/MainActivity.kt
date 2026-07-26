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
