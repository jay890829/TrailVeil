package app.trailveil.harness

import android.os.Debug
import app.trailveil.data.map.ViewportBounds
import app.trailveil.map.fog.*
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject

/** Test-only observer of actual surface engine calls. Its overhead is not a UX cost measurement. */
internal class FinalGeometryOpportunityProbe(private val delegate: FogNativeGeometryEngine) : FogNativeGeometryEngine {
    val step = AtomicInteger(-1)
    val rows = CopyOnWriteArrayList<JSONObject>()
    private val results = CopyOnWriteArrayList<Pair<WeakReference<FogNativeGeometry>, Int>>()
    private var nextCall = 0
    private data class Identity(val reference: WeakReference<Any>, val id: Long)
    private data class Key(val unionId: Long, val partitions: List<FogTileKey>, val bounds: List<Long>, val radius: Long)
    private data class Cached(val hash: String, val vertices: Int)
    private val identities = ArrayList<Identity>()
    private val simulated = LinkedHashMap<Key, Cached>(4, 0.75f, true)
    private var nextId = 0L
    private var vertices = 0
    override val description: String get() = delegate.description
    override fun rawReadWindows(bounds: FogTileBounds, style: FogRenderStyle) = delegate.rawReadWindows(bounds, style)
    override suspend fun render(bounds: FogTileBounds, style: FogRenderStyle,
        read: suspend (ViewportBounds) -> List<TrackSegment>): FogNativeGeometry? {
        var reads = 0
        return observe(bounds, style, { reads }) { delegate.render(bounds, style) { reads++; read(it) } }
    }
    override suspend fun renderBatched(bounds: FogTileBounds, style: FogRenderStyle,
        read: suspend (ViewportBounds) -> List<TrackSegment>,
        readBatch: suspend (List<ViewportBounds>) -> List<List<TrackSegment>>): FogNativeGeometry? {
        var reads = 0
        return observe(bounds, style, { reads }) {
            delegate.renderBatched(bounds, style, { reads++; read(it) }, { reads++; readBatch(it) })
        }
    }
    override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) {
        delegate.invalidate(updates, style)
        prune()
    }
    override fun clear() {
        delegate.clear()
        identities.clear(); simulated.clear(); vertices = 0
    }

    private suspend fun observe(bounds: FogTileBounds, style: FogRenderStyle, reads: () -> Int,
        action: suspend () -> FogNativeGeometry?): FogNativeGeometry? {
        val label = step.get()
        val call = nextCall++
        val thread = Thread.currentThread().id
        val cpu = Debug.threadCpuTimeNanos()
        val start = System.nanoTime()
        val result = try { action() } catch (failure: Throwable) {
            val wall = System.nanoTime() - start
            prune()
            rows += JSONObject().put("call", call).put("step", label).put("outcome", "threw")
                .put("failureType", failure.javaClass.simpleName).put("cancelled", failure is CancellationException)
                .put("engineWallNs", wall).put("readCallbacks", reads()).put("native", false)
                .put("eligible", false).put("simulatedHit", false).put("activeAtReturn", JSONObject.NULL)
            throw failure
        }
        val wall = System.nanoTime() - start
        val cpuEnd = Debug.threadCpuTimeNanos()
        val sameThread = thread == Thread.currentThread().id
        val active = currentCoroutineContext()[Job]?.isActive != false
        // All reflection/hash/simulation is AFTER the engine's own clocks. No geometry is replaced.
        val live = prune()
        val boundsBits = listOf(bounds.westLongitude, bounds.southLatitude, bounds.eastLongitude, bounds.northLatitude).map(Double::toRawBits)
        val boundsHash = hash(boundsBits.joinToString(";").toByteArray())
        val row = JSONObject().put("call", call).put("step", label).put("outcome", "returned").put("boundsSha256", boundsHash)
            .put("radiusBits", style.revealRadiusMeters.toRawBits()).put("engineWallNs", wall)
            .put("readCallbacks", reads()).put("activeAtReturn", active)
            // Matching thread IDs alone do not prove exclusive CPU after a Room suspension.
            .put("warmThreadCpuNs", if (sameThread && reads() == 0) cpuEnd - cpu else JSONObject.NULL)
        if (result == null) {
            rows += row.put("native", false).put("description", delegate.description).put("simulatedHit", false)
            return null
        }
        val partitions = partitionKeys(bounds)
        val current = entries()[partitions]
        val unionId = current?.let { value -> live.single { it.reference.get() === value }.id }
        val outputHash = geometryHash(result)
        results += WeakReference(result) to call
        val outputVertices = result.polygons.sumOf { it.shell.size + it.holes.sumOf(List<GeoPoint>::size) }
        val key = unionId?.let { Key(it, partitions, boundsBits, style.revealRadiusMeters.toRawBits()) }
        val cached = key?.let { simulated[it] }
        if (cached != null) check(cached.hash == outputHash && cached.vertices == outputVertices) {
            "same live union identity and exact viewport produced different final geometry"
        }
        if (active && key != null && cached == null && outputVertices <= 40_000) {
            simulated[key] = Cached(outputHash, outputVertices); vertices += outputVertices
            while (simulated.size > 4 || vertices > 40_000) {
                val iterator = simulated.entries.iterator()
                vertices -= iterator.next().value.vertices; iterator.remove()
            }
        }
        fun stage(name: String): Int = checkNotNull(Regex("(?:^| )$name=(\\d+)").find(result.diagnostics)).groupValues[1].toInt()
        rows += row.put("native", true).put("outputSha256", outputHash).put("outputVertices", outputVertices)
            .put("unionId", unionId ?: JSONObject.NULL).put("unionHit", stage("unionHit"))
            .put("differenceMs", stage("df")).put("ringMs", stage("rg"))
            .put("eligible", key != null && active).put("simulatedHit", cached != null && active)
            .put("simulatedEntries", simulated.size).put("simulatedVertices", vertices)
        return result
    }

    private fun entries(): Map<*, *> = field(field(delegate, "unionCache")!!, "entries") as Map<*, *>
    private fun prune(): List<Identity> {
        val values = entries().values.filterNotNull()
        identities.removeAll { identity -> values.none { it === identity.reference.get() } }
        for (value in values) if (identities.none { it.reference.get() === value }) identities += Identity(WeakReference(value), ++nextId)
        val liveIds = identities.map { it.id }.toSet()
        val iterator = simulated.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.unionId !in liveIds) { vertices -= entry.value.vertices; iterator.remove() }
        }
        return identities.toList()
    }
    private fun partitionKeys(bounds: FogTileBounds): List<FogTileKey> {
        val plan = checkNotNull(delegate.javaClass.getDeclaredMethod("partitionPlan", FogTileBounds::class.java)
            .apply { isAccessible = true }.invoke(delegate, bounds))
        return buildList {
            for (y in (field(plan, "y0") as Int)..(field(plan, "y1") as Int)) {
                for (x in (field(plan, "x0") as Int)..(field(plan, "x1") as Int)) add(FogTileKey(12, x, y, FogRenderVersions.CURRENT))
            }
        }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    fun uninstall(coordinator: FogViewportCoordinator) {
        val field = coordinator.javaClass.getDeclaredField("nativeGeometryEngine").apply { isAccessible = true }
        check(field.get(coordinator) === this)
        field.set(coordinator, delegate)
    }
    fun observationFor(geometry: FogNativeGeometry): JSONObject {
        val call = results.single { it.first.get() === geometry }.second
        return rows.single { it.getInt("call") == call }
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun geometryHash(geometry: FogNativeGeometry): String {
        val text = buildString {
            val b = geometry.bounds
            listOf(b.westLongitude, b.southLatitude, b.eastLongitude, b.northLatitude).forEach { append(it.toRawBits()).append(';') }
            append(geometry.polygons.size).append(';')
            geometry.polygons.forEach { polygon ->
                append(polygon.holes.size).append(';')
                (listOf(polygon.shell) + polygon.holes).forEach { ring ->
                    append(ring.size).append(';')
                    ring.forEach { point -> append(point.latitude.toRawBits()).append(';').append(point.longitude.toRawBits()).append(';') }
                }
            }
        }
        return hash(text.toByteArray())
    }
    companion object {
        fun install(coordinator: FogViewportCoordinator): FinalGeometryOpportunityProbe {
            val field = coordinator.javaClass.getDeclaredField("nativeGeometryEngine").apply { isAccessible = true }
            val engine = field.get(coordinator) as FogNativeGeometryEngine
            check(engine.javaClass.name == "app.trailveil.harness.TrackRegionGeometryEngine")
            return FinalGeometryOpportunityProbe(engine).also { field.set(coordinator, it) }
        }
    }
}
