package app.trailveil.map

/** Shared by native fill, raster masks and surrounding safety geometry. Opacity remains caller-owned. */
internal const val MAPLIBRE_FOG_COLOR: Int = 0xFF1F262B.toInt()

internal fun mapLibreFogPixel(alpha: Int): Int {
    val unsignedAlpha = alpha and 0xff
    return if (unsignedAlpha == 0) 0
    else (unsignedAlpha shl 24) or (MAPLIBRE_FOG_COLOR and 0x00ffffff)
}
