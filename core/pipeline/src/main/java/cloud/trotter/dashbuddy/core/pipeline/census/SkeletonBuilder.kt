package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.census.contract.CaseFold
import cloud.trotter.dashbuddy.domain.census.contract.CensusFingerprint
import cloud.trotter.dashbuddy.domain.census.contract.ClassNameGrammar
import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.KindClassifier
import cloud.trotter.dashbuddy.domain.census.contract.ResourceIdGrammar
import cloud.trotter.dashbuddy.domain.census.contract.SkeletonSchema
import cloud.trotter.dashbuddy.domain.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.census.contract.UiSkeletonDto
import cloud.trotter.dashbuddy.domain.census.contract.UiSkeletonNodeDto
import cloud.trotter.dashbuddy.domain.census.contract.WireStrings
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Builds the census SKELETON of an admitted UNKNOWN frame (ADR-0011 §1–§3, §8; #1145 M1a).
 *
 * PURE: no I/O, no logging, no wiring. #1146 attaches it to the UNKNOWN screen branch of
 * `AccessibilityPipeline.output()`; until then nothing calls it at runtime and nothing leaves the
 * phone. It consumes the RAW admitted `UiNode` tree plus the window title — never the capture DTO,
 * because release binds `NoOpCaptureBus` and the capture-side scans never run there.
 *
 * What it guarantees, by construction:
 * - **No plaintext leaves.** The output type ([UiSkeletonDto]) has no text slot; each text field
 *   becomes a [TextSlot] (`kind` + optional `h`).
 * - **The dasher's sensitive frames are never described.** A [SensitiveTextMarkers] hit on the raw
 *   tree or on the window title yields NO skeleton ([Refusal.SENSITIVE_FRAME] / [Refusal.SENSITIVE_TITLE]),
 *   scanned HERE regardless of the capture bus.
 * - **Only chrome-likely tokens are hashed.** A field is withheld by its own node's PII id (step 1,
 *   [IdClass]) or by a value-judging step ([withholdingStep], steps 2–8), judged on the CANONICAL form
 *   ([CensusHash.canonical]) and — when the raw trimmed value is itself within the cap — also on the raw
 *   form; either hit withholds. Only a `words:1..8` survivor is hashed, on its canonical form
 *   ([CensusHash], fail-closed to `withheld`). The FRAME-LEVEL duplicate rule then withholds (a) any field
 *   whose canonical value a value-judging step or a NAME/ADDRESS identity id caught anywhere in the
 *   frame, and (b) any field — and any node's id name part or class, split also at camelCase — that
 *   CONTAINS a letter run (≥ 2 letters, case-folded) of a NAME identity id's rendered text/desc, so a
 *   customer name the id marks on one node is not hashed on a node that repeats or embeds it ("Adam's
 *   order", `chipAdam`). Masks never seed; an ADDRESS seeds its exact value only.
 * - **Inert.** [outcome] never throws (only coroutine cancellation escapes): any failure is
 *   [Refusal.BUILD_FAILED].
 * - **Bounded.** The 40-character cap precedes the grammar and every pattern; an item over
 *   [SkeletonSchema.MAX_ITEM_BYTES] yields no skeleton ([Refusal.OVERSIZE]).
 */
object SkeletonBuilder {

    /** The §2 filter revision this builder implements; rides every item as `filterRev`. */
    const val FILTER_REV: Int = 1

    /** ADR-0011 §2 step 2: a value longer than this is withheld before any grammar or pattern runs. */
    const val MAX_TOKEN_LENGTH: Int = 40

    /** Why no skeleton was produced — the counter reasons #1146 will publish. Never carries text. */
    enum class Refusal {
        /** A [SensitiveTextMarkers] hit anywhere in the tree. */
        SENSITIVE_FRAME,

        /** A [SensitiveTextMarkers] hit on the window title. */
        SENSITIVE_TITLE,

        /** The serialized item exceeds [SkeletonSchema.MAX_ITEM_BYTES]. */
        OVERSIZE,

        /** The cluster fingerprint digest failed (fail closed). */
        FINGERPRINT_FAILED,

        /**
         * The envelope failed the contract's validation. Defensive: with the typed API (review GG6) and
         * the sanitized stamps nothing reachable produces it today.
         */
        INVALID_ENVELOPE,

        /** A node failed the contract's validation (e.g. a class/id carrying U+0000, ADR §8). */
        INVALID_TREE,

        /**
         * Anything else went wrong building the item — e.g. a `StackOverflowError` on a pathological
         * deep tree (#1160 review AA4). The census is a diagnostic: it must be INERT to the pipeline
         * that hosts it (the #909 silent-death class), so nothing but coroutine cancellation escapes.
         */
        BUILD_FAILED,
    }

    /** The builder's result: a skeleton, or the reason there is none. */
    sealed interface Outcome {
        /** [json] is the canonical serialization [itemBytes] measured — the caller never re-serializes. */
        data class Built(val skeleton: UiSkeletonDto, val json: String, val itemBytes: Int) : Outcome
        data class Refused(val reason: Refusal) : Outcome
    }

    /**
     * The VALUE-judging §2 steps that WITHHOLD (emit the constant `withheld`), in ADR order. Step 1 (the
     * node's own PII id) is not a value judgement and lives only in [IdClass] (review GG4); step 6 (only
     * `words:N` may be hashed) refuses the HASH and continues, it does not withhold.
     */
    enum class FilterStep(val adrStep: Int) {
        /** The CANONICAL value is longer than [MAX_TOKEN_LENGTH]. */
        LENGTH_CAP(2),

        /** `CustomerTextMarkers.unredactedMarker` hits. */
        CUSTOMER_MARKER(3),

        /** `PiiShapes.customerLeadIn` hits (incl. the gated prefixes). */
        LEAD_IN(4),

        /** The value contains a mask literal anywhere (`PiiShapes.containsMask`). */
        MASK(5),

        /**
         * The id-less name shape: the anchored redact-side pattern on the whole value, or the
         * boundary-delimited embedded one anywhere (case-sensitive initial) — `PiiShapes.hasNameShape`.
         */
        NAME_SHAPE(7),

        /** Any other promoted `PiiShapes` value shape, in its own match mode. */
        PII_SHAPE(8),
    }

    /** [outcome] reduced to the skeleton, or null — the ADR's `build(...)`. */
    fun build(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
    ): UiSkeletonDto? = (outcome(tree, windowTitle, meta, platform, day) as? Outcome.Built)?.skeleton

    /**
     * Build the skeleton of [tree] (+ [windowTitle]) for [platform] on [day], stamping the five
     * [ReplayMetadata] version fields. Typed at this API (review GG6); the contract DTO carries the wire
     * forms (`Platform.wire`, `yyyy-MM-dd`). Never throws; every refusal is a reason, never text.
     */
    fun outcome(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
    ): Outcome = outcome(tree, windowTitle, meta, platform, day, sensitive = null)

    /**
     * [outcome] with the tree's sensitive-marker verdict the caller ALREADY computed (review PP8): on the
     * debug path `CaptureWriter` has scanned the same raw tree, and #1146's publisher passes that verdict
     * here so the full-tree scan is not run twice. `null` (release — `NoOpCaptureBus`, no capture-side
     * scan — or any caller without one) makes the builder scan itself. The window title is always scanned
     * here (it is one short string).
     */
    fun outcome(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
        sensitive: SensitiveVerdict?,
    ): Outcome = outcome(tree, windowTitle, meta, platform, day, FrameFilter(::withholdingStep), sensitive)

    /** A tree's sensitive-marker verdict, as `SensitiveTextMarkers.findMarker` reports it (review PP8). */
    sealed interface SensitiveVerdict {
        data object Clear : SensitiveVerdict

        /** A marker hit. [markerId] is the marker's log id — never frame text. */
        data class Hit(val markerId: String) : SensitiveVerdict

        /** The scan itself failed (its fail-closed sentinel) — a build failure, not a sensitive frame. */
        data object ScanFailed : SensitiveVerdict

        companion object {
            /** Map a `SensitiveTextMarkers.findMarker` result to a verdict. */
            fun of(marker: String?): SensitiveVerdict = when (marker) {
                null -> Clear
                SensitiveTextMarkers.NORMALIZE_FAILED -> ScanFailed
                else -> Hit(marker)
            }
        }
    }

    /** [outcome] over an explicit [frame] — internal so a test can inject a defect (review II5). */
    internal fun outcome(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
        frame: FrameFilter,
        sensitive: SensitiveVerdict? = null,
    ): Outcome = try {
        buildOutcome(tree, windowTitle, meta, platform, day, frame, sensitive)
    } catch (e: CancellationException) {
        throw e
    } catch (_: InvalidTree) {
        Outcome.Refused(Refusal.INVALID_TREE)
    } catch (_: Throwable) {
        // Anything else — including a builder-defect `require` (TextSlot, ShapeKind, DTO init) — is a
        // BUILD failure, never mis-reported as a bad third-party tree (review II5).
        Outcome.Refused(Refusal.BUILD_FAILED)
    }

    /**
     * DIAGNOSTIC SEAM — never call in production (review OO2): [outcome] with the FRAME-LEVEL duplicate /
     * containment rule switched off (per-field filtering unchanged), so the corpus test can pin exactly
     * which slots the frame-level rule flips to `withheld` and prove no chrome is suppressed by it.
     */
    fun outcomeWithoutFrameRule(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
    ): Outcome = outcome(tree, windowTitle, meta, platform, day, FrameFilter(::withholdingStep, frameLevel = false))

    /** Thrown ONLY by the raw-tree validation in [FrameFilter.scan]: the input, not the builder, is bad. */
    internal class InvalidTree(message: String) : Exception(message)

    private fun buildOutcome(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
        frame: FrameFilter,
        sensitive: SensitiveVerdict?,
    ): Outcome {
        // The whole-frame drop runs on the RAW tree and the RAW title, before anything is built. A scan
        // that FAILED (the marker scan's own fail-closed sentinel) is a build failure, not a banking
        // screen — review GG3: #1146's counters must not report a normalizer defect as a sensitive frame.
        when (sensitive ?: SensitiveVerdict.of(SensitiveTextMarkers.findMarker(tree))) {
            SensitiveVerdict.Clear -> Unit
            is SensitiveVerdict.Hit -> return Outcome.Refused(Refusal.SENSITIVE_FRAME)
            SensitiveVerdict.ScanFailed -> return Outcome.Refused(Refusal.BUILD_FAILED)
        }
        if (!windowTitle.isNullOrBlank()) {
            sensitivity(SensitiveTextMarkers.findMarker(windowTitle), Refusal.SENSITIVE_TITLE)?.let { return Outcome.Refused(it) }
        }

        // Frame-level duplicate rule (ADR-0011 §2; #1160 reviews AA1, CC3). Pass 1 validates the raw
        // tree (InvalidTree → INVALID_TREE), runs the per-field filter ONCE per value (memoized per
        // frame, review CC5) and seeds the frame's sets.
        val pending = frame.scan(tree)
        val title = frame.field(windowTitle, IdClass.NONE)

        // Pass 2: emit, withholding every field the frame-level rule catches.
        val root = frame.emit(pending)
        val fingerprint = CensusFingerprint.of(root) ?: return Outcome.Refused(Refusal.FINGERPRINT_FAILED)
        val item = try {
            UiSkeletonDto(
                schemaId = SkeletonSchema.SCHEMA_ID,
                hashDomain = CensusHash.HASH_DOMAIN,
                filterRev = FILTER_REV,
                fingerprint = fingerprint,
                platform = platform.wire,
                platformAppVersion = stamp(meta.platformAppVersion),
                appVersion = stamp(meta.appVersion),
                rulesetReleaseTag = stamp(meta.rulesetReleaseTag),
                engineVersion = meta.engineVersion,
                rulesetFormatVersion = meta.rulesetFormatVersion,
                day = DAY_FORMAT.format(day),
                windowTitle = title?.let { frame.slot(it) },
                root = root,
            )
        } catch (_: IllegalArgumentException) {
            return Outcome.Refused(Refusal.INVALID_ENVELOPE)
        }
        val measured = SkeletonSchema.measure(item)
        if (measured.bytes > SkeletonSchema.MAX_ITEM_BYTES) return Outcome.Refused(Refusal.OVERSIZE)
        return Outcome.Built(item, measured.json, measured.bytes)
    }

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    private fun sensitivity(marker: String?, refusal: Refusal): Refusal? = when (marker) {
        null -> null
        SensitiveTextMarkers.NORMALIZE_FAILED -> Refusal.BUILD_FAILED
        else -> refusal
    }

    /**
     * An OPTIONAL version stamp, bounded for the wire (#1160 review CC2): the observed app's
     * `versionName` is arbitrary third-party text, and an over-long one must not refuse every frame of
     * that platform. Truncated to [UiSkeletonDto.MAX_VERSION_LENGTH]; null when not well-formed (a
     * truncation that splits a surrogate pair included). The DTO keeps its hard `require` for decode.
     */
    private fun stamp(value: String?): String? {
        if (value == null) return null
        var end = minOf(value.length, UiSkeletonDto.MAX_VERSION_LENGTH)
        // Review PP7: cut at a code-point boundary — never split a surrogate pair and then drop the stamp.
        if (end < value.length && end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end).takeIf { WireStrings.isWellFormed(it) }
    }

    /**
     * May [id] travel in the clear and key the fingerprint (ADR-0011 §1)? The contract's static SHAPE
     * ([ResourceIdGrammar.isStaticShape], which the DTO and the server also enforce) AND — client-side,
     * because the predicates live in this module (review II3) — no customer-PII value predicate fires on
     * the id's NAME part with its separators (`_`, `.`, `-`, `:`) read as spaces, judged at EVERY token
     * start (so `row_Deliver_to_Sam` is caught by the "Deliver to " marker, `chip_Adam_S` by the name
     * shape). A bare name with no marker, lead-in or initial (`chip_Adam`, `Adam Smith`) has no shape a
     * frame-free predicate can tell from chrome (`chip_Gold`, `Artwork Image`) — ADR residual risk 10.
     */
    fun isStaticId(id: String): Boolean {
        if (!ResourceIdGrammar.isStaticShape(id)) return false
        // Review PP1: camelCase segments are words too (`deliverToSam` → "deliver To Sam"), by the same
        // rule the frame-level check uses. Review PP4: only the CASE-SENSITIVE-initial name shape runs on
        // the id path — the IGNORE_CASE anchored variant nulled every `option_a` / `tab_b` chrome id.
        val spoken = CensusHash.canonical(
            ResourceIdGrammar.namePart(id).split(ID_SEPARATORS).joinToString(" ") { camelSegments(it).joinToString(" ") },
        )
        if (PiiShapes.containsMask(spoken) || PiiShapes.FIRST_LAST_INITIAL_EMBEDDED_REGEX.containsMatchIn(spoken)) return false
        val tokens = spoken.split(' ')
        return tokens.indices.none { i ->
            val tail = tokens.subList(i, tokens.size).joinToString(" ")
            CustomerTextMarkers.unredactedMarker(tail) != null || PiiShapes.customerLeadIn(tail) != null
        }
    }

    private val ID_SEPARATORS = Regex("[_.:-]")

    /** How the §2 step-1 id check classified a node's RAW id (reviews CC3, EE1, LL1). */
    internal enum class IdClass {
        /** An `ID_MARKER_TABLE` NAME row — a person's name: seeds its exact value AND its letter runs. */
        PII_NAME,

        /** An `ID_MARKER_TABLE` ADDRESS row — a place: seeds its exact value only (no runs, review LL1). */
        PII_ADDRESS,

        /** An `ID_MARKER_TABLE` CONTENT row — also reused for app copy: seeds nothing. */
        PII_CONTENT,

        /** An `ID_MARKER_TABLE` EXACT row — may be PII or chrome: seeds its exact value only (PP6). */
        PII_EXACT,

        /** In `PII_ID_SUFFIXES` only (the intake list; it also covers instruction BODIES). */
        INTAKE_ONLY,

        NONE,
    }

    private fun idClassOf(id: String?): IdClass {
        val marker = CustomerTextMarkers.idMarkerFor(id)
        return when {
            marker != null -> when (marker.kind) {
                CustomerTextMarkers.IdentityKind.NAME -> IdClass.PII_NAME
                CustomerTextMarkers.IdentityKind.ADDRESS -> IdClass.PII_ADDRESS
                CustomerTextMarkers.IdentityKind.CONTENT -> IdClass.PII_CONTENT
                CustomerTextMarkers.IdentityKind.EXACT -> IdClass.PII_EXACT
            }
            PiiShapes.hasPiiIdSuffix(id) -> IdClass.INTAKE_ONLY
            else -> IdClass.NONE
        }
    }

    /**
     * One non-blank field after pass 1: its RAW trimmed value (the verdict memo key), its canonical value
     * (the hash/grammar input and the frame-wide key), and whether its own id withholds it.
     */
    internal class Field(val trimmed: String, val canonical: String, val idWithholds: Boolean)

    /** A node after pass 1: its validated class/id, flags, and fields by wire key. */
    internal class Pending(
        val className: String?,
        val id: String?,
        val node: UiNode,
        val fields: List<Pair<String, Field>>,
        val children: List<Pending>,
    )

    /**
     * The per-frame filter state (review CC5): the value-only steps (2–8) and the value's own slot are
     * computed once per distinct canonical value; step 1 once per node. [caught] is the frame-level set.
     * [judge] is the value-only filter (steps 2–8); internal so a test can count its evaluations.
     */
    internal class FrameFilter(
        private val judge: (String) -> FilterStep?,
        private val unfiltered: (String) -> TextSlot = ::unfilteredSlot,
        /** False ONLY for the diagnostic seam [outcomeWithoutFrameRule] (review OO2). */
        private val frameLevel: Boolean = true,
    ) {
        /**
         * A cached verdict. Wrapped (review DD2): a bare `FilterStep?` map stores the common "passed"
         * result as `null`, which `getOrPut` reads as ABSENT and recomputes on every lookup.
         */
        private class Judged(val step: FilterStep?)

        private val valueSteps = HashMap<String, Judged>()
        private val valueSlots = HashMap<String, TextSlot>()

        /** Letter runs per (canonical value, threshold), computed once per frame (review II10). */
        private val runCache = HashMap<Triple<String, Int, Boolean>, List<String>>()

        private fun runsOf(canonical: String, minLetters: Int = 0, splitCamel: Boolean = false): List<String> =
            runCache.getOrPut(Triple(canonical, minLetters, splitCamel)) { letterRuns(canonical, minLetters, splitCamel) }

        /** Canonical values a VALUE-judging step caught anywhere in the frame (exact equality). */
        private val caught = HashSet<String>()

        /** Letter runs (≥ [MIN_IDENTITY_RUN] letters, folded) of NAME identity ids' text/desc (GG1, LL1). */
        private val identityRuns = HashSet<String>()

        fun scan(node: UiNode): Pending {
            // Reviews BB1/BB2/CC1/II6: validate the RAW input BEFORE the grammar gates, which would
            // otherwise drop a malformed value to null unseen. The ONLY source of INVALID_TREE (II5).
            node.className?.let { if (!WireStrings.isWellFormed(it)) throw InvalidTree("malformed class name") }
            node.viewIdResourceName?.let { if (!WireStrings.isWellFormed(it)) throw InvalidTree("malformed view id") }
            if (node.isChecked !in 0..2) throw InvalidTree("isChecked outside the 0/1/2 tri-state")
            val idClass = idClassOf(node.viewIdResourceName)
            val fields = ArrayList<Pair<String, Field>>()
            for ((field, value) in node.scrubbableStrings()) {
                field(value, idClass)?.let { fields += field.wire to it }
            }
            seedIdentity(node, idClass)
            return Pending(
                className = ClassNameGrammar.staticOrNull(node.className),
                id = node.viewIdResourceName?.takeIf { raw -> staticIds.getOrPut(raw) { isStaticId(raw) } },
                node = node,
                fields = fields,
                children = node.children.map { scan(it) },
            )
        }

        /** [isStaticId] per raw id, memoized per frame (review NN7: list surfaces repeat one id). */
        private val staticIds = HashMap<String, Boolean>()

        /**
         * Pass 1 for one field: canonicalize (reviews EE2, NN5), filter both forms (FF1), and seed [caught]
         * with the field's exact canonical value when a VALUE-judging step (3, 4, 7, 8) caught it. A mask
         * NEVER seeds (review LL1). Not the length cap (a duplicate is itself over-length). The id-based
         * seeds are [seedIdentity]'s.
         */
        fun field(value: String?, idClass: IdClass): Field? {
            if (value.isNullOrBlank()) return null
            val trimmed = value.trim()
            // Review OO1: a value whose canonical form does not reach a fixed point is withheld outright
            // (never judged on one form and hashed on another) and seeds nothing.
            val canonical = CensusHash.canonicalOrNull(value)
                ?: return Field(trimmed, CensusHash.canonical(value), idWithholds = true)
            val step = valueStep(trimmed, canonical)
            if (step != null && step != FilterStep.LENGTH_CAP && !PiiShapes.containsMask(canonical)) caught += canonical
            return Field(trimmed, canonical, idWithholds = idClass != IdClass.NONE)
        }

        /**
         * Step-1 seeds of an identity id (reviews EE1, LL1, NN3, PP2, PP6), from its rendered value only
         * (TEXT / CONTENT_DESCRIPTION — never role/hint/tooltip/click-label/uid/pane); a mask never seeds.
         * - NAME, ADDRESS, EXACT: the EXACT canonical value of the text and of the desc.
         * - NAME only: letter runs from its TEXT when the text is non-blank, otherwise from its
         *   CONTENT_DESCRIPTION — so a name rendered only in a desc still propagates ("Adam's order"),
         *   while a TalkBack desc "Customer name Adam" beside text "Adam" seeds no `customer`/`name`.
         * ADDRESS and EXACT never seed runs (address vocabulary and sheet titles are common English).
         */
        private fun seedIdentity(node: UiNode, idClass: IdClass) {
            if (idClass != IdClass.PII_NAME && idClass != IdClass.PII_ADDRESS && idClass != IdClass.PII_EXACT) return
            val text = node.text?.takeIf { it.isNotBlank() }?.let { CensusHash.canonicalOrNull(it) }?.takeIf { !PiiShapes.containsMask(it) }
            val desc = node.contentDescription?.takeIf { it.isNotBlank() }?.let { CensusHash.canonicalOrNull(it) }
                ?.takeIf { !PiiShapes.containsMask(it) }
            text?.let { caught += it }
            desc?.let { caught += it }
            if (idClass != IdClass.PII_NAME) return
            val runSource = if (!node.text.isNullOrBlank()) text else desc
            runSource?.let { identityRuns += runsOf(it, minLetters = MIN_IDENTITY_RUN) }
        }

        /**
         * Steps 2–8, memoized by the raw trimmed string (the canonical form derives from it); a filter
         * failure withholds (fail closed).
         *
         * Reviews FF1 + GG2: the CANONICAL form is judged first and alone decides the length cap (ADR
         * step 2), so wide-spaced chrome is not capped on its raw padding and a padded "Deliver  to  Sam"
         * is still caught and seeded. The RAW form is judged too — only when it differs AND is itself
         * within the cap, so every pattern still sees bounded input — because canonicalization can
         * shrink a value below a pattern's minimum (`"ab  cd"` is a quoted note raw, `"ab cd"` is not).
         * Either hit withholds.
         */
        private fun valueStep(trimmed: String, canonical: String): FilterStep? =
            valueSteps.getOrPut(trimmed) {
                Judged(
                    try {
                        judge(canonical)
                            ?: if (trimmed != canonical && trimmed.length <= MAX_TOKEN_LENGTH) judge(trimmed) else null
                    } catch (_: Exception) {
                        FilterStep.PII_SHAPE
                    },
                )
            }.step

        /** Pass 2 for one field: the constant `withheld`, or the value's own (memoized) slot. */
        fun slot(field: Field): TextSlot {
            if (field.idWithholds || valueStep(field.trimmed, field.canonical) != null) return TextSlot.WITHHELD
            if (frameLevel && (field.canonical in caught || containsIdentityRun(field.canonical))) return TextSlot.WITHHELD
            return valueSlots.getOrPut(field.canonical) { unfiltered(field.canonical) }
        }

        /**
         * The frame-level containment rule's ONE owner (reviews GG1, JJ1): does [candidate] carry a letter
         * run equal to any identity seed's run on this frame? Applied to every text slot AND to the id's
         * name part and the class name of every node — an id built from the customer's name
         * (`chip_Adam` beside `customer_name` "Adam") is as identifying as a text slot.
         */
        fun containsIdentityRun(candidate: String, splitCamel: Boolean = false): Boolean =
            frameLevel && identityRuns.isNotEmpty() && runsOf(candidate, splitCamel = splitCamel).any { it in identityRuns }

        fun emit(p: Pending): UiSkeletonNodeDto {
            val text = LinkedHashMap<String, TextSlot>()
            for ((wire, field) in p.fields) text[wire] = slot(field)
            return UiSkeletonNodeDto(
                // ADR §1 / reviews AA10, CC1: only a STATIC class / resource name travels (and keys the
                // fingerprint); anything else is absent. The §2 PII-id step used the RAW id.
                // JJ1: a static class/id that carries an identity run of THIS frame is absent too — for the
                // wire and (since the fingerprint is computed from this tree) the fingerprint.
                // KK1: ids and classes also split at camelCase boundaries (Compose test tags are usually
                // camelCase — `chipAdam`); text slots keep the plain letter-run split.
                className = p.className?.takeIf { !containsIdentityRun(it, splitCamel = true) },
                id = p.id?.takeIf { !containsIdentityRun(ResourceIdGrammar.namePart(it), splitCamel = true) },
                isClickable = p.node.isClickable,
                isEnabled = p.node.isEnabled,
                isChecked = p.node.isChecked,
                text = text,
                children = p.children.map { emit(it) },
            )
        }
    }

    /**
     * An identity value contributes only letter runs of at least this many letters (reviews GG1, II1):
     * two, so a two-letter first name ("Li", "Jo") is covered; whole-run equality keeps it from matching
     * inside another word, and a single-letter run (an initial) seeds nothing.
     */
    private const val MIN_IDENTITY_RUN = 2

    /**
     * The value's maximal runs of Unicode letters (with [splitCamel], also split at camelCase boundaries —
     * the id/class form, review KK1), case-FOLDED with the one [CaseFold] (review HH1) —
     * so "Adam's order" yields `adam`, `s`, `order` and "Adam, 2 items" yields `adam`, `items` (review
     * GG1). [minLetters] counts letter CODE POINTS of the original run before folding (review HH2:
     * UTF-16 units over-count a supplementary-plane letter, and a fold can change the length).
     */
    private fun letterRuns(value: String, minLetters: Int = 0, splitCamel: Boolean = false): List<String> {
        val runs = ArrayList<String>()
        fun emit(run: String) {
            if (run.codePointCount(0, run.length) >= minLetters) runs += CaseFold.fold(run)
        }
        for (token in plainLetterRuns(value)) {
            if (!splitCamel) {
                emit(token)
                continue
            }
            // KK1 + MM1 (ids/classes only): every CONTIGUOUS concatenation of the token's camel segments
            // — the singles (`chip`, `Adam`), the whole token (`chipAdam`), and the joins in between
            // (`Mc`+`Kenna` → `McKenna`), so a name with internal capitals still matches. Bounded by the
            // token (an id name part is ≤ 64 chars).
            val segments = camelSegments(token)
            for (from in segments.indices) {
                val sb = StringBuilder()
                for (to in from until segments.size) {
                    sb.append(segments[to])
                    emit(sb.toString())
                }
            }
        }
        return runs
    }

    /** Maximal runs of Unicode letters (code-point based), unfolded. */
    private fun plainLetterRuns(value: String): List<String> {
        val runs = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (Character.isLetter(cp)) {
                sb.appendCodePoint(cp)
            } else if (sb.isNotEmpty()) {
                runs += sb.toString()
                sb.setLength(0)
            }
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) runs += sb.toString()
        return runs
    }

    /**
     * A letter run split at camelCase boundaries (review KK1): lower→Upper (`chip|Adam`), and
     * Upper→Upper+lower (`XML|Adam`: before the upper that starts a lowercase word).
     */
    private fun camelSegments(token: String): List<String> {
        val segments = ArrayList<String>()
        val sb = StringBuilder()
        var prev = -1
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            val next = i + Character.charCount(cp)
            if (sb.isNotEmpty() && Character.isUpperCase(cp)) {
                val nextCp = if (next < token.length) token.codePointAt(next) else -1
                if (Character.isLowerCase(prev) ||
                    (Character.isUpperCase(prev) && nextCp >= 0 && Character.isLowerCase(nextCp))
                ) {
                    segments += sb.toString()
                    sb.setLength(0)
                }
            }
            sb.appendCodePoint(cp)
            prev = cp
            i = next
        }
        if (sb.isNotEmpty()) segments += sb.toString()
        return segments
    }

    /**
     * Step 6 and the hash, for a value no withholding step caught: only `words:1..8` hash; a digest
     * failure withholds. PRIVATE (review CC5): every caller goes through the frame-level rule.
     */
    private fun unfilteredSlot(canonical: String): TextSlot = try {
        val shape = KindClassifier.shapeKind(canonical)
        if (!shape.hashable) {
            TextSlot(kind = shape.wire)
        } else {
            // Review OO1: hash the canonical string the filter JUDGED — never re-canonicalize.
            CensusHash.ofCanonical(canonical)?.let { TextSlot(h = it, kind = shape.wire) } ?: TextSlot.WITHHELD
        }
    } catch (_: Exception) {
        TextSlot.WITHHELD
    }

    /**
     * The FIRST value-judging §2 step (2–8) that fires on [value], or null when none does. ADR order; the
     * length cap (step 2) precedes every text predicate, so steps 3–8 only ever see ≤
     * [MAX_TOKEN_LENGTH] characters. Step 1 is [IdClass] (review GG4). Internal for the tests.
     */
    internal fun withholdingStep(value: String): FilterStep? = when {
        value.length > MAX_TOKEN_LENGTH -> FilterStep.LENGTH_CAP
        CustomerTextMarkers.unredactedMarker(value) != null -> FilterStep.CUSTOMER_MARKER
        PiiShapes.customerLeadIn(value) != null -> FilterStep.LEAD_IN
        PiiShapes.containsMask(value) -> FilterStep.MASK
        PiiShapes.hasNameShape(value) -> FilterStep.NAME_SHAPE
        PiiShapes.VALUE_SHAPES.any { it.hits(value) } -> FilterStep.PII_SHAPE
        else -> null
    }
}
