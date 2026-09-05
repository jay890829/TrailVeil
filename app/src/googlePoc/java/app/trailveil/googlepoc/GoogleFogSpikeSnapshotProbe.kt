package app.trailveil.googlepoc

import android.graphics.Bitmap
import androidx.core.graphics.get
import app.trailveil.map.fog.FogSnapshotVisualProbePlan

/**
 * `V02-005` stage 3 (SP6/SP8): a strictly READ-ONLY spike oracle. Issues one snapshot and
 * classifies every probe pixel of [plan] against [generation]'s palette without touching the
 * controller's delivery barrier, refresh phases, last visual proof, or the overlay, so a
 * concurrent canonical install can never be corrupted by a spike probe. Main-thread only.
 * `onResult(null)` on snapshot or projection failure.
 *
 * `V02-008` moved it here, out of the controller and out of every release-configured Google
 * build. Being an extension is the point: it reads the controller's map and nothing else, so
 * the read-only claim above is now enforced by visibility rather than asserted in prose.
 */
fun GoogleFogTileOverlayController.probeCanonicalSnapshotForTesting(
    generation: Long,
    plan: FogSnapshotVisualProbePlan,
    onResult: (GoogleFogSpikeProbeResult?) -> Unit,
) {
    try {
        map.snapshot { snapshot ->
            if (snapshot == null) {
                onResult(null)
                return@snapshot
            }
            val result = try {
                classifyProbePixels(snapshot, plan, generation)
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
            snapshot.recycle()
            onResult(result)
        }
    } catch (_: Exception) {
        onResult(null)
    } catch (_: LinkageError) {
        onResult(null)
    }
}

private fun GoogleFogTileOverlayController.classifyProbePixels(
    snapshot: Bitmap,
    plan: FogSnapshotVisualProbePlan,
    generation: Long,
): GoogleFogSpikeProbeResult? {
    val projection = try {
        map.projection
    } catch (_: Exception) {
        return null
    } catch (_: LinkageError) {
        return null
    }
    var match = 0
    var placeholder = 0
    var stale = 0
    var other = 0
    var offScreen = 0
    var provenTiles = 0
    plan.probesByKey.keys.forEach { key ->
        var onScreenBlocks = 0
        var matchedBlocks = 0
        plan.probeBlocks(key).forEach { candidates ->
            var blockOnScreen = false
            var blockMatched = false
            for (probe in candidates) {
                val point = try {
                    projection.toScreenLocation(
                        com.google.android.gms.maps.model.LatLng(
                            probe.latitude,
                            probe.longitude,
                        ),
                    )
                } catch (_: Exception) {
                    null
                } catch (_: LinkageError) {
                    null
                }
                if (
                    point == null ||
                    point.x !in 0 until snapshot.width ||
                    point.y !in 0 until snapshot.height
                ) {
                    offScreen += 1
                    continue
                }
                blockOnScreen = true
                val pixelClass =
                    GoogleFogSpikePixelClassifier.classify(
                        snapshot[point.x, point.y],
                        generation,
                    )
                when (pixelClass) {
                    GoogleFogSpikePixelClass.MATCH -> {
                        match += 1
                        blockMatched = true
                    }
                    GoogleFogSpikePixelClass.PLACEHOLDER -> placeholder += 1
                    GoogleFogSpikePixelClass.STALE_PALETTE -> stale += 1
                    GoogleFogSpikePixelClass.OTHER -> other += 1
                }
                // The class counters describe the probes actually examined; a proven block
                // stops here, exactly as the production prover does.
                if (blockMatched) break
            }
            if (blockOnScreen) onScreenBlocks += 1
            if (blockMatched) matchedBlocks += 1
        }
        // Mirrors the production oracle's per-tile threshold shape: a tile with visible
        // canonical area needs min(3, onScreenBlocks) matching BLOCKS, and at least one on
        // screen. Blocks, never pixels — see observeBlocks.
        val required = minOf(
        GoogleFogTileOverlayController.MINIMUM_MATCHING_BLOCKS_PER_TILE,
        onScreenBlocks,
    )
        if (required > 0 && matchedBlocks >= required) provenTiles += 1
    }
    val requiredTiles = plan.probesByKey.count { (_, probes) ->
        probes.any { probe ->
            val point = try {
                projection.toScreenLocation(
                    com.google.android.gms.maps.model.LatLng(probe.latitude, probe.longitude),
                )
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
            point != null &&
                point.x in 0 until snapshot.width &&
                point.y in 0 until snapshot.height
        }
    }
    return GoogleFogSpikeProbeResult(
        matchProbes = match,
        placeholderProbes = placeholder,
        stalePaletteProbes = stale,
        otherProbes = other,
        offScreenProbes = offScreen,
        proven = provenTiles >= requiredTiles && requiredTiles > 0,
    )
}
