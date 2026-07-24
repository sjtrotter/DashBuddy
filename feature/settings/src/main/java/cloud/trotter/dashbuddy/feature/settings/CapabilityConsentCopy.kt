package cloud.trotter.dashbuddy.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.dashbuddy.feature.settings.R
import cloud.trotter.dashbuddy.domain.action.RuleAction

/**
 * App-owned disclosure copy for one automation [RuleAction], phrased in the
 * platform app's name. SSOT shared by the settings consent record
 * ([CapabilityConsentScreen]) and the consent PROMPT (#843) so both surfaces
 * describe the same tap identically. Copy is always resolved from the app-owned
 * [RuleAction] vocabulary — never from rule-supplied text (see
 * `docs/design/rule-capability-consent.md`).
 */
data class CapabilityCopy(val title: String, val description: String)

@Composable
fun capabilityCopy(action: RuleAction, platformName: String): CapabilityCopy = when (action) {
    RuleAction.ACCEPT_OFFER -> CapabilityCopy(
        stringResource(R.string.consent_cap_accept_title),
        stringResource(R.string.consent_cap_accept_desc, platformName),
    )
    RuleAction.DECLINE_OFFER -> CapabilityCopy(
        stringResource(R.string.consent_cap_decline_title),
        stringResource(R.string.consent_cap_decline_desc, platformName),
    )
    RuleAction.CONFIRM_DECLINE -> CapabilityCopy(
        stringResource(R.string.consent_cap_confirm_decline_title),
        stringResource(R.string.consent_cap_confirm_decline_desc, platformName),
    )
    RuleAction.EXPAND_EARNINGS -> CapabilityCopy(
        stringResource(R.string.consent_cap_expand_title),
        stringResource(R.string.consent_cap_expand_desc, platformName),
    )
}
