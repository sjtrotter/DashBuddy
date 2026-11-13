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

    /**
     * App start: schedules the stored time (drawing one if none is stored), and again on every Wi-Fi-only change. A
     * change also cancels a waiting manual run, which would keep the network constraint of the old choice.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var previous: Boolean? = null
            settings.censusChoices.map { it.wifiOnly }.distinctUntilChanged().collect { wifiOnly ->
                guarded {
                    if (previous != null) scheduler.cancelManual()
                    previous = wifiOnly
                    scheduler.scheduleDaily(settings.censusNextRunAtMillis.first() ?: draw(), wifiOnly)
                }
            }
        }
    }

    /**
     * The start of a daily run: draws the next day's time, and says whether this run may send. A run that starts more
     * than [LATE_LIMIT_MILLIS] after its drawn time waited for a network or battery constraint, and could then send
     * when the dasher reaches home Wi-Fi after a shift, so it sends nothing. A failure to read the time is late too.
     */
    suspend fun beginDailyRun(): Boolean {
        val onTime = try {
            val target = settings.censusNextRunAtMillis.first()
            target == null || clock.millis() - target <= LATE_LIMIT_MILLIS
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        advance()
        return onTime
    }

    /** Draws the next run, a new random time in the next UTC day. */
    suspend fun advance() = guarded {
        scheduler.scheduleDaily(draw(), settings.censusChoices.first().wifiOnly)
    }

    private suspend fun draw(): Long =
        CensusUploadWindow.next(clock.millis(), random).also { settings.setCensusNextRunAtMillis(it) }

    companion object {
        /**
         * How late a daily run may start and still send. WorkManager starts a due run some minutes late on its own;
         * an hour covers that, while a run held back longer is one that waited for Wi-Fi or a charge.
         */
        const val LATE_LIMIT_MILLIS: Long = 3_600_000L
    }

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
