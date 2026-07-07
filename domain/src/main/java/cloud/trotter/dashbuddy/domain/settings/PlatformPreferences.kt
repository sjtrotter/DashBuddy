package cloud.trotter.dashbuddy.domain.settings

import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-side contract for which gig platforms are enabled (#355). Inverted into
 * :domain so the sensor pipelines can filter without depending on :core:data;
 * the concrete repository (DataStore-backed, with installed-app detection and
 * the write side) lives in :core:data and is bound via Hilt.
 *
 * Exposed as [StateFlow] (#356): the repository materializes the preference
 * ONCE; every consumer reads `.value` at its gate instead of holding a private
 * cache that can silently freeze.
 */
interface PlatformPreferences {

    /** Enabled platforms. Defaults to the installed ones when nothing is saved. */
    val enabledPlatforms: StateFlow<Set<Platform>>

    /** Package names of the enabled platforms (listener-level event filtering). */
    val enabledPackages: StateFlow<Set<String>>

    /**
     * Per-platform grace / timing overrides (#438 item 6). Materialized ONCE
     * (#356); the timing consumers read `.value` synchronously at their step /
     * diff via [GraceConfigProvider]. A platform absent from the map uses
     * [GraceConfig.DEFAULT] (the code constants). No settings UI writes this yet
     * — the seam ships ahead of the editor, so today the map is empty and every
     * platform resolves to defaults (behavior identical to the former
     * compile-time constants).
     */
    val graceConfig: StateFlow<Map<Platform, GraceConfig>>
}
