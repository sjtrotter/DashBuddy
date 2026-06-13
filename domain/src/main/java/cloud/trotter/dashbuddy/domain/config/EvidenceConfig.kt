package cloud.trotter.dashbuddy.domain.config

data class EvidenceConfig(
    val masterEnabled: Boolean = DEFAULT_MASTER,
    val saveOffers: Boolean = DEFAULT_SAVE_OFFERS,
    val saveDeliverySummaries: Boolean = DEFAULT_SAVE_DELIVERIES,
    val saveSessionSummaries: Boolean = DEFAULT_SAVE_SESSIONS,
) {
    /**
     * THE evidence-capture policy (#426): a screenshot fires only when the
     * master toggle AND the matching category toggle are on. A null category
     * (an effect that never declared one) is DENIED — fail closed, so a new
     * screenshot source cannot bypass the user's settings by omission.
     */
    fun allows(category: EvidenceCategory?): Boolean = masterEnabled && when (category) {
        EvidenceCategory.OFFER -> saveOffers
        EvidenceCategory.DELIVERY_SUMMARY -> saveDeliverySummaries
        EvidenceCategory.SESSION_SUMMARY -> saveSessionSummaries
        null -> false
    }

    companion object {
        // ONE owner for every default (#401).
        const val DEFAULT_MASTER = false
        const val DEFAULT_SAVE_OFFERS = true
        const val DEFAULT_SAVE_DELIVERIES = true
        const val DEFAULT_SAVE_SESSIONS = true
    }
}

/**
 * The user-consentable kinds of evidence screenshot (#426) — one per toggle in
 * the Evidence Locker settings. Rule-declared `screenshot` effects name theirs
 * via the `category` arg (wire values below); app-emitted screenshots set it
 * directly. No "other" bucket on purpose: an uncategorized capture has no
 * toggle the user could turn off, so it is not allowed to exist.
 */
enum class EvidenceCategory(val wire: String) {
    OFFER("offer"),
    DELIVERY_SUMMARY("delivery_summary"),
    SESSION_SUMMARY("dash_summary"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(wire: String?): EvidenceCategory? = wire?.let { byWire[it] }
    }
}
