package cloud.trotter.log.gig.domain.census

import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.serialization.Serializable

/**
 * #1621: the census decision's record, a [ConsentReceipt] bound to the census server [endpoint] it was given for.
 * Consent covers one server: a receipt for another endpoint does not let uploads run (ADR-0011 §12).
 */
@Serializable
data class CensusConsentReceipt(val receipt: ConsentReceipt, val endpoint: String)

/** #1621: the census decision as the opt-in shows it and as uploads obey it. */
enum class CensusConsent {
    /** Never accepted, declined, or switched off. */
    OFF,

    /** Accepted for the endpoint this build sends to. */
    ON,

    /** Accepted for another endpoint: off until the dasher accepts again. */
    ENDPOINT_CHANGED,
    ;

    companion object {
        /**
         * THE consent rule: on only when the stored switch is on and its receipt is a grant for exactly [endpoint].
         * A switch with no receipt (an install from before the disclosure existed) reads OFF.
         */
        fun of(switchOn: Boolean, receipt: CensusConsentReceipt?, endpoint: String): CensusConsent = when {
            !switchOn || receipt == null || !receipt.receipt.granted -> OFF
            receipt.endpoint != endpoint -> ENDPOINT_CHANGED
            else -> ON
        }
    }
}

/**
 * #1621: the census choices, stored with the consent and read by the uploads (#1620). [platforms] is null until the
 * dasher saves a choice; [platformsAmong] then means every readable platform, so a platform added later never joins
 * a saved choice by itself.
 */
data class CensusChoices(val platforms: Set<Platform>?, val wifiOnly: Boolean) {
    fun platformsAmong(readable: List<Platform>): Set<Platform> =
        platforms?.intersect(readable.toSet()) ?: readable.toSet()

    companion object {
        /** Wi-Fi only is on until the dasher turns it off (ADR-0011 §21). */
        const val DEFAULT_WIFI_ONLY = true
    }
}
