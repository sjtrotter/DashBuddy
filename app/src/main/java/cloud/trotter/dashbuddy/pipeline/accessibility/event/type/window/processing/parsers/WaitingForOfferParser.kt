package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
import javax.inject.Inject

class WaitingForOfferParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.ON_DASH_MAP_WAITING_FOR_OFFER

    override fun parse(node: UiNode): ScreenInfo {
        // New layout ("Finding offers") doesn't expose pay or wait time reliably.
        val isNewLayout = node.findNode {
            it.text?.contains("Finding offers", ignoreCase = true) == true
        } != null

        if (isNewLayout) {
            Timber.d("WaitingForOfferParser: new layout (pay/wait unavailable)")
            return ScreenInfo.WaitingForOffer(
                screen = targetScreen,
                currentDashPay = null,
                waitTimeEstimate = null,
                isHeadingBackToZone = false
            )
        }

        // Legacy layout extraction.
        val isHeadingBack = node.findNode {
            it.viewIdResourceName?.endsWith("cross_sp_title") == true
        } != null

        var waitTime: String? = null
        val waitTimeNode = node.findNode {
            it.viewIdResourceName?.endsWith("wait_time_button") == true
        }
        if (waitTimeNode != null) {
            waitTime = waitTimeNode.children.firstOrNull {
                it.viewIdResourceName?.endsWith("textView_prism_button_title") == true
            }?.text?.replace("est. ", "")
        }

        val payNode = node.findNode { it.viewIdResourceName?.endsWith("running_total_pay") == true }
        val currentPay = UtilityFunctions.parseCurrency(payNode?.text)

        Timber.d("WaitingForOfferParser: legacy layout — pay=$currentPay, wait='$waitTime', headingBack=$isHeadingBack")
        return ScreenInfo.WaitingForOffer(
            screen = targetScreen,
            currentDashPay = currentPay,
            waitTimeEstimate = waitTime,
            isHeadingBackToZone = isHeadingBack
        )
    }
}
