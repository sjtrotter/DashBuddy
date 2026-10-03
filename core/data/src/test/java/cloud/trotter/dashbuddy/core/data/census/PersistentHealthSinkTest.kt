package cloud.trotter.dashbuddy.core.data.census

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.census.HealthKey
import cloud.trotter.dashbuddy.domain.census.HealthLedger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentHealthSinkTest {
    @Before fun resetProcessWarnings() {
        PersistentHealthSink.loadFailureWarned.set(false)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        val memory = MutableStateFlow(emptyPreferences())
        var loadGate: CompletableDeferred<Unit>? = null
        var writes = 0
        var loadFailures = 0
        var loads = 0
        override val data = flow {
            loads++
            if (loadFailures > 0) {
                loadFailures--
                throw IOException("health load failed")
            }
            loadGate?.await()
            emit(memory.value)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(memory.value).also { memory.value = it; writes++ }
    }

    private val key = HealthKey(LocalDate.now(ZoneOffset.UTC).minusDays(1).toString(), "doordash", "7.1")
    private val timestamp = LocalDate.parse(key.day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val stats = CensusUploadStats()
    private val memory = MemoryPreferences()
    private val store = HealthLedgerStore(memory, stats)
    private fun TestScope.sink() = PersistentHealthSink(store, stats, backgroundScope, StandardTestDispatcher(testScheduler))

    @Test fun `records queued before lazy load completes merge with disk`() = runTest {
        store.save(HealthLedger(generation = 5).record(key, "doordash.screen.offer"))
        memory.loadGate = CompletableDeferred()
        val sink = sink()
        sink.onScreen(timestamp, key.platform, key.platformAppVersion, null)
        runCurrent()
        sink.onTrip(timestamp, key.platform, key.platformAppVersion)
        sink.onScreen(timestamp, key.platform, key.platformAppVersion, "doordash.screen.offer")
        runCurrent()
        memory.loadGate!!.complete(Unit)
        val ledger = sink.snapshot()
        assertEquals(5L, ledger.generation)
        assertEquals(2, ledger.rows.getValue(key.toString()).admitted)
        assertEquals(1, ledger.rows.getValue(key.toString()).unknown)
        assertEquals(1, ledger.rows.getValue(key.toString()).trips)
        assertEquals(2L, stats.healthRecorded.get())
        assertEquals(ledger, store.load())
    }

    @Test fun `null versions skip without creating placeholders`() = runTest {
        val sink = sink()
        sink.onScreen(timestamp, key.platform, null, null)
        sink.onTrip(timestamp, key.platform, null)
        assertTrue(sink.snapshot().rows.isEmpty())
        assertEquals(2L, stats.healthSkippedNoVersion.get())
        assertEquals(0L, stats.healthRecorded.get())
    }

    @Test fun `invalid versions skip and valid records still save and load cleanly`() = runTest {
        val sink = sink()
        for (version in listOf("8.97.8|prod", "8.97.8-beta")) {
            sink.onScreen(timestamp, key.platform, version, null)
            sink.onTrip(timestamp, key.platform, version)
        }
        sink.onScreen(timestamp, key.platform, key.platformAppVersion, null)
        sink.onTrip(timestamp, key.platform, key.platformAppVersion)
        val ledger = sink.snapshot()
        assertEquals(setOf(key.toString()), ledger.rows.keys)
        assertEquals(1, ledger.rows.getValue(key.toString()).unknown)
        assertEquals(1, ledger.rows.getValue(key.toString()).trips)
        assertEquals(4L, stats.healthSkippedBadVersion.get())
        assertEquals(0L, stats.healthSkippedNoVersion.get())
        assertEquals(1L, stats.healthRecorded.get())
        assertEquals(ledger, store.load())
        assertEquals(0L, stats.healthCorrupt.get())
        assertTrue(stats.summary().contains(",healthSkippedBadVersion=4"))
    }

    @Test fun `three load failures back off retain queued records and warn once per process`() = runTest {
        memory.loadFailures = 3
        val warnings = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == Log.WARN && tag == "Census") warnings += message
            }
        }
        Timber.plant(tree)
        try {
            val sink = sink()
            sink.onScreen(timestamp, key.platform, key.platformAppVersion, null)
            runCurrent()
            assertEquals(1, memory.loads)
            for ((delayMillis, failures) in listOf(1_000L to 2, 2_000L to 3)) {
                advanceTimeBy(delayMillis - 1)
                runCurrent()
                assertEquals(failures - 1, memory.loads)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(failures, memory.loads)
            }
            assertEquals(3L, stats.healthLoadFailures.get())
            assertEquals(listOf("census health load failed"), warnings)
            assertTrue(stats.summary().contains(",healthLoadFailures=3"))
            advanceTimeBy(3_999)
            runCurrent()
            assertEquals(3, memory.loads)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(4, memory.loads)
            assertEquals(1, sink.snapshot().rows.getValue(key.toString()).unknown)
            assertEquals(3L, stats.healthLoadFailures.get())
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `load retry backoff caps at sixty seconds`() = runTest {
        memory.loadFailures = 9
        val sink = sink()
        sink.onScreen(timestamp, key.platform, key.platformAppVersion, null)
        runCurrent()
        var attempts = 1
        for (delayMillis in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L)) {
            advanceTimeBy(delayMillis - 1)
            runCurrent()
            assertEquals(attempts, memory.loads)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(++attempts, memory.loads)
        }
        assertEquals(9L, stats.healthLoadFailures.get())
        assertEquals(1, sink.snapshot().rows.getValue(key.toString()).unknown)
    }

    @Test fun `continuous traffic flushes by sixty seconds without a trailing debounce`() = runTest {
        val sink = sink()
        repeat(60) {
            sink.onScreen(timestamp, key.platform, key.platformAppVersion, null)
            runCurrent()
            advanceTimeBy(1_000)
        }
        runCurrent()
        assertEquals(1, memory.writes)
        assertEquals(60, store.load().rows.getValue(key.toString()).unknown)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, memory.writes) // Clean ticks do not write.
    }

    @Test fun `apply drains pending frames before exact acknowledgement and saves immediately`() = runTest {
        val sink = sink()
        sink.onScreen(timestamp, key.platform, key.platformAppVersion, null)
        val first = sink.snapshot()
        sink.onScreen(timestamp, key.platform, key.platformAppVersion, null)
        sink.apply { it.markPosted(key, first.rows.getValue(key.toString()).revision) }
        assertEquals(-1L, store.load().rows.getValue(key.toString()).postedRevision)
        sink.apply { it.markPosted(key, 2) }
        assertEquals(2L, store.load().rows.getValue(key.toString()).postedRevision)
    }

    @Test fun `reset discards queued old generation frames and persists the new generation`() = runTest {
        store.save(HealthLedger(generation = 7).record(key, null))
        val sink = sink()
        sink.onScreen(timestamp, key.platform, key.platformAppVersion, null)
        sink.reset()
        runCurrent()
        assertEquals(HealthLedger(generation = 8), sink.snapshot())
        assertEquals(HealthLedger(generation = 8), store.load())
        sink.onScreen(timestamp, key.platform, key.platformAppVersion, "doordash.screen.offer")
        assertEquals(1, sink.snapshot().rows.getValue(key.toString()).admitted)
        assertEquals(8L, store.load().generation)
    }

    @Test fun `bounded queue drops oldest and counts lost records`() = runTest {
        val sink = sink()
        repeat(1025) { sink.onScreen(timestamp, key.platform, key.platformAppVersion, if (it == 0) "doordash.screen.offer" else null) }
        val row = sink.snapshot().rows.getValue(key.toString())
        assertEquals(1L, stats.healthDropped.get())
        assertEquals(1024, row.unknown)
        assertEquals(0, row.admitted)
    }

    @Test fun `snapshot prunes beyond eight UTC days and keeps boundary`() = runTest {
        val today = LocalDate.now(ZoneOffset.UTC)
        val retained = key.copy(day = today.minusDays(8).toString())
        store.save(HealthLedger().record(retained, null).record(key.copy(day = today.minusDays(9).toString()), null))
        assertEquals(setOf(retained.toString()), sink().snapshot().rows.keys)
    }

    @Test fun `consumer prunes on load without a snapshot or upload`() = runTest {
        val today = LocalDate.now(ZoneOffset.UTC)
        val retained = key.copy(day = today.minusDays(8).toString())
        val current = key.copy(day = today.toString())
        store.save(HealthLedger().record(retained, null).record(key.copy(day = today.minusDays(9).toString()), null))
        val sink = sink()
        sink.onTrip(today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), key.platform, key.platformAppVersion)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(setOf(retained.toString(), current.toString()), store.load().rows.keys)
    }

    @Test fun `thirty day feed prunes on each UTC day change without uploads or consent`() = runTest {
        val sink = sink()
        val start = LocalDate.now(ZoneOffset.UTC)
        repeat(30) { offset ->
            val day = start.plusDays(offset.toLong())
            sink.onScreen(day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), key.platform, key.platformAppVersion, null)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            val expectedDays = (maxOf(0, offset - 8)..offset).map { start.plusDays(it.toLong()).toString() }.toSet()
            // The inclusive boundary keeps today and days up to eight days old.
            assertEquals(expectedDays, store.load().rows.keys.map { HealthKey.parse(it).day }.toSet())
        }
    }

    @Test fun `row cap counts refused screens and trips while existing rows keep recording`() = runTest {
        val sink = sink()
        repeat(HealthLedger.MAX_ROWS) { sink.onScreen(timestamp, key.platform, "$it", null) }
        sink.onScreen(timestamp, key.platform, "256", null)
        sink.onTrip(timestamp, key.platform, "256")
        sink.onScreen(timestamp, key.platform, "0", null)
        sink.onTrip(timestamp, key.platform, "0")
        val ledger = sink.snapshot()
        assertEquals(256, ledger.rows.size)
        assertEquals(2, ledger.rows.getValue(key.copy(platformAppVersion = "0").toString()).unknown)
        assertEquals(1, ledger.rows.getValue(key.copy(platformAppVersion = "0").toString()).trips)
        assertEquals(257L, stats.healthRecorded.get())
        assertEquals(2L, stats.healthRowsRefused.get())
        assertTrue(stats.summary().contains(",healthRowsRefused=2"))
    }

    @Test fun `malformed persisted keys and row values leave valid rows and generation intact`() = runTest {
        for (badRow in listOf("\"a|b|c|d\":{}", "\"${key.copy(platformAppVersion = "7.2")}\":{\"unknown\":\"bad\"}")) {
            memory.memory.value = emptyPreferences().toMutablePreferences().apply {
                this[stringPreferencesKey("ledger_json")] =
                    """{"generation":5,"rows":{$badRow,"$key":{"unknown":1,"revision":1}}}"""
            }
            val corruptBefore = stats.healthCorrupt.get()
            assertEquals(HealthLedger(generation = 5).record(key, null), store.load())
            assertEquals(corruptBefore + 1, stats.healthCorrupt.get())
        }
    }

    @Test fun `missing and corrupt ledger load empty and only corruption is counted`() = runTest {
        assertEquals(HealthLedger(), store.load())
        assertEquals(0L, stats.healthCorrupt.get())
        memory.memory.value = emptyPreferences().toMutablePreferences().apply {
            this[stringPreferencesKey("ledger_json")] = "{broken"
        }
        assertEquals(HealthLedger(), store.load())
        assertEquals(1L, stats.healthCorrupt.get())
        store.save(HealthLedger(generation = 3).record(key, null))
        store.clear()
        assertEquals(HealthLedger(generation = 4), store.load())
    }
}
