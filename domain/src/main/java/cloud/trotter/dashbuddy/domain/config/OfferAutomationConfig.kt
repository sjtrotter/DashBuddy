package cloud.trotter.dashbuddy.domain.config

/**
 * Offer automation the engine actually reads. The former auto-accept / auto-decline economics
 * (#1113) were persisted but read by nothing and were deleted; economics-driven acceptance
 * returns only behind the #843 per-capability consent model.
 */
data class OfferAutomationConfig(
    /**
     * #577 "quick declines" / single-click declines: when ON, the second
     * ("are you sure?") decline button is auto-confirmed so a decline is one
     * tap/voice command. This is "finish the decline I started," NOT the
     * economics-driven auto-decline ("decide for me"); the setting itself is
     * the dasher's consent.
     */
    val quickDeclinesEnabled: Boolean = DEFAULT_QUICK_DECLINES,
) {
    companion object {
        // ONE owner for every default (#401): the DataStore fallbacks
        // reference these instead of restating literals.
        const val DEFAULT_QUICK_DECLINES = false
    }
}
