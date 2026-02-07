package cloud.trotter.dashbuddy.pipeline.processing

import cloud.trotter.dashbuddy.data.location.OdometerRepository
import cloud.trotter.dashbuddy.data.log.Breadcrumbs
import cloud.trotter.dashbuddy.data.log.clicks.ClickLogRepository
import cloud.trotter.dashbuddy.data.log.snapshots.SnapshotRepository
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import cloud.trotter.dashbuddy.pipeline.model.NotificationInfo
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.click.ClickRecognizer
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenRecognizer
import cloud.trotter.dashbuddy.state.event.ClickEvent
import cloud.trotter.dashbuddy.state.event.NotificationEvent
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
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
    private val clickRecognizer: ClickRecognizer,
    private val clickLogRepository: ClickLogRepository
) {

    fun createFromNotification(info: NotificationInfo): NotificationEvent {
        return NotificationEvent(
            timestamp = info.timestamp,
            notification = info
        )
    }

    fun createFromClick(uiNode: UiNode): ClickEvent {
        val clickInfo = clickRecognizer.recognize(uiNode)

        val clickEvent = ClickEvent(
            System.currentTimeMillis(),
            clickInfo,
            uiNode
        )

        Timber.d("Click Event: $clickEvent is ${clickEvent.action}")
        clickLogRepository.log(clickEvent)
        return clickEvent
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