package com.fireemblem.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fireemblem.companion.ui.FireEmblemCompanionApp
import com.fireemblem.companion.ui.theme.FireEmblemTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FireEmblemTheme {
                FireEmblemCompanionApp()
            }
        }
    }
}
