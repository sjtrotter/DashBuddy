package cloud.trotter.dashbuddy.services.accessibility.screen // Package for the Screen enum

import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.state.ActivityHint
import java.util.Locale
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.log.Logger as Log

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
    val isPickup: Boolean = false,
    val isDelivery: Boolean = false,
    val isNavigating: Boolean = false,
    val activityHint: ActivityHint = ActivityHint.NEUTRAL,
    val screenName: String = "",
    val requiredTexts: List<String> = emptyList(),
    val someOfTheseTexts: List<String> = emptyList(), // At least one text in this list must match
    val forbiddenTexts: List<String> = emptyList(),
    val minTextCount: Int = 0, // Min number of texts expected on the screen (from root)
    val maxTextCount: Int = Int.MAX_VALUE, // Max number of texts
    // Custom matcher lambda for more complex logic beyond simple text checks for this screen
    val customMatcher: ((screenTexts: List<String>, sourceTexts: List<String>, context: StateContext) -> Boolean)? = null,
    val nodeMatcher: ((rootNode: UiNode) -> Boolean)? = null,
) {
    /** Default screen, matches nothing specifically or used as a fallback. */
    UNKNOWN(screenName = "UNKNOWN"),

    // --- Startup / Login / Offline States ---

    /** The app startup screen. */
    APP_STARTING_OR_LOADING(screenName = "App Startup"),

    // took out login screen, no real reason to match it.

    /** The main idle map screen. */
    MAIN_MAP_IDLE(screenName = "Main Map Idle", activityHint = ActivityHint.INACTIVE),

    /** The scheduling screen. */
    SCHEDULE_VIEW(screenName = "Schedule"),

    EARNINGS_VIEW(
        screenName = "Earnings",
        requiredTexts = listOf("earnings", "this week"),
        someOfTheseTexts = listOf("past weeks", "balance", "cash out with dasherdirect"),
    ),
    RATINGS_VIEW(
        screenName = "Ratings",
        requiredTexts = listOf("ratings", "acceptance rate", "completion rate", "overall"),
    ),
    SAFETY_VIEW(
        screenName = "Safety",
        requiredTexts = listOf(
            "safedash",
            "safety tools",
            "report safety issue",
            "share your location"
        ),
    ),
    CHAT_VIEW(
        screenName = "Chat",
        requiredTexts = listOf("dasher", "messages"),
        forbiddenTexts = listOf("folder"),
    ),
    NOTIFICATIONS_VIEW(
        screenName = "Notifications",
        requiredTexts = listOf("notifications"),
        forbiddenTexts = listOf("battery"),
    ),
    PROMOS_VIEW(
        screenName = "Promos",
        requiredTexts = listOf("navigate up", "promotions"),
    ),
    HELP_VIEW(
        screenName = "Help",
        requiredTexts = listOf(
            "doordash dasher",
            "close webview",
            "safety resources",
            "additional resources",
            "chat with support"
        ),
    ),

    // Removed main menu; using the nodes, there is no way to discern if the menu is open
    // also, it's not really needed anyway.

    // --- Starting or Ending a Dash Workflow ---

    /** This screen after Dash is clicked, when Dash Anytime is enabled. */
    SET_DASH_END_TIME(screenName = "Set Dash End Time", activityHint = ActivityHint.INACTIVE),

    /** The screen at the end of a dash. */
    DASH_SUMMARY_SCREEN(screenName = "Dash Summary", activityHint = ActivityHint.INACTIVE),

    // --- Actively Dashing States ---
    ON_DASH_MAP_WAITING_FOR_OFFER(
        screenName = "Waiting for Offer",
        requiredTexts = listOf("looking for offers", "this dash"),
        forbiddenTexts = listOf(
            "dash now",
            "we'll look for orders along the way",
            "select end time",
            "accept"
        ),
        activityHint = ActivityHint.ACTIVE
    ),
    DASH_CONTROL(
        screenName = "Dash Control",
        requiredTexts = listOf(
            "return to dash",
            "you're dashing in",
            "end dash",
            "you're dashing now"
        ),
        activityHint = ActivityHint.ACTIVE
    ),
    ON_DASH_ALONG_THE_WAY(
        screenName = "Dash Along the Way",
        requiredTexts = listOf(
            "we'll look for orders along the way",
            "navigate",
            "to zone",
            "spot saved until"
        ),
        activityHint = ActivityHint.ACTIVE
    ),
    TIMELINE_VIEW(
        screenName = "Timeline",
        requiredTexts = listOf(
            "current dash",
            "pause orders",
            "end now",
            "dash ends at",
            "add time"
        ),
        activityHint = ActivityHint.ACTIVE
    ),

    /** The paused dash screen. */
    DASH_PAUSED(screenName = "Dash Paused", activityHint = ActivityHint.ACTIVE),

    // --- Navigation Views ---
    // Generic view. Match AFTER more specific views.
    NAVIGATION_VIEW(
        screenName = "Generic Navigation",
        requiredTexts = listOf("min", "exit"),
        someOfTheseTexts = listOf("mi", "ft"),
        forbiddenTexts = listOf("accept", "decline"),
        isNavigating = true,
    ),

    /** Navigating, but to a pickup location. */
    NAVIGATION_VIEW_TO_PICK_UP(
        screenName = "Navigation to Pick Up",
        activityHint = ActivityHint.ACTIVE,
        isPickup = true,
        isNavigating = true
    ),

    /** Navigating, but to a dropoff location. */
    NAVIGATION_VIEW_TO_DROP_OFF(
        screenName = "Navigation to Drop Off",
        activityHint = ActivityHint.ACTIVE,
        isDelivery = true,
        isNavigating = true
    ),

    // --- Offer Handling ---
    OFFER_POPUP(
        screenName = "Offer",
        requiredTexts = listOf("decline", "$", "deliver by"),
        someOfTheseTexts = listOf(
            "guaranteed (incl. tips)",
            "total will be higher",
            "accept",
            "add to route",
            "mi",
            "ft",
        ),
        minTextCount = 6,
        activityHint = ActivityHint.ACTIVE
    ),

    OFFER_POPUP_CONFIRM_DECLINE(
        screenName = "Offer Decline Confirmation",
        requiredTexts = listOf("Are you sure you want to decline", "acceptance rate"),
        activityHint = ActivityHint.ACTIVE
    ),

    // --- Active Delivery - Pickup Phase ---

    /** Prior to arrival at pickup location. */
    PICKUP_DETAILS_PRE_ARRIVAL(isPickup = true, activityHint = ActivityHint.ACTIVE),

    PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI(
        // this one is weird af. needs work. -- the nodes show that it might be an overlay type screen.
        requiredTexts = listOf(
            "confirm at store",
            "orders",
            "you have",
            "orders to pick up at",
            "pick up each one to continue"
        ),
        isPickup = true,
        activityHint = ActivityHint.ACTIVE,
//        forbiddenTexts = listOf("confirm at store")
    ),

    /** After arrival at pickup location. */
    PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE(isPickup = true, activityHint = ActivityHint.ACTIVE),

    PICKUP_DETAILS_VERIFY_PICKUP(
        requiredTexts = listOf("verify order", "order for", "confirm pickup", "can’t verify order"),
        forbiddenTexts = listOf("pick up by"),
        isPickup = true,
        activityHint = ActivityHint.ACTIVE
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
        isPickup = true,
        activityHint = ActivityHint.ACTIVE
    ),

    /** Arrival at store for shopping order. */
    PICKUP_DETAILS_POST_ARRIVAL_SHOP(isPickup = true, activityHint = ActivityHint.ACTIVE),

    PICKUP_DETAILS_PICKED_UP(
        requiredTexts = listOf("Confirm pickup", "Loading…"),
        isPickup = true,
        activityHint = ActivityHint.ACTIVE
    ),

    // --- Active Delivery - Dropoff Phase ---

    /** The dropoff details screen before arrival at dropoff location. */
    DROPOFF_DETAILS_PRE_ARRIVAL(isDelivery = true, activityHint = ActivityHint.ACTIVE),


    // --- Post-Delivery ---

    /** Initial state with breakdown data hidden. */
    DELIVERY_SUMMARY_COLLAPSED(
        screenName = "Delivery Summary (Collapsed)",
        activityHint = ActivityHint.ACTIVE
    ),

    /** 2. Final State (Data visible -> Parse this!) */
    DELIVERY_SUMMARY_EXPANDED(
        screenName = "Delivery Summary (Expanded)",
        activityHint = ActivityHint.ACTIVE
    ),
    ;

    /**
     * Checks if the provided StateContext matches this screen's signature.
     * @param context The StateContext containing texts and other event data.
     * @return True if the context matches this screen's criteria, false otherwise.
     */
    fun matches(context: StateContext): Boolean {
        // UNKNOWN screen matches nothing by definition, it's a fallback.
        if (this == UNKNOWN) return false

        // *** Prioritize the nodeMatcher if it exists
        this.nodeMatcher?.let { matcher ->
            context.rootUiNode?.let { rootNode ->
                Log.d("Screen", "Using nodeMatcher for $screenName")
                return matcher(rootNode)
            }
        }
        // Use screenTexts from context for primary matching
        val textsToSearch =
            context.rootNodeTexts.joinToString(separator = " | ").lowercase(Locale.getDefault())

        if (context.rootNodeTexts.size < this.minTextCount || context.rootNodeTexts.size > this.maxTextCount) {
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

        // Execute custom matcher if provided
        this.customMatcher?.let {
            if (!it(context.rootNodeTexts, context.sourceNodeTexts, context)) return false
        }

        return true
    }
}