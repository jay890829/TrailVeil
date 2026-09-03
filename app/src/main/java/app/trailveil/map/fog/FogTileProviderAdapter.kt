package app.trailveil.map.fog

import java.util.LinkedHashMap

/**
 * The only canonical input needed by the provider adapter.
 *
 * Returning null means that canonical data is not available for [key].  The adapter converts
 * that result (and renderer failures) to an opaque tile rather than allowing a provider tile to
 * become an implicit clear/fog gap.
 */
fun interface CanonicalFogTileSource {
    fun render(key: FogTileKey): FogPixelMask?
}

/** Limits both dimensions of the provider's encoded-tile cache. */
data class FogTileCacheBudget(
    val maxEntries: Int = 256,
    val maxBytes: Long = 8L * 1024L * 1024L,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxBytes > 0L) { "maxBytes must be positive" }
    }
}

/** A generation identity that can be cancelled by the owner of a fog rebuild. */
class FogTileGeneration internal constructor(
    val id: Long,
    private val adapter: FogTileProviderAdapter,
    /** True for handover generations: the prior published set keeps serving while pending. */
    val handover: Boolean = false,
) {
    /**
     * Cancelling a revoke generation also revokes its previously published clear coverage;
     * cancelling a PENDING handover leaves the prior published set serving untouched.
     */
    fun cancel(): Boolean = adapter.cancel(this)
}

data class FogTileCacheSnapshot(
    val entryCount: Int,
    val byteCount: Long,
    val publishedGeneration: Long?,
)

data class FogTileResponse(
    val key: FogTileKey?,
    val bytes: ByteArray,
    /** Non-null only when [bytes] came from the atomically published canonical generation. */
    val publishedGeneration: Long?,
)

/**
 * Provider-neutral canonical fog adapter for a fixed 256x256 PNG tile contract.
 *
 * A caller starts one generation, renders the finite set of tiles for that viewport, then
 * publishes the set atomically.  A newer generation or cancellation invalidates the old set;
 * late work is discarded and cannot publish a clear tile.  Until a complete current generation
 * is installed, every lookup returns the opaque placeholder.
 *
 * Google world copies are normalized only at the adapter boundary.  The canonical key therefore
 * always has an x in [0, 2^z), while y and zoom are validated without clamping.  This preserves
 * the vertical Web Mercator boundary: an invalid y is unknown, not a nearby valid tile.
 */
class FogTileProviderAdapter(
    private val source: CanonicalFogTileSource = CanonicalFogTileSource { null },
    private val cacheBudget: FogTileCacheBudget = FogTileCacheBudget(),
    private val renderVersion: Int = FogRenderVersions.CURRENT,
    private val placeholder: ByteArray = FogTilePngCodec.opaquePlaceholder(),
) {
    init {
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        require(placeholder.isNotEmpty()) { "placeholder must not be empty" }
        require(placeholder.size.toLong() <= cacheBudget.maxBytes) {
            "placeholder must fit within the encoded tile byte budget"
        }
    }

    private val lock = Any()
    private val encodedTiles = LinkedHashMap<FogTileKey, ByteArray>(16, 0.75f, true)
    private var encodedByteCount = 0L
    private var nextGeneration = 0L
    private var activeGeneration: FogTileGeneration? = null
    private var publishedGeneration: Long? = null

    /**
     * Starts a rebuild and immediately revokes the previous generation's coverage.
     *
     * The revoke path survives only for first install and failure recovery, where nothing proven
     * exists and the opaque cover is already up (design §2.2); steady-state refreshes use
     * [beginHandoverGeneration].
     */
    fun beginGeneration(): FogTileGeneration = synchronized(lock) {
        val generation = FogTileGeneration(
            id = ++nextGeneration,
            adapter = this,
        )
        activeGeneration = generation
        clearPublishedLocked()
        generation
    }

    /**
     * Starts a handover rebuild: the currently published generation KEEPS serving
     * [tileResponse] while the pending one renders, and `publish` on the pending generation
     * atomically swaps the sets. This is the A/B-slot invariant ("a partial target only adds fog
     * above the complete active slot") translated to the tile adapter — stale serving during
     * handover shows the previous canonical only.
     */
    fun beginHandoverGeneration(): FogTileGeneration = synchronized(lock) {
        val generation = FogTileGeneration(
            id = ++nextGeneration,
            adapter = this,
            handover = true,
        )
        activeGeneration = generation
        generation
    }

    /**
     * Renders and atomically publishes [keys] for [generation].
     *
     * The source is invoked outside the lock so cancellation can proceed while a canonical read
     * is in flight.  A failed or missing source result is deliberately encoded as opaque fog.  If
     * the generation becomes stale at any point, the candidate is discarded in its entirety.
     */
    fun publish(
        generation: FogTileGeneration,
        keys: Iterable<FogTileKey>,
    ): Boolean {
        return publish(generation, keys) { key -> source.render(key) }
    }

    /** Publishes masks that were already rendered from the canonical viewport result. */
    fun publish(
        generation: FogTileGeneration,
        masks: Map<FogTileKey, FogPixelMask>,
    ): Boolean = publishMasks(generation, masks)

    /** Publishes one immutable canonical map without consulting a shared mutable source. */
    fun publishMasks(
        generation: FogTileGeneration,
        masks: Map<FogTileKey, FogPixelMask>,
    ): Boolean {
        return publish(generation, masks.keys) { key -> masks[key] }
    }

    private fun publish(
        generation: FogTileGeneration,
        keys: Iterable<FogTileKey>,
        render: (FogTileKey) -> FogPixelMask?,
    ): Boolean {
        if (!isCurrent(generation)) return false

        val candidate = LinkedHashMap<FogTileKey, ByteArray>()
        var candidateByteCount = 0L
        var inspected = 0L
        for (key in keys) {
            inspected += 1L
            // A hostile/infinite iterable must not turn a provider refresh into an unbounded
            // allocation or scan.  Four times the entry bound permits ordinary duplicate keys
            // while keeping the operation finite and fail-closed.
            if (inspected > cacheBudget.maxEntries.toLong() * MAX_KEY_INSPECTION_MULTIPLIER) {
                return false
            }
            if (!isCurrent(generation)) return false
            if (key.renderVersion != renderVersion) return false
            if (key !in candidate) {
                if (candidate.size >= cacheBudget.maxEntries) return false
                val encoded = encodeSafely(generation.id, key, render)
                candidate[key] = encoded
                candidateByteCount = try {
                    Math.addExact(candidateByteCount, encoded.size.toLong())
                } catch (_: ArithmeticException) {
                    return false
                }
                if (candidateByteCount > cacheBudget.maxBytes) return false
            }
        }

        synchronized(lock) {
            if (!isCurrentLocked(generation)) return false
            clearPublishedLocked()
            candidate.forEach { (key, bytes) ->
                encodedTiles[key] = bytes
                encodedByteCount += bytes.size.toLong()
            }
            publishedGeneration = generation.id
            return true
        }
    }

    /**
     * Cancels a generation only when it is still the active one.
     *
     * A PENDING handover dies without touching the serving set — the prior published coverage
     * stays intact. Everything else fails closed: revoke generations, and a handover that has
     * already swapped its own bytes in (cancelling a published set must never leave disproven
     * tiles serving).
     */
    fun cancel(generation: FogTileGeneration): Boolean = synchronized(lock) {
        if (!isCurrentLocked(generation)) return false
        activeGeneration = null
        if (!generation.handover || publishedGeneration == generation.id) {
            clearPublishedLocked()
        }
        true
    }

    /**
     * Returns an encoded tile for Google XYZ coordinates.
     *
     * x is a world-copy coordinate and is floor-mod normalized.  y and zoom are strict: invalid
     * values receive a fresh opaque placeholder and never reach the canonical source.
     */
    fun tileBytes(x: Int, y: Int, zoom: Int): ByteArray =
        tileResponse(x = x, y = y, zoom = zoom).bytes

    /** Atomically identifies the generation whose bytes were handed to the SDK. */
    fun tileResponse(x: Int, y: Int, zoom: Int): FogTileResponse {
        val key = normalizeKey(x = x, y = y, zoom = zoom)
            ?: return FogTileResponse(null, placeholder.copyOf(), null)
        synchronized(lock) {
            val encoded = encodedTiles[key]
            return if (encoded == null) {
                FogTileResponse(key, placeholder.copyOf(), null)
            } else {
                FogTileResponse(key, encoded.copyOf(), publishedGeneration)
            }
        }
    }

    fun cacheSnapshot(): FogTileCacheSnapshot = synchronized(lock) {
        FogTileCacheSnapshot(
            entryCount = encodedTiles.size,
            byteCount = encodedByteCount,
            publishedGeneration = publishedGeneration,
        )
    }

    fun isCurrent(generation: FogTileGeneration): Boolean = synchronized(lock) {
        isCurrentLocked(generation)
    }

    fun normalizeKey(x: Int, y: Int, zoom: Int): FogTileKey? {
        if (zoom !in SUPPORTED_ZOOM_RANGE) return null
        val tileCount = 1 shl zoom
        if (y !in 0 until tileCount) return null
        return FogTileKey(
            zoom = zoom,
            x = Math.floorMod(x, tileCount),
            y = y,
            renderVersion = renderVersion,
        )
    }

    private fun encodeSafely(
        generation: Long,
        key: FogTileKey,
        render: (FogTileKey) -> FogPixelMask?,
    ): ByteArray {
        val mask = try {
            render(key)
        } catch (_: Exception) {
            null
        }
        return try {
            mask?.let { canonical ->
                FogTilePngCodec.encode(
                    canonical,
                    FogTilePngCodec.colorForGeneration(generation),
                )
            } ?: placeholder.copyOf()
        } catch (_: Exception) {
            placeholder.copyOf()
        }
    }

    private fun isCurrentLocked(generation: FogTileGeneration): Boolean =
        activeGeneration === generation

    private fun clearPublishedLocked() {
        encodedTiles.clear()
        encodedByteCount = 0L
        publishedGeneration = null
    }

    private companion object {
        val SUPPORTED_ZOOM_RANGE: IntRange = 0..22
        const val MAX_KEY_INSPECTION_MULTIPLIER = 4L
    }
}
