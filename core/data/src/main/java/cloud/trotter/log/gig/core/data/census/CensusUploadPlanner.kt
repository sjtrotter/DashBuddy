package cloud.trotter.log.gig.core.data.census

import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import cloud.trotter.log.gig.domain.census.CensusUploadWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.security.SecureRandom
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * #1620 (ADR-0011 §21): owns the census's one daily run. The time is drawn on the phone ([CensusUploadWindow]), stored,
 * and drawn again at the start of each daily run, so it never follows a shift. Wi-Fi only (default on) is read from
 * the census choices and re-applied when it changes, keeping the stored time.
 */
@Singleton
class CensusUploadPlanner internal constructor(
    private val settings: DevSettingsRepository,
    private val scheduler: CensusUploadScheduler,
    private val clock: Clock,
    private val random: Random,
) {
    @Inject constructor(settings: DevSettingsRepository, scheduler: CensusUploadScheduler, clock: Clock) :
        this(settings, scheduler, clock, SecureRandom().asKotlinRandom())

    /** App start: schedules the stored time (drawing one if none is stored), and again on every Wi-Fi-only change. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.censusChoices.map { it.wifiOnly }.distinctUntilChanged().collect { wifiOnly ->
                guarded { scheduler.scheduleDaily(settings.censusNextRunAtMillis.first() ?: draw(), wifiOnly) }
            }
        }
    }

    /** The start of a daily run: the next run is a new random time in the next UTC day. */
    suspend fun advance() = guarded {
        scheduler.scheduleDaily(draw(), settings.censusChoices.first().wifiOnly)
    }

    private suspend fun draw(): Long =
        CensusUploadWindow.next(clock.millis(), random).also { settings.setCensusNextRunAtMillis(it) }

    /** A scheduling failure never reaches the caller: the periodic work keeps its last time. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Timber.tag("Census").w("census schedule failed")
        }
    }
}
