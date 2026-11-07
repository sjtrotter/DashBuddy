package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.log.gig.core.database.event.AppEventEntity
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.event.AppEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The records status: the projector's rebuilding flag (set at the wipe, cleared once the refold has
 * drained, also when it throws) and the repository's REBUILDING / UPDATING / UP_TO_DATE flow.
 */
@RunWith(RobolectricTestRunner::class)
class RecordsStatusRepositoryTest {

    private lateinit var db: GigLogDatabase
    private lateinit var repo: AppEventRepo

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), GigLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AppEventRepo(db, db.appEventDao(), db.effectsFiredDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertEvent(at: Long): Long = db.appEventDao().insert(
        AppEventEntity(aggregateId = null, eventType = AppEventType.DASH_START, eventPayload = "{}", occurredAt = at),
    )

    private suspend fun staleVersion() = db.analyticsDao().setWatermark(
        AnalyticsProjectionStateEntity(watermarkSequenceId = 0, projectorVersion = -1),
    )

    private fun statusOf(projector: AnalyticsProjector) = RecordsStatusRepository(
        projector,
        AnalyticsRepository(db.analyticsDao(), db.appEventDao(), db.hudShiftDao()),
        db.appEventDao(),
    ).status

    @Test
    fun `status follows the flag first, then the watermark against the latest id`() = runBlocking {
        val rebuilding = MutableStateFlow(false)
        val watermark = MutableStateFlow<Long?>(null)
        val latest = MutableStateFlow<Long?>(null)
        val status = RecordsStatusRepository(rebuilding, watermark, latest).status

        assertEquals(RecordsStatus.UP_TO_DATE, status.first())
        latest.value = 5
        assertEquals(RecordsStatus.UPDATING, status.first())
        watermark.value = 4
        assertEquals(RecordsStatus.UPDATING, status.first())
        rebuilding.value = true
        assertEquals(RecordsStatus.REBUILDING, status.first())
        rebuilding.value = false
        watermark.value = 5
        assertEquals(RecordsStatus.UP_TO_DATE, status.first())
    }

    @Test
    fun `the flag is set through the refold and cleared once it drains`() = runBlocking {
        insertEvent(1_000)
        staleVersion()
        val seen = mutableListOf<Boolean>()
        lateinit var projector: AnalyticsProjector
        // The drain reads the economy once at its start, which is inside the refold.
        val prefs = mock<AppPreferencesRepository> {
            on { userEconomy } doReturn flow {
                seen += projector.rebuilding.value
                emit(UserEconomy())
            }
        }
        projector = AnalyticsProjector(db, repo, db.appEventDao(), db.analyticsDao(), prefs)
        val status = statusOf(projector)

        projector.catchUp()

        assertEquals(listOf(true), seen)
        assertFalse(projector.rebuilding.value)
        assertEquals(RecordsStatus.UP_TO_DATE, status.first())
    }

    @Test
    fun `no version change never sets the flag`() = runBlocking {
        insertEvent(1_000)
        val seen = mutableListOf<Boolean>()
        lateinit var projector: AnalyticsProjector
        val prefs = mock<AppPreferencesRepository> {
            on { userEconomy } doReturn flow {
                seen += projector.rebuilding.value
                emit(UserEconomy())
            }
        }
        projector = AnalyticsProjector(db, repo, db.appEventDao(), db.analyticsDao(), prefs)
        projector.catchUp() // the first fold is the backfill, not a rebuild
        assertEquals(listOf(false), seen)
    }

    @Test
    fun `the flag clears when the refold throws`() = runBlocking {
        insertEvent(1_000)
        staleVersion()
        val failingRepo = mock<AppEventRepo> {
            on { getEventsAfter(any(), any()) } doThrow IllegalStateException("read failed")
        }
        val prefs = mock<AppPreferencesRepository> { on { userEconomy } doReturn flowOf(UserEconomy()) }
        val projector = AnalyticsProjector(db, failingRepo, db.appEventDao(), db.analyticsDao(), prefs)

        try {
            projector.catchUp()
            fail("the refold should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("read failed", e.message)
        }
        assertFalse(projector.rebuilding.value)
        // The wipe committed, so the records now run behind the log: UPDATING, not REBUILDING.
        val status = statusOf(projector)
        assertEquals(RecordsStatus.UPDATING, status.first())
        assertEquals(0L, db.analyticsDao().getWatermark()!!.watermarkSequenceId)
    }
}
