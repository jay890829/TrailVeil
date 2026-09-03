package app.trailveil.googlepoc

import com.google.android.gms.maps.GoogleMap

/**
 * `V02-005` stage 3: the design's §8 hardening list as one callable, so the SP1 sweep exercises
 * the EXACT configuration the production hardening function will pin (a JVM source-presence test
 * evolves from this list at stage 5). Main-thread only.
 */
object GoogleMapSpikeSettings {
    fun applySection8Hardening(map: GoogleMap) {
        map.uiSettings.isMapToolbarEnabled = false
        map.isIndoorEnabled = false
        map.isBuildingsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = true
        map.setOnMarkerClickListener { true }
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
        // Deliberately NO poi/click/longClick listeners: the SDK ignores POI taps with no
        // listener registered, which is the design's "unexplored POI clicks return nothing".
    }

    /** SDK defaults except the two §8-motivating toggles the pre-hardening sweep force-enables. */
    fun applyPreHardeningSweep(map: GoogleMap, buildingsEnabled: Boolean, indoorEnabled: Boolean) {
        map.isBuildingsEnabled = buildingsEnabled
        map.isIndoorEnabled = indoorEnabled
    }
}
