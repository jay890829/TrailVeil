package io.github.jay890829.trailveil.data.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val SPEED_COMPARISON_EPSILON_METERS_PER_SECOND = 1e-9

/** Policy applied before a provider fix is allowed into a recording. */
internal data class LocationQualityPolicy(
    val maxHorizontalAccuracyMeters: Double = DEFAULT_MAX_HORIZONTAL_ACCURACY_METERS,
    val maxAgeNanos: Long = DEFAULT_MAX_AGE_NANOS,
    val segmentGapNanos: Long = DEFAULT_SEGMENT_GAP_NANOS,
    val maxPlausibleLowerBoundSpeedMetersPerSecond: Double =
        DEFAULT_MAX_PLAUSIBLE_LOWER_BOUND_SPEED_METERS_PER_SECOND,
) {
    init {
        require(maxHorizontalAccuracyMeters.isFinite() && maxHorizontalAccuracyMeters >= 0.0) {
            "maxHorizontalAccuracyMeters must be finite and non-negative"
        }
        require(maxAgeNanos >= 0L) { "maxAgeNanos must be non-negative" }
        require(segmentGapNanos > 0L) { "segmentGapNanos must be positive" }
        require(
            maxPlausibleLowerBoundSpeedMetersPerSecond.isFinite() &&
                maxPlausibleLowerBoundSpeedMetersPerSecond >= 0.0,
        ) { "maxPlausibleLowerBoundSpeedMetersPerSecond must be finite and non-negative" }
    }

    internal companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val DEFAULT_MAX_HORIZONTAL_ACCURACY_METERS = 50.0
        const val DEFAULT_MAX_AGE_NANOS = 15L * NANOS_PER_SECOND
        const val DEFAULT_SEGMENT_GAP_NANOS = 60L * NANOS_PER_SECOND
        const val DEFAULT_MAX_PLAUSIBLE_LOWER_BOUND_SPEED_METERS_PER_SECOND = 100.0
    }
}

/** A location that has passed structural and quality checks. */
internal data class QualifiedLocationFix(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double,
    val capturedAtElapsedRealtimeNanos: Long,
    val epochMillis: Long,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val isMock: Boolean?,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0.0)
        require(capturedAtElapsedRealtimeNanos >= 0L)
        require(epochMillis >= 0L)
        require(altitudeMeters == null || altitudeMeters.isFinite())
        require(speedMetersPerSecond == null || speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        require(bearingDegrees == null || bearingDegrees.isFinite() && bearingDegrees in 0.0..<360.0)
    }
}

internal enum class AcceptedLocationKind {
    FIRST,
    CONTINUOUS,
    AFTER_BREAK,
}

internal enum class LocationRejectionReason {
    INVALID_LATITUDE,
    INVALID_LONGITUDE,
    INVALID_HORIZONTAL_ACCURACY,
    INVALID_CAPTURED_TIMESTAMP,
    INVALID_EPOCH_TIMESTAMP,
    INVALID_ALTITUDE,
    INVALID_SPEED,
    INVALID_BEARING,
    STALE,
    FUTURE_TIMESTAMP,
    DUPLICATE_OR_OUT_OF_ORDER,
    INACCURATE,
    IMPOSSIBLE_JUMP,
}

internal enum class LocationBreakReason {
    GAP,
    IMPOSSIBLE_JUMP,
}

/**
 * The only output that should cross the boundary into persistence. A rejected decision
 * intentionally contains no provider fix or coordinate fields.
 */
internal sealed interface LocationQualityDecision {
    data class Accepted(
        val kind: AcceptedLocationKind,
        val fix: QualifiedLocationFix,
        val distanceMeters: Double,
        val breakReason: LocationBreakReason? = null,
    ) : LocationQualityDecision {
        init {
            require(distanceMeters.isFinite() && distanceMeters >= 0.0)
            require((kind == AcceptedLocationKind.AFTER_BREAK) == (breakReason != null)) {
                "Only an after-break fix carries a break reason"
            }
            require(kind == AcceptedLocationKind.CONTINUOUS || distanceMeters == 0.0) {
                "First and after-break fixes cannot bridge distance"
            }
        }
    }

    data class Rejected(
        val reasons: Set<LocationRejectionReason>,
        val breakReason: LocationBreakReason? = null,
    ) : LocationQualityDecision {
        init {
            require(reasons.isNotEmpty())
        }
    }
}

/**
 * Single-consumer stateful quality gate for one recording. It is independent of provider and
 * storage APIs. Supply monotonic current time to [evaluate] so callers and tests stay deterministic.
 */
internal class LocationQualityFilter(
    private val policy: LocationQualityPolicy = LocationQualityPolicy(),
) {
    private var continuityAnchor: QualifiedLocationFix? = null
    private var latestAcceptedElapsedRealtimeNanos: Long? = null
    private var pendingBreakReason: LocationBreakReason? = null

    fun evaluate(
        rawFix: RawLocationFix,
        nowElapsedRealtimeNanos: Long,
    ): LocationQualityDecision {
        require(nowElapsedRealtimeNanos >= 0L) { "nowElapsedRealtimeNanos must be non-negative" }

        breakForElapsedGapIfNeeded(nowElapsedRealtimeNanos)

        val reasons = validationReasons(rawFix, nowElapsedRealtimeNanos).toMutableSet()
        if (reasons.isEmpty() && rawFix.horizontalAccuracyMeters > policy.maxHorizontalAccuracyMeters) {
            reasons += LocationRejectionReason.INACCURATE
        }

        val qualified = if (reasons.isEmpty()) rawFix.toQualified() else null
        val acceptedTimeFloor = latestAcceptedElapsedRealtimeNanos
        if (
            qualified != null &&
            acceptedTimeFloor != null &&
            qualified.capturedAtElapsedRealtimeNanos <= acceptedTimeFloor
        ) {
            reasons += LocationRejectionReason.DUPLICATE_OR_OUT_OF_ORDER
        }
        if (reasons.isNotEmpty()) return rejected(reasons)

        checkNotNull(qualified)
        val previous = continuityAnchor
        if (previous != null) {
            val distanceMeters = LocationDistance.haversineMeters(previous, qualified)
            val elapsedSeconds =
                (qualified.capturedAtElapsedRealtimeNanos - previous.capturedAtElapsedRealtimeNanos)
                    .toDouble() / LocationQualityPolicy.NANOS_PER_SECOND.toDouble()
            val lowerBoundSpeedMetersPerSecond = max(
                0.0,
                distanceMeters - previous.horizontalAccuracyMeters - qualified.horizontalAccuracyMeters,
            ) / elapsedSeconds
            if (
                lowerBoundSpeedMetersPerSecond -
                policy.maxPlausibleLowerBoundSpeedMetersPerSecond >
                SPEED_COMPARISON_EPSILON_METERS_PER_SECOND
            ) {
                continuityAnchor = null
                pendingBreakReason = LocationBreakReason.IMPOSSIBLE_JUMP
                return rejected(setOf(LocationRejectionReason.IMPOSSIBLE_JUMP))
            }

            continuityAnchor = qualified
            latestAcceptedElapsedRealtimeNanos = qualified.capturedAtElapsedRealtimeNanos
            return LocationQualityDecision.Accepted(
                kind = AcceptedLocationKind.CONTINUOUS,
                fix = qualified,
                distanceMeters = distanceMeters,
            )
        }

        val breakReason = pendingBreakReason
        val kind = if (breakReason == null) {
            AcceptedLocationKind.FIRST
        } else {
            AcceptedLocationKind.AFTER_BREAK
        }
        pendingBreakReason = null
        continuityAnchor = qualified
        latestAcceptedElapsedRealtimeNanos = qualified.capturedAtElapsedRealtimeNanos
        return LocationQualityDecision.Accepted(
            kind = kind,
            fix = qualified,
            distanceMeters = 0.0,
            breakReason = breakReason,
        )
    }

    private fun breakForElapsedGapIfNeeded(nowElapsedRealtimeNanos: Long) {
        val previous = continuityAnchor ?: return
        if (
            nowElapsedRealtimeNanos > previous.capturedAtElapsedRealtimeNanos &&
            nowElapsedRealtimeNanos - previous.capturedAtElapsedRealtimeNanos > policy.segmentGapNanos
        ) {
            continuityAnchor = null
            pendingBreakReason = LocationBreakReason.GAP
        }
    }

    private fun validationReasons(
        rawFix: RawLocationFix,
        nowElapsedRealtimeNanos: Long,
    ): Set<LocationRejectionReason> = buildSet {
        if (!rawFix.latitude.isFinite() || rawFix.latitude !in -90.0..90.0) {
            add(LocationRejectionReason.INVALID_LATITUDE)
        }
        if (!rawFix.longitude.isFinite() || rawFix.longitude !in -180.0..180.0) {
            add(LocationRejectionReason.INVALID_LONGITUDE)
        }
        if (!rawFix.horizontalAccuracyMeters.isFinite() || rawFix.horizontalAccuracyMeters < 0.0) {
            add(LocationRejectionReason.INVALID_HORIZONTAL_ACCURACY)
        }
        if (rawFix.capturedAtElapsedRealtimeNanos < 0L) {
            add(LocationRejectionReason.INVALID_CAPTURED_TIMESTAMP)
        }
        if (rawFix.epochMillis < 0L) add(LocationRejectionReason.INVALID_EPOCH_TIMESTAMP)
        if (rawFix.altitudeMeters?.isFinite() == false) add(LocationRejectionReason.INVALID_ALTITUDE)
        if (
            rawFix.speedMetersPerSecond != null &&
            (!rawFix.speedMetersPerSecond.isFinite() || rawFix.speedMetersPerSecond < 0.0)
        ) {
            add(LocationRejectionReason.INVALID_SPEED)
        }
        if (
            rawFix.bearingDegrees != null &&
            (!rawFix.bearingDegrees.isFinite() || rawFix.bearingDegrees !in 0.0..<360.0)
        ) {
            add(LocationRejectionReason.INVALID_BEARING)
        }

        if (rawFix.capturedAtElapsedRealtimeNanos >= 0L) {
            when {
                rawFix.capturedAtElapsedRealtimeNanos > nowElapsedRealtimeNanos ->
                    add(LocationRejectionReason.FUTURE_TIMESTAMP)
                nowElapsedRealtimeNanos - rawFix.capturedAtElapsedRealtimeNanos > policy.maxAgeNanos ->
                    add(LocationRejectionReason.STALE)
            }
        }
    }

    private fun rejected(reasons: Set<LocationRejectionReason>): LocationQualityDecision.Rejected =
        LocationQualityDecision.Rejected(reasons, pendingBreakReason)

    private fun RawLocationFix.toQualified() = QualifiedLocationFix(
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = horizontalAccuracyMeters,
        capturedAtElapsedRealtimeNanos = capturedAtElapsedRealtimeNanos,
        epochMillis = epochMillis,
        altitudeMeters = altitudeMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
        isMock = isMock,
    )
}

/** Great-circle distance using the fixed IUGG mean Earth radius. */
internal object LocationDistance {
    const val MEAN_EARTH_RADIUS_METERS = 6_371_008.8

    fun haversineMeters(from: QualifiedLocationFix, to: QualifiedLocationFix): Double =
        haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)

    fun haversineMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        require(fromLatitude.isFinite() && fromLatitude in -90.0..90.0)
        require(toLatitude.isFinite() && toLatitude in -90.0..90.0)
        require(fromLongitude.isFinite() && fromLongitude in -180.0..180.0)
        require(toLongitude.isFinite() && toLongitude in -180.0..180.0)

        val latitudeDeltaRadians = Math.toRadians(toLatitude - fromLatitude)
        val longitudeDeltaDegrees = normalizeLongitudeDeltaDegrees(toLongitude - fromLongitude)
        val longitudeDeltaRadians = Math.toRadians(longitudeDeltaDegrees)
        val fromLatitudeRadians = Math.toRadians(fromLatitude)
        val toLatitudeRadians = Math.toRadians(toLatitude)
        val sinLatitude = sin(latitudeDeltaRadians / 2.0)
        val sinLongitude = sin(longitudeDeltaRadians / 2.0)
        val a = sinLatitude * sinLatitude +
            cos(fromLatitudeRadians) * cos(toLatitudeRadians) * sinLongitude * sinLongitude
        val clampedA = min(1.0, max(0.0, a))
        return 2.0 * MEAN_EARTH_RADIUS_METERS *
            atan2(sqrt(clampedA), sqrt(1.0 - clampedA))
    }

    private fun normalizeLongitudeDeltaDegrees(delta: Double): Double {
        val normalized = (delta + 180.0) % 360.0
        return if (normalized < 0.0) normalized + 180.0 else normalized - 180.0
    }
}
