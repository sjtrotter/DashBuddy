package cloud.trotter.dashbuddy.pipeline.processing

import cloud.trotter.dashbuddy.data.location.OdometerRepository
import cloud.trotter.dashbuddy.data.log.snapshots.SnapshotRepository
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenRecognizer
import cloud.trotter.dashbuddy.state.event.NotificationEvent
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.model.NotificationInfo
import timber.log.Timber
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
        // 1. Identify
        val screenInfo = screenRecognizer.identify(uiNode)

        // 2. Snapshot Logic
        val masterEnabled = settingsRepository.devSnapshotsEnabled.value
        val whitelist = settingsRepository.snapshotWhitelist.value

        // Logic: "If the system is ON, and this specific screen type is ALLOWED"
        if (masterEnabled && screenInfo.screen in whitelist) {
            snapshotRepository.saveSnapshot(uiNode, screenInfo.screen.name)
        } else {
            Timber.d("Skipping snapshot for ${screenInfo.screen.name}")
        }

        // 3. Build Event
        return ScreenUpdateEvent(
            timestamp = System.currentTimeMillis(),
            screenInfo = screenInfo,
            odometer = odometerRepository.getCurrentMiles()
        )
    }
}