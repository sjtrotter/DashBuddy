package cloud.trotter.dashbuddy.domain.model.offer

/**
 * How the platform is presenting this offer (#881) — the *presentation* kind, not the work.
 *
 * Recognition is data, not code (CLAUDE.md P8): a ruleset that has the concept parses it as a
 * lower-case wire string (today `uber.screen.offer`'s `offerKind`, discriminated off the card's
 * own CTA text — "Match" vs "Accept"); a platform that has no such concept simply declares no
 * `offerKind` field and every offer carries `null`. **Nothing keys behaviour off [Platform]** —
 * the only consumer is the #830 replace path, which reads a [DIRECT] offer landing over a [MATCH]
 * one as an EXPECTED supersession rather than churn, and does so purely from these two values.
 *
 * Deliberately NOT an input to scoring, [ParsedOffer.offerHash], or
 * [ParsedOffer.presentationKey]: a match and a direct offer for the same trip are the same work at
 * the same price (dev direction on #251, 2026-07-27), and folding the kind into identity would make
 * the direct-over-match hand-off look like a different presentation for the wrong reason.
 */
enum class OfferKind(val wire: String) {
    /**
     * A **match** display — the platform is showing a trip it has not (yet) assigned to this driver
     * exclusively (Uber's Trip Radar: the CTA reads "Match"). The platform may send a real, direct
     * offer over the top of it; declining one does not carry the same weight as declining a direct
     * offer on the platform's own acceptance metrics.
     */
    MATCH("match"),

    /** A **direct** offer — this trip is being offered to this driver (the CTA reads "Accept"). */
    DIRECT("direct");

    companion object {
        /**
         * Resolve a ruleset wire string, case-insensitively. Fail-NULL (never a default kind): an
         * unrecognized value is a gap between ruleset vocabulary and this enum, and a wrong kind is
         * worse than no kind — a null simply leaves every consumer on its pre-#881 behaviour.
         */
        fun fromWire(wire: String?): OfferKind? =
            entries.firstOrNull { it.wire.equals(wire?.trim(), ignoreCase = true) }
    }
}
