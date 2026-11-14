package cloud.trotter.log.gig.core.data.wipe

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Android's own Clear storage for this app (#1579). */
fun interface AppStorageClearer {
    /** True when Android started the clear; it then stops the app's processes, so the caller does not go on. */
    fun clear(): Boolean
}

/**
 * [ActivityManager.clearApplicationUserData]: the same operation as Settings > Apps > gig.log > Storage > Clear
 * storage. Android stops the app, deletes its private and app-specific external storage and its Keystore keys,
 * revokes the runtime permissions it granted and removes its notifications.
 */
class ActivityManagerStorageClearer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppStorageClearer {
    override fun clear(): Boolean =
        context.getSystemService(ActivityManager::class.java)?.clearApplicationUserData() == true
}
