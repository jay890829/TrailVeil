package app.trailveil.map

import android.content.Context
import androidx.startup.Initializer
import com.google.android.gms.maps.MapsInitializer

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
            MapsInitializer.initialize(context.applicationContext)
        } catch (_: Exception) {
            // Intentionally ignored; see above.
        } catch (_: LinkageError) {
            // Intentionally ignored; see above.
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
