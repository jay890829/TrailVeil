package app.trailveil.map.fog

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Optional per-render diagnostic; accumulates the coordinator's sequential LOD windows. */
internal class FogRasterWorkProbe : AbstractCoroutineContextElement(FogRasterWorkProbe) {
    val readNanos = AtomicLong()
    val selectionNanos = AtomicLong()
    val paintNanos = AtomicLong()
    companion object Key : CoroutineContext.Key<FogRasterWorkProbe>
}
