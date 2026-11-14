package cloud.trotter.log.gig.core.data.wipe

import android.app.ActivityManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #1579: the wipe is Android's own Clear storage for this app, the operation Settings > Apps runs. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ActivityManagerStorageClearerTest {
    @Test fun `the clear asks Android to clear this app's storage`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ActivityManager::class.java)
        assertFalse(shadowOf(manager).isApplicationUserDataCleared)

        assertTrue(ActivityManagerStorageClearer(context).clear())
        assertTrue(shadowOf(manager).isApplicationUserDataCleared)
    }
}
