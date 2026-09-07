package app.trailveil.map

import android.content.Context
import app.trailveil.R
import java.io.IOException

/**
 * The open-source notices for the map provider this build renders with.
 *
 * `V02-016`: declared once per provider source set, like [ProductionMapProvider], because the answer
 * is a different artifact in each. Putting either provider's answer in the shared set would package
 * that provider's material into the other's APK, which is the `V02-008` defect the split exists to
 * prevent and which `build-github-release.ps1` refuses on the artifact.
 *
 * ### Why a checked-in file, and where its text comes from
 *
 * The obvious mechanism was `GoogleApiAvailability.getOpenSourceSoftwareLicenseInfo`, which lets
 * Play services answer for the whole family at runtime. **That method no longer exists** on the
 * artifact this project resolves - checked against `play-services-base 18.5.0`, and absent from
 * `GoogleApiAvailability`, `GoogleApiAvailabilityLight` and `GooglePlayServicesUtil` alike. The
 * remaining alternative was Google's `oss-licenses-plugin`, which would add a Gradle plugin, a
 * runtime dependency and its own `OssLicensesMenuActivity` to a published artifact whose audit
 * compares its permission set and its class graph against pinned lists, and would leave the two
 * variants unable to share one screen. The owner chose the file (2026-09-07).
 *
 * **The text is Google's own, not this repository's summary of it.** Every Play services AAR carries
 * `third_party_licenses.txt` and `third_party_licenses.json` at its ROOT - not under `res/`, which
 * is why nothing of the kind appears in the built APK's resource table, and why this task began by
 * recording that the Google build packaged no notices at all. Harvesting those two files is exactly
 * what the plugin does; `R.raw.google_third_party_notices` is the result of doing it once, by hand,
 * and checking it in. `ThirdPartyNoticesTest` is what keeps it honest as the dependency moves.
 *
 * Reads from disk, so call it off the main thread.
 */
internal fun providerThirdPartyNotices(context: Context): String? = try {
    context.resources.openRawResource(R.raw.google_third_party_notices)
        .bufferedReader()
        .use { reader -> reader.readText() }
} catch (_: IOException) {
    // The screen says the notices could not be loaded rather than showing an empty page. It cannot
    // be a crash: this is the screen a user opens BECAUSE something is unclear to them.
    null
}
