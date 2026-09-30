package cloud.trotter.dashbuddy.core.pipeline.accessibility

import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A fixed enabled-package set for the #1148 window-resolution tests. */
internal class FakePlatformPreferences(enabled: Set<String>) : PlatformPreferences {
    override val enabledPlatforms: StateFlow<Set<Platform>> =
        MutableStateFlow(enabled.map { Platform.fromPackage(it) }.toSet())
    override val enabledPackages: StateFlow<Set<String>> = MutableStateFlow(enabled)
    override val graceConfig: StateFlow<Map<Platform, GraceConfig>> = MutableStateFlow(emptyMap())
}
