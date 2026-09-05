package app.trailveil.map

/**
 * Silences the map SDK's request logging, for the Google build.
 *
 * The Maps SDK for Android exposes no logging switch: it writes what it writes, and there is no
 * supported call that would quiet it. Nothing here is therefore a deliberate no-op rather than an
 * omission, and it is the honest state - `P5-002`'s finding was that a tile URL is a position, and
 * on this build that exposure is the SDK's to make, not this application's to prevent.
 *
 * `app/src/main` still contains no logging call of any kind, so this application writes nothing to
 * logcat on either build.
 *
 * `V02-008`: the twin of this function in the other provider's source set does have work to do.
 * Both declare the same symbol and exactly one is compiled into any variant, which is the idiom
 * `TrailVeilMapSurface` already uses.
 */
internal fun silenceMapRequestLogging() = Unit
