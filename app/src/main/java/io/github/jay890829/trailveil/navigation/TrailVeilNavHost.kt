package io.github.jay890829.trailveil.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.jay890829.trailveil.feature.placeholder.PlaceholderScreen

private const val PlaceholderRoute = "placeholder"

@Composable
fun TrailVeilNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = PlaceholderRoute,
    ) {
        composable(PlaceholderRoute) {
            PlaceholderScreen()
        }
    }
}
