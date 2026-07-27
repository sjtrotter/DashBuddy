package cloud.trotter.dashbuddy.domain.model.bubble

import java.util.Locale

/**
 * How the bubble HUD picks WHICH dash session it renders when more than one is live (#867).
 *
 * Multi-apping means two platforms can be Online at once. The HUD is a single-session surface, so
 * something has to decide whose cards/chat it shows — and the field defect this enum exists for was
 * that the decision was implicit (whichever platform produced the last recognized frame) and
 * *unlabeled*, so the stack silently swapped platforms mid-glance.
 *
 * This is a **dev-settings experiment switch**, not a user-facing preference: the developer
 * live-tests the three presentations and whichever wins becomes the shipped default (the others can
 * then be deleted). Regardless of mode, platform chips render on the cards + the header whenever
 * ≥2 sessions are live — the silent-swap class is impossible in every mode.
 *
 * Semantics (the manual platform switcher is the chip row above the stack):
 *
 * - [FOLLOW_ACTIVE] — today's behavior: the HUD follows the platform that produced the last
 *   recognized frame. A switcher pick acts as a **temporary pin**: it holds while that platform's
 *   session is live and RELEASES automatically when that session ends (back to following).
 * - [PINNED] — the switcher pick is **durable**: it survives its session ending and re-takes effect
 *   when that platform dashes again; only another pick changes it. While the pinned platform has no
 *   live session the display falls back to follow-active (there is no per-platform session history
 *   to show, and the header chip always names what is actually on screen — a fallback is never a
 *   lie). The pin is in-memory / session-lifetime only — it is deliberately NOT persisted.
 * - [MERGED] — test-only: the card stack folds EVERY live session into one timeline with per-card
 *   platform chips. The switcher is hidden (it would do nothing) and the chat stays on the
 *   follow-active session — see `BubbleSessionResolver` for why the merge is card-stack-only.
 *
 * Stored as [wire]; an unknown/absent value fails closed to [FOLLOW_ACTIVE] (the shipped behavior).
 */
enum class BubbleSessionMode(val wire: String) {
    FOLLOW_ACTIVE("follow_active"),
    PINNED("pinned"),
    MERGED("merged"),
    ;

    companion object {
        /** The fail-closed default: the shipped follow-last-active presentation. */
        val Default = FOLLOW_ACTIVE

        private val byWire = entries.associateBy { it.wire }

        /** Decode a stored [wire] value; anything unrecognized (or null) falls back to [Default]. */
        fun fromWire(wire: String?): BubbleSessionMode =
            wire?.lowercase(Locale.ROOT)?.let { byWire[it] } ?: Default
    }
}
