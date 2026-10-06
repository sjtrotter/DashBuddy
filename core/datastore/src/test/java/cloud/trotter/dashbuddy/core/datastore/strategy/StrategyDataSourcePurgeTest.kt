package cloud.trotter.dashbuddy.core.datastore.strategy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyDataSourcePurgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `purge removes all seven dead keys once and preserves quick declines`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storeJob = Job()
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + storeJob),
            produceFile = { File(tmp.root, "strategy_purge.preferences_pb") },
        )
        try {
            val quickDeclinesKey = booleanPreferencesKey("quick_declines_enabled")
            ds.edit { prefs ->
                prefs[booleanPreferencesKey("auto_master_enabled")] = true
                prefs[booleanPreferencesKey("auto_accept_enabled")] = true
                prefs[doublePreferencesKey("auto_accept_min_pay")] = 12.0
                prefs[doublePreferencesKey("auto_accept_min_ratio")] = 3.25
                prefs[booleanPreferencesKey("auto_decline_enabled")] = true
                prefs[doublePreferencesKey("auto_decline_max_pay")] = 5.25
                prefs[doublePreferencesKey("auto_decline_min_ratio")] = 0.80
                prefs[quickDeclinesKey] = true
            }
            val src = StrategyDataSource(ds)

            assertTrue(src.purgeDeadAutomationKeys())
            assertEquals(setOf<Any>(quickDeclinesKey), ds.data.first().asMap().keys)
            assertTrue(src.quickDeclines.first())
            assertFalse(src.purgeDeadAutomationKeys())
            assertEquals(setOf<Any>(quickDeclinesKey), ds.data.first().asMap().keys)
        } finally {
            storeJob.cancel()
        }
    }
}
