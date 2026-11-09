package cloud.trotter.log.gig.core.data.pipeline

import cloud.trotter.log.gig.core.datastore.diagnostics.RecognitionNoticeDataSource
import cloud.trotter.log.gig.domain.pipeline.RecognitionNoticeStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `:domain` [RecognitionNoticeStore] over its DataStore file (#1560): the last recognition notice
 * time per platform wire. A pass-through, like `RuleCapabilityRepository` over its data source; failures
 * propagate to the owner (`RecognitionHealthMonitor`), which fails open.
 */
@Singleton
class RecognitionNoticeRepository @Inject constructor(
    private val dataSource: RecognitionNoticeDataSource,
) : RecognitionNoticeStore {
    override suspend fun load(): Map<String, Long> = dataSource.load()

    override suspend fun save(lastNoticeAt: Map<String, Long>) = dataSource.save(lastNoticeAt)
}
