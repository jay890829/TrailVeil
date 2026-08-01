package app.trailveil.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.trailveil.feature.recording.RecordingEntryRoute

internal const val RecordingRoute = "recording"

@Composable
fun TrailVeilNavHost(activity: ComponentActivity) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = RecordingRoute,
    ) {
        composable(RecordingRoute) {
            RecordingEntryRoute(activity = activity)
        }
    }
}