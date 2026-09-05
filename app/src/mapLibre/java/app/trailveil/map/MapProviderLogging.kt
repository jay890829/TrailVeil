package app.trailveil.map

import org.maplibre.android.http.HttpLogger
import org.maplibre.android.log.Logger

/**
 * Silences the map SDK's request logging, for the OpenFreeMap build.
 *
 * A tile URL is a position. MapLibre logs one at DEBUG for every request it cancels, and the
 * `P5-002` capture on 2026-08-23 caught `.../14/13698/7027.pbf` - a 2.2 km square containing the
 * device, recoverable with three lines of arithmetic. `app/src/main` contains no logging calls of
 * any kind, so the map SDK is the ONLY path by which a recording session reaches logcat at all.
 *
 * `WARN` drops the VERBOSE/DEBUG request chatter that carries the URL while leaving real map
 * failures visible. `logRequestUrl` is a second switch rather than a redundant one:
 * `HttpLogger.logFailure` interpolates the URL into a message that still prints at WARN, and a log
 * level cannot reach inside an already-formatted string.
 *
 * This does NOT reduce what the tile provider sees - PLAN names that as its own boundary. It stops
 * the same fact being copied into a buffer any adb client can read.
 *
 * `V02-008`: this lives beside the map surface it configures rather than in the shared Application
 * class, because the shared class must not name or link either provider's SDK. The provider source
 * sets are mutually exclusive by construction - each declares this same function, and exactly one
 * is compiled into any variant - which is the idiom `TrailVeilMapSurface` already uses.
 */
internal fun silenceMapRequestLogging() {
    Logger.setVerbosity(Logger.WARN)
    HttpLogger.logRequestUrl = false
}
