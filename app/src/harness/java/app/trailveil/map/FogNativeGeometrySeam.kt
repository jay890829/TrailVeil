package app.trailveil.map

import app.trailveil.harness.TrackRegionGeometryEngine
import app.trailveil.map.fog.FogNativeGeometryEngine

/** Instantiated per runtime, never global; no read or allocation of geometry until requested. */
internal fun fogNativeGeometryEngine(): FogNativeGeometryEngine = TrackRegionGeometryEngine()
