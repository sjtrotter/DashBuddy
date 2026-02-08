package cloud.trotter.dashbuddy.pipeline.features.screen

import cloud.trotter.dashbuddy.data.location.OdometerRepository
import cloud.trotter.dashbuddy.data.log.Breadcrumbs
import cloud.trotter.dashbuddy.data.log.snapshots.SnapshotRepository
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenRecognizer
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import javax.inject.Inject

class ScreenFactory @Inject constructor(
    private val odometerRepository: OdometerRepository,
    private val screenRecognizer: ScreenRecognizer,
    private val settingsRepository: SettingsRepository,
    private val snapshotRepository: SnapshotRepository,
    private val breadcrumbs: Breadcrumbs
) {
    fun create(uiNode: UiNode): ScreenUpdateEvent {
        // 1. Identify
        val screenInfo = screenRecognizer.identify(uiNode)

        // 2. Breadcrumbs
        breadcrumbs.add(screenInfo.screen.name)

        // 3. Snapshot (Side Effect)
        val masterEnabled = settingsRepository.devSnapshotsEnabled.value
        val whitelist = settingsRepository.snapshotWhitelist.value

        if (masterEnabled && screenInfo.screen in whitelist) {
            val trail = breadcrumbs.getTrail()
            snapshotRepository.saveSnapshot(uiNode, screenInfo.screen.name, trail)
        }

        // 4. Build Event
        return ScreenUpdateEvent(
            timestamp = System.currentTimeMillis(),
            screenInfo = screenInfo,
            odometer = odometerRepository.getCurrentMiles()
        )
    }
}