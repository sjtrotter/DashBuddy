package cloud.trotter.dashbuddy.pipeline.accessibility.screen

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import java.util.Locale

//import cloud.trotter.dashbuddy.log.Logger as Log

/**
 * Represents distinct UI screens identified within the Dasher application.
 * Each enum constant holds its own criteria (signature) for matching.
 * The `matches` method is used by the ScreenRecognizer to determine the current screen.
 *
 * NOTE: Refactoring to make this enum simple, identifying the screen and flags and
 * lifting the matching logic out into separate [ScreenMatcher]s.
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
    EARNINGS_VIEW(
        requiredTexts = listOf("earnings", "this week"),
        someOfTheseTexts = listOf("past weeks", "balance", "crimson"),
    ),
    RATINGS_VIEW(
        requiredTexts = listOf("ratings", "acceptance rate", "completion rate", "overall"),
    ),
    SAFETY_VIEW(
        requiredTexts = listOf(
            "safedash",
            "safety tools",
            "report safety issue",
            "share your location"
        ),
    ),
    CHAT_VIEW(
        requiredTexts = listOf("dasher", "messages"),
        forbiddenTexts = listOf("folder"),
    ),
    NOTIFICATIONS_VIEW(
        requiredTexts = listOf("notifications"),
        forbiddenTexts = listOf("battery"),
    ),
    PROMOS_VIEW(
        requiredTexts = listOf("navigate up", "promotions"),
    ),
    HELP_VIEW(
        requiredTexts = listOf(
            "doordash dasher",
            "close webview",
            "safety resources",
            "additional resources",
            "chat with support"
        ),
    ),
    // --- Starting or Ending a Dash Workflow ---
    /** This screen after Dash is clicked, when Dash Anytime is enabled. */
    SET_DASH_END_TIME,

    /** The screen at the end of a dash. */
    DASH_SUMMARY_SCREEN,

    // --- Actively Dashing States ---
    ON_DASH_MAP_WAITING_FOR_OFFER,
    ON_DASH_ALONG_THE_WAY(
        requiredTexts = listOf(
            "we'll look for orders along the way",
            "navigate",
            "to zone",
            "spot saved until"
        ),
    ),
    TIMELINE_VIEW(
        requiredTexts = listOf(
            "current dash",
            "pause orders",
            "end now",
            "dash ends at",
            "add time"
        ),
    ),

    /** The paused dash screen. */
    DASH_PAUSED,

    // --- Navigation Views ---
    NAVIGATION_VIEW(
        requiredTexts = listOf("min", "exit"),
        someOfTheseTexts = listOf("mi", "ft"),
        forbiddenTexts = listOf("accept", "decline"),
    ),

    /** Navigating, but to a pickup location. */
    NAVIGATION_VIEW_TO_PICK_UP,

    /** Navigating, but to a dropoff location. */
    NAVIGATION_VIEW_TO_DROP_OFF,

    // --- Offer Handling ---
    OFFER_POPUP,

    OFFER_POPUP_CONFIRM_DECLINE(
        requiredTexts = listOf("Are you sure you want to decline", "acceptance rate"),
    ),

    // --- Active Delivery - Pickup Phase ---

    /** Prior to arrival at pickup location. */
    PICKUP_DETAILS_PRE_ARRIVAL,

    PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI(
        requiredTexts = listOf(
            "confirm at store",
            "orders",
            "you have",
            "orders to pick up at",
            "pick up each one to continue"
        ),
    ),

    /** After arrival at pickup location. */
    PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE,

    PICKUP_DETAILS_VERIFY_PICKUP(
        requiredTexts = listOf("verify order", "order for", "confirm pickup", "can’t verify order"),
        forbiddenTexts = listOf("pick up by"),
    ),
    PICKUP_DETAILS_POST_ARRIVAL_PICKUP_MULTI(
        requiredTexts = listOf(
            "pick up",
            "orders",
            "you have",
            "orders to pick up at",
            "pick up each one to continue"
        ),
        forbiddenTexts = listOf("confirm at store", "customer"),
    ),

    /** Arrival at store for shopping order. */
    PICKUP_DETAILS_POST_ARRIVAL_SHOP,

    PICKUP_DETAILS_PICKED_UP(
        requiredTexts = listOf("Confirm pickup", "Loading…"),
    ),

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
     * If false, this screen is likely refactored to a standalone matcher or is UNKNOWN,
     * so the LegacyEnumMatcher should ignore it.
     */
    val hasMatchingCriteria: Boolean
        get() = requiredTexts.isNotEmpty() ||
                someOfTheseTexts.isNotEmpty() ||
                forbiddenTexts.isNotEmpty() ||
                minTextCount > 0 ||
                maxTextCount < Int.MAX_VALUE ||
                customMatcher != null
}