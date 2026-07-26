package cloud.trotter.dashbuddy.state.effects

/**
 * Filename policy for evidence captures (#859).
 *
 * A rule declares its screenshot name as a template (`"Offer - {storeName}"`), interpolated
 * against the branch's parse fields. A field that parsed null does NOT interpolate — by
 * design, so the ruleset lint can see which template failed on which corpus frame — and the
 * un-interpolated token used to reach the gallery verbatim: **331 files named
 * `Offer - {storeName}.png`** accumulated on the dev's device.
 *
 * A filename is a leaf output, so the honest degrade is to drop what could not be resolved
 * and keep what could: `"Offer - {storeName}"` → `"Offer"`. Generic over every rule and
 * platform — this sanitizes any template shape, and knows nothing about offers, stores, or
 * which ruleset authored the prefix.
 *
 * This is a fail-safe, not the primary control: the primary control stays the
 * `ParseOutputGoldenTest` arg-template lint, which still sees the literal token and still
 * fails when a rule introduces a new never-interpolating template.
 */
object EvidenceFilename {

    /** Used when nothing survives sanitization — matches the engine's untemplated default. */
    const val FALLBACK_PREFIX = "Rule"

    /** `{field}` — the exact shape [cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset] leaves behind. */
    private val UNRESOLVED_TOKEN = Regex("""\{\w*}""")

    /** Separator punctuation left dangling once a token is dropped (`"Offer - "` → `"Offer"`). */
    private const val DANGLING = " \t-–—:,;|/_"

    /**
     * Sanitize a rule-declared filename prefix: no literal `{token}` ever reaches a saved
     * file, and no dangling separator is left where one was dropped.
     */
    fun sanitizePrefix(raw: String?): String {
        val stripped = (raw ?: "")
            .replace(UNRESOLVED_TOKEN, "")
            .replace(Regex("\\s+"), " ")
            .trim { it in DANGLING }
        return stripped.ifBlank { FALLBACK_PREFIX }
    }
}
