package cloud.trotter.dashbuddy.services.accessibility.screen // Package for the Screen enum

import cloud.trotter.dashbuddy.state.ActivityHint
import java.util.Locale
import cloud.trotter.dashbuddy.state.StateContext as StateContext

/**
 * Represents distinct UI screens identified within the Dasher application.
 * Each enum constant holds its own criteria (signature) for matching.
 * The `matches` method is used by the ScreenRecognizer to determine the current screen.
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
    val customMatcher: ((screenTexts: List<String>, sourceTexts: List<String>, context: StateContext) -> Boolean)? = null
) {
    UNKNOWN(
        screenName = "UNKNOWN"
    ), // Default, matches nothing specifically or used as a fallback

    // --- Startup / Login / Offline States ---
    APP_STARTING_OR_LOADING(
        screenName = "App Startup",
        someOfTheseTexts = listOf("starting…", "signing in…"),
        maxTextCount = 7 // Usually few texts on these screens, helps differentiate
    ),
    LOGIN_SCREEN(
        screenName = "Login",
        requiredTexts = listOf("phone number", "email", "continue", "sign in with google"),
        forbiddenTexts = listOf("dash now", "looking for offers")
    ),
    MAIN_MAP_IDLE(
        screenName = "Main Map Idle",
        requiredTexts = listOf("dash", "open navigation drawer", "safety", "help"),
        // ensure the screen has the button.
        someOfTheseTexts = listOf("dash now", "dash along the way", "schedule", "navigate"),
        minTextCount = 5,
        activityHint = ActivityHint.INACTIVE
    ),
    SCHEDULE_VIEW(
        screenName = "Schedule",
        requiredTexts = listOf(
            "schedule",
            "sun",
            "mon",
            "tue",
            "wed",
            "thu",
            "fri",
            "sat",
            "locations"
        ),
//        someOfTheseTexts = listOf("available slots", "sun", "mon", "tue", "wed", "thu", "fri", "sat")
        minTextCount = 23
    ),
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
    MAIN_MENU_VIEW(
        screenName = "Main Menu",
        requiredTexts = listOf(
            "dash",
            "schedule",
            "earnings",
            "ratings",
            "account",
            "dash preferences",
            "settings",
            "log out"
        )
    ),

    // --- Starting or Ending a Dash Workflow ---
    SET_DASH_END_TIME(
        screenName = "Set Dash End Time",
        requiredTexts = listOf(
            "navigate up",
            "dash now",
            "select end time",
            "dashers needed until"
        ),
        activityHint = ActivityHint.INACTIVE
    ),
    DASH_SUMMARY_SCREEN(
        screenName = "Dash Summary",
        requiredTexts = listOf(
            "Dash summary",
            "Start time",
            "Active Time",
            "Dash Time",
            "Deliveries Completed",
            "Dash total",
            "Done"
        ),
        someOfTheseTexts = listOf(
            "am",
            "pm",
        )
    ),

    // --- Actively Dashing States ---
    ON_DASH_MAP_WAITING_FOR_OFFER(
        screenName = "Waiting for Offer",
        requiredTexts = listOf("looking for offers", "this dash", "timeline"),
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
            "up",
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

    // --- Navigation Views ---
    // Generic view. Match AFTER more specific views.
    NAVIGATION_VIEW(
        screenName = "Generic Navigation",
        requiredTexts = listOf("min", "exit"),
        someOfTheseTexts = listOf("mi", "ft"),
        forbiddenTexts = listOf("accept", "decline"),
        isNavigating = true,
    ),
    NAVIGATION_VIEW_TO_PICK_UP(
        screenName = "Navigation to Pick Up",
        requiredTexts = listOf("heading to", "pick up instructions", "pick up by"),
        someOfTheseTexts = listOf("mi", "ft"),
        forbiddenTexts = listOf(
            "accept",
            "decline",
            "read instructions on arrival"
        ),
        activityHint = ActivityHint.ACTIVE,
        isPickup = true,
        isNavigating = true
    ),
    NAVIGATION_VIEW_TO_DROP_OFF(
        screenName = "Navigation to Drop Off",
        requiredTexts = listOf("heading to", "read instructions on arrival", "deliver by"),
        someOfTheseTexts = listOf("mi", "ft", "leave it at the door", "hand to customer"),
        forbiddenTexts = listOf("accept", "decline"),
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
    PICKUP_DETAILS_PRE_ARRIVAL(
        requiredTexts = listOf("pickup from", "directions"),
        someOfTheseTexts = listOf("arrived at store", "directions"),
//        forbiddenTexts = listOf("offer", "heading to customer", "deliver to", "looking for offers", "accept")
        isPickup = true,
        activityHint = ActivityHint.ACTIVE
    ),
    PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI(
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
    PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE(
        requiredTexts = listOf("order for", "pick up by"),
        someOfTheseTexts = listOf("verify order", "continue with pickup", "confirm pickup"),
//        forbiddenTexts = listOf("offer", "heading to customer", "deliver to", "looking for offers", "accept")
        isPickup = true,
        activityHint = ActivityHint.ACTIVE
    ),
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
    PICKUP_DETAILS_POST_ARRIVAL_SHOP(
        requiredTexts = listOf("shop and deliver", "to shop", "done"),
        isPickup = true,
        activityHint = ActivityHint.ACTIVE
//        forbiddenTexts = listOf("offer", "heading to customer", "deliver to", "looking for offers", "accept")
    ),
    PICKUP_DETAILS_PICKED_UP(
        requiredTexts = listOf("Confirm pickup", "Loading…"),
        isPickup = true,
        activityHint = ActivityHint.ACTIVE
    ),

    // --- Active Delivery - Dropoff Phase ---
    DROPOFF_DETAILS_PRE_ARRIVAL(
        requiredTexts = listOf("Deliver to", "by", "settings"),
        someOfTheseTexts = listOf("Leave it at the door", "am", "pm"),
        isDelivery = true,
        activityHint = ActivityHint.ACTIVE
    ),


    // --- Post-Delivery ---
    DELIVERY_COMPLETED_DIALOG(
        screenName = "Delivery Completed",
        requiredTexts = listOf("completed", "$"),
        someOfTheseTexts = listOf("delivery", "deliveries", "done", "continue"),
        forbiddenTexts = listOf(
            "accept",
            "decline",
            "dash summary",
            "current orders",
            "add time",
            "dash ends at",
            "active time",
            "dash time",
            "completed deliveries"
        ),
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