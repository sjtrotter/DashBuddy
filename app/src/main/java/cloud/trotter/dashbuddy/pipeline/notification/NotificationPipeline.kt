package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.core.data.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.pipeline.notification.input.NotificationSource
import cloud.trotter.dashbuddy.pipeline.notification.mapper.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification sub-pipe: maps StatusBarNotification → classified observation.
 */
@Singleton
class NotificationPipeline @Inject constructor(
    private val source: NotificationSource,
    private val filter: NotificationFilter,
    private val classifier: NotificationClassifier,
    private val captureBus: CaptureBus,
) {
    companion object {
        const val PIPELINE_ID = "notification"
    }

    fun output(): Flow<Observation.Notification> = source.events
        .mapNotNull { sbn -> sbn.toDomain() }
        .filter { raw -> filter.isRelevant(raw) }
        .map { raw ->
            val obs = classifier.classify(raw).copy(timestamp = raw.postTime)
            captureBus.offer(PIPELINE_ID, raw, obs.target)
            obs
        }
}
