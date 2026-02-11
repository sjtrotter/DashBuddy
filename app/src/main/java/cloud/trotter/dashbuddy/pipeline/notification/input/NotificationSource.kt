package cloud.trotter.dashbuddy.pipeline.notification.input

import android.service.notification.StatusBarNotification
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationSource @Inject constructor() {
    private val _events = MutableSharedFlow<StatusBarNotification>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    fun emit(event: StatusBarNotification) {
        _events.tryEmit(event)
    }
}