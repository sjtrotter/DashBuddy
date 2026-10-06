package cloud.trotter.dashbuddy.core.data.strategy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.data.analytics.TimeConstantRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.core.datastore.strategy.StrategyDataSource
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.LearnedTimeConstants
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantPair
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantSeeds
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyRepositoryTimeConstantsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val platforms = Platform.entries.filter { it != Platform.Unknown }
    private val learned = LearnedTimeConstants(TimeConstantPair(2.0, 8.0), 10)

    @Test fun `persisted map resolves config then collector replaces idempotently even under overrides`() = runTest {
        val strategyStore = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("strategy.preferences_pb") })
        val prefsStore = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("prefs.preferences_pb") })
        val source = StrategyDataSource(strategyStore)
        val prefs = AppPreferencesRepository(AppPreferencesDataSource(prefsStore))
        source.replaceTimeConstants(mapOf(platforms[0] to learned))
        val snapshots = MutableSharedFlow<Map<Platform, LearnedTimeConstants>>(extraBufferCapacity = 8)
        val reader = mock<TimeConstantRepository> { on { learnedTimeConstants } doReturn snapshots }
        val repository = StrategyRepository(source, prefs, StandardTestDispatcher(testScheduler), reader)
        val first = repository.evaluationConfig.filterNotNull().first()
        assertEquals(2.25, first.forPlatform(platforms[0]).userEconomy.effectiveAvgMinutesPerMile, 0.0)
        assertEquals(7.5, first.forPlatform(platforms[0]).userEconomy.effectiveBasePickupMinutes, 0.0)
        assertEquals(TimeConstantSeeds.DEFAULT.minutesPerMile,
            first.forPlatform(platforms[1]).userEconomy.effectiveAvgMinutesPerMile, 0.0)
        prefs.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, 4.0)
        advanceUntilIdle()
        val replacement = mapOf(platforms[1] to learned.copy(sampleCount = 40))
        snapshots.emit(replacement)
        advanceUntilIdle()
        assertEquals(replacement, source.learnedTimeConstants.first { it == replacement })
        snapshots.emit(replacement)
        advanceUntilIdle()
        assertEquals(replacement, source.learnedTimeConstants.first { it == replacement })
        val cfg = repository.evaluationConfig.filterNotNull().first { it.timeConstants == replacement && it.userEconomy.isUserSet(EconomyField.AVG_MIN_PER_MILE) }
        assertEquals(4.0, cfg.forPlatform(platforms[0]).userEconomy.effectiveAvgMinutesPerMile, 0.0)
        assertEquals(4.0, cfg.forPlatform(platforms[1]).userEconomy.effectiveAvgMinutesPerMile, 0.0)
        assertEquals(7.8, cfg.forPlatform(platforms[1]).userEconomy.effectiveBasePickupMinutes, 1e-9)
        assertEquals(0, cfg.forPlatform(platforms[0]).userEconomy.timeConstantSampleCount)
        prefs.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, null)
        advanceUntilIdle()
        val automatic = repository.evaluationConfig.filterNotNull().first { !it.userEconomy.isUserSet(EconomyField.AVG_MIN_PER_MILE) }
        assertEquals(2.1, automatic.forPlatform(platforms[1]).userEconomy.effectiveAvgMinutesPerMile, 1e-9)
        snapshots.emit(emptyMap())
        advanceUntilIdle()
        assertTrue(source.learnedTimeConstants.first { it.isEmpty() }.isEmpty())
    }

    @Test fun `persistUserSetEconomy marks only the time field the user actually set`() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("independent.preferences_pb") })
        val source = AppPreferencesDataSource(ds)
        val repo = AppPreferencesRepository(source)
        repo.persistUserSetEconomy(UserEconomy(avgMinutesPerMile = 3.0,
            userSetFields = setOf(EconomyField.AVG_MIN_PER_MILE)))
        assertEquals(setOf(EconomyField.AVG_MIN_PER_MILE.name), source.userSetEconomyFields.first())
        assertNull(source.basePickupMin.first())
        repo.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, null)
        repo.persistUserSetEconomy(UserEconomy(basePickupMinutes = 0.0,
            userSetFields = setOf(EconomyField.BASE_PICKUP_MIN)))
        assertEquals(setOf(EconomyField.BASE_PICKUP_MIN.name), source.userSetEconomyFields.first())
        assertNull(source.avgMinPerMile.first())
    }

    @Test fun `collector retries transient reads but preserves cancellation`() = runTest {
        val source = StrategyDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("retry.preferences_pb") }))
        val prefs = AppPreferencesRepository(AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("retry-prefs.preferences_pb") })))
        var attempts = 0
        val values = mapOf(platforms[0] to learned)
        val transient = flow {
            attempts++
            if (attempts == 1) throw IOException("temporary read failure")
            emit(values)
            awaitCancellation()
        }
        val reader = mock<TimeConstantRepository> { on { learnedTimeConstants } doReturn transient }
        StrategyRepository(source, prefs, StandardTestDispatcher(testScheduler), reader)
        assertEquals(values, source.learnedTimeConstants.first { it == values })
        assertEquals(2, attempts)
        var canceledAttempts = 0
        val canceled = flow<Map<Platform, LearnedTimeConstants>> {
            canceledAttempts++
            throw CancellationException("stop collector")
        }
        val canceledReader = mock<TimeConstantRepository> { on { learnedTimeConstants } doReturn canceled }
        StrategyRepository(source, prefs, StandardTestDispatcher(testScheduler), canceledReader)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, canceledAttempts)
        assertEquals(values, source.learnedTimeConstants.first())
    }
}
