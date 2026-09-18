package app.trailveil.googlepoc

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.LatitudeBuckets
import app.trailveil.data.db.TrackPointCells
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportBounds
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.harness.DemoExplorationSeeder
import app.trailveil.harness.DemoTaiwanAnchors
import app.trailveil.map.fog.FogPocSpatialSelection
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRenderVersions
import app.trailveil.map.fog.FogTileRenderer
import app.trailveil.map.fog.FogViewportCoordinator
import app.trailveil.map.fog.FogViewportRequest
import app.trailveil.map.fog.FogViewportTileGrid
import app.trailveil.map.fog.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V03-013` opt-in cost probe for the low-zoom raster (the 4.0-4.4 s `raster=` stage every arm
 * pays on a zoom-out to z10 and below over the 200-session Taiwan fixture).
 *
 * It re-runs the exact stages of `FogViewportCoordinator.renderTilesLocked` one at a time on the
 * seeded database - the SQL plan and scan, Room materialisation, the data source's model, the
 * spatial selection, the per-tile raster - and then the coordinator's own cold and warm
 * `renderTiles`, so the seconds can be attributed instead of guessed. Emits counts and timings only.
 */
@RunWith(AndroidJUnit4::class)
class LowZoomRasterCostProbeTest {
    @Test fun lowZoomRasterStagesAreTimedOnTheTaiwanFixture() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("explicit probe opt-in required",
            InstrumentationRegistry.getArguments().getString("trailveilLowZoomRasterProbe") == "true")
        assumeTrue("synthetic emulator probe only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val context = instrumentation.targetContext
        val container = (context.applicationContext as TrailVeilApplication).appContainer
        withContext(Dispatchers.IO) {
            if (DemoExplorationSeeder.seededSessionCount(context) != DemoTaiwanAnchors.DEFAULT_SESSIONS) {
                val seeded = DemoExplorationSeeder.seedRegion(context)
                assertEquals(DemoTaiwanAnchors.DEFAULT_SESSIONS, seeded.sessions)
            }
            val database = container.databaseForTesting()
            val recordingDao = database.recordingDao()
            val coordinator = container.fogRuntime().viewportCoordinator
            val style = FogRenderStyle()
            val renderer = FogTileRenderer(style)
            val source = ViewportTrackDataSource(RoomViewportTrackPointReader(recordingDao))
            val anchor = DemoTaiwanAnchors.anchors().first()
            val center = GeoPoint(anchor.latitude, anchor.longitude)
            val total = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM track_points").use {
                check(it.moveToFirst()); it.getLong(0)
            }
            emit(context, "V03-013-LOWZOOM-PROBE start points=$total hardware=${Build.HARDWARE}")
            for (pass in 1..2) {
                for (zoom in listOf(12, 10, 9, 8)) {
                    val keys = FogViewportTileGrid.around(center, zoom, FogRenderVersions.CURRENT, paddingTiles = 1)
                    val bounds = FogViewportTileGrid.queryBounds(keys, FogViewportCoordinator.DEFAULT_QUERY_MARGIN_METERS)
                    val (label, sql) = sqlFor(bounds)
                    val plan = database.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                        val detail = cursor.getColumnIndex("detail")
                        buildList { while (cursor.moveToNext()) add(cursor.getString(detail)) }
                    }
                    val scan = timed {
                        database.openHelper.readableDatabase.query(sql).use { cursor ->
                            var rows = 0L
                            while (cursor.moveToNext()) rows++
                            rows
                        }
                    }
                    val materialised = timed {
                        database.openHelper.readableDatabase.query(sql).use { cursor ->
                            val out = ArrayList<DoubleArray>(scan.first.toInt())
                            while (cursor.moveToNext()) {
                                out.add(
                                    doubleArrayOf(
                                        cursor.getLong(0).toDouble(), cursor.getLong(1).toDouble(),
                                        cursor.getLong(2).toDouble(), cursor.getLong(3).toDouble(),
                                        cursor.getLong(4).toDouble(), cursor.getDouble(5), cursor.getDouble(6),
                                    ),
                                )
                            }
                            out.size
                        }
                    }
                    val daoRead = timed {
                        val buckets = LatitudeBuckets.covering(bounds.south, bounds.north)
                        if (buckets == null) {
                            recordingDao.fogPointsInLongitudeInterval(bounds.south, bounds.west, bounds.north, bounds.east).size
                        } else {
                            recordingDao.fogPointsInBucketedBox(buckets, bounds.south, bounds.west, bounds.north, bounds.east).size
                        }
                    }
                    val model = timed { source.read(bounds, coarse = TrackPointCells.coarseReadIsSubPixel(zoom)) }
                    val segments = model.first.toFogTrackSegments()
                    val points = segments.sumOf { it.points.size }
                    val select = timed { FogPocSpatialSelection.select(keys, segments, style) }
                    var renderMax = 0L
                    val render = timed {
                        keys.forEach { key ->
                            val one = timed { renderer.render(key, select.first[key].orEmpty()) }
                            renderMax = maxOf(renderMax, one.second)
                        }
                    }
                    val perTile = keys.joinToString("/") { key ->
                        select.first[key].orEmpty().sumOf { segment -> segment.points.size }.toString()
                    }
                    coordinator.clearDerivedCache()
                    val request = FogViewportRequest(center, zoom.toDouble())
                    val cold = timed { coordinator.renderTiles(request, keys) }
                    val warm = timed { coordinator.renderTiles(request, keys) }
                    emit(
                        context,
                        "V03-013-LOWZOOM-PROBE pass=$pass zoom=$zoom keys=${keys.size} query=$label " +
                            "plan=${plan.joinToString(" | ")} rows=${scan.first} scanMs=${scan.second} " +
                            "cursorMs=${materialised.second} daoMs=${daoRead.second} daoRows=${daoRead.first} " +
                            "modelMs=${model.second} segments=${segments.size} points=$points " +
                            "selectMs=${select.second} pointsPerTile=$perTile " +
                            "renderMs=${render.second} renderMaxTileMs=$renderMax " +
                            "coordColdMs=${cold.second} coordWarmMs=${warm.second}",
                    )
                    if (label == "plain") {
                        sqlVariants(bounds).forEach { (variant, variantSql) ->
                            val variantPlan = database.openHelper.readableDatabase
                                .query("EXPLAIN QUERY PLAN $variantSql").use { cursor ->
                                    val detail = cursor.getColumnIndex("detail")
                                    buildList { while (cursor.moveToNext()) add(cursor.getString(detail)) }
                                }
                            val read = timed {
                                database.openHelper.readableDatabase.query(variantSql).use { cursor ->
                                    var rows = 0L
                                    val columns = cursor.columnCount
                                    while (cursor.moveToNext()) {
                                        for (column in 0 until columns) cursor.getDouble(column)
                                        rows++
                                    }
                                    rows
                                }
                            }
                            emit(
                                context,
                                "V03-013-LOWZOOM-SQL pass=$pass zoom=$zoom variant=$variant rows=${read.first} " +
                                    "readMs=${read.second} plan=${variantPlan.joinToString(" | ")}",
                            )
                        }
                    }
                }
            }
            emit(context, "V03-013-LOWZOOM-PROBE done")
        }
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = SystemClock.elapsedRealtime()
        val value = block()
        return value to (SystemClock.elapsedRealtime() - started)
    }

    /**
     * The DAO's two fog reads as they are today (unordered since `V03-013`; the data source sorts),
     * with the bound values inlined so EXPLAIN QUERY PLAN sees the real plan. `daoMs` in the PROBE
     * line is Room running the real query; `scanMs`/`cursorMs` run this text.
     */
    private fun sqlFor(bounds: ViewportBounds): Pair<String, String> {
        val select = "SELECT p.id, p.session_id, p.segment_id, s.sequence, p.sequence, p.latitude, p.longitude " +
            "FROM track_points p INNER JOIN track_segments s ON s.id = p.segment_id AND s.session_id = p.session_id "
        val buckets = LatitudeBuckets.covering(bounds.south, bounds.north)
        return if (buckets == null) {
            "plain" to (
                select + "WHERE p.latitude BETWEEN ${bounds.south} AND ${bounds.north} " +
                    "AND p.longitude BETWEEN ${bounds.west} AND ${bounds.east}"
                )
        } else {
            "bucketed" to (
                select + "WHERE p.lat_bucket IN (${buckets.joinToString(",")}) " +
                    "AND p.longitude BETWEEN ${bounds.west} AND ${bounds.east} " +
                    "AND p.latitude BETWEEN ${bounds.south} AND ${bounds.north}"
                )
        }
    }

    /**
     * Variants of the plain read: the join and the ORDER BY are the two costs a plan can shed.
     * `joinOrder` is the pre-`V03-013` DAO text (with its ORDER BY), kept so the saving stays measurable.
     */
    private fun sqlVariants(bounds: ViewportBounds): List<Pair<String, String>> {
        val where = "WHERE p.latitude BETWEEN ${bounds.south} AND ${bounds.north} " +
            "AND p.longitude BETWEEN ${bounds.west} AND ${bounds.east}"
        val joined = "SELECT p.id, p.session_id, p.segment_id, s.sequence, p.sequence, p.latitude, p.longitude " +
            "FROM track_points p INNER JOIN track_segments s ON s.id = p.segment_id AND s.session_id = p.session_id "
        val joinedNotIndexed = "SELECT p.id, p.session_id, p.segment_id, s.sequence, p.sequence, p.latitude, p.longitude " +
            "FROM track_points p NOT INDEXED INNER JOIN track_segments s ON s.id = p.segment_id AND s.session_id = p.session_id "
        val alone = "SELECT p.id, p.session_id, p.segment_id, p.sequence, p.latitude, p.longitude FROM track_points p "
        val order = " ORDER BY p.session_id ASC, s.sequence ASC, p.sequence ASC, p.id ASC"
        val orderAlone = " ORDER BY p.session_id ASC, p.segment_id ASC, p.sequence ASC, p.id ASC"
        return listOf(
            "joinOrder" to (joined + where + order),
            "joinOrderNotIndexed" to (joinedNotIndexed + where + order),
            "joinNoOrder" to (joined + where),
            "noJoinOrder" to (alone + where + orderAlone),
            "noJoinNoOrder" to (alone + where),
            "noJoinNoOrderNotIndexed" to (alone.replace("FROM track_points p ", "FROM track_points p NOT INDEXED ") + where),
        )
    }

    private fun emit(context: Context, line: String) =
        SpikeEvidence.emit(context, "v03-013-lowzoom-probe.txt", line)
}
