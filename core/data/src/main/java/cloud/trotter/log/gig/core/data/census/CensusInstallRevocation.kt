package cloud.trotter.log.gig.core.data.census

import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.census.CensusLastRun
import cloud.trotter.log.gig.domain.census.CensusRunOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1625: the phone's half of a `401 revoked` outside the upload worker (the contributor grant routes), the same steps
 * as the worker's revoke: the census switched off first (so a failed wipe cannot leave uploads on; capture sharing goes
 * off in the same edit), the envelopes invalidated, the health ledger reset, the credential wiped, and `revoked` recorded
 * as the last run so the census screen says why. The caller holds [CensusUploadLock].
 */
@Singleton
class CensusInstallRevocation @Inject constructor(
    private val preferences: DevSettingsRepository,
    private val envelopeSink: CensusEnvelopeSink,
    private val healthSink: PersistentHealthSink,
    private val credentials: CensusCredentialStore,
) {
    suspend fun apply() {
        preferences.setCensusUploadEnabled(false)
        envelopeSink.invalidate()
        healthSink.reset()
        credentials.wipe()
        preferences.setCensusLastRun(CensusLastRun(System.currentTimeMillis(), CensusRunOutcome.REVOKED))
    }
}
