package app.trailveil.map

import android.content.Context
import androidx.startup.Initializer
import com.google.android.gms.maps.MapsInitializer

/**
 * The renderer this process was granted, or [ProviderRenderer.UNREPORTED] until the callback runs.
 *
 * `MapsInitializer`'s renderer preference is process-wide and latched on the first call, so the
 * grant is a process fact rather than a per-composition one, and this is the only place that knows
 * it. `V02-008`: the map surface consults it, and a legacy grant is terminal - `V02-005` stage 9
 * measured that renderer on six images and never got a green run out of it.
 */
@Volatile
internal var grantedGoogleRenderer: ProviderRenderer = ProviderRenderer.UNREPORTED
    private set

/** Loads the renderer/dynamite path at process startup for warm history returns. */
class GoogleMapWarmup : Initializer<Unit> {
    override fun create(context: Context) {
        // A warm start is an optimisation and must never be load-bearing. `MapsInitializer` absorbs
        // GooglePlayServicesNotAvailableException and some RemoteException ranges, but a
        // RemoteException from the dynamite delegate calls comes back out as an unchecked
        // RuntimeRemoteException; a broken module can still raise LinkageError. androidx.startup
        // runs this inside InitializationProvider's ContentProvider.onCreate and rethrows whatever
        // escapes, so an unguarded throw kills the process at launch — on exactly the devices whose
        // broken Maps SDK the terminal MapProviderUnavailableSurface exists to explain. Swallow it
        // and let the real map path reach that surface.
        //
        // Nothing is logged: `TrailVeilApplication` documents that the app itself writes no logcat
        // at all, and this failure is not swallowed in effect — the real map path hits the same
        // broken SDK moments later and reports it through the surface the user can actually see.
        try {
            // `V02-008`: the three-argument call, so the GRANTED renderer is observed rather than
            // assumed. No preference is requested - asking for one is a test-harness affordance,
            // and production takes whatever the device offers and then decides whether it can use
            // it. On the pinned SDK (play-services-maps 20.0.0) the callback is invoked inline,
            // inside `initialize` itself - measured from the SDK bytecode by the V02-008 verifier -
            // so the grant is latched here, inside ContentProvider.onCreate, before
            // Application.onCreate and before any composition. The SDK's contract does not
            // promise that, which is why the grant is a volatile process-wide value rather than a
            // return, and why UNREPORTED exists at all.
            MapsInitializer.initialize(
                context.applicationContext,
                MapsInitializer.Renderer.LATEST,
            ) { renderer ->
                grantedGoogleRenderer = when (renderer) {
                    MapsInitializer.Renderer.LEGACY -> ProviderRenderer.LEGACY
                    MapsInitializer.Renderer.LATEST -> ProviderRenderer.LATEST
                }
            }
        } catch (_: Exception) {
            // Intentionally ignored; see above.
        } catch (_: LinkageError) {
            // Intentionally ignored; see above.
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
