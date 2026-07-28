package io.github.jay890829.trailveil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.jay890829.trailveil.navigation.TrailVeilNavHost
import io.github.jay890829.trailveil.ui.theme.TrailVeilTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrailVeilTheme {
                TrailVeilNavHost()
            }
        }
    }
}
