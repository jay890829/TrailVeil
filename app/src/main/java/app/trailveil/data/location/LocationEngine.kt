package app.trailveil.data.location

import kotlinx.coroutines.flow.Flow

/** A source of untrusted, provider-level location fixes. */
interface LocationEngine {
    fun fixes(request: LocationUpdateRequest = LocationUpdateRequest()): Flow<RawLocationFix>
}

/** The cadence requested from a [LocationEngine]. */
data class LocationUpdateRequest(
    val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    val minDisplacementMeters: Double = DEFAULT_MIN_DISPLACEMENT_METERS,
) {
    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        require(minIntervalMillis > 0) { "minIntervalMillis must be positive" }
        require(minIntervalMillis <= intervalMillis) {
            "minIntervalMillis cannot exceed intervalMillis"
        }
        require(minDisplacementMeters.isFinite() && minDisplacementMeters >= 0.0) {
            "minDisplacementMeters must be finite and non-negative"
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 5_000L
        const val DEFAULT_MIN_INTERVAL_MILLIS = 2_000L
        const val DEFAULT_MIN_DISPLACEMENT_METERS = 5.0
    }
}

/**
 * A provider-level fix. This deliberately has no validation: providers may report malformed or
 * otherwise unusable values, which [LocationQualityFilter] must reject rather than crash on.
 */
data class RawLocationFix(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double,
    val capturedAtElapsedRealtimeNanos: Long,
    val epochMillis: Long,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val isMock: Boolean? = null,
)
