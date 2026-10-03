package cloud.trotter.dashbuddy.core.data.census

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** Periodic / manual uploader runs and the identity reset (#1185) can overlap: serialize credential, spool and batch ownership. */
@Singleton
class CensusUploadLock @Inject constructor() {
    val mutex = Mutex()
}
