package io.github.jay890829.trailveil.map.poc

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.jay890829.trailveil.R
import io.github.jay890829.trailveil.map.fog.FogPixelMask
import io.github.jay890829.trailveil.map.fog.FogMosaicTile
import io.github.jay890829.trailveil.map.fog.FogRenderStyle
import io.github.jay890829.trailveil.map.fog.FogPocSpatialSelection
import io.github.jay890829.trailveil.map.fog.FogPocMosaic
import io.github.jay890829.trailveil.map.fog.FogPocTileGrid
import io.github.jay890829.trailveil.map.fog.FogPocTiming
import io.github.jay890829.trailveil.map.fog.FogPocTimingStage
import io.github.jay890829.trailveil.map.fog.FogTileBounds
import io.github.jay890829.trailveil.map.fog.FogTileKey
import io.github.jay890829.trailveil.map.fog.FogTileRenderer
import io.github.jay890829.trailveil.map.fog.SyntheticFogDatasets
import io.github.jay890829.trailveil.map.fog.TrackSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource

class MapLibrePocActivity : ComponentActivity() {
    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private var fallbackRequested = false
    private var styleLoadStartedNanos = 0L
    private val stressDataset by lazy {
        lifecycleScope.async(Dispatchers.Default) { SyntheticFogDatasets.stress100k() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        mapView = MapView(this)
        setContentView(mapView)
        mapView.onCreate(savedInstanceState)
        mapView.addOnDidFailLoadingMapListener {
            if (!fallbackRequested) {
                fallbackRequested = true
                loadStyle(offline = true)
            }
        }
        mapView.getMapAsync { readyMap ->
            mapLibreMap = readyMap
            lifecycleScope.launch {
                val dataset = stressDataset.await()
                val firstPoint = dataset.first().points.first()
                if (savedInstanceState == null) {
                    readyMap.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(firstPoint.latitude, firstPoint.longitude))
                        .zoom(POC_ZOOM.toDouble())
                        .build()
                }
                loadStyle(offline = intent.getBooleanExtra(EXTRA_OFFLINE, false))
            }
        }
    }

    private fun loadStyle(offline: Boolean) {
        val readyMap = mapLibreMap ?: return
        styleLoadStartedNanos = SystemClock.elapsedRealtimeNanos()
        val builder = if (offline) {
            val json = resources.openRawResource(R.raw.maplibre_fallback_style)
                .bufferedReader()
                .use { it.readText() }
            Style.Builder().fromJson(json)
        } else {
            Style.Builder().fromUri(OPEN_FREE_MAP_LIBERTY_STYLE)
        }
        readyMap.setStyle(builder) { style ->
            logTiming(
                FogPocTimingStage.STYLE_LOAD,
                styleLoadStartedNanos,
                pointCount = 0,
                tileCount = 0,
            )
            renderFogProof(style)
        }
    }

    private fun renderFogProof(style: Style) {
        lifecycleScope.launch {
            val dataset = stressDataset.await()
            val firstPoint = dataset.first().points.first()
            val tileKeys = FogPocTileGrid.around(firstPoint, POC_ZOOM, renderVersion = 0)
            val initialSegments = listOf(
                TrackSegment(dataset.first().id, listOf(firstPoint)),
            )

            val initialStarted = SystemClock.elapsedRealtimeNanos()
            val initialMosaic = renderMosaic(tileKeys, initialSegments)
            addFogMosaic(style, initialMosaic)
            logTiming(
                FogPocTimingStage.INITIAL_RENDER,
                initialStarted,
                pointCount = 1,
                tileCount = initialMosaic.tileCount,
            )

            val updateStarted = SystemClock.elapsedRealtimeNanos()
            val updatedMosaic = renderMosaic(
                tileKeys.map { it.copy(renderVersion = 1) },
                dataset,
            )
            val frameListener = object : MapView.OnDidFinishRenderingFrameListener {
                override fun onDidFinishRenderingFrame(
                    fully: Boolean,
                    frameEncodingTime: Double,
                    frameRenderingTime: Double,
                ) {
                    mapView.removeOnDidFinishRenderingFrameListener(this)
                    logTiming(
                        FogPocTimingStage.NEXT_RENDERED_FRAME,
                        updateStarted,
                        pointCount = SyntheticFogDatasets.STRESS_POINT_COUNT,
                        tileCount = updatedMosaic.tileCount,
                    )
                }
            }
            mapView.addOnDidFinishRenderingFrameListener(frameListener)
            style.getSourceAs<ImageSource>(FOG_SOURCE_ID)?.setImage(updatedMosaic.bitmap)
            logTiming(
                FogPocTimingStage.UPDATE_RENDER,
                updateStarted,
                pointCount = SyntheticFogDatasets.STRESS_POINT_COUNT,
                tileCount = updatedMosaic.tileCount,
            )
        }
    }

    private suspend fun renderMosaic(
        keys: List<FogTileKey>,
        segments: List<TrackSegment>,
    ): RenderedFogMosaic = withContext(Dispatchers.Default) {
        val renderStyle = FogRenderStyle(tileSize = POC_TILE_SIZE)
        val renderer = FogTileRenderer(renderStyle)
        val selectedSegments = FogPocSpatialSelection.select(keys, segments, renderStyle)
        val tiles = keys.map { key ->
            currentCoroutineContext().ensureActive()
            FogMosaicTile(
                key = key,
                mask = renderer.render(key, selectedSegments.getValue(key)),
            )
        }
        val mosaic = FogPocMosaic.compose(tiles)
        RenderedFogMosaic(
            bounds = mosaic.bounds,
            bitmap = mosaic.mask.toBitmap(),
            tileCount = mosaic.tileCount,
        )
    }

    private fun addFogMosaic(style: Style, mosaic: RenderedFogMosaic) {
        val bounds = mosaic.bounds
        val coordinates = LatLngQuad(
            LatLng(bounds.northLatitude, bounds.westLongitude),
            LatLng(bounds.northLatitude, bounds.eastLongitude),
            LatLng(bounds.southLatitude, bounds.eastLongitude),
            LatLng(bounds.southLatitude, bounds.westLongitude),
        )
        style.addSource(ImageSource(FOG_SOURCE_ID, coordinates, mosaic.bitmap))
        val layer = RasterLayer(FOG_LAYER_ID, FOG_SOURCE_ID).withProperties(
            PropertyFactory.rasterFadeDuration(0f),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
        )
        style.addLayer(layer)
    }

    private fun FogPixelMask.toBitmap(): Bitmap {
        val pixels = copyAlpha().map { alpha ->
            (alpha.toInt() and 0xff) shl 24
        }.toIntArray()
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // This coordinate-free benchmark payload intentionally remains capturable through adb.
    @SuppressLint("LogNotTimber")
    private fun logTiming(
        stage: FogPocTimingStage,
        startedNanos: Long,
        pointCount: Int,
        tileCount: Int,
    ) {
        val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L
        Log.i(
            LOG_TAG,
            FogPocTiming(stage, elapsedMillis, pointCount, tileCount).asStructuredLog(),
        )
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    private data class RenderedFogMosaic(
        val bounds: FogTileBounds,
        val bitmap: Bitmap,
        val tileCount: Int,
    )

    private companion object {
        const val EXTRA_OFFLINE = "offline"
        const val OPEN_FREE_MAP_LIBERTY_STYLE =
            "https://tiles.openfreemap.org/styles/liberty"
        const val POC_ZOOM = 8
        const val POC_TILE_SIZE = 256
        const val LOG_TAG = "TrailVeilMapLibrePoc"

        const val FOG_SOURCE_ID = "trailveil-fog-mosaic"
        const val FOG_LAYER_ID = "trailveil-fog-mosaic-layer"
    }
}
