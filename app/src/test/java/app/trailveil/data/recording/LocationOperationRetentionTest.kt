package app.trailveil.data.recording

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationOperationRetentionTest {
    @Test
    fun structuredLocationOperationRoundTripsWithoutUsingWallClockTime() {
        val token = UUID.randomUUID().toString()
        val sequence = LocationOperationSequence(token, 42L)

        assertEquals(sequence, sequence.toOperationId().locationSequenceOrNull())
        assertNull(RecordingOperationId("legacy-location-id").locationSequenceOrNull())
    }

    @Test
    fun malformedStructuredIdsAreRejectedInsteadOfBecomingUnboundedLegacyIds() {
        listOf(
            "location:",
            "location:not-a-uuid:1",
            "location:${UUID.randomUUID()}:0",
            "location:${UUID.randomUUID()}:-1",
            "location:${UUID.randomUUID()}:not-a-number",
        ).forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) {
                RecordingOperationId(malformed).locationSequenceOrNull()
            }
        }
    }

    @Test
    fun productionFactoryUsesOneTokenAndStrictlyIncreasingLocationSequences() {
        val first = requireNotNull(
            app.trailveil.recording.UuidRecordingOperationIdFactory
                .next("location")
                .locationSequenceOrNull(),
        )
        val second = requireNotNull(
            app.trailveil.recording.UuidRecordingOperationIdFactory
                .next("location")
                .locationSequenceOrNull(),
        )

        assertEquals(first.runtimeToken, second.runtimeToken)
        assertEquals(first.sequence + 1L, second.sequence)
        assertTrue(first.sequence > 0L)
    }
}
