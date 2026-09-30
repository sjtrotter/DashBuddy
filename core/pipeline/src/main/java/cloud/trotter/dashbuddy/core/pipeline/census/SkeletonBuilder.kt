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
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
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
 *   whose canonical value a value-judging step caught anywhere in the frame, and (b) any field that
 *   CONTAINS a letter run (≥ 3 letters, case-insensitive) of an IDENTITY id's rendered text/desc — so a
 *   customer name the id marks on one node is not hashed on an id-less node that repeats or embeds it
 *   ("Adam's order").
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
    ): Outcome = outcome(tree, windowTitle, meta, platform, day, FrameFilter(::withholdingStep))

    /** [outcome] over an explicit [frame] — internal so a test can inject a defect (review II5). */
    internal fun outcome(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
        frame: FrameFilter,
    ): Outcome = try {
        buildOutcome(tree, windowTitle, meta, platform, day, frame)
    } catch (e: CancellationException) {
        throw e
    } catch (_: InvalidTree) {
        Outcome.Refused(Refusal.INVALID_TREE)
    } catch (_: Throwable) {
        // Anything else — including a builder-defect `require` (TextSlot, ShapeKind, DTO init) — is a
        // BUILD failure, never mis-reported as a bad third-party tree (review II5).
        Outcome.Refused(Refusal.BUILD_FAILED)
    }

    /** Thrown ONLY by the raw-tree validation in [FrameFilter.scan]: the input, not the builder, is bad. */
    internal class InvalidTree(message: String) : Exception(message)

    private fun buildOutcome(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
        frame: FrameFilter,
    ): Outcome {
        // The whole-frame drop runs on the RAW tree and the RAW title, before anything is built. A scan
        // that FAILED (the marker scan's own fail-closed sentinel) is a build failure, not a banking
        // screen — review GG3: #1146's counters must not report a normalizer defect as a sensitive frame.
        sensitivity(SensitiveTextMarkers.findMarker(tree), Refusal.SENSITIVE_FRAME)?.let { return Outcome.Refused(it) }
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
    private fun stamp(value: String?): String? =
        value?.take(UiSkeletonDto.MAX_VERSION_LENGTH)?.takeIf { WireStrings.isWellFormed(it) }

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
        val spoken = CensusHash.canonical(ResourceIdGrammar.namePart(id).replace(ID_SEPARATORS, " "))
        if (PiiShapes.containsMask(spoken) || PiiShapes.hasNameShape(spoken)) return false
        val tokens = spoken.split(' ')
        return tokens.indices.none { i ->
            val tail = tokens.subList(i, tokens.size).joinToString(" ")
            CustomerTextMarkers.unredactedMarker(tail) != null || PiiShapes.customerLeadIn(tail) != null
        }
    }

    private val ID_SEPARATORS = Regex("[_.:-]")

    /** How the §2 step-1 id check classified a node's RAW id (reviews CC3, EE1). */
    internal enum class IdClass {
        /** An `ID_MARKER_TABLE` row with `valueIsPii` — an IDENTITY id (customer name, address line). */
        PII_VALUE,

        /** An `ID_MARKER_TABLE` row without it — a CONTENT id also reused for app copy. */
        PII_CONTENT,

        /** In `PII_ID_SUFFIXES` only (the intake list; it also covers instruction BODIES). */
        INTAKE_ONLY,

        NONE,
    }

    private fun idClassOf(id: String?): IdClass {
        val marker = CustomerTextMarkers.idMarkerFor(id)
        return when {
            marker != null -> if (marker.valueIsPii) IdClass.PII_VALUE else IdClass.PII_CONTENT
            PiiShapes.hasPiiIdSuffix(id) -> IdClass.INTAKE_ONLY
            else -> IdClass.NONE
        }
    }

    /**
     * The fields an identity id's hit may seed frame-wide from (review EE1): the rendered value only —
     * never role/hint/tooltip/clickLabel/uid/pane, whose chrome ("Button") would otherwise be withheld
     * everywhere in the frame.
     */
    private val SEED_FIELDS = setOf(UiNodeTextField.TEXT, UiNodeTextField.CONTENT_DESCRIPTION)

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
    ) {
        /**
         * A cached verdict. Wrapped (review DD2): a bare `FilterStep?` map stores the common "passed"
         * result as `null`, which `getOrPut` reads as ABSENT and recomputes on every lookup.
         */
        private class Judged(val step: FilterStep?)

        private val valueSteps = HashMap<String, Judged>()
        private val valueSlots = HashMap<String, TextSlot>()

        /** Letter runs per (canonical value, threshold), computed once per frame (review II10). */
        private val runCache = HashMap<Pair<String, Int>, List<String>>()

        private fun runsOf(canonical: String, minLetters: Int = 0): List<String> =
            runCache.getOrPut(canonical to minLetters) { letterRuns(canonical, minLetters) }

        /** Canonical values a VALUE-judging step caught anywhere in the frame (exact equality). */
        private val caught = HashSet<String>()

        /** Letter runs (≥ [MIN_IDENTITY_RUN] letters, case-folded) of identity ids' text/desc (review GG1). */
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
                field(value, idClass, seedsFromId = field in SEED_FIELDS)?.let { fields += field.wire to it }
            }
            return Pending(
                className = ClassNameGrammar.staticOrNull(node.className),
                id = node.viewIdResourceName?.takeIf { isStaticId(it) },
                node = node,
                fields = fields,
                children = node.children.map { scan(it) },
            )
        }

        /**
         * Pass 1 for one field: canonicalize (review EE2), filter both forms (FF1), and seed [caught] per the frame-level
         * rule. [seedsFromId] is true only for the TEXT / CONTENT_DESCRIPTION fields (review EE1).
         */
        fun field(value: String?, idClass: IdClass, seedsFromId: Boolean = true): Field? {
            if (value.isNullOrBlank()) return null
            val trimmed = value.trim()
            val canonical = CensusHash.canonical(value)
            val step = valueStep(trimmed, canonical)
            // Seeds: (a) a value-judging step 3, 4, 5, 7, 8 on any field, applied by EXACT canonical
            // equality; (b) step 1 ONLY for an IDENTITY id (`valueIsPii`, review EE1) on its rendered
            // text/desc, applied by TOKEN CONTAINMENT of its letter runs (review GG1). Not the length cap
            // (a duplicate is itself over-length), not a content id (`description_text_view` renders app
            // copy), not an intake-only id (its list also covers chrome instruction bodies, review CC3).
            if (step != null && step != FilterStep.LENGTH_CAP) caught += canonical
            if (idClass == IdClass.PII_VALUE && seedsFromId) {
                caught += canonical
                identityRuns += runsOf(canonical, minLetters = MIN_IDENTITY_RUN)
            }
            return Field(trimmed, canonical, idWithholds = idClass != IdClass.NONE)
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
            if (field.idWithholds || field.canonical in caught || valueStep(field.trimmed, field.canonical) != null) return TextSlot.WITHHELD
            if (identityRuns.isNotEmpty() && runsOf(field.canonical).any { it in identityRuns }) return TextSlot.WITHHELD
            return valueSlots.getOrPut(field.canonical) { unfiltered(field.canonical) }
        }

        fun emit(p: Pending): UiSkeletonNodeDto {
            val text = LinkedHashMap<String, TextSlot>()
            for ((wire, field) in p.fields) text[wire] = slot(field)
            return UiSkeletonNodeDto(
                // ADR §1 / reviews AA10, CC1: only a STATIC class / resource name travels (and keys the
                // fingerprint); anything else is absent. The §2 PII-id step used the RAW id.
                className = p.className,
                id = p.id,
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
     * The value's maximal runs of Unicode letters, case-FOLDED with the one [CaseFold] (review HH1) —
     * so "Adam's order" yields `adam`, `s`, `order` and "Adam, 2 items" yields `adam`, `items` (review
     * GG1). [minLetters] counts letter CODE POINTS of the original run before folding (review HH2:
     * UTF-16 units over-count a supplementary-plane letter, and a fold can change the length).
     */
    private fun letterRuns(value: String, minLetters: Int = 0): List<String> {
        val runs = ArrayList<String>()
        val sb = StringBuilder()
        var letters = 0
        fun flush() {
            if (sb.isNotEmpty() && letters >= minLetters) runs += CaseFold.fold(sb.toString())
            sb.setLength(0)
            letters = 0
        }
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (Character.isLetter(cp)) {
                sb.appendCodePoint(cp)
                letters++
            } else {
                flush()
            }
            i += Character.charCount(cp)
        }
        flush()
        return runs
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
            CensusHash.of(canonical)?.let { TextSlot(h = it, kind = shape.wire) } ?: TextSlot.WITHHELD
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
