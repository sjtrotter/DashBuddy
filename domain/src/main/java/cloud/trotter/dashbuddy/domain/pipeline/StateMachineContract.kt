package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.NotificationIntent

/**
 * The contract the state machine advertises to the rule loader. Rules that
 * emit flow/mode values outside [SUPPORTED_FLOWS]/[SUPPORTED_MODES] are
 * rejected at load time. Rules that use effect verbs outside
 * [SUPPORTED_VERBS] or transition triggers outside [SUPPORTED_TRIGGERS]
 * are likewise rejected.
 *
 * [REQUIRED_FIELDS_BY_FLOW] and [EFFECT_INTENTS] extend this from "which vocabulary tokens are
 * legal" to "which parse fields a rule that uses a token must actually produce" (#762 D6/D1) —
 * the prerequisite-parsed-fields guard ADR-0002 §"What stays in Kotlin" always reserved for Kotlin.
 * They are enforced at compile time by `RuleCompiler`; see each field's doc.
 */
object StateMachineContract {
    const val API_VERSION_MAJOR = 1
    const val API_VERSION_MINOR = 0

    val SUPPORTED_FLOWS: Set<String> = Flow.entries.map { it.wire }.toSet()
    val SUPPORTED_MODES: Set<String> = Mode.entries.map { it.wire }.toSet()
    val SUPPORTED_VERBS: Set<String> = EffectVerb.entries.map { it.wire }.toSet()
    val SUPPORTED_TRIGGERS: Set<String> = TransitionTrigger.entries.map { it.wire }.toSet()

    /**
     * Per-flow **required parse fields** for `task:*` (and any other) flows (#762 D6). A SCREEN rule
     * (or branch, post parse-inheritance) that drives a listed [Flow] wire value must declare every
     * field named here in its `parse.fields` — the compiler enforces this even when the rule has NO
     * `parse` block at all (a missing block ⇒ empty declared set ⇒ the required fields are missing).
     * This is the field-level analog of `ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE`
     * (`:core:pipeline`), but keyed on the state-machine flow rather than the parse shape,
     * so a flow whose lifecycle handling depends on a field can't be driven by a rule that never
     * parses it.
     *
     * **Deliberately empty today, and that is the empirically correct content — not a placeholder.**
     * The map must "encode what's true, not aspirations": a field may be required for a flow ONLY if
     * **every** shipped rule that declares that flow already parses it. An audit of the fielded
     * rulesets (2026-07-14) found that **every** `task:*` flow has at least one legitimately
     * parse-less rule that declares it, so no field can be mandated without either breaking a
     * shipped rule or editing fielded rules to satisfy an aspiration (both forbidden by the #762
     * spec — prefer relaxing the set):
     *  - `task:pickup:navigation` — `doordash.screen.pickup_pre_arrival_multi` (a multi-order
     *    confirm-at-store screen with no single store name to parse) declares it with no `parse`.
     *  - `task:pickup:arrived` — many parse-less rules, incl. the GoPuff bin-scan branch of
     *    `doordash.screen.pickup_steps`, which is **deliberately** parse-less (no PII) and emits this
     *    flow; mandating a field here would break the #501 warehouse-batch anchor.
     *  - `task:dropoff:navigation` — `doordash.screen.dropoff_alcohol_id_intro` declares it with no
     *    `parse` (an instructional screen).
     *  - `task:dropoff:arrived` — many parse-less handoff/photo/pin screens.
     *  - `idle` — many parse-less "you're online" screens.
     *  - `offer:presented` / `post:task` / `session:ended` are already covered by the parse-**shape**
     *    contract (`REQUIRED_FIELDS_BY_SHAPE` for `offer`/`post_task`/`session_ended`), so a flow
     *    entry would be redundant.
     *
     * The **mechanism** is the durable deliverable: the day a flow gains a field that every rule
     * driving it parses, adding one line here makes the guard enforce it (with the no-parse-block
     * case covered). An empty set for a flow is fine; an empty map means no flow currently supports
     * a universally-satisfied requirement.
     */
    val REQUIRED_FIELDS_BY_FLOW: Map<String, Set<String>> = emptyMap()

    /**
     * **Effect-bearing** notification intents → the parse fields their effect consumer requires
     * (#762 D1). A NOTIFICATION rule (or branch) whose resolved `intent` is a key here must declare
     * every listed field in its `parse.fields`, or the compiler rejects it — closing the drift class
     * where a rule is *recognized* but then silently dropped because the Kotlin effect guard finds a
     * null field it needs (the live `uber.notification.tip_received` shape: recognized, parse block
     * with no fields, so `amount`/`storeName`/`deliveredAt` are all null and
     * `AppEffect.ProcessTipNotification` never fires).
     *
     * An intent **not** in this map is **informational by construction** and is always allowed with
     * no fields — that asymmetry is the OTA/data-driven-recognition contract ([NotificationIntent]).
     * The keys are the [NotificationIntent] SSOT constants; the field sets mirror exactly what
     * `EffectMap.diffNotification` reads before firing the effect.
     */
    val EFFECT_INTENTS: Map<String, Set<String>> = mapOf(
        NotificationIntent.ADDITIONAL_TIP to setOf("amount", "storeName", "deliveredAt"),
    )
}
