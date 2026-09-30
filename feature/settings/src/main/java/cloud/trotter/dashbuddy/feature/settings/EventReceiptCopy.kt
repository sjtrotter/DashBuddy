package cloud.trotter.dashbuddy.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * #1151 — the ONE resolution of the wide-event-receipt disclosure (the Play prominent-disclosure
 * shape #1138 M3 reuses): the Dashboard prompt, the Settings switch's helper text and the debug
 * block all render this, and the screen it names is the one `event_receipt_settings_path` string.
 */
@Composable
fun eventReceiptDisclosure(): String =
    stringResource(R.string.event_receipt_body, eventReceiptSettingsPath())

/** Where the switch lives: Settings → Data & Privacy → Automation & Consent. */
@Composable
fun eventReceiptSettingsPath(): String = stringResource(R.string.event_receipt_settings_path)
