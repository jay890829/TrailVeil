package app.trailveil.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Final comparison choice: map diagnostics stay internal and draw no on-screen badge. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun GoogleFogArmBadge(
    modifier: Modifier = Modifier,
    lastCoverMillis: Long? = null,
    maximumCoverMillis: Long? = null,
    surfaceDescription: String? = null,
    stageSummary: String? = null,
) = Unit
