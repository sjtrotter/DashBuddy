package cloud.trotter.dashbuddy.state.model

import android.text.SpannableString

/**
 * A data class to hold the results of the offer evaluation process.
 * @param offerAction The action to take on the offer.
 * @param bubbleMessage A formatted SpannableString containing the evaluation summary.
 */
data class OfferEvaluation(
    val offerAction: OfferAction,
    val bubbleMessage: SpannableString
)