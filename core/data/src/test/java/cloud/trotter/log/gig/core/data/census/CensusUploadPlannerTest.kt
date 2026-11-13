package cloud.trotter.log.gig.core.data.census

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import cloud.trotter.log.gig.domain.census.CensusUploadWindow.DAY_MILLIS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

/** #1620 (ADR-0011 §21): one census run a day at a time drawn on the phone, drawn again each day. */
@OptIn(ExperimentalCoroutinesApi::class)
class CensusUploadPlannerTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class Scheduler : CensusUploadScheduler {
        val daily = mutableListOf<Pair<Long, Boolean>>()
        var now = 0
        override fun enqueueNow(wifiOnly: Boolean, replaceQueued: Boolean) { now++ }
        override fun scheduleDaily(runAtMillis: Long, wifiOnly: Boolean) { daily += runAtMillis to wifiOnly }
        var cancelled = 0
        override fun cancelManual() { cancelled++ }
    }

    /** WorkManager refuses every schedule update. */
    private class FailingScheduler : CensusUploadScheduler {
        override fun enqueueNow(wifiOnly: Boolean, replaceQueued: Boolean) = Unit
        override fun scheduleDaily(runAtMillis: Long, wifiOnly: Boolean) = throw IllegalStateException("refused")
        override fun cancelManual() = Unit
    }

    private val settings = DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), false, Dispatchers.Unconfined, "test")
    private val scheduler = Scheduler()
    /** 2026-11-13T15:20Z, mid-shift time of day. */
    private val now = Instant.parse("2026-11-13T15:20:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val dayStart = Instant.parse("2026-11-14T00:00:00Z").toEpochMilli()

    @Test fun `app start draws a random time in the next UTC day, Wi-Fi only by default, and keeps it`() = runTest {
        val planner = CensusUploadPlanner(settings, scheduler, clock, Random(1))
        planner.start(backgroundScope); runCurrent()
        val (at, wifiOnly) = scheduler.daily.single()
        assertTrue("in the next UTC day", at in dayStart until dayStart + DAY_MILLIS)
        assertTrue("Wi-Fi only is the default", wifiOnly)
        assertEquals(at, settings.censusNextRunAtMillis.first())

        // A later app start (another process) keeps the stored time: opening the app never moves the upload.
        CensusUploadPlanner(settings, scheduler, clock, Random(99)).start(backgroundScope); runCurrent()
        assertEquals(at, scheduler.daily.last().first)
    }

    @Test fun `turning Wi-Fi only off keeps the time and lets the run use any network`() = runTest {
        val planner = CensusUploadPlanner(settings, scheduler, clock, Random(1))
        planner.start(backgroundScope); runCurrent()
        assertEquals("app start leaves a manual run alone", 0, scheduler.cancelled)
        settings.setCensusWifiOnly(false); runCurrent()
        assertEquals("a waiting manual run under the old choice is cancelled", 1, scheduler.cancelled)
        assertEquals(2, scheduler.daily.size)
        assertEquals(scheduler.daily[0].first, scheduler.daily[1].first)
        assertEquals(false, scheduler.daily[1].second)
    }

    @Test fun `each daily run draws a new time in the following day`() = runTest {
        val random = Random(7)
        val planner = CensusUploadPlanner(settings, scheduler, clock, random)
        planner.advance()
        val first = scheduler.daily.single().first
        assertTrue(first in dayStart until dayStart + DAY_MILLIS)

        val nextDay = CensusUploadPlanner(settings, scheduler, Clock.fixed(Instant.ofEpochMilli(first), ZoneOffset.UTC), random)
        nextDay.advance()
        val second = scheduler.daily.last().first
        assertTrue("the day after", second in dayStart + DAY_MILLIS until dayStart + 2 * DAY_MILLIS)
        assertNotEquals("redrawn, not the same time of day", first % DAY_MILLIS, second % DAY_MILLIS)
        assertEquals(second, settings.censusNextRunAtMillis.first())
        assertEquals("a daily run never asks for a manual run", 0, scheduler.now)
    }

    @Test fun `a daily run more than an hour after its drawn time may not send, and both redraw`() = runTest {
        val target = now.toEpochMilli()
        settings.setCensusNextRunAtMillis(target)
        val limit = CensusUploadPlanner.LATE_LIMIT_MILLIS
        fun at(millis: Long) = CensusUploadPlanner(settings, scheduler, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC), Random(2))

        assertTrue("on time", at(target + limit).beginDailyRun())
        assertNotEquals("redrawn", target, settings.censusNextRunAtMillis.first())

        settings.setCensusNextRunAtMillis(target)
        assertEquals("waited past the limit", false, at(target + limit + 1).beginDailyRun())
        val next = settings.censusNextRunAtMillis.first()!!
        assertTrue("redrawn into the next UTC day", next in dayStart until dayStart + DAY_MILLIS)
        assertEquals(2, scheduler.daily.size)
    }

    private fun plannerAt(millis: Long, scheduler: CensusUploadScheduler = this.scheduler) =
        CensusUploadPlanner(settings, scheduler, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC), Random(3))

    @Test fun `a missing target sends nothing and draws one`() = runTest {
        assertEquals(null, settings.censusNextRunAtMillis.first())
        assertEquals(false, plannerAt(now.toEpochMilli()).beginDailyRun())
        assertTrue(settings.censusNextRunAtMillis.first()!! in dayStart until dayStart + DAY_MILLIS)
    }

    @Test fun `a run before its target is stale work and sends nothing`() = runTest {
        val target = now.toEpochMilli()
        settings.setCensusNextRunAtMillis(target)
        assertEquals(false, plannerAt(target - 1).beginDailyRun())
        assertEquals("redrawn and rescheduled", 1, scheduler.daily.size)
        settings.setCensusNextRunAtMillis(target)
        assertTrue("at the target itself", plannerAt(target).beginDailyRun())
    }

    @Test fun `a run whose next day cannot be scheduled sends nothing, and neither does any later work`() = runTest {
        val target = now.toEpochMilli()
        settings.setCensusNextRunAtMillis(target)
        assertEquals("on time, but the next day failed to schedule", false, plannerAt(target + 60_000, FailingScheduler()).beginDailyRun())
        assertEquals("the target is consumed, and no unscheduled one is stored", null, settings.censusNextRunAtMillis.first())
        // The periodic work the failed update left in place fires again: a retry, a day later, or early.
        assertEquals(false, plannerAt(target + 90_000, FailingScheduler()).beginDailyRun())
        assertEquals(false, plannerAt(target + DAY_MILLIS, FailingScheduler()).beginDailyRun())
        assertEquals(false, plannerAt(target - 3_600_000L, FailingScheduler()).beginDailyRun())
    }

    @Test fun `a target admits one run only, so a retry inside the hour sends nothing`() = runTest {
        val target = now.toEpochMilli()
        settings.setCensusNextRunAtMillis(target)
        assertTrue("admitted at the target", plannerAt(target).beginDailyRun())
        // The upload fails and WorkManager retries 30 s later, well inside the target's hour.
        assertEquals("the retry sends nothing", false, plannerAt(target + 30_000).beginDailyRun())
    }

    @Test fun `app start stores no target it failed to schedule`() = runTest {
        plannerAt(now.toEpochMilli(), FailingScheduler()).start(backgroundScope); runCurrent()
        assertEquals(null, settings.censusNextRunAtMillis.first())
    }
}
