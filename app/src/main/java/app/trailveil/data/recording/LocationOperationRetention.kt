package app.trailveil.data.recording

import java.util.UUID

/**
 * Production location-operation IDs are ordered within one app-process token. The ordering lets
 * Room retain a fixed recent receipt window and reject an older retry after its full receipt has
 * been pruned. Legacy opaque IDs remain replayable and are never selected for pruning.
 */
internal data class LocationOperationSequence(
    val runtimeToken: String,
    val sequence: Long,
) {
    init {
        UUID.fromString(runtimeToken)
        require(sequence > 0L) { "location operation sequence must be positive" }
    }

    fun toOperationId(): RecordingOperationId =
        RecordingOperationId("$LOCATION_OPERATION_PREFIX$runtimeToken:$sequence")
}

internal fun RecordingOperationId.locationSequenceOrNull(): LocationOperationSequence? =
    value.locationSequenceOrNull()

internal fun String.locationSequenceOrNull(): LocationOperationSequence? {
    if (!startsWith(LOCATION_OPERATION_PREFIX)) return null
    val payload = removePrefix(LOCATION_OPERATION_PREFIX)
    val separator = payload.lastIndexOf(':')
    require(separator > 0 && separator < payload.lastIndex) {
        "malformed structured location operation id"
    }
    val runtimeToken = payload.substring(0, separator)
    val sequence = payload.substring(separator + 1).toLongOrNull()
        ?: throw IllegalArgumentException("malformed structured location operation sequence")
    return LocationOperationSequence(runtimeToken, sequence)
}

/** An old supported-format retry was recognized and rejected without executing it again. */
internal class ExpiredLocationOperationException(message: String) : IllegalStateException(message)

internal const val LOCATION_RECEIPT_RETAIN_COUNT = 1_024
internal const val LOCATION_RECEIPT_PRUNE_INTERVAL = 256L
private const val LOCATION_OPERATION_PREFIX = "location:"
