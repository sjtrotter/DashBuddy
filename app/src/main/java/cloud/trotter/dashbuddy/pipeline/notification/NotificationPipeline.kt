package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.pipeline.notification.input.NotificationSource
import cloud.trotter.dashbuddy.pipeline.notification.mapper.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

class NotificationPipeline @Inject constructor(
    private val source: NotificationSource,
    private val filter: NotificationFilter,
    private val classifier: NotificationClassifier,
    private val factory: NotificationFactory,
) {
    fun output(): Flow<StateEvent> = source.events
        .mapNotNull { sbn -> sbn.toDomain() }          // StatusBarNotification → RawNotificationData
        .filter { raw -> filter.isRelevant(raw) }       // package guard
        .map { raw -> raw to classifier.classify(raw) } // classify: RawNotificationData → NotificationInfo
        .map { (raw, info) -> factory.create(raw, info) } // produce NotificationEvent
}
