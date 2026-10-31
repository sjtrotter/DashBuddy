package cloud.trotter.log.gig.feature.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.gig.domain.settings.EventReceiptConsent

/**
 * #1151 — the wide-event-receipt disclosure, as its parts (#1457). ONE resolution, rendered by the
 * permission chain's first step (`ScreenEventsCard` in `:app`), the Automation & consent dialog and
 * its "What this does" section, and the debug block. Each host lays the parts out its own way; none
 * of them writes its own copy of the text.
 */
data class EventReceiptDisclosure(
    /** What allowing does: the signal from every app, what is read and saved, and screenshots. */
    val body: String,
    /**
     * The Android 11 caveat, present only on the one SDK where the app cannot promise the runtime
     * package filter clears ([EventReceiptConsent.isWideReceiptReliable] is the rule's one owner).
     * It qualifies [body], so hosts show it right after the body.
     */
    val android11Caveat: String?,
    /** #1151 RR1: "Optional…" in release, "Required in this test build…" in debug (one key, two source sets). */
    val requirementNote: String,
    /** Where the choice can be changed later: the Automation & consent screen. */
    val changeLater: String,
)

@Composable
fun eventReceiptDisclosureParts(sdkInt: Int = Build.VERSION.SDK_INT): EventReceiptDisclosure =
    EventReceiptDisclosure(
        body = stringResource(R.string.event_receipt_body),
        android11Caveat = if (EventReceiptConsent.isWideReceiptReliable(sdkInt)) {
            null
        } else {
            stringResource(R.string.event_receipt_android11_caveat)
        },
        requirementNote = stringResource(R.string.event_receipt_requirement_note),
        changeLater = stringResource(R.string.event_receipt_change_later_format, eventReceiptSettingsPath()),
    )

/** Where the switch lives: Settings > Data & privacy > Automation & consent. */
@Composable
fun eventReceiptSettingsPath(): String = stringResource(R.string.event_receipt_settings_path)
