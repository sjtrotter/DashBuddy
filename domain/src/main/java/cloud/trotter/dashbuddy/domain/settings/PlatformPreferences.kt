package cloud.trotter.dashbuddy.domain.settings

import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.Flow

/**
 * Read-side contract for which gig platforms are enabled (#355). Inverted into
 * :domain so the sensor pipelines can filter without depending on :core:data;
 * the concrete repository (DataStore-backed, with installed-app detection and
 * the write side) lives in :core:data and is bound via Hilt.
 */
interface PlatformPreferences {

    /** Enabled platforms. Defaults to the installed ones when nothing is saved. */
    val enabledPlatforms: Flow<Set<Platform>>

    /** Package names of the enabled platforms (listener-level event filtering). */
    val enabledPackages: Flow<Set<String>>
}
