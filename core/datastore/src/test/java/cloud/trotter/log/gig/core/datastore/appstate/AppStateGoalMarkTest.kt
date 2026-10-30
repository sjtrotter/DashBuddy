package cloud.trotter.log.gig.core.datastore.appstate

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateGoalMarkTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun goalMark_overwritesSurvivesRecreationAndClearsWithAppState() = runTest {
        val file = File(temp.root, "app_state.preferences_pb")
        fun source(job: Job) = AppStateDataSource(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job), produceFile = { file }))
        val firstJob = Job()
        val first = source(firstJob)
        assertNull(first.goalMetMark.first())
        first.setGoalMetMark("2026-10-17|7900")
        first.setGoalMetMark("2026-10-18|8000")
        assertEquals("2026-10-18|8000", first.goalMetMark.first())
        firstJob.cancelAndJoin()
        val secondJob = Job()
        try {
            val reopened = source(secondJob)
            assertEquals("2026-10-18|8000", reopened.goalMetMark.first())
            reopened.clear()
            assertNull(reopened.goalMetMark.first())
        } finally {
            secondJob.cancelAndJoin()
        }
    }
}
