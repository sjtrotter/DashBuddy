package cloud.trotter.dashbuddy.state.model

import android.text.SpannableString

/**
 * A data class to hold the results of the offer evaluation process.
 * @param action The action to take on the offer.
 * @param message A formatted SpannableString containing the evaluation summary.
 */
data class OfferEvaluation(
    val action: OfferAction,
    val message: SpannableString
)