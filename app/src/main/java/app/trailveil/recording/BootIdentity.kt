package app.trailveil.recording

import android.content.Context
import android.provider.Settings

/**
 * Which boot the device is currently in, or null when the platform will not say.
 *
 * `V02-015`. The app has to be able to tell "the recording process died and the row outlived it"
 * from "the device restarted under this exploration", because it may continue the first and must
 * not continue the second. It used to tell them apart by arithmetic on the wall clock, which a time
 * sync or a manual change breaks - see [app.trailveil.feature.recording.bootContinuity] for what
 * that cost. A counter that increases once per boot answers the question without reference to time.
 *
 * A seam rather than a direct call, because the decision it feeds is the one that arms location
 * collection: JVM tests need to state "same boot", "different boot" and "the platform refused"
 * without a device, and a static platform read cannot be asked to be any of them.
 */
internal fun interface BootIdentitySource {
    /** The current boot's identity, or null when it cannot be established. */
    fun current(): Long?
}

/**
 * `Settings.Global.BOOT_COUNT`: incremented by the platform on every boot since API 24.
 *
 * Null on refusal rather than a substituted value. The two failures it can have - the setting is
 * absent on this build, or reading it throws - are both "this device will not tell us", and the
 * decision above has a branch for exactly that which asks the user instead of guessing. Inventing a
 * constant here would turn every such device into a permanent "same boot", which is the answer that
 * resumes recording nobody asked for.
 */
internal fun androidBootIdentitySource(context: Context): BootIdentitySource {
    val applicationContext = context.applicationContext
    return BootIdentitySource {
        try {
            Settings.Global.getInt(
                applicationContext.contentResolver,
                Settings.Global.BOOT_COUNT,
            ).toLong()
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
