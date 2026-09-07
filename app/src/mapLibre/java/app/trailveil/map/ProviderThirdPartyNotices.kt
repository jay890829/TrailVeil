package app.trailveil.map

import android.content.Context
import app.trailveil.R
import java.io.IOException

/**
 * The open-source notices for the map provider this build renders with.
 *
 * `V02-016`: declared once per provider source set, like [ProductionMapProvider], because the answer
 * is a different artifact in each - MapLibre ships a notice file, and there is nothing shared to put
 * in `src/main`. Putting either provider's answer in the shared set would package that provider's
 * material into the other's APK, which is the `V02-008` defect the split exists to prevent and which
 * `build-github-release.ps1` refuses on the artifact.
 *
 * MapLibre's notices are a 76 KB resource that `src/mapLibre/AndroidManifest.xml` already declares
 * as `app.trailveil.MAPLIBRE_THIRD_PARTY_NOTICES`. That declaration makes them discoverable to
 * tooling; this makes them readable by the user, which is the half that was missing.
 *
 * Reads from disk, so call it off the main thread.
 */
internal fun providerThirdPartyNotices(context: Context): String? = try {
    context.resources.openRawResource(R.raw.maplibre_third_party_notices)
        .bufferedReader()
        .use { reader -> reader.readText() }
} catch (_: IOException) {
    // The screen says the notices could not be loaded rather than showing an empty page. It cannot
    // be a crash: this is the screen a user opens BECAUSE something is unclear to them.
    null
}
