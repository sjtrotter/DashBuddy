package cloud.trotter.dashbuddy.pipeline.processing

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.location.OdometerRepository
import cloud.trotter.dashbuddy.data.log.LogRepository
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenRecognizer
import cloud.trotter.dashbuddy.state.event.NotificationEvent
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.model.NotificationInfo
import javax.inject.Inject
import javax.inject.Singleton

//import cloud.trotter.dashbuddy.log.Logger as Log

@Singleton
class StateContextFactory @Inject constructor(
    private val odometerRepository: OdometerRepository,
    private val screenRecognizer: ScreenRecognizer,
    private val logRepository: LogRepository
) {

    fun createFromNotification(info: NotificationInfo): NotificationEvent {
        return NotificationEvent(
            timestamp = info.timestamp,
            notification = info
        )
    }

    fun createFromAccessibility(
        uiNode: UiNode
    ): ScreenUpdateEvent {

        // 1. Run Recognition (The heavy part)
        val screenInfo = screenRecognizer.identify(uiNode)
        if (DashBuddyApplication.getDebugMode()) {
            logRepository.saveSnapshot(uiNode, screenInfo.screen.name)
        }

        // 2. Build Event
        return ScreenUpdateEvent(
            timestamp = System.currentTimeMillis(),
            screenInfo = screenInfo,
            odometer = odometerRepository.getCurrentMiles()
        )
    }
}