package io.github.jay890829.trailveil.data.location

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import java.util.concurrent.Executor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A [LocationEngine] backed exclusively by Android's platform [LocationManager].
 *
 * The default FUSED provider falls back to GPS only when FUSED is absent. A present but disabled
 * provider remains an actionable failure so the service never silently changes its contract.
 */
class PlatformLocationEngine(
    private val locationManager: LocationManager,
    private val callbackExecutor: Executor = DirectExecutor,
    private val provider: String = LocationManager.FUSED_PROVIDER,
    private val providerSelector: LocationProviderSelector = DefaultLocationProviderSelector,
) : LocationEngine {

    @SuppressLint("MissingPermission")
    override fun fixes(request: LocationUpdateRequest): Flow<RawLocationFix> = callbackFlow {
        val selectedProvider = try {
            val preferredState = providerState(provider)
            val candidate = if (
                provider == LocationManager.FUSED_PROVIDER && !preferredState.exists
            ) {
                LocationManager.GPS_PROVIDER
            } else {
                provider
            }
            providerSelector.select(
                preferredProvider = candidate,
                state = if (candidate == provider) preferredState else providerState(candidate),
            )
        } catch (error: SecurityException) {
            close(
                LocationPermissionException(
                    "Location permission is required to inspect provider state.",
                    error,
                ),
            )
            return@callbackFlow
        } catch (error: PlatformLocationException) {
            close(error)
            return@callbackFlow
        } catch (error: Exception) {
            close(LocationRegistrationException("Unable to inspect location provider state.", error))
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toPlatformLocationSample().toRawLocationFix())
            }

            override fun onProviderDisabled(provider: String) {
                close(LocationProviderDisabledException("Location provider '$provider' is disabled."))
            }
        }

        try {
            locationManager.requestLocationUpdates(
                selectedProvider,
                request.toAndroidLocationRequest(),
                callbackExecutor,
                listener,
            )
        } catch (error: SecurityException) {
            close(
                LocationPermissionException(
                    "Location permission is required to register updates from $selectedProvider.",
                    error,
                ),
            )
            return@callbackFlow
        } catch (error: Exception) {
            close(
                LocationRegistrationException(
                    "Unable to register location updates from $selectedProvider.",
                    error,
                ),
            )
            return@callbackFlow
        }

        awaitClose {
            try {
                locationManager.removeUpdates(listener)
            } catch (_: SecurityException) {
                // Permission revocation can race cancellation; the system has already detached us.
            }
        }
    }

    private fun providerState(provider: String): LocationProviderState {
        val exists = locationManager.hasProvider(provider)
        return LocationProviderState(
            exists = exists,
            enabled = exists && locationManager.isProviderEnabled(provider),
        )
    }

    private companion object {
        val DirectExecutor = Executor { command -> command.run() }
    }
}

/** Small Android-free seam for selecting and validating the configured provider. */
fun interface LocationProviderSelector {
    /** Returns the provider to register, or throws an actionable terminal exception. */
    fun select(preferredProvider: String, state: LocationProviderState): String
}

/** Android-free representation of the configured provider's state. */
data class LocationProviderState(
    val exists: Boolean,
    val enabled: Boolean,
)

internal object DefaultLocationProviderSelector : LocationProviderSelector {
    override fun select(preferredProvider: String, state: LocationProviderState): String = when {
        !state.exists -> throw LocationProviderUnavailableException(
            "Location provider '$preferredProvider' is unavailable on this device.",
        )

        !state.enabled -> throw LocationProviderDisabledException(
            "Location provider '$preferredProvider' is disabled.",
        )

        else -> preferredProvider
    }
}

/** Base type for terminal platform-location failures that a caller can report or recover from. */
sealed class PlatformLocationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class LocationProviderUnavailableException(message: String) : PlatformLocationException(message)

class LocationProviderDisabledException(message: String) : PlatformLocationException(message)

class LocationPermissionException(message: String, cause: SecurityException) :
    PlatformLocationException(message, cause)

class LocationRegistrationException(message: String, cause: Throwable) :
    PlatformLocationException(message, cause)

/** Android-free request representation, retained to make request conversion unit-testable. */
internal data class PlatformLocationRequest(
    val intervalMillis: Long,
    val minIntervalMillis: Long,
    val minDistanceMeters: Float,
)

internal fun LocationUpdateRequest.toPlatformLocationRequestSpec(): PlatformLocationRequest =
    PlatformLocationRequest(
        intervalMillis = intervalMillis,
        minIntervalMillis = minIntervalMillis,
        minDistanceMeters = minDisplacementMeters.toFloat(),
    )

private fun LocationUpdateRequest.toAndroidLocationRequest(): LocationRequest {
    val request = toPlatformLocationRequestSpec()
    require(request.minDistanceMeters.isFinite()) {
        "minDisplacementMeters is too large for Android's LocationRequest."
    }
    return LocationRequest.Builder(request.intervalMillis)
        .setMinUpdateIntervalMillis(request.minIntervalMillis)
        .setMinUpdateDistanceMeters(request.minDistanceMeters)
        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
        .build()
}

/** Android-free snapshot used to keep conversion behavior directly unit-testable. */
internal data class PlatformLocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val elapsedRealtimeNanos: Long,
    val epochMillis: Long,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val isMock: Boolean,
)

internal fun PlatformLocationSample.toRawLocationFix(): RawLocationFix = RawLocationFix(
    latitude = latitude,
    longitude = longitude,
    horizontalAccuracyMeters = accuracyMeters ?: Double.NaN,
    capturedAtElapsedRealtimeNanos = elapsedRealtimeNanos,
    epochMillis = epochMillis,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = bearingDegrees,
    isMock = isMock,
)

private fun Location.toPlatformLocationSample() = PlatformLocationSample(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    epochMillis = time,
    altitudeMeters = if (hasAltitude()) altitude else null,
    speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
    bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
    isMock = isMock,
)
