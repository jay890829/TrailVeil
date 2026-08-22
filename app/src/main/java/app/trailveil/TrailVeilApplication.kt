package app.trailveil

import android.app.Application
import app.trailveil.recording.AppContainer
import app.trailveil.recording.RecordingForegroundNotifier
import org.maplibre.android.http.HttpLogger
import org.maplibre.android.log.Logger

class TrailVeilApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        silenceMapRequestLogging()
        RecordingForegroundNotifier(this).ensureChannel()
    }

    /**
     * A tile URL is a position. MapLibre logs one at DEBUG for every request it cancels, and the
     * `P5-002` capture on 2026-08-23 caught `.../14/13698/7027.pbf` — a 2.2 km square containing the
     * device, recoverable with three lines of arithmetic. `app/src/main` contains no logging calls of
     * any kind, so the map SDK is the ONLY path by which a recording session reaches logcat at all.
     *
     * `WARN` drops the VERBOSE/DEBUG request chatter that carries the URL while leaving real map
     * failures visible. `logRequestUrl` is a second switch rather than a redundant one:
     * `HttpLogger.logFailure` interpolates the URL into a message that still prints at WARN, and a
     * log level cannot reach inside an already-formatted string.
     *
     * This does NOT reduce what the tile provider sees — PLAN names that as its own boundary. It
     * stops the same fact being copied into a buffer any adb client can read.
     */
    private fun silenceMapRequestLogging() {
        Logger.setVerbosity(Logger.WARN)
        HttpLogger.logRequestUrl = false
    }
    /** Process-scoped dependencies; this intentionally does not start or recover recording. */
    internal val appContainer: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
}
