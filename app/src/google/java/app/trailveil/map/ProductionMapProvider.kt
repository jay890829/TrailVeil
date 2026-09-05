package app.trailveil.map

/**
 * The provider this build renders with.
 *
 * The Maps SDK fetches its own basemap from its own endpoints, keyed by the API key in the merged
 * manifest, so there is no style document for this application to point at and no style URI to
 * record. That is what the null means; see [MapProviderConfiguration].
 */
internal val ProductionMapProvider = MapProviderConfiguration(
    providerName = "Google Maps",
    styleUri = null,
)
