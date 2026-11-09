package cloud.trotter.log.gig.ui.main.setup.permissions

import cloud.trotter.log.gig.domain.state.Platform
import java.util.Locale

/**
 * #170 — what the screen reading card states that varies by build or by registry, so the card itself stays
 * stateless copy:
 * - [appNames]: the apps this build reads, the display names of [Platform.readable] (the bound platforms, plus the
 *   unbound ones in the debug build);
 * - [unreadOfferPopUpApps]: the apps whose offer pop-up this build can't read. Android 14+ shows an offer pop-up
 *   marked accessibility-data-sensitive only to a service that declares itself an accessibility tool, so a build
 *   that does not (the Play build) can't read the pop-up of a platform whose offer arrives that way
 *   ([Platform.offerOverlay]); empty in a build that declares the flag;
 * - [screenEventsOffered]: whether this build offers Screen events;
 * - [privacyUrl]: the privacy notice link (PRIVACY.md as of this build's commit).
 */
data class AccessibilityDisclosureFacts(
    val appNames: List<String>,
    val unreadOfferPopUpApps: List<String>,
    val screenEventsOffered: Boolean,
    val privacyUrl: String,
) {
    companion object {
        fun of(
            accessibilityTool: Boolean,
            screenEventsOffered: Boolean,
            privacyUrl: String,
            readsUnboundPlatforms: Boolean,
        ): AccessibilityDisclosureFacts {
            val readable = Platform.readable(readsUnboundPlatforms)
            return AccessibilityDisclosureFacts(
                appNames = readable.map { it.displayName },
                unreadOfferPopUpApps = if (accessibilityTool) {
                    emptyList()
                } else {
                    readable.filter { it.offerOverlay }.map { it.displayName }
                },
                screenEventsOffered = screenEventsOffered,
                privacyUrl = privacyUrl,
            )
        }
    }
}

/**
 * "A", "A and B", "A, B and C": [names] as one plain list. [pairFormat] joins the last two (`%1$s and %2$s`, a
 * string resource, so a translation brings its own word); earlier names are joined with a comma first.
 */
internal fun joinAppNames(names: List<String>, pairFormat: String, locale: Locale): String = when (names.size) {
    0 -> ""
    1 -> names.single()
    else -> String.format(locale, pairFormat, names.dropLast(1).joinToString(", "), names.last())
}
