package app.trailveil

import android.app.Application
import app.trailveil.map.silenceMapRequestLogging
import app.trailveil.recording.AppContainer
import app.trailveil.recording.RecordingForegroundNotifier

class TrailVeilApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Provider-specific, and deliberately not implemented here: see the two declarations of
        // this function in the provider source sets, exactly one of which is in any variant.
        silenceMapRequestLogging()
        RecordingForegroundNotifier(this).ensureChannel()
    }

    /** Process-scoped dependencies; this intentionally does not start or recover recording. */
    internal val appContainer: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
}
