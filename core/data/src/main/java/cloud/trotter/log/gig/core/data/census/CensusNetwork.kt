package cloud.trotter.log.gig.core.data.census

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1620: the network a census run would send on. WorkManager's constraint is set when a run is queued; the worker
 * checks again before sending, so a run queued under an older Wi-Fi only choice never sends over mobile data.
 */
@Singleton
open class CensusNetwork @Inject constructor(@param:ApplicationContext private val context: Context) {
    /** True when the active network is metered (mobile data, a metered hotspot) or there is none. */
    open fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
}
