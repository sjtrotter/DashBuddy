package cloud.trotter.log.gig.core.data.census.contributor

import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.database.analytics.ContributorActivityDao
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.census.contributor.ContributorEligibility
import cloud.trotter.log.gig.domain.census.contributor.ShiftSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1625: reads [ContributorEligibility]'s inputs from what the phone already has: the census consent, the census ID's
 * enrolment time, and the shifts in the analytics read model. Returns the answer only; the counts never leave here.
 */
@Singleton
class ContributorEligibilityReader @Inject constructor(
    private val settings: DevSettingsRepository,
    private val credentials: CensusCredentialStore,
    private val activity: ContributorActivityDao,
) {
    /** Never throws: an input that cannot be read is not eligible (fail closed). */
    suspend fun eligible(nowMillis: Long, zone: ZoneId): Boolean = try {
        if (settings.censusConsent.first() != CensusConsent.ON) {
            false
        } else {
            val inputs = ContributorEligibility.Inputs(
                censusOn = true,
                enrolledAtMillis = credentials.enrolledAtMillis(nowMillis),
                shifts = activity.shiftsSince(ContributorEligibility.windowStartMillis(nowMillis, zone))
                    .map { ShiftSpan(it.startedAtMillis, it.lastAtMillis) },
                endedShifts = activity.endedShiftCount(),
            )
            ContributorEligibility.eligible(inputs, nowMillis, zone)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).w("census contributor eligibility unreadable cause=%s", e.javaClass.simpleName)
        false
    }

    private companion object {
        const val TAG = "Census"
    }
}
