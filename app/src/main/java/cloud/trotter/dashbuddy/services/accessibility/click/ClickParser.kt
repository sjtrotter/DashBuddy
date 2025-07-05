package cloud.trotter.dashbuddy.services.accessibility.click

import cloud.trotter.dashbuddy.log.Logger as Log

object ClickParser {
    private const val TAG = "ClickParser"

    fun parse(clickedTexts: List<String>): ClickInfo {

        if (clickedTexts.isEmpty()) {
            return ClickInfo.UnhandledClick
        }

        Log.d(TAG, "Parsing click with texts: $clickedTexts")

        // Check against all texts found in the clicked node's hierarchy.
        for (text in clickedTexts) {
            val trimmedText = text.trim()
            when {
                // --- Offer Handling ---
                trimmedText.contains("Accept", ignoreCase = true) ||
                        trimmedText.contains("Add to route", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.ACCEPT_OFFER)

                trimmedText.equals("Decline", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.DECLINE_OFFER_INITIAL)

                trimmedText.contains("Decline offer", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.DECLINE_OFFER)

                trimmedText.contains("Decline order", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.DECLINE_ORDER)

                // --- Dash Lifecycle ---
                trimmedText.equals("Dash Now", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.START_DASH)

                trimmedText.matches(Regex("\\d{2}:\\d{2}")) ->
                    return ClickInfo.ButtonClick(ClickType.SELECT_DASH_END_TIME)

                trimmedText.contains("End Dash", ignoreCase = true) &&
                        trimmedText.contains("since", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.OPEN_DASH_CONTROLS)

                trimmedText.equals("End dash", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.CONFIRM_END_DASH)

                trimmedText.equals("Resume Dash", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.RESUME_DASH)

                trimmedText.equals("Return to dash", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.RETURN_TO_DASH)

                trimmedText.equals("Continue dashing", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.CONTINUE_DASHING)

                // --- Main Navigation & Menus ---
                trimmedText.equals("Open navigation drawer", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.OPEN_MAIN_MENU)

                trimmedText.equals("Promos", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.VIEW_PROMOS)

                trimmedText.equals("Earnings", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.VIEW_EARNINGS)

                trimmedText.equals("Timeline", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.VIEW_TIMELINE)

                trimmedText.equals("Navigate up", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.NAVIGATE_UP)

                // --- Pickup / At Store Flow ---
                trimmedText.equals("Arrived at store", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.ARRIVED_AT_STORE)

                trimmedText.equals("Send this intro", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.SEND_INTRO_MESSAGE)

                trimmedText.equals("Start shopping", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.START_SHOPPING)

                trimmedText.equals("Scan item barcode", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.SCAN_ITEM_BARCODE)

                trimmedText.equals("Proceed to checkout", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.PROCEED_TO_CHECKOUT)

                trimmedText.equals("Confirm payment", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.CONFIRM_PAYMENT)

                trimmedText.equals("Take receipt photo", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.TAKE_RECEIPT_PHOTO)

                trimmedText.equals("No receipt", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.SKIP_RECEIPT_PHOTO)

                trimmedText.equals("Confirm pickup", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.CONFIRM_PICKUP)

                trimmedText.equals("Tell us what’s causing your wait", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.TELL_US_WHATS_CAUSING_WAIT)

                trimmedText.equals("Submit", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.SUBMIT_WAIT_REASON)


                // --- Delivery / Drop-off Flow ---
                trimmedText.equals("Directions", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.OPEN_DIRECTIONS)

                trimmedText.equals("Message", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.MESSAGE_CUSTOMER)

                trimmedText.equals("Complete delivery steps", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.COMPLETE_DELIVERY_STEPS)

                trimmedText.equals("Can't hand order to recipient", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.CANNOT_HAND_TO_CUSTOMER)

                trimmedText.equals("Take photo", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.TAKE_PHOTO)

                trimmedText.equals("Capture image", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.CAPTURE_IMAGE)

                trimmedText.contains("Confirm delivery", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.CONFIRM_DELIVERY)

                trimmedText.equals("Verify recipient", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.VERIFY_RECIPIENT_ID)

                trimmedText.equals("Agree and continue", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.AGREE_AND_CONTINUE)

                trimmedText.equals("I have received this order", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.CONFIRM_RECEIVED_ORDER_SIGNATURE)


                // --- Generic / Common Actions ---
                trimmedText.equals("Done", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.DONE)

                trimmedText.equals("Next", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.NEXT)

                trimmedText.equals("Continue", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.CONTINUE)

                trimmedText.equals("Confirm", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.CONFIRM)

                trimmedText.equals("Got it", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.GOT_IT)

                trimmedText.equals("Go back", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.GO_BACK)

                trimmedText.equals("I need help", ignoreCase = true) ->
                    return ClickInfo.ButtonClick(ClickType.NEED_HELP)

                // --- Original Fallbacks ---
                trimmedText.startsWith("$") ->
                    return ClickInfo.ButtonClick(ClickType.VIEW_PAY_DETAILS)
            }
        }

        return ClickInfo.UnhandledClick
    }
}
