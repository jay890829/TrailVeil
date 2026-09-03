package app.trailveil.googlepoc

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * `V02-005` stage 3: one evidence line, three channels — the instrumentation stream (gradle
 * stdout / test-results XML), a fixed logcat tag (`adb logcat -d -s TrailVeilSpike:I`, the only
 * pull-free channel on the playstore image where shell cannot read /sdcard/Android/data), and an
 * append-only file under the app's external files dir (pullable on the google_apis AVDs).
 * Lines must stay coordinate-free by construction: counters, timings, booleans, enum names.
 */
// LogNotTimber is suppressed for this object alone, and only for the logcat evidence channel:
// Timber is a production dependency planted by the app process, while this line must be readable
// with `adb logcat -d -s TrailVeilSpike:I` from an instrumentation process on the playstore image,
// where shell cannot read the app's external files dir. Routing it through Timber would tie a test
// evidence channel to production log configuration and lose the fixed, greppable tag.
@Suppress("LogNotTimber")
object SpikeEvidence {
    const val LOG_TAG = "TrailVeilSpike"

    fun emit(context: Context, fileName: String, line: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", "$line\n") },
        )
        Log.i(LOG_TAG, line)
        try {
            val directory = File(context.getExternalFilesDir(null), "spike-evidence")
            directory.mkdirs()
            File(directory, fileName).appendText(line + "\n")
        } catch (_: Exception) {
            // The stream and logcat channels already carry the line; file loss is non-fatal.
        }
    }

    /** PNG evidence (LEAK frames, worst frames). Fixture data is synthetic; privacy-cleared. */
    fun savePng(context: Context, bitmap: android.graphics.Bitmap, name: String): String? = try {
        val directory = File(context.getExternalFilesDir(null), "spike-evidence")
        directory.mkdirs()
        val file = File(directory, name)
        file.outputStream().use { stream ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        file.name
    } catch (_: Exception) {
        null
    }
}
