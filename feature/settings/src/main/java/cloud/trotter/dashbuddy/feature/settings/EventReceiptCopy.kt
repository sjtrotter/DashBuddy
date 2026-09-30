package cloud.trotter.dashbuddy.feature.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent

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

/**
 * #1151 review MM2 — the Settings helper text: the disclosure, plus the Android 11 caveat on the one
 * SDK where the app cannot promise the runtime package filter clears
 * ([EventReceiptConsent.isWideReceiptReliable] is the rule's one owner).
 */
@Composable
fun eventReceiptSettingsHelper(sdkInt: Int = Build.VERSION.SDK_INT): String {
    // RR1: the switch row tells the same truth as the chain's step — disclosure + requirement note.
    val disclosure = eventReceiptDisclosure() + "\n\n" + eventReceiptRequirementNote()
    return if (EventReceiptConsent.isWideReceiptReliable(sdkInt)) {
        disclosure
    } else {
        disclosure + " " + stringResource(R.string.event_receipt_android11_caveat)
    }
}

/**
 * #1151 RR1 — whether declining is allowed in THIS build: "Optional…" in release, "Required in this
 * debug build…" in debug. One key, overridden in `src/debug/res` — never a BuildConfig branch, and
 * never a sentence shared by both variants that is true in only one of them.
 */
@Composable
fun eventReceiptRequirementNote(): String = stringResource(R.string.event_receipt_requirement_note)
