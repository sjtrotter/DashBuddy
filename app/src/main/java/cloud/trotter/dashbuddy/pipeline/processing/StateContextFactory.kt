package cloud.trotter.dashbuddy.pipeline.processing

import cloud.trotter.dashbuddy.data.location.OdometerRepository
import cloud.trotter.dashbuddy.data.log.snapshots.SnapshotRepository
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenRecognizer
import cloud.trotter.dashbuddy.state.event.NotificationEvent
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.model.NotificationInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateContextFactory @Inject constructor(
    private val odometerRepository: OdometerRepository,
    private val screenRecognizer: ScreenRecognizer,
    private val settingsRepository: SettingsRepository,
    private val snapshotRepository: SnapshotRepository,
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

        // 2. Evidence Locker (Debug State)
        // Access the cached value directly. No blocking, no statics.
        val devConfig = settingsRepository.devSnapshotsEnabled.value

        if (devConfig) {
            // Optional: finer grain control based on screen type
            // e.g. only save if (evidenceConfig.saveOffers && screenInfo.screen == Screen.OFFER)
            snapshotRepository.saveSnapshot(uiNode, screenInfo.screen.name)
        }

        // 3. Build Event
        return ScreenUpdateEvent(
            timestamp = System.currentTimeMillis(),
            screenInfo = screenInfo,
            odometer = odometerRepository.getCurrentMiles()
        )
    }
}