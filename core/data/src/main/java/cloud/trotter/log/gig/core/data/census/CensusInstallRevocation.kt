package cloud.trotter.log.gig.core.data.census

import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one owner of the phone's half of a census `401 revoked` (#1625): the census upload worker, the deletion request
 * (#1622) and the contributor grant routes all run these steps. The census switch goes off first, so a failed wipe can
 * never leave uploads on (capture sharing goes off in the same edit); then the envelopes are invalidated, the health
 * ledger reset (its rows belong to the revoked ID and must never post under a new one), and the credential wiped. It
 * takes no lock: every caller already holds [CensusUploadLock]. Each caller records its own outcome.
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
    }
}
