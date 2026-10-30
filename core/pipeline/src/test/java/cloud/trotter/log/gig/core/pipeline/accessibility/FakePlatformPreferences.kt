package cloud.trotter.log.gig.core.pipeline.accessibility

import cloud.trotter.log.gig.domain.settings.GraceConfig
import cloud.trotter.log.gig.domain.settings.PlatformPreferences
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A fixed enabled-package set for the #1148 window-resolution tests. */
class FakePlatformPreferences(enabled: Set<String>) : PlatformPreferences {
    override val enabledPlatforms: StateFlow<Set<Platform>> =
        MutableStateFlow(enabled.map { Platform.fromPackage(it) }.toSet())
    override val enabledPackages: StateFlow<Set<String>> = MutableStateFlow(enabled)
    override val graceConfig: StateFlow<Map<Platform, GraceConfig>> = MutableStateFlow(emptyMap())
}
