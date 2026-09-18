package app.trailveil.map

import app.trailveil.map.fog.FogMosaicLayout
import app.trailveil.map.fog.FogPocTileGrid
import app.trailveil.map.fog.FogTileKey
import app.trailveil.map.fog.FogViewportFootprint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLibreFogIdleReuseTest {
    @Test
    fun matchesUsesReferenceIdentityForSdkObjectsAndValueEqualityForValueObjects() {
        val base = certificate()

        assertTrue("a complete even certificate must match itself", base.matches(base))
        assertTrue(
            "equal footprint and mode values should match even when copied",
            base.matches(
                base.copy(
                    footprint = base.footprint.copy(
                        layout = base.footprint.layout.copy(),
                    ),
                    mode = base.mode.copy(),
                ),
            ),
        )

        listOf(
            "map" to base.copy(mapInstance = IdentityToken("map")),
            "style" to base.copy(styleIdentity = IdentityToken("style")),
            "runtime" to base.copy(runtimeIdentity = IdentityToken("runtime")),
        ).forEach { (identity, candidate) ->
            assertFalse(
                "equal but non-identical $identity objects must not reuse an idle certificate",
                base.matches(candidate),
            )
        }
    }

    @Test
    fun matchesRejectsGenerationRevisionEpochSlotFootprintAndModeChanges() {
        val base = certificate()
        val changed = listOf(
            "generation" to base.copy(generation = base.generation + 1L),
            "revision" to base.copy(revision = base.revision + 1L),
            "canonical epoch" to base.copy(canonicalEpoch = base.canonicalEpoch + 2L),
            "slot" to base.copy(slot = FogGenerationSlot.B),
            "footprint" to base.copy(footprint = footprintFixture(keyX = 1)),
            "render mode" to base.copy(
                mode = MapLibreFogRenderMode(
                    nativeRequested = !base.mode.nativeRequested,
                    maskVectorRequested = base.mode.maskVectorRequested,
                ),
            ),
            "mask-vector mode" to base.copy(
                mode = MapLibreFogRenderMode(
                    nativeRequested = base.mode.nativeRequested,
                    maskVectorRequested = !base.mode.maskVectorRequested,
                ),
            ),
        )

        changed.forEach { (field, candidate) ->
            assertFalse("a changed $field must invalidate reuse", base.matches(candidate))
        }
    }

    @Test
    fun anOddCanonicalEpochCannotMatchEvenWhenEveryOtherFieldMatches() {
        val odd = certificate(canonicalEpoch = 13L)

        assertFalse(
            "an in-progress odd canonical epoch is never reusable",
            odd.matches(odd.copy()),
        )
    }

    private fun certificate(
        mapInstance: Any = IdentityToken("map"),
        styleIdentity: Any = IdentityToken("style"),
        runtimeIdentity: Any = IdentityToken("runtime"),
        generation: Long = 7L,
        revision: Long = 11L,
        canonicalEpoch: Long = 12L,
        slot: FogGenerationSlot = FogGenerationSlot.A,
        footprint: FogViewportFootprint = footprintFixture(keyX = 0),
        mode: MapLibreFogRenderMode = MapLibreFogRenderMode(
            nativeRequested = true,
            maskVectorRequested = false,
        ),
    ) = MapLibreFogIdleCertificate(
        mapInstance = mapInstance,
        styleIdentity = styleIdentity,
        runtimeIdentity = runtimeIdentity,
        generation = generation,
        revision = revision,
        canonicalEpoch = canonicalEpoch,
        slot = slot,
        footprint = footprint,
        mode = mode,
    )

    private fun footprintFixture(keyX: Int): FogViewportFootprint {
        val key = FogTileKey(zoom = 2, x = keyX, y = 1, renderVersion = 1)
        return FogViewportFootprint(
            keys = listOf(key),
            layout = FogMosaicLayout(
                bounds = FogPocTileGrid.bounds(key),
                tileCount = 1,
                columns = 1,
                rows = 1,
                samplingGridWidth = 8,
                samplingGridHeight = 8,
            ),
        )
    }

    private data class IdentityToken(val name: String)
}
