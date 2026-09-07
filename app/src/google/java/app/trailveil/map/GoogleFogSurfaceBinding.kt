package app.trailveil.map

/**
 * What `GoogleHostedMapSurface` needs from a fog surface, so there can be more than one.
 *
 * `V03-011` arm 2 puts the fog on a single anchored `GroundOverlay` instead of a `TileOverlay`.
 * That is a different binding, not a different setting inside the existing one: the delivery
 * barrier, the tile provider and the request set all cease to exist, and half of
 * [GoogleCanonicalFogSurfaceBinding] is about running them. The host, however, held the binding in
 * two concretely typed variables and calls twelve members on it, so a second implementation could
 * not be assigned to either. This interface is the smallest thing that fixes that, and it is
 * deliberately nothing more: no new behaviour, no default methods, and every member already existed
 * with this exact signature.
 *
 * **It lives in `src/google`, which every Google build type compiles, so both the shipped tile
 * binding and the `googlePoc`-only mosaic binding can implement it.** The mosaic binding itself
 * lives in `src/googlePoc` and is therefore physically absent from `googleRelease` - the only kind
 * of absence this repository accepts, because it is the artifact's shape rather than an assertion
 * about sources.
 *
 * The members are the host's whole vocabulary, in the order the host uses them:
 * lifecycle and camera callbacks it forwards from the SDK, the two programmed-flight tickets, the
 * overlay-data notification, the diagnostics string it writes to the MapView tag, and release.
 */
internal interface GoogleFogSurfaceBinding {

    /** The SDK finished its first style load. */
    fun onMapLoaded()

    fun onHostStarted()

    fun onHostStopped()

    /** [reason] is the SDK's own `OnCameraMoveStartedListener` constant, not a fog enum. */
    fun onCameraMoveStarted(reason: Int)

    fun onCameraMoveFrame()

    fun onCameraIdle()

    fun onCameraMoveCancelled()

    /**
     * Claims the fog's programmed-flight ticket and returns it.
     *
     * A `Long` rather than an opaque type because the host stores it in a
     * [CameraFlightClaim][claimCameraFlight] alongside its own, and because that is what the
     * existing binding already returned.
     */
    fun beginProgrammedFlight(): Long

    /** Releases a ticket from [beginProgrammedFlight]; false when it was already released. */
    fun endProgrammedFlight(ticket: Long): Boolean

    /**
     * The follow camera's own ticket, distinct from [beginProgrammedFlight].
     *
     * A programmed flight is a one-shot move the entry route asks for; a follow ease is the
     * continuous tracking move. The coordinator counts them separately, so the host claims
     * them separately and a fog surface has to offer both.
     */
    fun beginFollowEase(): Long

    /** Releases a ticket from [beginFollowEase]; false when it was already released. */
    fun endFollowEase(ticket: Long): Boolean

    /** The recording's tracks or markers changed, so the fog's content is stale. */
    fun onOverlayDataChanged()

    /**
     * The gates line the host writes to `R.id.map_fog_binding_gates`.
     *
     * Part of the interface because the device suites read that tag and would otherwise see
     * `no-binding` for an arm-2 surface, which reads as "the fog never built" rather than "this
     * binding does not describe itself".
     */
    fun describeForTesting(): String

    fun release()
}
