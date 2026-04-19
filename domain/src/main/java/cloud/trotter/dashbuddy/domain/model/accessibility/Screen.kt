package cloud.trotter.dashbuddy.domain.model.accessibility

import java.util.Locale

/**
 * Represents distinct UI screens identified within the Dasher application.
 * Each enum constant holds its own criteria (signature) for matching.
 * The `matches` method is used by the ScreenRecognizer to determine the current screen.
 *
 * NOTE: Refactoring to make this enum simple, identifying the screen and flags and
 * lifting the matching logic out into separate [cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher]s.
 */
enum class Screen(
    // Signature properties directly in the enum constructor
    val requiredTexts: List<String> = emptyList(),
    val someOfTheseTexts: List<String> = emptyList(), // At least one text in this list must match
    val forbiddenTexts: List<String> = emptyList(),
    val minTextCount: Int = 0, // Min number of texts expected on the screen (from root)
    val maxTextCount: Int = Int.MAX_VALUE, // Max number of texts
    // Custom matcher lambda for more complex logic beyond simple text checks for this screen
    val customMatcher: ((screenTexts: List<String>) -> Boolean)? = null,
) {
    /** Default screen, matches nothing specifically or used as a fallback. */
    UNKNOWN,

    // Sensitive Screen.
    SENSITIVE,

    // --- Startup / Login / Offline States ---
    APP_STARTING_OR_LOADING,
    MAIN_MAP_IDLE,
    MAIN_MAP_ON_DASH,
    SCHEDULE_VIEW,
    EARNINGS_VIEW,
    RATINGS_VIEW,
    SAFETY_VIEW,
    CHAT_VIEW,
    NOTIFICATIONS_VIEW,
    PROMOS_VIEW,
    HELP_VIEW,
    // --- Starting or Ending a Dash Workflow ---
    /** This screen after Dash is clicked, when Dash Anytime is enabled. */
    SET_DASH_END_TIME,

    /** The screen at the end of a dash. */
    DASH_SUMMARY_SCREEN,

    // --- Actively Dashing States ---
    ON_DASH_MAP_WAITING_FOR_OFFER,
    ON_DASH_ALONG_THE_WAY,
    TIMELINE_VIEW,

    /** The paused dash screen. */
    DASH_PAUSED,

    // --- Navigation Views ---
    NAVIGATION_VIEW,

    /** Navigating, but to a pickup location. */
    NAVIGATION_VIEW_TO_PICK_UP,

    /** Navigating, but to a dropoff location. */
    NAVIGATION_VIEW_TO_DROP_OFF,

    // --- Offer Handling ---
    OFFER_POPUP,

    OFFER_POPUP_CONFIRM_DECLINE,

    // --- Active Delivery - Pickup Phase ---

    /** Prior to arrival at pickup location. */
    PICKUP_DETAILS_PRE_ARRIVAL,

    PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI,

    /** After arrival at pickup location. */
    PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE,

    PICKUP_DETAILS_VERIFY_PICKUP,
    PICKUP_DETAILS_POST_ARRIVAL_PICKUP_MULTI,

    /** Arrival at store for shopping order. */
    PICKUP_DETAILS_POST_ARRIVAL_SHOP,

    PICKUP_DETAILS_PICKED_UP,

    // --- Active Delivery - Dropoff Phase ---

    /** The dropoff details screen before arrival at dropoff location. */
    DROPOFF_DETAILS_PRE_ARRIVAL,


    // --- Post-Delivery ---

    /** Initial state with breakdown data hidden. */
    DELIVERY_SUMMARY_COLLAPSED,

    /** 2. Final State (Data visible -> Parse this!) */
    DELIVERY_SUMMARY_EXPANDED,
    ;

    /**
     * Checks if the provided UiNode matches this screen's signature.
     * @param node The StateContext containing texts and other event data.
     * @return True if the context matches this screen's criteria, false otherwise.
     */
    fun matches(node: UiNode): Boolean {
        // UNKNOWN screen matches nothing by definition, it's a fallback.
        if (this == UNKNOWN) return false

        val texts = node.allText

        val textsToSearch =
            texts.joinToString(separator = " | ").lowercase(Locale.getDefault())

        if (texts.size < this.minTextCount || texts.size > this.maxTextCount) {
            return false
        }

        for (text in this.requiredTexts) {
            if (!textsToSearch.contains(text.lowercase(Locale.getDefault()))) return false
        }

        if (this.someOfTheseTexts.isNotEmpty() && this.someOfTheseTexts.none { groupText ->
                textsToSearch.contains(
                    groupText.lowercase(Locale.getDefault())
                )
            }) {
            return false
        }

        for (text in this.forbiddenTexts) {
            if (textsToSearch.contains(text.lowercase(Locale.getDefault()))) return false
        }

        // Execute custom matcher if provided, and not already returned
        this.customMatcher?.let {
            if (!it(texts)) return false
        }

        return true
    }

    /**
     * Returns true if this screen has specific matching criteria defined in the Enum.
     * All screens have been migrated to standalone [cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher]
     * implementations, so this always returns false. Kept for reference during future enum cleanup.
     */
    val hasMatchingCriteria: Boolean
        get() = requiredTexts.isNotEmpty() ||
                someOfTheseTexts.isNotEmpty() ||
                forbiddenTexts.isNotEmpty() ||
                minTextCount > 0 ||
                maxTextCount < Int.MAX_VALUE ||
                customMatcher != null
}