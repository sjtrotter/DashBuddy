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
 * and drawn again at the start of each daily run; a run sends only within an hour after it. Wi-Fi only (default on)
 * is read from the census choices and re-applied when it changes, keeping the stored time.
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
                    val stored = settings.censusNextRunAtMillis.first()
                    if (stored != null) scheduler.scheduleDaily(stored, wifiOnly) else schedule(draw(), wifiOnly)
                }
            }
        }
    }

    /**
     * The start of a daily run: consumes the stored target, draws the next day's time, and says whether this run may
     * send. It may only when it starts between its target and [LATE_LIMIT_MILLIS] after it. Later, it waited for a
     * network or battery constraint; earlier, or with no target, it is work left from a schedule that failed to update.
     *
     * Fail closed: the target is removed from the store before anything is sent, so a WorkManager retry of this run,
     * or any work left in place, finds no target and sends nothing; and if the target cannot be read or removed, or the
     * next day's time cannot be scheduled, this run sends nothing either.
     */
    suspend fun beginDailyRun(): Boolean {
        val onTime = try {
            val target = settings.censusNextRunAtMillis.first()
            if (target != null) settings.clearCensusNextRunAtMillis()
            target != null && clock.millis() - target in 0..LATE_LIMIT_MILLIS
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        val advanced = advance()
        return onTime && advanced
    }

    /** Draws the next run, a new random time in the next UTC day. False when it could not be scheduled. */
    suspend fun advance(): Boolean = guarded {
        schedule(draw(), settings.censusChoices.first().wifiOnly)
    }

    private fun draw(): Long = CensusUploadWindow.next(clock.millis(), random)

    /**
     * Stores [runAtMillis] only once the work is scheduled for it: if scheduling fails, the stored target stays the
     * old one, and work that the failed update left in place never matches a target it was not scheduled for.
     */
    private suspend fun schedule(runAtMillis: Long, wifiOnly: Boolean) {
        scheduler.scheduleDaily(runAtMillis, wifiOnly)
        settings.setCensusNextRunAtMillis(runAtMillis)
    }

    companion object {
        /**
         * How late a daily run may start and still send. WorkManager starts a due run some minutes late on its own;
         * an hour covers that, while a run held back longer is one that waited for Wi-Fi or a charge.
         */
        const val LATE_LIMIT_MILLIS: Long = 3_600_000L
    }

    /** A scheduling failure never throws to the caller; it returns false and the periodic work keeps its last time. */
    private suspend fun guarded(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Timber.tag("Census").w("census schedule failed")
        false
    }
}
