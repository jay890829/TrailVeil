package app.trailveil.map.fog

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FogSnapshotVisualProbePlanMemoTest {
    private val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
    private val mask = FogPixelMask(16, 16, ByteArray(256) { 0xff.toByte() })
    private val masks = mapOf(key to mask)
    private val request = FogViewportCoverageRequest(
        GeoPoint(30.0, -45.0), 2,
        GeoPoint(1.0, -89.0), GeoPoint(60.0, -89.0),
        GeoPoint(60.0, -1.0), GeoPoint(1.0, -1.0),
    )
    private val zone = FogProbeExclusionZone(10.0, 11.0, -50.0, -49.0)
    private val plan = FogSnapshotVisualProbePlanner().plan(request, masks)

    @Test fun identicalInputsReuseComputationButChangedCameraGenerationOrZonesMiss() {
        val memo = FogSnapshotVisualProbePlanMemo()
        memo.remember(1, request, masks, emptyList(), plan)
        assertSame(plan, memo.find(1, request.copy(), masks.toMap(), emptyList()))
        assertNull(memo.find(2, request, masks, emptyList()))
        assertNull(memo.find(1, request.copy(center = GeoPoint(31.0, -45.0)), masks, emptyList()))
        assertNull(memo.find(1, request.copy(farRight = GeoPoint(61.0, -1.0)), masks, emptyList()))
        assertNull(memo.find(1, request.copy(floorZoom = 3), masks, emptyList()))
        assertNull(memo.find(1, request, masks, listOf(zone)))
        memo.clear()
        assertNull(memo.find(1, request, masks, emptyList()))
    }

    @Test fun equalPixelsInReplacementMaskAreNotAnIdentityHit() {
        val memo = FogSnapshotVisualProbePlanMemo()
        memo.remember(1, request, masks, emptyList(), plan)
        val replacement = FogPixelMask(mask.width, mask.height, mask.copyAlpha())
        assertNull(memo.find(1, request, mapOf(key to replacement), emptyList()))
        assertNull(memo.find(1, request, emptyMap(), emptyList()))
        assertNull(memo.find(1, request, mapOf(key.copy(x = 2) to mask), emptyList()))
    }

    @Test fun callerCollectionMutationCannotRewriteTheCachedInputs() {
        val memo = FogSnapshotVisualProbePlanMemo()
        val mutableMasks = masks.toMutableMap()
        val mutableZones = mutableListOf(zone)
        memo.remember(1, request, mutableMasks, mutableZones, plan)
        mutableMasks.clear()
        assertNull(memo.find(1, request, mutableMasks, mutableZones))
        mutableZones.clear()
        assertNull(memo.find(1, request, masks, mutableZones))
        assertSame(plan, memo.find(1, request, masks, listOf(zone)))
    }
}
