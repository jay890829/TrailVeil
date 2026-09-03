package app.trailveil.map.fog

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlin.math.abs
import kotlin.math.round

/** One expected opaque canonical fog pixel expressed at its geographic pixel centre. */
data class FogSnapshotVisualProbe(
    val key: FogTileKey,
    val latitude: Double,
    val longitude: Double,
    /** True when the canonical mask has an opaque 3 x 3 neighbourhood around this point. */
    val strongNeighbourhood: Boolean,
    /**
     * Which of the planner's blocks this pixel was drawn from. Probes sharing a [blockIndex] are
     * INTERCHANGEABLE candidates for the same canonical region: the proof needs any one of them to
     * match, not all of them. That is the whole remedy for carry-forward F — see
     * [FogSnapshotVisualProbePlanner.CANDIDATES_PER_BLOCK].
     */
    val blockIndex: Int,
) {
    init {
        require(blockIndex >= 0) { "probe blockIndex must not be negative" }
    }
}

/**
 * A geographic exclusion rectangle for probe planning: screen-space overlay footprints (the
 * current-location dot, the track-polyline buffer) converted by the binding to geographic
 * bounds at plan time. [westLongitude]..[eastLongitude] runs west to east and may cross the
 * antimeridian.
 */
data class FogProbeExclusionZone(
    val southLatitude: Double,
    val northLatitude: Double,
    val westLongitude: Double,
    val eastLongitude: Double,
) {
    init {
        require(southLatitude <= northLatitude) { "exclusion zone latitudes must be ordered" }
        require(southLatitude in -90.0..90.0 && northLatitude in -90.0..90.0) {
            "exclusion zone latitudes must be in -90..90"
        }
        require(westLongitude.isFinite() && eastLongitude.isFinite()) {
            "exclusion zone longitudes must be finite"
        }
    }

    fun contains(latitude: Double, longitude: Double): Boolean {
        if (latitude !in southLatitude..northLatitude) return false
        // A raw span of one full world (or more) covers every longitude; the wrapped span math
        // below would degenerate it to zero.
        if (eastLongitude - westLongitude >= 360.0) return true
        val span = WebMercator.wrapLongitude(eastLongitude - westLongitude)
            .let { wrapped -> if (wrapped < 0.0) wrapped + 360.0 else wrapped }
        val offset = WebMercator.wrapLongitude(longitude - westLongitude)
            .let { wrapped -> if (wrapped < 0.0) wrapped + 360.0 else wrapped }
        return offset <= span
    }
}

/** Immutable visual-install oracle input for one exact provider viewport. */
class FogSnapshotVisualProbePlan internal constructor(
    coverageKeys: Set<FogTileKey>,
    probesByKey: Map<FogTileKey, List<FogSnapshotVisualProbe>>,
    zoneBlockedKeys: Set<FogTileKey> = emptySet(),
) {
    val coverageKeys: Set<FogTileKey> =
        Collections.unmodifiableSet(LinkedHashSet(coverageKeys))
    val probesByKey: Map<FogTileKey, List<FogSnapshotVisualProbe>> =
        Collections.unmodifiableMap(
            LinkedHashMap(
                probesByKey.mapValues { (_, probes) ->
                    Collections.unmodifiableList(ArrayList(probes))
                },
            ),
        )

    /**
     * Bounded-exclusion rule (design §2.3): tiles whose visible opaque pixels were ENTIRELY
     * eaten by exclusion zones. They stay REQUIRED — a proof pass may not treat them as proven;
     * the caller must hide the excluded overlays and re-plan before proving them. A tile never
     * silently drops out of both [probesByKey] and this set.
     */
    val zoneBlockedKeys: Set<FogTileKey> =
        Collections.unmodifiableSet(LinkedHashSet(zoneBlockedKeys))

    /**
     * [probesByKey] regrouped into the planner's blocks, in first-seen order.
     *
     * The grouping is part of the plan rather than something each prover re-derives, because losing
     * it silently converts an interchangeable candidate set back into a set of independently
     * required probes — which is strictly harsher than the pre-fallback rule it replaced.
     */
    private val probeBlocksByKey: Map<FogTileKey, List<List<FogSnapshotVisualProbe>>> =
        Collections.unmodifiableMap(
            LinkedHashMap(
                this.probesByKey.mapValues { (_, probes) ->
                    val blocks = LinkedHashMap<Int, MutableList<FogSnapshotVisualProbe>>()
                    probes.forEach { probe ->
                        blocks.getOrPut(probe.blockIndex) { ArrayList() } += probe
                    }
                    Collections.unmodifiableList(
                        blocks.values.map { candidates ->
                            Collections.unmodifiableList(ArrayList(candidates))
                        },
                    )
                },
            ),
        )

    init {
        require(this.coverageKeys.isNotEmpty()) { "visual probe coverage must not be empty" }
        require(this.probesByKey.keys.all { key -> key in this.coverageKeys }) {
            "visual probes must belong to the exact provider coverage"
        }
        require(this.probesByKey.values.all(List<FogSnapshotVisualProbe>::isNotEmpty)) {
            "visual probe groups must not be empty"
        }
        require(this.zoneBlockedKeys.all { key -> key in this.coverageKeys }) {
            "zone-blocked keys must belong to the exact provider coverage"
        }
        require(this.zoneBlockedKeys.none { key -> key in this.probesByKey }) {
            "a tile with usable probes is not zone-blocked"
        }
    }

    /**
     * False when this plan cannot decide the install: at least one visible tile's canonical fog
     * is entirely hidden behind exclusion zones, so a passing verdict would mean "every probe I
     * was allowed to look at matched" rather than "the fog is installed".
     *
     * A proof caller MUST gate on this: an unprovable plan is not a failure (nothing is known to
     * be wrong) and not a pass — the caller hides the excluded overlays and re-plans, and the
     * cover stays up meanwhile. Without the gate a fully zone-eaten viewport would "prove" with
     * zero fog pixels verified.
     */
    fun isProvable(): Boolean = zoneBlockedKeys.isEmpty()

    /**
     * The tiles this plan can actually decide. Empty for a fully explored viewport (nothing
     * unknown is visible, so there is nothing to prove) — which is a legitimate pass only when
     * [isProvable] also holds.
     */
    fun provableKeys(): Set<FogTileKey> = probesByKey.keys

    /**
     * This tile's probes grouped into blocks. Each inner list is one block's interchangeable
     * candidates; a prover proves the block by finding ONE match among them, and only then counts
     * the block towards the per-tile threshold.
     */
    fun probeBlocks(key: FogTileKey): List<List<FogSnapshotVisualProbe>> =
        probeBlocksByKey[key] ?: emptyList()
}

/**
 * Finds canonical opaque pixels that are actually inside the current visible-region polygon.
 *
 * A non-null Google map snapshot is not an installation fence. These probes let the Google-only
 * bridge require visible fog-colour pixels from every rendered tile that has visible unknown
 * coverage before it removes the independent full-screen cover. Fully explored visible tiles need
 * no opaque probe because exposing their basemap is the intended canonical result.
 */
class FogSnapshotVisualProbePlanner(
    private val blocksPerAxis: Int = DEFAULT_BLOCKS_PER_AXIS,
) {
    init {
        require(blocksPerAxis > 0) { "blocksPerAxis must be positive" }
    }

    fun plan(
        request: FogViewportCoverageRequest,
        masks: Map<FogTileKey, FogPixelMask>,
        exclusionZones: List<FogProbeExclusionZone> = emptyList(),
    ): FogSnapshotVisualProbePlan {
        require(masks.isNotEmpty()) { "visual probe masks must not be empty" }
        require(masks.keys.all { key -> key.zoom == request.floorZoom }) {
            "visual probe masks must match the viewport zoom"
        }
        val tileCount = 1 shl request.floorZoom
        val centerX = ((request.center.longitude + 180.0) / 360.0) * tileCount
        require(centerX.isFinite()) { "visual probe centre must project to finite X" }
        val fullWorld = request.visibleCorners().let { corners ->
            corners.maxOf(GeoPoint::longitude) - corners.minOf(GeoPoint::longitude) >= 360.0
        }
        val polygon = request.visibleCorners().map { point ->
            ProjectedProbePoint(
                x = centerX +
                    WebMercator.wrapLongitude(point.longitude - request.center.longitude) /
                    360.0 * tileCount,
                y = WebMercator.normalizedY(point.latitude) * tileCount,
            )
        }
        require(polygon.all { point -> point.x.isFinite() && point.y.isFinite() }) {
            "visual probe polygon must be finite"
        }

        val probesByKey = LinkedHashMap<FogTileKey, List<FogSnapshotVisualProbe>>()
        val zoneBlockedKeys = LinkedHashSet<FogTileKey>()
        masks.forEach { (key, mask) ->
            require(mask.width > 0 && mask.height > 0) { "visual probe mask must be non-empty" }
            val nearestWorld = round((centerX - (key.x + 0.5)) / tileCount).toInt()
            val worldColumns = ((nearestWorld - 1)..(nearestWorld + 1)).map { world ->
                key.x + world * tileCount
            }
            val selection = findVisibleOpaqueProbes(
                key = key,
                mask = mask,
                polygon = polygon,
                worldColumns = worldColumns,
                tileCount = tileCount,
                fullWorld = fullWorld,
                exclusionZones = exclusionZones,
            )
            if (selection.probes.isNotEmpty()) {
                probesByKey[key] = selection.probes
            } else if (selection.visibleOpaqueSeen) {
                // Bounded-exclusion rule: every visible opaque pixel fell inside a zone. The
                // tile stays REQUIRED (never probe-exempt) via the plan's zone-blocked set.
                zoneBlockedKeys += key
            }
        }
        return FogSnapshotVisualProbePlan(masks.keys, probesByKey, zoneBlockedKeys)
    }

    private class ProbeSelection(
        val probes: List<FogSnapshotVisualProbe>,
        val visibleOpaqueSeen: Boolean,
    )

    private fun findVisibleOpaqueProbes(
        key: FogTileKey,
        mask: FogPixelMask,
        polygon: List<ProjectedProbePoint>,
        worldColumns: List<Int>,
        tileCount: Int,
        fullWorld: Boolean,
        exclusionZones: List<FogProbeExclusionZone>,
    ): ProbeSelection {
        val probes = ArrayList<FogSnapshotVisualProbe>(blocksPerAxis * blocksPerAxis)
        var visibleOpaqueSeen = false
        fun outsideZones(x: Int, y: Int): Boolean {
            visibleOpaqueSeen = true
            if (exclusionZones.isEmpty()) return true
            val normalizedX = (key.x + (x + 0.5) / mask.width) / tileCount
            val normalizedY = (key.y + (y + 0.5) / mask.height) / tileCount
            val latitude = WebMercator.latitudeAtNormalizedY(normalizedY)
            val longitude = normalizedX * 360.0 - 180.0
            return exclusionZones.none { zone -> zone.contains(latitude, longitude) }
        }
        for (blockY in 0 until blocksPerAxis) {
            val yStart = blockY * mask.height / blocksPerAxis
            val yEnd = (blockY + 1) * mask.height / blocksPerAxis
            for (blockX in 0 until blocksPerAxis) {
                val xStart = blockX * mask.width / blocksPerAxis
                val xEnd = (blockX + 1) * mask.width / blocksPerAxis
                // Carry-forward F: one probe per block made the whole install hinge on one pixel,
                // and the owner decision keeps Google's labels and POI icons compositing ABOVE the
                // fog overlay. A glyph over that pixel is deterministic, so re-planning a
                // stationary camera reproduces it on all ten attempts and the tile can never be
                // verified. Collect several separated candidates per block instead; the prover
                // needs any one of them, so the block still demands positive fog evidence and
                // nothing about the fail-closed rule is traded away.
                val separation = maxOf(1, minOf(xEnd - xStart, yEnd - yStart) / 2)
                val chosen = ArrayList<ProbePixel>(CANDIDATES_PER_BLOCK)
                val strongCount = collectSeparatedPixels(
                    mask, xStart, xEnd, yStart, yEnd, separation, chosen,
                ) { x, y ->
                    hasOpaqueNeighbourhood(mask, x, y) &&
                        isVisible(key, mask, x, y, polygon, worldColumns, fullWorld) &&
                        outsideZones(x, y)
                }
                // Weak pixels only fill the slots the strong pass could not, and they are appended
                // after them, so a block's first candidate stays the best evidence available.
                collectSeparatedPixels(
                    mask, xStart, xEnd, yStart, yEnd, separation, chosen,
                ) { x, y ->
                    mask.alphaAt(x, y) != 0 &&
                        isVisible(key, mask, x, y, polygon, worldColumns, fullWorld) &&
                        outsideZones(x, y)
                }
                chosen.forEachIndexed { index, pixel ->
                    probes += pixel.toProbe(
                        key = key,
                        mask = mask,
                        tileCount = tileCount,
                        strong = index < strongCount,
                        blockIndex = blockY * blocksPerAxis + blockX,
                    )
                }
            }
        }
        return ProbeSelection(probes, visibleOpaqueSeen)
    }

    /**
     * Appends matching pixels from one block to [into] until it holds [CANDIDATES_PER_BLOCK], each
     * at least [separation] pixels (Chebyshev) away from every pixel already there. Returns how
     * many this pass appended.
     *
     * The separation is what makes the extra candidates worth anything: pixels next to each other
     * are under the same label glyph, so unspread candidates are not fallbacks at all.
     *
     * The separation test deliberately runs BEFORE [predicate], which carries the
     * `visibleOpaqueSeen` side effect. Skipping it can only under-report on a block that has
     * already chosen a candidate, and a tile with any chosen candidate never consults that flag.
     */
    private fun collectSeparatedPixels(
        mask: FogPixelMask,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        separation: Int,
        into: MutableList<ProbePixel>,
        predicate: (Int, Int) -> Boolean,
    ): Int {
        var added = 0
        if (into.size >= CANDIDATES_PER_BLOCK) return added
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val crowded = into.any { chosen ->
                    maxOf(abs(chosen.x - x), abs(chosen.y - y)) < separation
                }
                if (crowded || !predicate(x, y)) continue
                into += ProbePixel(x, y)
                added += 1
                if (into.size >= CANDIDATES_PER_BLOCK) return added
            }
        }
        return added
    }

    private fun hasOpaqueNeighbourhood(mask: FogPixelMask, x: Int, y: Int): Boolean {
        if (x !in 1 until mask.width - 1 || y !in 1 until mask.height - 1) return false
        for (offsetY in -1..1) {
            for (offsetX in -1..1) {
                if (mask.alphaAt(x + offsetX, y + offsetY) == 0) return false
            }
        }
        return true
    }

    private fun isVisible(
        key: FogTileKey,
        mask: FogPixelMask,
        x: Int,
        y: Int,
        polygon: List<ProjectedProbePoint>,
        worldColumns: List<Int>,
        fullWorld: Boolean,
    ): Boolean {
        val projectedY = key.y + (y + 0.5) / mask.height
        return worldColumns.any { column ->
            val point = ProjectedProbePoint(
                x = column + (x + 0.5) / mask.width,
                y = projectedY,
            )
            if (fullWorld) {
                val minimumY = polygon.minOf(ProjectedProbePoint::y)
                val maximumY = polygon.maxOf(ProjectedProbePoint::y)
                point.y in minimumY..maximumY
            } else {
                pointInPolygon(point, polygon)
            }
        }
    }

    private fun pointInPolygon(
        point: ProjectedProbePoint,
        polygon: List<ProjectedProbePoint>,
    ): Boolean {
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            if (
                (current.y > point.y) != (previous.y > point.y) &&
                point.x < (previous.x - current.x) * (point.y - current.y) /
                (previous.y - current.y) + current.x
            ) {
                inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun ProbePixel.toProbe(
        key: FogTileKey,
        mask: FogPixelMask,
        tileCount: Int,
        strong: Boolean,
        blockIndex: Int,
    ): FogSnapshotVisualProbe {
        val normalizedX = (key.x + (x + 0.5) / mask.width) / tileCount
        val normalizedY = (key.y + (y + 0.5) / mask.height) / tileCount
        return FogSnapshotVisualProbe(
            key = key,
            latitude = WebMercator.latitudeAtNormalizedY(normalizedY),
            longitude = normalizedX * 360.0 - 180.0,
            strongNeighbourhood = strong,
            blockIndex = blockIndex,
        )
    }

    private data class ProjectedProbePoint(val x: Double, val y: Double)
    private data class ProbePixel(val x: Int, val y: Int)

    companion object {
        const val DEFAULT_BLOCKS_PER_AXIS = 16

        /**
         * How many interchangeable candidates one block may contribute. Four is what a 16 x 16
         * block fits at half-block separation; more would cost plan size and prover work for
         * candidates too close together to survive a glyph the first four did not.
         */
        const val CANDIDATES_PER_BLOCK = 4
    }
}
