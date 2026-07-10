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
     * a trailing `(…)` parenthetical, a ` - Location` suffix, and a `#NNN` franchise number. Always
     * computed from the **pickup canonical anchor** at resolution (F7) — never the payout form, which
     * carries the qualifier the anchor doesn't. `Locale.ROOT` so `İ`-style locales can't fork a chain.
     */
    fun normalizedChain(name: String): String {
        var s = name.trim()
        // Strip a trailing parenthetical qualifier: "CAVA (Sonterra Village)" → "CAVA".
        s = s.replace(TRAILING_PAREN, "")
        // Strip a trailing " - Location" suffix: "Maple Street Biscuit - Alamo Ranch" → "Maple …".
        val dash = s.indexOf(" - ")
        if (dash >= 0) s = s.substring(0, dash)
        // Strip a trailing "#NNN" franchise number: "SPROUTS FARMERS MARKET #161" → "SPROUTS …".
        s = s.replace(TRAILING_HASH, "")
        return s.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()
    }

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
        // " - Area" suffix.
        val dash = payoutName.indexOf(" - ")
        if (dash >= 0) return payoutName.substring(dash + 3).trim().ifEmpty { null }
        return null
    }

    /**
     * Canonicalize a running key for the [storeKey]: `Locale.ROOT` lowercase + whitespace-collapse so
     * "Alamo Ranch" and "alamo ranch" don't fork one store into two entities (F7). Null/blank → null
     * (an empty key segment, the chain-only provisional form).
     */
    fun normalizeRunningKey(raw: String?): String? =
        raw?.lowercase(Locale.ROOT)?.replace(WHITESPACE, " ")?.trim()?.ifEmpty { null }

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
    private val TRAILING_HASH = Regex("""\s*#\s*[\w-]+\s*$""")
    private val TRAILING_HASH_CAPTURE = Regex("""#\s*([\w-]+)\s*$""")
    private val WHITESPACE = Regex("""\s+""")
}
