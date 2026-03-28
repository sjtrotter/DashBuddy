package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window

import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.log.Breadcrumbs
import cloud.trotter.dashbuddy.core.data.log.SnapshotRepository
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenClassifier
import cloud.trotter.dashbuddy.domain.model.state.ScreenUpdateEvent
import javax.inject.Inject

class ScreenFactory @Inject constructor(
    private val odometerRepository: OdometerRepository,
    private val screenClassifier: ScreenClassifier,
    private val devSettingsRepository: DevSettingsRepository,
    private val snapshotRepository: SnapshotRepository,
    private val breadcrumbs: Breadcrumbs
) {
    fun create(uiNode: UiNode): ScreenUpdateEvent {
        // 1. Identify
        val screenInfo = screenClassifier.identify(uiNode)

        // 2. Breadcrumbs
        breadcrumbs.add(screenInfo.screen.name)

        // 3. Snapshot (Side Effect)
        val masterEnabled = devSettingsRepository.devSnapshotsEnabled.value
        val whitelist = devSettingsRepository.snapshotWhitelist.value

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