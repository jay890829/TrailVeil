package app.trailveil.map

/**
 * The provider this build renders with.
 *
 * `V02-008`: this value names a provider and carries its style URI, so it belongs to a provider
 * source set and not to the shared one. While it lived in `src/main` the Google build's dex carried
 * the string `OpenFreeMap` and the OpenFreeMap style URI - a non-Google map's identity inside an
 * APK whose whole premise is that it contains none. Each provider source set declares this same
 * symbol and exactly one is compiled into any variant, the idiom `TrailVeilMapSurface` uses.
 */
internal val ProductionMapProvider = MapProviderConfiguration(
    providerName = "OpenFreeMap",
    styleUri = "https://tiles.openfreemap.org/styles/liberty",
)
