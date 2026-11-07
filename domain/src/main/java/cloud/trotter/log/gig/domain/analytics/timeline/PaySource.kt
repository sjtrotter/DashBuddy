package cloud.trotter.log.gig.domain.analytics.timeline

import cloud.trotter.log.gig.domain.analytics.PayBasis

/** Where a delivery line's pay came from (#1516), the one mapping from `payBasis`. */
enum class PaySource {
    RECEIPT,
    /** A receipt pooled across the job's drops, apportioned per drop. */
    RECEIPT_SPLIT,
    OFFER_AMOUNT_EST,
    NO_PAY_FOUND,
    YOU_ENTERED,
    ;

    val estimated: Boolean get() = this == OFFER_AMOUNT_EST

    companion object {
        /**
         * [jobDeliveries] is the number of deliveries of this drop's job: `DROP_SHARE` is stamped on a sole drop
         * too (its share is the whole receipt), so only a job with two or more drops is a split. A machine source
         * with no pay is [NO_PAY_FOUND]; a driver's entry stays [YOU_ENTERED].
         */
        fun of(payBasis: String, pay: Double?, jobDeliveries: Int): PaySource {
            val source = when (payBasis) {
                PayBasis.DROP_SHARE -> if (jobDeliveries >= 2) RECEIPT_SPLIT else RECEIPT
                PayBasis.RECEIPT_TOTAL -> RECEIPT
                PayBasis.OFFER_PAY -> OFFER_AMOUNT_EST
                PayBasis.MANUAL, PayBasis.USER_CORRECTED -> YOU_ENTERED
                else -> NO_PAY_FOUND // NONE, SUSPECT_FULL_RECEIPT (its money nulled), an unknown basis
            }
            return if (pay == null && source != YOU_ENTERED) NO_PAY_FOUND else source
        }
    }
}
