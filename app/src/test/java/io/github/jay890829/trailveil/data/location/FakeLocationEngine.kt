package io.github.jay890829.trailveil.data.location

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

internal class FakeLocationEngine : LocationEngine {
    private val channel = Channel<RawLocationFix>(capacity = Channel.UNLIMITED)
    val capturedRequests = mutableListOf<LocationUpdateRequest>()

    override fun fixes(request: LocationUpdateRequest): Flow<RawLocationFix> = flow {
        capturedRequests += request
        channel.receiveAsFlow().collect(::emit)
    }

    suspend fun emit(fix: RawLocationFix) {
        channel.send(fix)
    }

    fun close() {
        channel.close()
    }
}
