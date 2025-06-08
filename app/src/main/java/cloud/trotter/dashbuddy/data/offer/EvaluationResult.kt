package cloud.trotter.dashbuddy.data.offer

import android.text.SpannableString

/**
 * A data class to hold the results of the offer evaluation process.
 * @param offerEntity The fully populated OfferEntity ready for database insertion.
 * @param bubbleMessage A formatted SpannableString containing the evaluation summary.
 */
data class EvaluationResult(
    val offerEntity: OfferEntity,
    val bubbleMessage: SpannableString
)
