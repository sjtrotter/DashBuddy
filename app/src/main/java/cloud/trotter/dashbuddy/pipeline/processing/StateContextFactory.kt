package cloud.trotter.dashbuddy.pipeline.processing

import cloud.trotter.dashbuddy.data.location.OdometerRepository
import cloud.trotter.dashbuddy.data.log.Breadcrumbs
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
    private val breadcrumbs: Breadcrumbs,
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

        // 2. 🍞 Update Breadcrumbs (Before saving!)
        breadcrumbs.add(screenInfo.screen.name)

        // 3. Snapshot Logic
        val masterEnabled = settingsRepository.devSnapshotsEnabled.value
        val whitelist = settingsRepository.snapshotWhitelist.value

        if (masterEnabled && screenInfo.screen in whitelist) {
            // Pass the trail to the repository
            val trail = breadcrumbs.getTrail()
            snapshotRepository.saveSnapshot(uiNode, screenInfo.screen.name, trail)
        } else {
            Timber.d("⚠️ Snapshotting disabled for ${screenInfo.screen.name}")
        }

        // 3. Build Event
        return ScreenUpdateEvent(
            timestamp = System.currentTimeMillis(),
            screenInfo = screenInfo,
            odometer = odometerRepository.getCurrentMiles()
        )
    }
}