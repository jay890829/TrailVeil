package app.trailveil.map

import android.content.Context
import androidx.startup.Initializer

/**
 * `V03-013`: applies the selected [GoogleFogArm] before anything can read it.
 *
 * The ordering is the whole point of using `androidx.startup` rather than `Application.onCreate`.
 * [GoogleMapWarmup] records the guarantee this relies on: androidx.startup runs an initializer
 * inside `InitializationProvider`'s `ContentProvider.onCreate`, which is **before
 * Application.onCreate and before any composition**. [GoogleFogCoverageArm]'s contract is that a
 * binding reads the profile once in its field initialisers, so any writer that ran later would
 * leave the first surface of the process on the previous arm - the exact misattribution the
 * on-map badge exists to make impossible.
 *
 * Failure is swallowed for the same reason [GoogleMapWarmup] swallows its own: androidx.startup
 * rethrows whatever escapes, inside a ContentProvider, so an unguarded throw kills the process at
 * launch. A harness selector must never be the reason the app will not start; falling back to the
 * shipped default is both safe and the correct reading of "the selector did not work".
 */
class GoogleFogArmInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        try {
            GoogleFogArm.stored(context).apply()
        } catch (_: Exception) {
            // Intentionally ignored; the process keeps GoogleFogCoverageArm's shipped defaults.
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
