package app.trailveil.map.fog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogGenerationReusePolicyTest {
    @Test
    fun onlyPendingCurrentGenerationCanBeReused() {
        assertTrue(
            FogGenerationReusePolicy.canReuse(
                activeGenerationId = 7L,
                installedGenerationId = null,
                adapterIsCurrent = true,
            ),
        )
        assertFalse(
            FogGenerationReusePolicy.canReuse(
                activeGenerationId = null,
                installedGenerationId = null,
                adapterIsCurrent = true,
            ),
        )
        assertFalse(
            FogGenerationReusePolicy.canReuse(
                activeGenerationId = 7L,
                installedGenerationId = 7L,
                adapterIsCurrent = true,
            ),
        )
        assertFalse(
            FogGenerationReusePolicy.canReuse(
                activeGenerationId = 7L,
                installedGenerationId = null,
                adapterIsCurrent = false,
            ),
        )
    }
}
