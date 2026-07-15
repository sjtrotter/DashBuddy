package cloud.trotter.dashbuddy.domain.state

import java.util.Locale

/**
 * The #159 store-identity SSOT: the one `:domain` normalizer + running-key extractor + deterministic
 * `storeKey` builder shared by the read-model resolver (`AnalyticsProjector`) AND the field-verified
 * shadow projector ([StoreChainProjector]). Every place `normalizedChain` / `runningKey` / `storeKey`
 * appears derives from here, so a refold reproduces the same key byte-for-byte (F7) and no second copy
 * of the normalization rule can drift (Principle 5).
 *
 * **Platform-agnostic (P8):** the extraction patterns are surface-SHAPE heuristics (parenthetical
 * codes, ` - Area` suffixes, `#NNN` franchise numbers) — documented as such, never a `when` on a
 * platform literal. A platform that needs different shapes extends the *data* (the phase-2 OTA
 * lookup), not a branch here.
 *
 * **Privacy:** store names/keys are MERCHANT data — fine at rest, never an INFO+ log surface (P7).
 */
object StoreKeys {

    /**
     * The chain bucket for a store name — the issue's `offer_name_normalized`. Lowercased,
     * whitespace-collapsed, and stripped of the per-location qualifiers a payout/dropoff form adds:
     * a trailing `(…)` parenthetical, a trailing ` - Location` suffix, and a `#NNN` franchise number.
     * Always computed from the **pickup canonical anchor** at resolution (F7) — never the payout form,
     * which carries the qualifier the anchor doesn't. `Locale.ROOT` so `İ`-style locales can't fork a
     * chain.
     *
     * Hardening (adversarial review):
     * - **Trailing-only dash strip (FIX 5a):** the ` - Location` strip removes only the LAST
     *   ` - <qualifier>` suffix (regex `\s-\s[^-]*$`) and is applied AT MOST ONCE, never splits at the
     *   FIRST ` - `, so a brand whose name itself contains ` - ` ("Roli - Poli - Alamo Ranch" →
     *   "roli - poli") keeps its brand core instead of collapsing to "roli".
     * - **Strip-to-stable (FIX 5b):** the paren / hash strips run in a loop, and the single dash strip is
     *   interleaved between two paren/hash passes, so a stacked qualifier ("Panda Express (Loop 410) -
     *   San Antonio" → "panda express") fully reduces without the dash strip ever running twice.
     * - **Delimiter defence (FIX 5c):** the key delimiter `|` is replaced with a space (then whitespace
     *   collapses), so a merchant string containing `|` can't forge a [storeKey] segment collision.
     * - **Degenerate-empty guard (FIX 5d):** an all-qualifier input ("(0164-0045)", "#161") whose strip
     *   would leave nothing falls back to the lowered/collapsed UNstripped form, so two distinct
     *   qualifier-only names don't both collapse into an empty `platform||` bucket.
     */
    fun normalizedChain(name: String): String {
        val raw = name.trim()
        // Paren/hash strips loop to stability; the dash strip runs ONCE between two paren/hash passes so
        // a paren exposed by the dash strip ("… (Loop 410) - San Antonio") still reduces, WITHOUT ever
        // eating a brand's own internal " - " on a second dash pass (FIX 5a/5b).
        var s = stripParenHash(raw)
        s = s.replace(TRAILING_DASH_SUFFIX, "").trim().ifEmpty { s }
        s = stripParenHash(s)
        val stripped = collapse(s)
        // FIX 5d: an all-qualifier input strips to empty — fall back to the UNstripped lowered form so
        // distinct qualifier-only names stay distinct instead of merging into `platform||`.
        return stripped.ifEmpty { collapse(raw) }
    }

    /** Strip trailing `(…)` and `#NNN` qualifiers until stable (never below empty). */
    private fun stripParenHash(name: String): String {
        var s = name
        var prev: String
        do {
            prev = s
            s = s.replace(TRAILING_PAREN, "").replace(TRAILING_HASH, "").trim()
        } while (s != prev && s.isNotEmpty())
        return s
    }

    /** `Locale.ROOT` lower + replace the key delimiter `|` with a space (FIX 5c) + collapse whitespace. */
    private fun collapse(s: String): String =
        s.lowercase(Locale.ROOT).replace('|', ' ').replace(WHITESPACE, " ").trim()

    /**
     * The platform's location discriminator inside a payout/dropoff store form — the `(02426)` code,
     * the ` - Alamo Ranch` area, the `#161` franchise number, the `(0164-0045)` hyphenated code, or a
     * `(Sonterra Village)` place name. Returns the RAW extracted token (the shadow projector surfaces
     * it verbatim); [normalizeRunningKey] canonicalizes it for the key. Null when the form carries no
     * discriminator. **Precedence (F7): a parenthetical wins** over a ` - Area` suffix.
     */
    fun extractRunningKey(payoutName: String): String? {
        // Parenthetical wins (F7): any (...) content — digit code, hyphenated code, or place name.
        TRAILING_PAREN_CAPTURE.find(payoutName)?.let { return it.groupValues[1].trim().ifEmpty { null } }
        // "#NNN" franchise suffix (digits or a hyphenated code after the hash).
        TRAILING_HASH_CAPTURE.find(payoutName)?.let { return it.groupValues[1].trim().ifEmpty { null } }
        // " - Area" suffix — LAST occurrence, symmetric with normalizedChain's trailing-only strip (FIX
        // 5a) so a brand whose name contains " - " yields the location tail, not the second brand token.
        val dash = payoutName.lastIndexOf(" - ")
        if (dash >= 0) return payoutName.substring(dash + 3).trim().ifEmpty { null }
        return null
    }

    /**
     * Canonicalize a **receipt-derived** running key for the [storeKey]: `Locale.ROOT` lowercase +
     * whitespace-collapse so "Alamo Ranch" and "alamo ranch" don't fork one store into two entities
     * (F7). Null/blank → null (an empty key segment, the chain-only provisional form).
     *
     * **`@`-strip (#773 F-1, prefix soundness):** an `@` prefix is the provenance marker of an
     * ADDRESS-derived key ([addressRunningKey]). [extractRunningKey]'s parenthetical / ` - `-tail
     * captures pass arbitrary untrusted merchant text, so a payout form like `"Joe's (@joescafe)"`
     * could otherwise mint a receipt-tier key that STARTS WITH `@` and masquerade as address-tier. This
     * canonicalizer runs on the receipt path only and strips any leading `@`, so a legitimate
     * address key (minted by [addressRunningKey], which bypasses this function) is the ONLY source of a
     * `@`-prefixed running key — provenance-by-prefix is sound, with no extra column.
     */
    fun normalizeRunningKey(raw: String?): String? =
        raw?.let { collapse(it).trimStart('@').trim() }?.ifEmpty { null }

    /**
     * The **address-derived** running key (#773): the leading street-NUMBER token of [address],
     * `@`-prefixed — the fallback location discriminator for a **chain-bare** payout (a receipt that
     * carries no `(code)` / ` - Area` running key, e.g. grocery). Used ONLY when [extractRunningKey]
     * yields nothing, so an address-keyed store's running key always carries the `@` provenance marker
     * ([normalizeRunningKey] guarantees no receipt key can).
     *
     * Accept the FIRST whitespace token of the trimmed [address] iff it is a **pure ASCII digit-run**
     * (`"12125 Alamo Rnch Pkwy, San Antonio, TX 78240, USA"` → `"@12125"`; `"7330 N Loop 1604 W…"` →
     * `"@7330"`) — returning `"@" + token`. Anything else is **fail-null** (F-3, the conservative
     * reading — fail toward conflation, never a truncated/garbage fragment): a hyphenated house number
     * (`"12125-A"`), a range (`"2500-2504"`), a suffixed number (`"12125B"`), a non-numeric first line
     * (a mall/plaza name), or a degenerate/blank input all → null.
     *
     * Street-number-only by design (F7 churn defense): render variance lives in the address TAIL
     * (`ZIP+4`/`ZIP5`, `USA`/`United States`) and street names are abbreviation-prone, but the leading
     * number is the fragment most invariant across renders of the SAME location. `Locale.ROOT` — the
     * ASCII digit-run check is locale-independent by construction.
     */
    fun addressRunningKey(address: String?): String? {
        val firstToken = address?.trim()?.takeIf { it.isNotEmpty() }
            ?.split(WHITESPACE)?.firstOrNull()
            ?: return null
        // Length cap: a real US street number is 1–6 digits; an untrusted-UI degenerate like a
        // 20-digit run is not a street number — fail-null, never a garbage key (#773
        // adversarial-review finding 3, same fail-toward-conflation philosophy as F-3).
        return if (firstToken.length in 1..MAX_STREET_NUMBER_DIGITS && firstToken.all { it in '0'..'9' }) {
            "@$firstToken"
        } else {
            null
        }
    }

    private const val MAX_STREET_NUMBER_DIGITS = 6

    /**
     * The deterministic entity key: `platform + "|" + normalizedChain + "|" + runningKey`
     * (D2). The `runningKey` segment is empty while unknown (a chain-only provisional key). The
     * `platform` segment prevents a cross-platform chain collision (F5). Pure ⇒ a refold reproduces
     * it byte-for-byte. [runningKey] is expected already-normalized ([normalizeRunningKey]).
     */
    fun storeKey(platform: String, normalizedChain: String, runningKey: String?): String =
        platform + "|" + normalizedChain + "|" + (runningKey ?: "")

    private val TRAILING_PAREN = Regex("""\s*\([^)]*\)\s*$""")
    private val TRAILING_PAREN_CAPTURE = Regex("""\(([^)]+)\)\s*$""")
    /** Trailing " - Location" only — LAST occurrence, no internal hyphen after it (FIX 5a). */
    private val TRAILING_DASH_SUFFIX = Regex("""\s-\s[^-]*$""")
    private val TRAILING_HASH = Regex("""\s*#\s*[\w-]+\s*$""")
    private val TRAILING_HASH_CAPTURE = Regex("""#\s*([\w-]+)\s*$""")
    private val WHITESPACE = Regex("""\s+""")
}
