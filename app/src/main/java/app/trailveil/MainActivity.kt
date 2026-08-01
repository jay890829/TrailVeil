package app.trailveil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.trailveil.navigation.TrailVeilNavHost
import app.trailveil.ui.theme.TrailVeilTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrailVeilTheme {
                TrailVeilNavHost(activity = this)
            }
        }
    }
}
