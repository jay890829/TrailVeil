package io.github.jay890829.trailveil

import android.app.Application
import io.github.jay890829.trailveil.recording.AppContainer
import io.github.jay890829.trailveil.recording.RecordingForegroundNotifier

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
