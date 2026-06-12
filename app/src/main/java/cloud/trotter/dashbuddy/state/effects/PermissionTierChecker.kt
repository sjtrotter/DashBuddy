package cloud.trotter.dashbuddy.state.effects

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.pipeline.PermissionTier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [PermissionTier] check (#417) — replaces the alpha always-true stub.
 * Each tier backs onto the OS state that actually carries it, so a denied
 * tier genuinely blocks the effect (dev principle 6: capability gates fail
 * closed):
 *
 * - [PermissionTier.ACCESSIBILITY] — the live accessibility-service handle.
 *   Taps and screenshots are dispatched THROUGH the service; if it is not
 *   connected (user disabled it, system killed it), nothing downstream could
 *   succeed anyway — deny up front instead of failing deep in a handler.
 * - [PermissionTier.LOCATION] — the `ACCESS_FINE_LOCATION` runtime grant,
 *   checked live (the user can revoke it in Settings at any moment).
 * - [PermissionTier.AUDIO] — structurally granted: Android has no runtime
 *   permission for TTS/audio output. The user-facing "may DashBuddy speak"
 *   consent is an app-level setting concern (#422 PR 3 / #428), not an OS
 *   tier.
 * - [PermissionTier.NONE] — no requirement by definition.
 */
@Singleton
class PermissionTierChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accessibilitySource: AccessibilitySource,
) {
    fun isGranted(tier: PermissionTier): Boolean = when (tier) {
        PermissionTier.NONE -> true
        PermissionTier.ACCESSIBILITY -> accessibilitySource.getService() != null
        PermissionTier.LOCATION -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        PermissionTier.AUDIO -> true
    }
}
