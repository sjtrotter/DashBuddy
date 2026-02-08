package cloud.trotter.dashbuddy.pipeline.features.notification

import cloud.trotter.dashbuddy.pipeline.inputs.NotificationSource
import cloud.trotter.dashbuddy.pipeline.model.NotificationInfo
import cloud.trotter.dashbuddy.state.event.StateEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

class NotificationPipeline @Inject constructor(
    private val source: NotificationSource,
    private val filter: NotificationFilter,
    private val factory: NotificationFactory
) {
    fun output(): Flow<StateEvent> = source.events
        .mapNotNull { sbn -> NotificationInfo.from(sbn) } // Parse
        .filter { info -> filter.isRelevant(info) }       // Filter Spam
        .map { info -> factory.create(info) }             // Produce Event
}