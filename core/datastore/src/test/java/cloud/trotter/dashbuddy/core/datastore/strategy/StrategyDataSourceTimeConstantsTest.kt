package cloud.trotter.dashbuddy.core.datastore.strategy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import cloud.trotter.dashbuddy.domain.evaluation.LearnedTimeConstants
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantPair
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyDataSourceTimeConstantsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val platforms = Platform.entries.filter { it != Platform.Unknown }
    private val value = LearnedTimeConstants(TimeConstantPair(2.0, 8.0), 40)

    @Test fun `complete tuples replace atomically remove absent platforms and preserve all other keys`() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("strategy.preferences_pb") })
        val source = StrategyDataSource(ds)
        source.recordShopRate(platforms[0], 20, 20.0)
        source.recordItemsPerUnitRatio(platforms[0], 20, 16)
        source.setAllowShopping(false)
        val before = ds.data.first().asMap()
        val seen = mutableListOf<Map<Platform, LearnedTimeConstants>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.learnedTimeConstants.collect { seen += it } }
        val both = mapOf(platforms[0] to value, platforms[1] to value.copy(sampleCount = 9))
        source.replaceTimeConstants(both)
        assertEquals(both, source.learnedTimeConstants.first())
        source.replaceTimeConstants(both)
        val one = mapOf(platforms[1] to value.copy(median = TimeConstantPair(3.0, 0.0), sampleCount = 1))
        source.replaceTimeConstants(one)
        assertEquals(one, source.learnedTimeConstants.first())
        val raw = ds.data.first().asMap()
        assertFalse(raw.keys.any { it.name.endsWith(":" + platforms[0].wire) && it.name.startsWith("time_constant") })
        assertNull(raw[doublePreferencesKey("learned_minutes_per_mile:${platforms[0].wire}")])
        assertNull(raw[doublePreferencesKey("learned_stop_overhead_minutes:${platforms[0].wire}")])
        before.forEach { (key, v) -> assertEquals(v, raw[key]) }
        source.replaceTimeConstants(emptyMap())
        assertEquals(before, ds.data.first().asMap())
        assertTrue(seen.all { it.isEmpty() || it == both || it == one })
    }

    @Test fun `incomplete nonfinite wrongly typed and invalid persisted tuples are rejected together`() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("corrupt.preferences_pb") })
        val source = StrategyDataSource(ds)
        val p = platforms[0]
        val pace = doublePreferencesKey("learned_minutes_per_mile:${p.wire}")
        val overhead = doublePreferencesKey("learned_stop_overhead_minutes:${p.wire}")
        val count = intPreferencesKey("time_constant_sample_count:${p.wire}")
        for ((a, b, n) in listOf(Triple(null, 8.0, 10), Triple(2.0, null, 10), Triple(2.0, 8.0, null),
            Triple(Double.NaN, 8.0, 10), Triple(2.0, Double.POSITIVE_INFINITY, 10),
            Triple(0.0, 8.0, 10), Triple(-1.0, 8.0, 10), Triple(2.0, -1.0, 10),
            Triple(2.0, 8.0, 0), Triple(2.0, 8.0, -1))) {
            ds.edit { prefs ->
                prefs.clear()
                a?.let { prefs[pace] = it }; b?.let { prefs[overhead] = it }; n?.let { prefs[count] = it }
            }
            assertTrue(source.learnedTimeConstants.first().isEmpty())
        }
        ds.edit { it[stringPreferencesKey(pace.name)] = "bad type" }
        assertTrue(source.learnedTimeConstants.first().isEmpty())
        assertTrue(runCatching { source.replaceTimeConstants(mapOf(p to value.copy(sampleCount = 0))) }.exceptionOrNull() is IllegalArgumentException)
        source.replaceTimeConstants(mapOf(p to value))
        assertEquals(mapOf(p to value), source.learnedTimeConstants.first())
    }
}
