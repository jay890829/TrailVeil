package app.trailveil.navigation

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.trailveil.TrailVeilApplication
import app.trailveil.data.history.RecordingHistoryDataSource
import app.trailveil.data.history.RecordingHistoryDetail
import app.trailveil.data.history.RecordingHistorySession
import app.trailveil.feature.history.RecordingHistoryDetailScreen
import app.trailveil.feature.history.RecordingHistoryListScreen
import app.trailveil.feature.recording.RecordingEntryRoute
import kotlinx.coroutines.flow.collectLatest

internal const val RecordingRoute = "recording"
internal const val HistoryRoute = "history"
private const val HistorySessionIdArgument = "sessionId"
private const val HistoryDetailRoutePattern = "history/{$HistorySessionIdArgument}"

private sealed interface HistoryRouteState<out T> {
    data object Loading : HistoryRouteState<Nothing>
    data class Loaded<T>(val value: T) : HistoryRouteState<T>
}

internal fun historyDetailRoute(sessionId: Long): String {
    require(sessionId > 0L) { "sessionId must be positive" }
    return "history/$sessionId"
}

@Composable
fun TrailVeilNavHost(activity: ComponentActivity) {
    val navController = rememberNavController()
    val history = remember(activity.application) {
        (activity.application as TrailVeilApplication).appContainer.recordingHistory
    }

    NavHost(
        navController = navController,
        startDestination = RecordingRoute,
    ) {
        composable(
            route = RecordingRoute,
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(HistoryBackTransitionDurationMillis),
                )
            },
        ) {
            RecordingEntryRoute(
                activity = activity,
                onOpenHistory = { navController.navigate(HistoryRoute) },
            )
        }
        composable(
            route = HistoryRoute,
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(HistoryBackTransitionDurationMillis),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(HistoryBackTransitionDurationMillis),
                )
            },
        ) {
            RecordingHistoryListRoute(
                history = history,
                onOpenSession = { sessionId ->
                    navController.navigate(historyDetailRoute(sessionId))
                },
            )
        }
        composable(
            route = HistoryDetailRoutePattern,
            arguments = listOf(
                navArgument(HistorySessionIdArgument) { type = NavType.LongType },
            ),
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(HistoryBackTransitionDurationMillis),
                )
            },
        ) { entry ->
            val sessionId = entry.arguments?.getLong(HistorySessionIdArgument)
            RecordingHistoryDetailRoute(
                history = history,
                sessionId = sessionId,
                onBack = navController::popBackStack,
            )
        }
    }
}

internal const val HistoryBackTransitionDurationMillis = 250

@Composable
private fun RecordingHistoryListRoute(
    history: RecordingHistoryDataSource,
    onOpenSession: (Long) -> Unit,
) {
    var state by remember(history) {
        mutableStateOf<HistoryRouteState<List<RecordingHistorySession>>>(
            HistoryRouteState.Loading,
        )
    }
    LaunchedEffect(history) {
        history.sessions().collectLatest { state = HistoryRouteState.Loaded(it) }
    }
    val sessions = (state as? HistoryRouteState.Loaded)?.value
    RecordingHistoryListScreen(
        sessions = sessions.orEmpty(),
        loading = sessions == null,
        onOpenSession = onOpenSession,
    )
}

@Composable
private fun RecordingHistoryDetailRoute(
    history: RecordingHistoryDataSource,
    sessionId: Long?,
    onBack: () -> Unit,
) {
    var state by remember(history, sessionId) {
        mutableStateOf<HistoryRouteState<RecordingHistoryDetail?>>(
            if (sessionId != null && sessionId > 0L) {
                HistoryRouteState.Loading
            } else {
                HistoryRouteState.Loaded(null)
            },
        )
    }
    LaunchedEffect(history, sessionId) {
        if (sessionId == null || sessionId <= 0L) return@LaunchedEffect
        history.sessionDetail(sessionId).collectLatest { state = HistoryRouteState.Loaded(it) }
    }
    val loaded = state as? HistoryRouteState.Loaded
    RecordingHistoryDetailScreen(
        detail = loaded?.value,
        loading = loaded == null,
        onBack = onBack,
    )
}
