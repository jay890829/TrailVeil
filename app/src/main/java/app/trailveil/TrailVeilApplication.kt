package app.trailveil

import android.app.Application
import app.trailveil.recording.AppContainer
import app.trailveil.recording.RecordingForegroundNotifier

class TrailVeilApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordingForegroundNotifier(this).ensureChannel()
    }
    /** Process-scoped dependencies; this intentionally does not start or recover recording. */
    internal val appContainer: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
}
