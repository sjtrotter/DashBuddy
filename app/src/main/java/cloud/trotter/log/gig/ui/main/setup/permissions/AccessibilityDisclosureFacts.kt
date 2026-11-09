package cloud.trotter.log.gig.ui.main.setup.permissions

import androidx.annotation.StringRes
import cloud.trotter.log.gig.R
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
 * - [screenEventsOffered]: whether this build offers Screen events.
 */
data class AccessibilityDisclosureFacts(
    val appNames: List<String>,
    val unreadOfferPopUpApps: List<String>,
    val screenEventsOffered: Boolean,
) {
    /**
     * The card's paragraphs, in order. The one owner of which paragraphs a build shows; the card renders them and
     * `AccessibilityDisclosureCompositionTest` reads them against each build's strings.
     */
    fun paragraphs(): List<DisclosureParagraph> = listOfNotNull(
        DisclosureParagraph(R.string.perm_a11y_use, appNames),
        unreadOfferPopUpApps.takeIf { it.isNotEmpty() }?.let { DisclosureParagraph(R.string.perm_a11y_overlay_limit, it) },
        DisclosureParagraph(R.string.perm_a11y_data),
        DisclosureParagraph(R.string.perm_a11y_screen_events).takeIf { screenEventsOffered },
        DisclosureParagraph(R.string.perm_a11y_screenshots),
        DisclosureParagraph(R.string.perm_a11y_where),
        DisclosureParagraph(R.string.perm_a11y_taps),
    )

    companion object {
        fun of(
            accessibilityTool: Boolean,
            screenEventsOffered: Boolean,
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
            )
        }
    }
}

/** One card paragraph: a string resource, and the app names its `%1$s` takes when it has one. */
data class DisclosureParagraph(@param:StringRes val res: Int, val names: List<String>? = null)

/**
 * "A", "A and B", "A, B and C": [names] as one plain list. [pairFormat] joins the last two (`%1$s and %2$s`, a
 * string resource, so a translation brings its own word); earlier names are joined with a comma first.
 */
internal fun joinAppNames(names: List<String>, pairFormat: String, locale: Locale): String = when (names.size) {
    0 -> ""
    1 -> names.single()
    else -> String.format(locale, pairFormat, names.dropLast(1).joinToString(", "), names.last())
}
