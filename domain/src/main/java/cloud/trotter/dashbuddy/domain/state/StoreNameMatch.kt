package cloud.trotter.dashbuddy.domain.state

import java.util.Locale

/**
 * Store-name correlation across surfaces (#526 / #159). The same physical store renders
 * differently per DoorDash screen — the offer/pickup show the brand name (`Target`,
 * `Maple Street Biscuit Company`), while the dropoff card and payout show a running-key form
 * (`Target (02426)`, `Maple Street Biscuit - Alamo Ranch`). String equality fails; these surfaces
 * are correlated by their **shared leading name tokens** (the brand core they have in common).
 *
 * The single source of truth for that comparison — used by [PlatformRegionStepper] to resolve a
 * dropoff's store from the job's pickups, and by the store-chain projection to link offer ↔ pickup
 * ↔ dropoff ↔ payout. Locale-safe (`Locale.ROOT`).
 */
object StoreNameMatch {

    /** Lowercase alphanumeric tokens; drops store-number / punctuation noise that differs per surface. */
    fun tokens(name: String): List<String> =
        name.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** Count of equal tokens from the start of both lists — how much of the brand name they share. */
    fun sharedLeadingTokens(a: List<String>, b: List<String>): Int {
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        return i
    }

    /** Shared leading tokens between two raw store-name strings. */
    fun sharedLeadingTokens(a: String, b: String): Int = sharedLeadingTokens(tokens(a), tokens(b))

    /**
     * The candidate that shares the most leading tokens with [target] (≥1), or null if none do.
     * Used to pick which known store (e.g. a job's pickup) a surface form belongs to. Ties resolve
     * to the first candidate in iteration order.
     */
    fun bestMatch(candidates: List<String>, target: String): String? {
        if (candidates.isEmpty()) return null
        val t = tokens(target)
        return candidates
            .map { it to sharedLeadingTokens(tokens(it), t) }
            .filter { it.second >= 1 }
            .maxByOrNull { it.second }
            ?.first
    }
}
