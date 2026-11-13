package cloud.trotter.log.gig.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1615: the backed-up stores are a subset of every store, and each name is a plain file stem. */
class DataStoreFilesTest {

    @Test
    fun `every backed-up store is a provided store`() {
        assertTrue(DataStoreFiles.ALL.containsAll(DataStoreFiles.BACKED_UP))
    }

    @Test
    fun `the backed-up set is the one Android backup carries`() {
        assertEquals(
            setOf("vehicles", "app_prefs", "app_state", "odometer_prefs", "strategy_prefs", "platform_prefs", "weekly_plan"),
            DataStoreFiles.BACKED_UP,
        )
    }

    @Test
    fun `store names are plain file stems`() {
        for (name in DataStoreFiles.ALL) {
            assertTrue(name, Regex("[a-z_]+").matches(name))
        }
    }
}
