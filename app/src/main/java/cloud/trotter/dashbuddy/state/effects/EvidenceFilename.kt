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

    /**
     * `{field}` — the exact shape [cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset] leaves
     * behind.
     *
     * **Both braces MUST stay escaped (#909).** The original form `"""\{\w*}"""` compiles fine on
     * the host JVM (`java.util.regex` tolerates a bare closing brace) but Android's regex engine is
     * ICU-backed and **rejects** it — `PatternSyntaxException: Syntax error near index 6`. Thrown
     * from a `val` initializer that becomes an `ExceptionInInitializerError`, it killed the
     * [SideEffectEngine]'s single queue-drain worker on the first evidence-enabled capture of the
     * process and silently destroyed 91.7% of one evening's `app_events` (#909). The
     * correctly-escaped sibling is
     * [cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset]'s `TEMPLATE_PATTERN` (`\{(\w+)\}`).
     *
     * `\}` is byte-identical in intent to a bare `}` on both engines — a literal closing brace —
     * so the strip behaviour is unchanged: `{storeName}`, `{payAmount}`, and the degenerate `{}`
     * all match exactly as before.
     *
     * `IcuRegexGuardTest` (`:app` unit tests) is the permanent, build-red backstop for this class.
     */
    private val UNRESOLVED_TOKEN = Regex("""\{\w*\}""")

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
