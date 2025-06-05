package cloud.trotter.dashbuddy.state.screens // Package for the Screen enum

import java.util.Locale
import cloud.trotter.dashbuddy.state.Context as StateContext

/**
 * Represents distinct UI screens identified within the Dasher application.
 * Each enum constant holds its own criteria (signature) for matching.
 * The `matches` method is used by the ScreenRecognizer to determine the current screen.
 */
enum class Screen(
    // Signature properties directly in the enum constructor
    val screenName: String = "",
    val requiredTexts: List<String> = emptyList(),
    val someOfTheseTexts: List<String> = emptyList(), // At least one text in this list must match
    val forbiddenTexts: List<String> = emptyList(),
    val requiredSourceTexts: List<String> = emptyList(), // Texts to check in event.sourceNodeTexts
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
        someOfTheseTexts = listOf("starting…", "signing in…", "loading…"),
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
        minTextCount = 5
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
        someOfTheseTexts = listOf("past weeks", "balance", "cash out with dasherdirect")
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

    //    ACCOUNT_DETAILS_VIEW(
//        requiredTexts = listOf("account details", "edit account", "vehicles") // Example keywords
//    ),
//    PROMOS_VIEW(
//        requiredTexts = listOf("promos", "challenges", "peak pay") // Example keywords
//    ),
//    DASHER_HELP_VIEW(
//        requiredTexts = listOf("help", "faq", "contact support") // Example keywords
//    ),
//
    // --- Starting a Dash Workflow ---
    SET_DASH_END_TIME(
        screenName = "Set Dash End Time",
        requiredTexts = listOf(
            "navigate up",
            "dash now",
            "select end time",
            "dashers needed until"
        ),
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
        )
    ),
    DASH_CONTROL(
        screenName = "Dash Control",
        requiredTexts = listOf(
            "return to dash",
            "you're dashing in",
            "end dash",
            "you're dashing now"
        ),
    ),
    ON_DASH_ALONG_THE_WAY(
        screenName = "Dash Along the Way",
        requiredTexts = listOf(
            "we'll look for orders along the way",
            "navigate",
            "up",
            "to zone",
            "spot saved until"
        ), // "Navigate" button is also key
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
    ),

    // --- Generic Navigation View ---
    NAVIGATION_VIEW(
        screenName = "Navigation",
        requiredTexts = listOf("mi", "min", "exit"),
        forbiddenTexts = listOf("accept", "decline")
    ),

    // --- Offer Handling ---
    OFFER_POPUP(
        screenName = "Offer",
        requiredTexts = listOf("decline", "accept", "mi", "$", "deliver by"),
        someOfTheseTexts = listOf("guaranteed (incl. tips)", "total will be higher"),
        minTextCount = 6 // Offer popups usually have a certain density of info
    ),
//    DECLINE_OFFER_REASON_MODAL( // From your logs: "Decline this order?", "This order doesn't pay enough"
//        requiredTexts = listOf("decline this order?", "submit"),
//        someOfTheseTexts = listOf("order is too small", "distance is too far", "i don't want to go to this store")
//    ),
//    DECLINE_OFFER_CONFIRM_MODAL(
//        requiredTexts = listOf("are you sure you want to decline this offer?", "decline offer", "view offer details")
//    ),
//
//    // --- Active Delivery - Pickup Phase ---
//    DELIVERY_NAVIGATION_TO_STORE(
//        requiredTexts = listOf("heading to", "pick up by"), // "mi" and "min" are good indicators of navigation
//        someOfTheseTexts = listOf("navigate", "directions"),
//        forbiddenTexts = listOf("deliver to", "looking for offers", "complete delivery steps", "accept")
//    ),
//    DELIVERY_ARRIVED_AT_STORE( // "Slide after arrival" is a key indicator
//        requiredTexts = listOf("pickup from"),
//        someOfTheseTexts = listOf("arrived at store", "slide after arrival", "confirm items", "start shopping"),
//        forbiddenTexts = listOf("offer", "heading to customer", "deliver to", "looking for offers", "accept")
//    ),
//    DELIVERY_SHOP_AND_DELIVER_LIST(
//        requiredTexts = listOf("shop and deliver", "to shop", "start shopping", "found item", "item unavailable")
//    ),
//    DELIVERY_SHOP_AND_DELIVER_SCANNING(
//        requiredTexts = listOf("scan barcode", "enter barcode manually")
//    ),
//    DELIVERY_SHOP_AND_DELIVER_ITEM_UNAVAILABLE(
//        requiredTexts = listOf("item unavailable", "substitute item", "remove item")
//    ),
//    DELIVERY_SHOP_AND_DELIVER_CHECKOUT(
//        requiredTexts = listOf("proceed to checkout", "pay with red card", "enter total")
//    ),
//    DELIVERY_TAKE_RECEIPT_PHOTO(
//        requiredTexts = listOf("take receipt photo", "capture image"),
//        someOfTheseTexts = listOf("protect your rating", "no receipt", "submit photo")
//    ),
//    DELIVERY_REVIEW_RECEIPT_PHOTO(
//        requiredTexts = listOf("retake", "submit photo"), // "Is this photo clear?"
//        someOfTheseTexts = listOf("is this photo clear?")
//    ),
//    DELIVERY_WAITING_AT_STORE_DIALOG(
//        requiredTexts = listOf("what's causing your wait?")
//    ),
//    DELIVERY_CONFIRM_PICKUP_DIALOG( // "Confirm order was picked up" or "Slide after pickup"
//        requiredTexts = listOf("confirm", "go back"),
//        someOfTheseTexts = listOf("confirm order was picked up", "slide after pickup")
//    ),
//
//    // --- Active Delivery - Drop-off Phase ---
//    DELIVERY_NAVIGATION_TO_CUSTOMER(
//        requiredTexts = listOf("deliver by", "heading to"), // Often has customer name/initial
//        someOfTheseTexts = listOf("navigate", "directions"),
//        forbiddenTexts = listOf("pick up by", "pickup from", "looking for offers", "complete delivery steps", "accept")
//    ),
//    DELIVERY_ARRIVED_AT_CUSTOMER(
//        requiredTexts = listOf("deliver to"), // "I've arrived at recipient"
//        someOfTheseTexts = listOf("i've arrived at recipient", "complete delivery steps"),
//        forbiddenTexts = listOf("pick up by", "pickup from")
//    ),
//    DELIVERY_COMPLETE_STEPS_LIST(
//        requiredTexts = listOf("complete delivery steps"),
//        someOfTheseTexts = listOf("leave it at my door", "take photo", "handed order to customer", "confirm delivery")
//    ),
//    DELIVERY_TAKE_PHOTO_UI( // For drop-off photo
//        requiredTexts = listOf("take photo", "capture image"),
//        forbiddenTexts = listOf("receipt", "deliver to customer") // Differentiate from receipt and main steps list
//    ),
//    DELIVERY_REVIEW_PHOTO_UI( // For drop-off photo
//        requiredTexts = listOf("retake", "submit photo", "confirm delivery"),
//        someOfTheseTexts = listOf("is this photo clear?")
//    ),
//    DELIVERY_CONFIRM_DIALOG( // Final confirmation after all steps
//        requiredTexts = listOf("confirm delivery", "complete")
//    ),
//    DELIVERY_PROBLEM_REPORTING(
//        requiredTexts = listOf("report issue", "can't reach customer") // Example
//    ),
//
//    // --- Pausing / Ending Dash ---
//    DASH_PAUSED_SCREEN(
//        requiredTexts = listOf("dash paused", "resume dash", "end dash")
//    ),
//    END_DASH_CONFIRMATION_DIALOG(
//        requiredTexts = listOf("end your current dash?", "end dash", "go back")
//    ),
//    DASH_SUMMARY_SCREEN(
//        requiredTexts = listOf("dash summary", "total earnings"),
//        someOfTheseTexts = listOf("deliveries completed", "this dash:", "duration", "view details")
//    ),
//
//    // --- Other In-Dash States ---
//    CHAT_VIEW( // Could be with customer or support
//        requiredTexts = listOf("type a message"),
//        someOfTheseTexts = listOf("send", "customer support")
//    ),
//    SAFETY_TOOLKIT_VIEW(
//        requiredTexts = listOf("safety toolkit", "share my location") // Example
//    )
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
            context.screenTexts.joinToString(separator = " | ").lowercase(Locale.getDefault())
        val sourceTextsToSearch =
            context.sourceNodeTexts.joinToString(separator = " | ").lowercase(Locale.getDefault())


        if (context.screenTexts.size < this.minTextCount || context.screenTexts.size > this.maxTextCount) {
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

        for (text in this.requiredSourceTexts) {
            if (!sourceTextsToSearch.contains(text.lowercase(Locale.getDefault()))) return false
        }

        // Execute custom matcher if provided
        this.customMatcher?.let {
            if (!it(context.screenTexts, context.sourceNodeTexts, context)) return false
        }

        return true
    }
}