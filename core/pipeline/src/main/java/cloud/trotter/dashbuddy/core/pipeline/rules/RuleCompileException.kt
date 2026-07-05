package cloud.trotter.dashbuddy.core.pipeline.rules

/**
 * Thrown when a rule JSON cannot be compiled into a valid lambda.
 *
 * [failClosed] marks a rejection that must reject the WHOLE file rather than be
 * isolated to the single offending rule by the per-rule fault-isolation loop
 * (#293 item 4). Set it for **security / Pledge / DoS** controls — an anti-DoS
 * cap (#419), a customer-PII / capture-redaction guard (#598/#620/#624), an
 * actuation reject (#425), or a compile-recursion/depth bound (#590) — where a
 * silent per-rule skip would weaken a documented fail-closed control. The
 * default (`false`) is a genuine *authoring* malformation (a bad predicate, an
 * unknown key, a mistyped field, a bad platform prefix): those degrade one
 * recognition surface to UNKNOWN (→ scrubbed — safe) and so isolate to a
 * skip+WARN. Sensitive-layer rules ALSO always reject whole-file, decided
 * separately from the rule id (see `RuleCompiler.rawRuleIsSensitive`).
 */
class RuleCompileException(
    message: String,
    cause: Throwable? = null,
    val failClosed: Boolean = false,
) : Exception(message, cause)
