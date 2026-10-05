package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.privacy.MaskTokens
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData

/**
 * App-owned, rules-independent backstop for the CUSTOMER-PII redaction pledge
 * (#624/#632) — the recognized-frame analogue of [SensitiveTextMarkers], shared
 * by BOTH the screen ([firstUnredactedMarker]/[scrub], a UiNode tree) and the
 * notification ([firstUnredactedMarkerInNotif]/[scrubNotif], flat fields) capture
 * paths off one marker SSOT.
 *
 * The two SSOTs differ in both target and action:
 * - [SensitiveTextMarkers] guards the UNKNOWN path against the DASHER's OWN
 *   sensitive screens (banking/identity) by DROPPING the whole capture.
 * - This SSOT guards CUSTOMER PII by SCRUBBING only the offending node/field to
 *   `[redacted]` (the capture still ships, minus the leaked PII), on both frame
 *   classes: RECOGNIZED frames whose rule SHOULD have declared a `redact` block
 *   but didn't (#624/#632), and — since #806 — UNKNOWN screen, notification, AND
 *   click envelopes, customer-bearing surfaces no rule recognized (the "Deliver
 *   to "/"Pickup for " task-detail views) that would otherwise persist
 *   name/address/gate-code verbatim, since [SensitiveTextMarkers]
 *   (dasher-banking) correctly ignores customer content.
 *   The UNKNOWN-path scan is fail-toward-privacy: a benign marker-shaped string
 *   is scrubbed too (losing triage-useful text is the accepted cost).
 *
 * Why it's needed: the #598 `sha256`→`redact` compile gate only fires for a rule
 * that HASHES PII in its parse. A rule that ships raw customer text WITHOUT
 * hashing it (the live `dropoff_reminder` screen leak / the `trip_*_dropoff`
 * notification leak #631 found+fixed) stays silent — and once rules are
 * downloaded over a CDN (#192/#416/#419) a rule that simply omits redaction would
 * leak. This backstop closes that class on both sensor paths.
 *
 * Markers are customer-PII LABEL PREFIXES: text that starts with one carries a
 * customer name/address immediately after. The set spans BOTH platforms'
 * vocabulary (DoorDash screens/pushes AND Uber pushes) so the backstop is not
 * DoorDash-only — cross-platform marker DATA in one SSOT, not per-platform Kotlin
 * (#585 platform-coupling catalog: recognition vocabulary is data). Deliberate
 * exclusions (VET V2): `"Heading to "` and `"Your delivery from "` prefix STORE
 * names (merchants are not PII), and Uber's `"Going to "` is excluded for the
 * same reason — it prefixes a STORE on `trip_en_route_pickup` (`^Going to (?!\d)`)
 * and only an address on `trip_en_route_dropoff` (`^Going to \d`); a plain prefix
 * scan can't tell them apart, so that dropoff title relies on the rule-declared
 * `redact` as the primary control (store-FP risk, the "Heading to " precedent —
 * NOT because the title lacks a lead-in). DoorDash's `order_ready` is a true
 * no-marker residual: the customer name sits at the START (`"<name>'s order is
 * ready…"`), so no prefix precedes it. So is `pickup_arrival`'s `customer_name`
 * node (#526 D6a): the "Order for " label is a SEPARATE sibling node
 * (`customer_name_label`), so the `customer_name` node's text is the bare name with
 * no in-node prefix for this scan to catch — the rule-declared `redact` on that node
 * is the ONLY capture control there (and the #548 guard pins the parse to it). The
 * `CaptureBackstopCorpusTest` pins the set to ZERO false positives on the committed
 * (already-redacted) corpus.
 *
 * Two further candidates were VETTED AND REJECTED on 2026-08-09 (#994/#995) — CHROME-ambiguous
 * rather than store-ambiguous, but the same category as "Heading to " and found the same way
 * (add the marker, watch `CaptureBackstopCorpusTest` go red on a clean, PII-free corpus):
 * - `"Return "` — the DoorDash timeline renders a RETURN order's task line as
 *   `"Return <FirstName L> to <store>"` (#994), but the platform's own chrome renders the
 *   `on_dash_map` button `"Return to dash"` (6 committed fixtures trip it), and a bare
 *   case-insensitive `startsWith` cannot separate the customer line from the button.
 * - `"Focus on "` — the receipt-scan camera renders `"Focus on <FirstName L>"` (#995), but the
 *   shopping-accuracy tip on `performance_rate_detail` opens `"Focus on accuracy by remembering
 *   drinks and desserts…"`, so the same prefix also precedes app copy.
 * Both leaks are owned instead by the rule-declared `redact` — the primary control per the #806
 * doctrine — on `doordash.screen.timeline` and the new `doordash.screen.pickup_receipt_scan`.
 * The residual is the usual one: an UNKNOWN sibling of either surface (the still-unmodelled
 * return flow) is not covered by THIS scan until a rule recognizes it.
 *
 * ## The node-ID half ([ID_MARKERS], #910)
 *
 * That split-node shape is not an exception, it is the DOMINANT rendering: the 07-28
 * pull shipped a customer name AND a full street address on an UNKNOWN window whose
 * lead-in node read exactly `"Delivery for"` — no trailing space, no name — while the
 * name lived in a bare sibling. A prefix scan can never own that: the marker is in one
 * node and the PII is in another. So the UNKNOWN paths carry a SECOND, structural scan
 * keyed on the node's own view id ([unredactedIdMarker] / [firstUnredactedIdMarker] /
 * [scrubUnknown]) — an enumerated list of ids whose VALUE is customer PII by
 * construction, matched with the same `endsWith` suffix semantics the rules'
 * `hasIdSuffix` predicate uses (the `com.<vendor>:id/` prefix varies by package).
 * Like [MARKERS], the ids are cross-platform recognition DATA in one SSOT, not
 * per-platform Kotlin (principle 8).
 *
 * Scope is deliberately UNKNOWN-only (screen + click envelopes). On a RECOGNIZED frame
 * the rule's declared `redact` is the primary control and its decisions are deliberate
 * — #886 keeps `pickup_navigation`'s address raw because it is a MERCHANT address — so
 * an id scan there could fight the ruleset instead of backing it up. The text-marker
 * backstop above still covers the recognized path.
 *
 * Accepted cost — the `user_name` id is REUSED for non-customer values: it renders
 * the DASHER's own name in some DoorDash chrome, and on a pickup card it holds the
 * MERCHANT ("Pickup from <store>", which `pickup_pre_arrival` parses as `storeName`).
 * Scrubbing those on an UNKNOWN frame loses a little triage text and leaks nothing —
 * fail toward privacy, exactly as the text scan does. It costs nothing on the
 * RECOGNIZED path, which this scan deliberately does not touch, so no rule's
 * merchant-keeps-raw decision is affected. The #1058 additions carry the same accepted cost in
 * the other direction: an UNKNOWN frame loses its unit line and its delivery-instruction body from
 * the triage text, which is the whole point — those two nodes are exactly what the 08-28 alcohol
 * arrival card shipped verbatim while no rule recognized it.
 */
object CustomerTextMarkers {

    /** Customer-PII label prefixes. Case-insensitive `startsWith` match. */
    val MARKERS: List<String> = listOf(
        // DoorDash screen + notification vocabulary.
        "Deliver to ",
        "Pickup for ", // DoorDash pickup / "Current task" task-detail views -> customer name (#806).
        "Order for ",
        "Verify items for ",
        "Delivery for ",
        // #962, fielded 2026-07-30: the on-time rating's PER-ORDER timeline
        // drill-down (Ratings -> On-time rate -> an order) renders the delivery leg
        // as "Delivery to <customer first name>" beside "Pickup at <store>". The
        // list above already carried "Deliver to " and "Delivery for " but NOT this
        // third conjugation, so capture …UNKNOWN__2134b3 persisted a customer's
        // given name verbatim. Data-only addition to the SSOT; zero hits across the
        // whole committed corpus, so no recognized frame's kept text moves.
        "Delivery to ",
        "Message from ", // DoorDash in-app chat push title -> customer name.
        // Uber notification vocabulary (#632) — the keepPrefix lead-ins the
        // uber.json5 trip_at_dropoff redact declares.
        "Leave the order at ", // Uber trip_at_dropoff title -> dropoff ADDRESS.
        "Meet at door for ", // Uber trip_at_dropoff title -> customer name.
    )

    /**
     * View-id SUFFIXES whose node value IS customer PII, independent of any text
     * prefix (#910). Matched case-insensitively with `endsWith`, the same semantics
     * as the rules' `hasIdSuffix` predicate, because the `com.<vendor>:id/` prefix
     * varies by package.
     *
     * Scanned on the UNKNOWN screen/click envelope paths ONLY — see the class KDoc
     * for why the recognized path keeps the rule's `redact` as its primary control.
     * Every entry is a node whose ONLY content is a customer name or a customer
     * address line, so scrubbing it can never take app vocabulary with it: the
     * label siblings (`user_name_label`, `customer_name_label`) are deliberately
     * absent, so a replayed UNKNOWN frame keeps its shape for triage.
     *
     * The table carries, per suffix, the KIND of value the node holds (#1160 reviews EE1, LL1):
     * `NAME` — an id whose value is ONLY ever a person's name (`customer_name`, `order_cx_name`): seeds
     * its exact value and its letter runs; `ADDRESS` — a place (the
     * address lines, `arriving_at_title`, `address_subpremise_line`); `CONTENT` — a node that can hold
     * customer text but is also reused for app copy (the free-text instruction bodies;
     * `description_text_view`, which this file documents as generic DoorDash chrome); `EXACT` — a value
     * that may be PII or chrome (`tvTitle`, `tvLastMessage`): seeds its exact value only; `PERSON_OR_MERCHANT` —
     * an id REUSED for other people but never chrome (`user_name`, also the merchant's and the dasher's
     * name): exact value only for text, plus a whole-value id/class run when it reads as a person's name
     * (#1160 reviews PP6, SS1, XX3). The intake list
     * is DERIVED from this table ([ID_MARKER_SUFFIXES], review AL3): the intake-only ids (message bodies,
     * maneuver/road text, instruction bodies) are CONTENT rows with `runtimeScrub = NEVER`, so the two can
     * never disagree on a PII id (#1160 reviews NN2, PP6, UU3, AL3). The runtime backstop scrubs on EVERY suffix exactly as
     * before; only the census's frame-wide duplicate rule reads the kind: a NAME seeds its exact value
     * and its letter runs, an ADDRESS its exact value only (address vocabulary — "Road", "View", "San" —
     * is common English), CONTENT seeds nothing.
     */
    val ID_MARKER_TABLE: List<IdMarker> = listOf(
        // DoorDash multi-order pickup rows / pickup arrival card -> customer name.
        IdMarker("customer_name", IdentityKind.NAME, idProtect = true),
        // DoorDash drop-off + pickup contact blocks -> customer name (the node the
        // "Delivery for" label sibling names; #910 V5).
        // EXACT for the census (#1160 review SS1): the class KDoc records it is REUSED for the MERCHANT on
        // pickup cards and for the dasher's own name, so its letter runs must never seed ("The Home Depot"
        // → `the`; "Jack in the Box" → `in`/`box` would withhold chrome and, through the class/id check,
        // null `TextView`-class wrappers per store). EXACT still withholds an exact duplicate of a
        // customer's first name; a merchant name costs nothing (recognition never anchors on one).
        IdMarker("user_name", IdentityKind.PERSON_OR_MERCHANT, idProtect = true),
        // DoorDash address block -> street line and city/ST/ZIP line (#910 V1/V5).
        IdMarker("address_line_1", IdentityKind.ADDRESS),
        IdMarker("address_line_2", IdentityKind.ADDRESS),
        // DoorDash's OWN nav arrival banner title -> the destination, which on a dropoff leg is
        // the customer's full street address (#993, fielded 08-02: "<street>, Apt <n>, <City>,
        // <ST> <zip>, USA"). The rule-declared `redact` now covers it on every dropoff-phase rule
        // and both nav rules, but an UNKNOWN banner-bearing frame had no control at all. Same
        // accepted over-scrub as `user_name` above: on a PICKUP approach the banner names the
        // MERCHANT, so an UNKNOWN pickup-nav frame loses a merchant line from its triage text —
        // fail toward privacy, and the RECOGNIZED path is untouched by this scan, so #886's
        // deliberate "pickup_navigation keeps its merchant address raw" decision still stands.
        IdMarker("arriving_at_title", IdentityKind.ADDRESS),
        // #1058 (fielded 2026-08-28, four envelopes): the drop-off address block's SUBPREMISE
        // line — the customer's unit/apartment number, rendered fused with its label
        // ("Apt/Suite: <n>"). It is customer-locating PII by construction, `SnapshotRedactor`
        // already treats the id that way on the COMMIT path, and every dropoff rule's `redact`
        // declares it — but the UNKNOWN path had nothing, so an unrecognized variant of the
        // arrival card (the alcohol render, which carries no customer lead-in for the prefix
        // scan) persisted it verbatim.
        IdMarker("address_subpremise_line", IdentityKind.ADDRESS),
        // #1058, same four envelopes: the customer's own free-text delivery instructions. The
        // node holds nothing else — the "Hand it to recipient" label is a separate
        // `instructions_title` sibling — and the fielded value carried a door code. This is the
        // #803 class: customer-AUTHORED text that can hold a code, a unit, a floor or a name, so
        // the whole node goes. Both states of the same field are listed: the collapsed one is
        // what fielded, and the expanded one is the SAME customer text with the same content by
        // construction — the ruleset's own `redact` blocks have always declared the pair
        // together, and listing only the state that happened to field is the enumeration debt
        // #986 already paid for once.
        IdMarker("dasher_instruction_content_collapsed", IdentityKind.CONTENT),
        IdMarker("dasher_instruction_content_expanded", IdentityKind.CONTENT),
        // #1107 (fielded 2026-09-13, three envelopes): DoorDash 8.97.8's "Drop off steps"
        // wrapper renders the customer's free-text delivery instruction in a
        // `description_text_view` node — the fielded value carried a gate code — and nothing
        // recognized the surface, so it persisted verbatim. `doordash.screen.dropoff_step_instructions`
        // is the primary (recognized-path) control; this is the rules-INDEPENDENT half, so an
        // UNKNOWN render of that wrapper — or any future page reusing the id — is scrubbed with
        // no rule at all. ACCEPTED OVER-SCRUB: the id is GENERIC DoorDash chrome vocabulary and
        // legitimately carries app text elsewhere (the Dasher Rewards board's "Raise to 50%",
        // the GoPuff pickup-steps blurb, an unassign confirmation's "Required"). This scan runs
        // on UNKNOWN screen/click envelopes ONLY, so the cost is a line of triage text on an
        // unrecognized frame — the same fail-toward-privacy trade `arriving_at_title` already
        // documents for a pickup-leg merchant line — and no recognized frame's kept text moves.
        IdMarker("description_text_view", IdentityKind.CONTENT),
        // #1160 review NN2: GoPuff (DoorDash Drive) batch screens' per-order CUSTOMER name (#501) — until
        // now only in the intake list (`PiiShapes.PII_ID_SUFFIXES`), so the runtime UNKNOWN scrub missed
        // it and the census could hash its frame duplicates. Promoted so the two SSOTs agree on "what is
        // a customer-name id"; the runtime scrub widens by this one suffix (fail toward privacy).
        IdMarker("order_cx_name", IdentityKind.NAME, idProtect = true),
        // #1160 reviews PP6, SS9, TT1: the chat list's header (`tvTitle` — the customer's name on a chat row,
        // but a generic id other surfaces use for a sheet title such as "Pick up order") and last-message
        // preview (`tvLastMessage` — ALWAYS the customer's own text, never chrome). EXACT for the census:
        // withheld on their own field and seeding their exact value, never runs. Runtime UNKNOWN scrub:
        // ALWAYS for both (review ZZ1 — fail closed; no value-shape gate can tell "李明", "محمد" or "de la
        // Cruz" from chrome). ACCEPTED RECALL COST (ADR-0011 residual 11): an UNKNOWN sheet title under
        // `tvTitle` ("Pick up order") loses that line in the X-Ray; the census skeleton keeps the id and
        // structure.
        IdMarker("tvTitle", IdentityKind.EXACT, idProtect = true),
        IdMarker("tvLastMessage", IdentityKind.EXACT),
        // #919 (fielded 2026-07-29 and 2026-09-13/14, seven UNKNOWN click envelopes): the chat COMPOSE box —
        // the dasher's in-progress message, naming the customer's order contents and whatever else they typed.
        // Was intake-only (NEVER); promoted to the runtime UNKNOWN scrub so the known instance is masked by
        // id even when the widget stops reporting editable semantics. The CLASS half is [unredactedInputNode].
        // CONTENT: seeds nothing for the census (free text, not an identity).
        IdMarker("message_input", IdentityKind.CONTENT),
        // #1160 review AL3: the INTAKE-ONLY ids (formerly `PiiShapes.PII_ID_SUFFIXES`, a second hand list with
        // exact-last-segment semantics) are rows here now — ONE list, ONE match semantics (`endsWith`,
        // ignoring case: a widening toward privacy on the commit path). `runtimeScrub = NEVER`: the runtime
        // UNKNOWN scrub keeps its deliberate set; the census withholds their own field and seeds nothing
        // (CONTENT). Kept LAST so a first-match lookup never shadows a runtime row above.
        // The embedded Google-Nav maneuver cluster (#886): a number-LESS destination street carries no
        // digits, so no address shape catches it — ids are the only handle.
        IdMarker("primaryManeuverText", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        IdMarker("subManeuverText", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        IdMarker("secondaryManeuverText", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        IdMarker("roadNameView", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        // Chat bodies and inputs.
        IdMarker("message_self_message", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        IdMarker("message_other_message", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        IdMarker("chat_input_text_field", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        // Bottom-sheet address/instruction blocks (the address lines also end in an ADDRESS row above).
        IdMarker("bottom_sheet_address_line_1", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        IdMarker("bottom_sheet_address_line_2", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        IdMarker("bottom_sheet_instructions", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        // Instruction bodies.
        IdMarker("step_description", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        IdMarker("instructions_list", IdentityKind.CONTENT, RuntimeScrub.NEVER),
        IdMarker("instruction_text", IdentityKind.CONTENT, RuntimeScrub.NEVER),
    )

    /** Every table suffix — the commit-path intake list, DERIVED (review AL3; `SnapshotRedactor`). */
    val ID_MARKER_SUFFIXES: Set<String> = ID_MARKER_TABLE.map { it.suffix }.toSet()

    /**
     * One [ID_MARKER_TABLE] row. [runtimeScrub] (#1160 reviews SS9, TT1, ZZ1) says whether the runtime UNKNOWN
     * scrub covers the suffix: [RuntimeScrub.ALWAYS] (the [ID_MARKERS] projection) or [RuntimeScrub.NEVER]
     * (census-only). There is no value-dependent mode (ZZ1): the runtime path fails closed on the id alone.
     * [idProtect] (#1160 review AB4): the census adds the row's WHOLE value as an id-only run — true only
     * where the value can plausibly be a name a test tag embeds (`customer_name`, `order_cx_name`,
     * `user_name`, `tvTitle`); a chat reply (`tvLastMessage` "Ok") must not null `ok_button`, and an
     * address or content row never protects an id by its whole value.
     */
    data class IdMarker(
        val suffix: String,
        val kind: IdentityKind,
        val runtimeScrub: RuntimeScrub = RuntimeScrub.ALWAYS,
        val idProtect: Boolean = false,
    )

    /** Whether the runtime UNKNOWN id scrub applies to an [IdMarker] (#1160 reviews TT1, ZZ1). */
    enum class RuntimeScrub {
        ALWAYS,
        NEVER,
    }

    /**
     * What an [IdMarker]'s node value IS (#1160 review LL1), and — the ONE owner the census builder and its
     * test-side mirrors both read (review AB1) — what it seeds on the frame: [seedsExactValue] (its rendered
     * text/desc canonical value), its letter runs of at least [minRunLetters] letters when the value has at
     * most [maxRunSeedTokens] whitespace tokens and — with [personNameShapeOnly] — every token reads as a
     * person's name ([seedsRunsFrom], reviews AD2, AF1), and whether those runs also guard CLASS names
     * ([runsGuardClasses], review AC2 — a customer's first name as a class segment is the leak vector).
     */
    enum class IdentityKind(
        val seedsExactValue: Boolean,
        val maxRunSeedTokens: Int,
        val minRunLetters: Int,
        val personNameShapeOnly: Boolean,
        val runsGuardClasses: Boolean,
    ) {
        /** A person's name: runs (≥ 2 letters — "Li", "Jo") from any value. */
        NAME(seedsExactValue = true, maxRunSeedTokens = Int.MAX_VALUE, minRunLetters = 2, personNameShapeOnly = false, runsGuardClasses = true),

        /** A place (an address line, a destination, a unit): exact only (address vocabulary is common English). */
        ADDRESS(seedsExactValue = true, maxRunSeedTokens = 0, minRunLetters = 0, personNameShapeOnly = false, runsGuardClasses = false),

        /** Customer-bearing content that is also reused for app copy: seeds nothing. */
        CONTENT(seedsExactValue = false, maxRunSeedTokens = 0, minRunLetters = 0, personNameShapeOnly = false, runsGuardClasses = false),

        /**
         * A value that may be PII or chrome (a chat header is a name, a sheet title is "Pick up order"):
         * withheld on its own field and seeding its EXACT value only — no letter runs (#1160 review PP6). For
         * the id check an `idProtect` row adds its whole value as one run (ZZ3).
         */
        EXACT(seedsExactValue = true, maxRunSeedTokens = 0, minRunLetters = 0, personNameShapeOnly = false, runsGuardClasses = false),

        /**
         * A value that is a person OR a merchant, never chrome (`user_name`, #1160 reviews XX3, ZZ3, AD2, AF1):
         * it seeds letter runs only when it reads as a PERSON'S NAME — at most two tokens, each a letter-only
         * Capitalized word of ≥ 2 letters (an apostrophe allowed; no hyphen or digit), optionally followed by
         * ONE trailing capital initial ("Riley S", "Mary Jo S.", review AG3) — and only runs of ≥ 3 letters, so "Text Riley" beside "Riley" is withheld while "In-N-Out
         * Burger", "Sonic Drive-In" and "7-Eleven" seed their exact value only (never `in`/`out`, which
         * would withhold "Sign in"/"Cash out" and fork `sign_in_button` per merchant). A 2-letter first name
         * keeps its exact seed. A name-shaped merchant ("Wing Stop") seeds runs — accepted, residual risk 9.
         */
        PERSON_OR_MERCHANT(seedsExactValue = true, maxRunSeedTokens = 2, minRunLetters = 3, personNameShapeOnly = true, runsGuardClasses = false),
        ;

        /** Does a canonical [value] of this kind seed letter runs (reviews AD2, AF1)? Canonical = single spaces. */
        fun seedsRunsFrom(value: String): Boolean {
            if (maxRunSeedTokens <= 0) return false
            // AG3: a person's name may end in a single capital initial ("Riley S", "Riley S.", "Mary Jo S"); it
            // is not counted as a name token, and the run floor keeps it from seeding.
            val tokens = value.split(' ').let { t ->
                if (personNameShapeOnly && t.size > 1 && isInitial(t.last())) t.dropLast(1) else t
            }
            if (maxRunSeedTokens != Int.MAX_VALUE && tokens.size > maxRunSeedTokens) return false
            return !personNameShapeOnly || tokens.all { isPersonNameToken(it) }
        }

        private fun isInitial(token: String): Boolean {
            val letter = token.removeSuffix(".")
            return letter.codePointCount(0, letter.length) == 1 && Character.isUpperCase(letter.codePointAt(0))
        }

        private fun isPersonNameToken(token: String): Boolean {
            if (token.isEmpty() || !Character.isUpperCase(token.codePointAt(0))) return false
            var letters = 0
            var i = 0
            while (i < token.length) {
                val cp = token.codePointAt(i)
                when {
                    Character.isLetter(cp) -> letters++
                    cp == '\''.code || cp == 0x2019 -> Unit
                    else -> return false
                }
                i += Character.charCount(cp)
            }
            return letters >= 2
        }
    }

    /**
     * The ALWAYS runtime-scrub suffix list — the [ID_MARKER_TABLE] rows with [RuntimeScrub.ALWAYS], in table order
     * (pinned). The table is the one owner of "what kind of value an id carries"; this is its projection.
     */
    val ID_MARKERS: List<String> = ID_MARKER_TABLE.filter { it.runtimeScrub == RuntimeScrub.ALWAYS }.map { it.suffix }

    /** Substring that classifies a node's text as already-redacted (VET V1). */
    private const val REDACTED_MARK = MaskTokens.REDACTED_PREFIX

    /**
     * The first marker [text] carries UN-redacted, or null when clean. A node
     * whose text contains "[redacted" anywhere is treated as already-redacted and
     * skipped (VET V1) — otherwise a rule's OWN redact output ("Deliver to door
     * of [redacted:…]") would trip the "Deliver to " marker and re-scrub.
     */
    fun unredactedMarker(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        if (text.contains(REDACTED_MARK)) return null
        return MARKERS.firstOrNull { text.startsWith(it, ignoreCase = true) }
    }

    /**
     * The first un-redacted customer marker anywhere in [tree] — across EVERY
     * serialized string field of every node ([UiNode.scrubbableStrings], #835:
     * text, contentDescription AND stateDescription) — or null when clean. Cheap
     * short-circuiting scan run on every recognized capture; the [scrub] copy is
     * built only on a hit.
     *
     * Walks the tree itself rather than the memoized [UiNode.allText] because
     * that list is the RECOGNITION SSOT and deliberately excludes
     * `stateDescription` — reading it here would leave the field this scan is
     * supposed to cover invisible.
     */
    fun firstUnredactedMarker(tree: UiNode): String? =
        tree.scrubbableStrings().firstNotNullOfOrNull { (_, value) -> unredactedMarker(value) }
            ?: tree.children.firstNotNullOfOrNull { firstUnredactedMarker(it) }

    /**
     * Return a copy of [tree] with every node's marker-carrying string field
     * scrubbed to [CompiledRedact.REDACTED] — per field, across the
     * [UiNode.scrubbableStrings] SSOT (#835). Call only after
     * [firstUnredactedMarker] returned non-null, so the tree copy never happens
     * on the clean path.
     */
    fun scrub(tree: UiNode): UiNode = tree
        .mapScrubbableStrings { if (unredactedMarker(it) != null) CompiledRedact.REDACTED else it }
        .copy(children = tree.children.map { scrub(it) })

    // --- Node-id path, UNKNOWN envelopes only (#910) --------------------------

    /**
     * The runtime-scrub marker suffix [id] ends with, or null — the UNKNOWN-envelope scan's predicate
     * (#1160 reviews SS9, TT1, ZZ1): an ALWAYS row matches on the id alone, a NEVER row never. The census
     * filter (ADR-0011 §2 step 1) calls [idMarkerFor] over the whole table.
     */
    fun idMarkerSuffix(id: String?): String? {
        // UU4: the runtime mode is applied BEFORE the first match, so a NEVER row can never switch an
        // overlapping ALWAYS row's scrub off.
        if (id.isNullOrEmpty()) return null
        return ID_MARKER_TABLE.firstOrNull { row ->
            row.runtimeScrub == RuntimeScrub.ALWAYS && id.endsWith(row.suffix, ignoreCase = true)
        }?.suffix
    }

    /**
     * The [ID_MARKER_TABLE] row [id] ends with (case-insensitive SUFFIX match on the FULL resource id —
     * the rules' `hasIdSuffix` semantics), or null. The ONE owner of that comparison (#1145).
     */
    fun idMarkerFor(id: String?): IdMarker? {
        if (id.isNullOrEmpty()) return null
        return ID_MARKER_TABLE.firstOrNull { id.endsWith(it.suffix, ignoreCase = true) }
    }

    /**
     * The [ID_MARKERS] suffix [node]'s own view id carries while the node still
     * holds UN-redacted text/description, or null. A node whose every value is
     * already masked (a rule's own `[redacted:…]` output, or an empty node) returns
     * null — same already-redacted skip as [unredactedMarker], so this never
     * re-scrubs a mask.
     */
    fun unredactedIdMarker(node: UiNode): String? {
        val marker = idMarkerSuffix(node.viewIdResourceName) ?: return null
        // #835: every serialized string field counts as "still carrying raw" — a
        // customer-PII node whose only remaining value is its `stateDescription`
        // must still be scrubbed. (XX7: one pass over the fields.)
        val carriesRaw = node.scrubbableStrings()
            .any { (_, value) -> !value.isNullOrEmpty() && !value.contains(REDACTED_MARK) }
        return if (carriesRaw) marker else null
    }

    /**
     * #919 — the UNKNOWN-envelope INPUT scan: [node] is a text input ([UiNode.isTextInput]) still carrying
     * an un-redacted string, or null. User-authored free text (a chat draft, a search box, a note) is never
     * corpus material, so the node is masked WHOLE — every [UiNode.scrubbableStrings] field, the hint and
     * error included (ACCEPTED RECALL COST: an UNKNOWN frame's "Type a message…" placeholder is lost to
     * triage; the id, class and structure stay). Returns the node's class name (log-safe: widget
     * vocabulary, never PII) for the WARN. Same already-redacted/empty skip as [unredactedIdMarker].
     * UNKNOWN envelopes only — a recognized frame keeps its rule's deliberate decisions.
     */
    fun unredactedInputNode(node: UiNode): String? {
        if (!node.isTextInput) return null
        val carriesRaw = node.scrubbableStrings()
            .any { (_, value) -> !value.isNullOrEmpty() && !value.contains(REDACTED_MARK) }
        return if (carriesRaw) (node.className ?: "editable") else null
    }

    /** The first [unredactedInputNode] hit anywhere in [tree], or null when clean (structural scan, no copy). */
    fun firstUnredactedInputNode(tree: UiNode): String? =
        unredactedInputNode(tree) ?: tree.children.firstNotNullOfOrNull { firstUnredactedInputNode(it) }

    /**
     * The first [ID_MARKERS] hit anywhere in [tree], or null when clean. Cheap
     * structural scan (no text comparison); the [scrubUnknown] copy is built only
     * on a hit.
     */
    fun firstUnredactedIdMarker(tree: UiNode): String? =
        unredactedIdMarker(tree) ?: tree.children.firstNotNullOfOrNull { firstUnredactedIdMarker(it) }

    /**
     * The UNKNOWN-envelope scrub: a copy of [tree] with every node scrubbed to
     * [CompiledRedact.REDACTED] that carries an un-redacted text marker ([unredactedMarker]),
     * a customer-PII view id ([unredactedIdMarker]), OR text input ([unredactedInputNode], #919).
     * One traversal for all three scans, so an UNKNOWN frame is never rebuilt twice. Call
     * only after one of the three scans returned non-null.
     */
    fun scrubUnknown(tree: UiNode): UiNode {
        val wholeNode = unredactedIdMarker(tree) != null || unredactedInputNode(tree) != null
        // An id hit OR a text-input node (#919) scrubs the node WHOLE (every field of the
        // [UiNode.scrubbableStrings] SSOT, #835); otherwise each field is judged
        // on its own text marker.
        return tree
            .mapScrubbableStrings {
                // #1147: a null field stays null (nothing to leak; no phantom keys on the envelope).
                if (it != null && (wholeNode || unredactedMarker(it) != null)) CompiledRedact.REDACTED else it
            }
            .copy(children = tree.children.map { scrubUnknown(it) })
    }

    // --- Notification path (#632) --------------------------------------------
    // Notification captures are FLAT fields, not a UiNode tree — so a hit scrubs
    // the WHOLE offending field (there are no children to isolate). Same
    // already-redacted skip (VET V1) and same marker SSOT as the screen path.
    // Text fields iterate the #666 RawNotificationData.textFields() SSOT rather
    // than hand-listing title/text/bigText/tickerText/subText.

    /**
     * The first un-redacted customer marker across [raw]'s flat text fields
     * (title/text/bigText/tickerText/subText, via [RawNotificationData.textFields])
     * OR its `actionLabels` (#666 item 2 — a push action button label is
     * serialized into the envelope same as the text fields and was previously
     * excluded from this scan), or null when clean. Cheap scan run on every
     * recognized notification capture; the [scrubNotif] copy is built only on
     * a hit.
     */
    fun firstUnredactedMarkerInNotif(raw: RawNotificationData): String? =
        raw.textFields().firstNotNullOfOrNull { (_, value) -> unredactedMarker(value) }
            ?: raw.actionLabels.firstNotNullOfOrNull { unredactedMarker(it) }

    /**
     * A copy of [raw] with every flat text field AND every action label carrying
     * an un-redacted customer marker scrubbed WHOLE to [CompiledRedact.REDACTED].
     * Call only after [firstUnredactedMarkerInNotif] returned non-null.
     * `actionLabels` is intentionally scrubbed here (not folded into
     * [RawNotificationData.textFields]) since it isn't part of
     * [RawNotificationData.toFullString]/`contentHash` — the dedup identity must
     * stay stable regardless of action-label scrubbing.
     */
    fun scrubNotif(raw: RawNotificationData): RawNotificationData = raw
        .withTextFields(raw.textFields().associate { (field, value) -> field to scrubField(value) })
        .copy(actionLabels = raw.actionLabels.map { scrubActionLabel(it) })

    private fun scrubField(field: String?): String? =
        if (unredactedMarker(field) != null) CompiledRedact.REDACTED else field

    private fun scrubActionLabel(label: String): String =
        if (unredactedMarker(label) != null) CompiledRedact.REDACTED else label
}
