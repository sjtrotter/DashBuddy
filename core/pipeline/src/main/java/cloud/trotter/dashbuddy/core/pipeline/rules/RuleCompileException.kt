package cloud.trotter.dashbuddy.core.pipeline.rules

/**
 * Thrown when a rule JSON cannot be compiled into a valid lambda.
 *
 * [isolable] — per-rule fault isolation (#293 item 4) is **OPT-IN**. The
 * default (`false`) is the conservative WHOLE-FILE reject (the pre-#293
 * status quo), so a FUTURE security-relevant compile check that forgets to
 * think about isolation can never be silently downgraded to a per-rule skip —
 * the failure mode of a forgotten tag is a loud over-reject, not a quiet
 * pledge weakening (Principle 6: do not trust call-site discipline). Tag
 * `isolable = true` ONLY on rule-authoring-level validations (a bad predicate,
 * an unknown key, a mistyped field, a bad platform prefix, a bad parse shape)
 * whose worst case is ONE recognition surface degrading to UNKNOWN (→ capture
 * scrub — safe). Security / Pledge / DoS controls (#419 caps, #598/#620/#624
 * capture-PII guards, #425 actuation rejects, #590 depth bounds) stay
 * untagged and reject the whole file by default. Even an isolable error still
 * rejects the whole file when the offending rule is sensitive-layer (see
 * `RuleCompiler.rawRuleIsSensitive` — the belt on top of this default).
 */
class RuleCompileException(
    message: String,
    cause: Throwable? = null,
    val isolable: Boolean = false,
) : Exception(message, cause)
