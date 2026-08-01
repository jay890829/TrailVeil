package app.trailveil.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityFilterTest {
    @Test
    fun policyDefaultsAndValidationAreExplicit() {
        val policy = LocationQualityPolicy()
        assertEquals(50.0, policy.maxHorizontalAccuracyMeters, 0.0)
        assertEquals(15L * SECOND, policy.maxAgeNanos)
        assertEquals(60L * SECOND, policy.segmentGapNanos)
        assertEquals(100.0, policy.maxPlausibleLowerBoundSpeedMetersPerSecond, 0.0)

        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                LocationQualityPolicy(maxHorizontalAccuracyMeters = invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocationQualityPolicy(maxAgeNanos = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocationQualityPolicy(segmentGapNanos = 0)
        }
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                LocationQualityPolicy(maxPlausibleLowerBoundSpeedMetersPerSecond = invalid)
            }
        }
    }

    @Test
    fun firstAndContinuousFixesUseOnlyAcceptedAnchors() {
        val filter = LocationQualityFilter()
        val first = accepted(filter.evaluate(raw(elapsedNanos = 0), nowElapsedRealtimeNanos = 0))
        assertEquals(AcceptedLocationKind.FIRST, first.kind)
        assertEquals(0.0, first.distanceMeters, 0.0)
        assertNull(first.breakReason)

        val second = accepted(
            filter.evaluate(
                raw(longitude = longitudeForMeters(10.0), elapsedNanos = SECOND),
                nowElapsedRealtimeNanos = SECOND,
            ),
        )
        assertEquals(AcceptedLocationKind.CONTINUOUS, second.kind)
        assertEquals(10.0, second.distanceMeters, 1e-6)
        assertNull(second.breakReason)
    }

    @Test
    fun accuracyThresholdIsInclusiveAndInvalidAccuracyIsRejected() {
        listOf(49.999, 50.0).forEach { accuracy ->
            assertTrue(
                LocationQualityFilter().evaluate(
                    raw(accuracyMeters = accuracy),
                    nowElapsedRealtimeNanos = BASE_TIME,
                ) is LocationQualityDecision.Accepted,
            )
        }
        assertReason(
            LocationQualityFilter().evaluate(
                raw(accuracyMeters = 50.001),
                nowElapsedRealtimeNanos = BASE_TIME,
            ),
            LocationRejectionReason.INACCURATE,
        )
        listOf(-0.001, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertReason(
                LocationQualityFilter().evaluate(
                    raw(accuracyMeters = invalid),
                    nowElapsedRealtimeNanos = BASE_TIME,
                ),
                LocationRejectionReason.INVALID_HORIZONTAL_ACCURACY,
            )
        }
    }

    @Test
    fun coordinateBoundariesAreAcceptedAndMalformedCoordinatesAreRejected() {
        listOf(
            90.0 to 180.0,
            -90.0 to -180.0,
            0.0 to 0.0,
        ).forEach { (latitude, longitude) ->
            assertTrue(
                LocationQualityFilter().evaluate(
                    raw(latitude = latitude, longitude = longitude),
                    nowElapsedRealtimeNanos = BASE_TIME,
                ) is LocationQualityDecision.Accepted,
            )
        }

        listOf(90.001, -90.001, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertReason(
                LocationQualityFilter().evaluate(
                    raw(latitude = invalid),
                    nowElapsedRealtimeNanos = BASE_TIME,
                ),
                LocationRejectionReason.INVALID_LATITUDE,
            )
        }
        listOf(180.001, -180.001, Double.NaN, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertReason(
                LocationQualityFilter().evaluate(
                    raw(longitude = invalid),
                    nowElapsedRealtimeNanos = BASE_TIME,
                ),
                LocationRejectionReason.INVALID_LONGITUDE,
            )
        }
    }

    @Test
    fun malformedOptionalFieldsAreRejectedButMockMetadataIsPreserved() {
        val invalidFixes = listOf(
            raw(altitudeMeters = Double.NaN) to LocationRejectionReason.INVALID_ALTITUDE,
            raw(speedMetersPerSecond = -1.0) to LocationRejectionReason.INVALID_SPEED,
            raw(speedMetersPerSecond = Double.POSITIVE_INFINITY) to LocationRejectionReason.INVALID_SPEED,
            raw(bearingDegrees = -0.001) to LocationRejectionReason.INVALID_BEARING,
            raw(bearingDegrees = 360.0) to LocationRejectionReason.INVALID_BEARING,
            raw(bearingDegrees = Double.NaN) to LocationRejectionReason.INVALID_BEARING,
        )
        invalidFixes.forEach { (fix, reason) ->
            assertReason(
                LocationQualityFilter().evaluate(fix, nowElapsedRealtimeNanos = BASE_TIME),
                reason,
            )
        }

        val accepted = accepted(
            LocationQualityFilter().evaluate(
                raw(isMock = true),
                nowElapsedRealtimeNanos = BASE_TIME,
            ),
        )
        assertEquals(true, accepted.fix.isMock)
    }

    @Test
    fun staleBoundaryFutureAndInvalidTimestampsUseMonotonicTime() {
        assertTrue(
            LocationQualityFilter().evaluate(
                raw(elapsedNanos = BASE_TIME - 15L * SECOND),
                nowElapsedRealtimeNanos = BASE_TIME,
            ) is LocationQualityDecision.Accepted,
        )
        assertReason(
            LocationQualityFilter().evaluate(
                raw(elapsedNanos = BASE_TIME - 15L * SECOND - 1L),
                nowElapsedRealtimeNanos = BASE_TIME,
            ),
            LocationRejectionReason.STALE,
        )
        assertReason(
            LocationQualityFilter().evaluate(
                raw(elapsedNanos = BASE_TIME + 1L),
                nowElapsedRealtimeNanos = BASE_TIME,
            ),
            LocationRejectionReason.FUTURE_TIMESTAMP,
        )
        assertReason(
            LocationQualityFilter().evaluate(
                raw(elapsedNanos = -1L),
                nowElapsedRealtimeNanos = BASE_TIME,
            ),
            LocationRejectionReason.INVALID_CAPTURED_TIMESTAMP,
        )
        assertReason(
            LocationQualityFilter().evaluate(
                raw(epochMillis = -1L),
                nowElapsedRealtimeNanos = BASE_TIME,
            ),
            LocationRejectionReason.INVALID_EPOCH_TIMESTAMP,
        )
    }

    @Test
    fun duplicateAndOutOfOrderFixesAreRejectedWhileEpochRegressionIsIgnored() {
        val filter = LocationQualityFilter()
        accepted(filter.evaluate(raw(epochMillis = 2_000), BASE_TIME))

        assertReason(
            filter.evaluate(raw(epochMillis = 3_000), BASE_TIME),
            LocationRejectionReason.DUPLICATE_OR_OUT_OF_ORDER,
        )
        assertReason(
            filter.evaluate(raw(elapsedNanos = BASE_TIME - 1L, epochMillis = 4_000), BASE_TIME),
            LocationRejectionReason.DUPLICATE_OR_OUT_OF_ORDER,
        )

        val accepted = accepted(
            filter.evaluate(
                raw(
                    longitude = longitudeForMeters(1.0),
                    elapsedNanos = BASE_TIME + SECOND,
                    epochMillis = 1_000,
                ),
                BASE_TIME + SECOND,
            ),
        )
        assertEquals(AcceptedLocationKind.CONTINUOUS, accepted.kind)
    }

    @Test
    fun datelineSequenceUsesShortDistanceAndPassesJumpFiltering() {
        val filter = LocationQualityFilter()
        accepted(
            filter.evaluate(
                raw(longitude = 179.999, elapsedNanos = 0),
                nowElapsedRealtimeNanos = 0,
            ),
        )
        val crossing = accepted(
            filter.evaluate(
                raw(longitude = -179.999, elapsedNanos = 10L * SECOND),
                nowElapsedRealtimeNanos = 10L * SECOND,
            ),
        )

        assertEquals(AcceptedLocationKind.CONTINUOUS, crossing.kind)
        assertEquals(222.390, crossing.distanceMeters, 0.01)
    }

    @Test
    fun gapThresholdIsStrictlyGreaterThanSixtySeconds() {
        val exactly = LocationQualityFilter()
        accepted(exactly.evaluate(raw(elapsedNanos = 0), 0))
        val exactDecision = accepted(
            exactly.evaluate(
                raw(longitude = longitudeForMeters(10.0), elapsedNanos = 60L * SECOND),
                60L * SECOND,
            ),
        )
        assertEquals(AcceptedLocationKind.CONTINUOUS, exactDecision.kind)

        val below = LocationQualityFilter()
        accepted(below.evaluate(raw(elapsedNanos = 0), 0))
        assertEquals(
            AcceptedLocationKind.CONTINUOUS,
            accepted(
                below.evaluate(
                    raw(elapsedNanos = 60L * SECOND - 1L),
                    60L * SECOND - 1L,
                ),
            ).kind,
        )

        val over = LocationQualityFilter()
        accepted(over.evaluate(raw(elapsedNanos = 0), 0))
        val afterGap = accepted(
            over.evaluate(
                raw(elapsedNanos = 60L * SECOND + 1L),
                60L * SECOND + 1L,
            ),
        )
        assertEquals(AcceptedLocationKind.AFTER_BREAK, afterGap.kind)
        assertEquals(LocationBreakReason.GAP, afterGap.breakReason)
        assertEquals(0.0, afterGap.distanceMeters, 0.0)
    }

    @Test
    fun deliveryTimeRevealsGapAndRejectedFixKeepsBreakPendingForRecovery() {
        val lateDelivery = LocationQualityFilter()
        accepted(lateDelivery.evaluate(raw(elapsedNanos = 0), 0))
        val late = accepted(
            lateDelivery.evaluate(
                raw(elapsedNanos = 50L * SECOND),
                60L * SECOND + 1L,
            ),
        )
        assertEquals(AcceptedLocationKind.AFTER_BREAK, late.kind)
        assertEquals(LocationBreakReason.GAP, late.breakReason)

        val rejectedGap = LocationQualityFilter()
        accepted(rejectedGap.evaluate(raw(elapsedNanos = 0), 0))
        val rejected = rejectedGap.evaluate(
            raw(latitude = 91.0, elapsedNanos = 61L * SECOND),
            61L * SECOND,
        ) as LocationQualityDecision.Rejected
        assertTrue(LocationRejectionReason.INVALID_LATITUDE in rejected.reasons)
        assertEquals(LocationBreakReason.GAP, rejected.breakReason)

        val recovered = accepted(
            rejectedGap.evaluate(
                raw(elapsedNanos = 62L * SECOND),
                62L * SECOND,
            ),
        )
        assertEquals(AcceptedLocationKind.AFTER_BREAK, recovered.kind)
        assertEquals(LocationBreakReason.GAP, recovered.breakReason)
        assertEquals(0.0, recovered.distanceMeters, 0.0)
    }

    @Test
    fun plausibleHighSpeedAndExactMaximumPassButFasterJumpBreaks() {
        val atNinety = LocationQualityFilter()
        accepted(atNinety.evaluate(raw(accuracyMeters = 0.0, elapsedNanos = 0), 0))
        assertEquals(
            AcceptedLocationKind.CONTINUOUS,
            accepted(
                atNinety.evaluate(
                    raw(
                        longitude = longitudeForMeters(180.0),
                        accuracyMeters = 0.0,
                        elapsedNanos = 2L * SECOND,
                    ),
                    2L * SECOND,
                ),
            ).kind,
        )

        val atMaximum = LocationQualityFilter()
        accepted(atMaximum.evaluate(raw(accuracyMeters = 0.0, elapsedNanos = 0), 0))
        assertEquals(
            AcceptedLocationKind.CONTINUOUS,
            accepted(
                atMaximum.evaluate(
                    raw(
                        longitude = longitudeForMeters(200.0),
                        accuracyMeters = 0.0,
                        elapsedNanos = 2L * SECOND,
                    ),
                    2L * SECOND,
                ),
            ).kind,
        )

        val tooFast = LocationQualityFilter()
        accepted(tooFast.evaluate(raw(accuracyMeters = 0.0, elapsedNanos = 0), 0))
        val jump = tooFast.evaluate(
            raw(
                longitude = longitudeForMeters(202.0),
                accuracyMeters = 0.0,
                elapsedNanos = 2L * SECOND,
            ),
            2L * SECOND,
        ) as LocationQualityDecision.Rejected
        assertTrue(LocationRejectionReason.IMPOSSIBLE_JUMP in jump.reasons)
        assertEquals(LocationBreakReason.IMPOSSIBLE_JUMP, jump.breakReason)
    }

    @Test
    fun accuracyUncertaintyAvoidsFalseJumpButAcceptedDistanceUsesCenters() {
        val filter = LocationQualityFilter()
        accepted(filter.evaluate(raw(accuracyMeters = 10.0, elapsedNanos = 0), 0))
        val decision = accepted(
            filter.evaluate(
                raw(
                    longitude = longitudeForMeters(220.0),
                    accuracyMeters = 10.0,
                    elapsedNanos = 2L * SECOND,
                ),
                2L * SECOND,
            ),
        )

        assertEquals(AcceptedLocationKind.CONTINUOUS, decision.kind)
        assertEquals(220.0, decision.distanceMeters, 1e-5)
    }

    @Test
    fun jumpClearsContinuityButPreservesOrderingFloorAndRecoveryReason() {
        val filter = LocationQualityFilter()
        accepted(filter.evaluate(raw(accuracyMeters = 0.0, elapsedNanos = 0), 0))
        val jump = filter.evaluate(
            raw(
                longitude = longitudeForMeters(400.0),
                accuracyMeters = 0.0,
                elapsedNanos = 2L * SECOND,
            ),
            2L * SECOND,
        ) as LocationQualityDecision.Rejected
        assertEquals(LocationBreakReason.IMPOSSIBLE_JUMP, jump.breakReason)

        val outOfOrder = filter.evaluate(
            raw(accuracyMeters = 0.0, elapsedNanos = 0),
            2L * SECOND,
        ) as LocationQualityDecision.Rejected
        assertTrue(LocationRejectionReason.DUPLICATE_OR_OUT_OF_ORDER in outOfOrder.reasons)
        assertEquals(LocationBreakReason.IMPOSSIBLE_JUMP, outOfOrder.breakReason)

        val recovered = accepted(
            filter.evaluate(
                raw(
                    longitude = longitudeForMeters(1.0),
                    accuracyMeters = 0.0,
                    elapsedNanos = 3L * SECOND,
                ),
                3L * SECOND,
            ),
        )
        assertEquals(AcceptedLocationKind.AFTER_BREAK, recovered.kind)
        assertEquals(LocationBreakReason.IMPOSSIBLE_JUMP, recovered.breakReason)
        assertEquals(0.0, recovered.distanceMeters, 0.0)
    }

    @Test
    fun consumerPersistsOnlyAcceptedDecisionsAndRejectedTypeHasNoCoordinates() {
        val filter = LocationQualityFilter()
        val persisted = mutableListOf<QualifiedLocationFix>()
        fun consume(rawFix: RawLocationFix, now: Long) {
            when (val decision = filter.evaluate(rawFix, now)) {
                is LocationQualityDecision.Accepted -> persisted += decision.fix
                is LocationQualityDecision.Rejected -> Unit
            }
        }

        consume(raw(accuracyMeters = 0.0, elapsedNanos = 0), 0)
        consume(raw(longitude = 0.1, accuracyMeters = 50.001, elapsedNanos = SECOND), SECOND)
        consume(raw(latitude = 91.0, elapsedNanos = 2L * SECOND), 2L * SECOND)
        consume(
            raw(
                longitude = longitudeForMeters(400.0),
                accuracyMeters = 0.0,
                elapsedNanos = 3L * SECOND,
            ),
            3L * SECOND,
        )
        consume(
            raw(
                longitude = longitudeForMeters(1.0),
                accuracyMeters = 0.0,
                elapsedNanos = 4L * SECOND,
            ),
            4L * SECOND,
        )
        consume(raw(latitude = 92.0, elapsedNanos = 65L * SECOND), 65L * SECOND)
        consume(raw(longitude = 0.00002, elapsedNanos = 66L * SECOND), 66L * SECOND)

        assertEquals(3, persisted.size)
        assertEquals(listOf(0L, 4L * SECOND, 66L * SECOND), persisted.map { it.capturedAtElapsedRealtimeNanos })
        assertTrue(persisted.all { it.latitude in -90.0..90.0 })
        assertFalse(persisted.any { it.longitude == 0.1 || it.latitude == 91.0 || it.latitude == 92.0 })

        val rejectedFields = LocationQualityDecision.Rejected::class.java.declaredFields.map { it.name }
        assertFalse(rejectedFields.any { it.contains("latitude", ignoreCase = true) })
        assertFalse(rejectedFields.any { it.contains("longitude", ignoreCase = true) })
        assertFalse(rejectedFields.any { it.contains("raw", ignoreCase = true) || it.contains("fix", ignoreCase = true) })
    }

    private fun accepted(decision: LocationQualityDecision): LocationQualityDecision.Accepted {
        assertTrue("Expected Accepted but was $decision", decision is LocationQualityDecision.Accepted)
        return decision as LocationQualityDecision.Accepted
    }

    private fun assertReason(
        decision: LocationQualityDecision,
        expected: LocationRejectionReason,
    ) {
        assertTrue("Expected Rejected but was $decision", decision is LocationQualityDecision.Rejected)
        assertTrue(expected in (decision as LocationQualityDecision.Rejected).reasons)
    }

    private fun raw(
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        accuracyMeters: Double = 5.0,
        elapsedNanos: Long = BASE_TIME,
        epochMillis: Long = 1_000,
        altitudeMeters: Double? = null,
        speedMetersPerSecond: Double? = null,
        bearingDegrees: Double? = null,
        isMock: Boolean? = null,
    ) = RawLocationFix(
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = accuracyMeters,
        capturedAtElapsedRealtimeNanos = elapsedNanos,
        epochMillis = epochMillis,
        altitudeMeters = altitudeMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
        isMock = isMock,
    )

    private fun longitudeForMeters(meters: Double): Double =
        Math.toDegrees(meters / LocationDistance.MEAN_EARTH_RADIUS_METERS)

    private companion object {
        const val SECOND = 1_000_000_000L
        const val BASE_TIME = 100L * SECOND
    }
}
