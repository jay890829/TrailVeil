package app.trailveil.data.location

import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer

/**
 * A source of untrusted, provider-level location fixes.
 *
 * The production engine is provider-throttled and retains at most
 * [LOCATION_FIX_BUFFER_CAPACITY] fixes in FIFO order. It never conflates or silently drops a fix:
 * filling that queue terminates the stream with [LocationBackpressureException], so the recording
 * service can close continuity as an interruption instead of joining points across an unknown gap.
 */
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

/** The fixed producer/consumer boundary used by the platform callback flow. */
internal const val LOCATION_FIX_BUFFER_CAPACITY = 64

/** Fuses the platform callback producer with the one explicit queue owned by this policy. */
internal fun Flow<RawLocationFix>.withLocationFixBuffer(): Flow<RawLocationFix> = buffer(
    capacity = LOCATION_FIX_BUFFER_CAPACITY,
    onBufferOverflow = BufferOverflow.SUSPEND,
)

/** Terminal signal: a provider outran the bounded persistence pipeline. */
internal class LocationBackpressureException(message: String) : IllegalStateException(message)

internal enum class LocationFixOfferResult {
    DELIVERED,
    /** Downstream cancellation already owns teardown; this is not a recording failure. */
    ALREADY_CLOSED,
    /** This fix was not queued and the stream was closed with a terminal backpressure cause. */
    OVERFLOW_TERMINATED,
}

/**
 * Non-blocking provider callback boundary. Every unsuccessful send is classified explicitly.
 */
internal fun SendChannel<RawLocationFix>.offerLocationFix(
    fix: RawLocationFix,
): LocationFixOfferResult {
    val result = trySend(fix)
    return when {
        result.isSuccess -> LocationFixOfferResult.DELIVERED
        result.isClosed -> LocationFixOfferResult.ALREADY_CLOSED
        else -> {
            val terminatedForOverflow = close(
                LocationBackpressureException(
                    "Location fix queue reached its $LOCATION_FIX_BUFFER_CAPACITY-fix capacity.",
                ),
            )
            if (terminatedForOverflow) {
                LocationFixOfferResult.OVERFLOW_TERMINATED
            } else {
                // Cancellation or another callback won the close race. The installed close cause,
                // not this stale full observation, owns the downstream outcome.
                LocationFixOfferResult.ALREADY_CLOSED
            }
        }
    }
}
