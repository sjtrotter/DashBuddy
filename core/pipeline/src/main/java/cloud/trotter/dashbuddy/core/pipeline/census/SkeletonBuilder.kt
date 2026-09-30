package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
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
 * - **Only chrome-likely tokens are hashed.** Every non-blank text field runs the §2 filter
 *   ([withholdingStep]) in ADR order; any withholding step emits the constant `withheld`; only a
 *   `words:1..8` survivor is hashed ([CensusHash], fail-closed to `withheld`). The FRAME-LEVEL
 *   duplicate rule then withholds any field whose trimmed value a withholding step caught anywhere
 *   else in the same frame (a name the id marks on one node is not hashed on its id-less twin).
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

        /** The envelope inputs failed the contract's validation (e.g. a malformed `day`/`platform`). */
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
     * The §2 steps that WITHHOLD (emit the constant `withheld`), in ADR order. Step 6 (only
     * `words:N` may be hashed) is deliberately absent: it refuses the HASH and continues, it does not
     * withhold.
     */
    enum class FilterStep(val adrStep: Int) {
        /**
         * The node id carries an `ID_MARKERS` suffix — its VALUE is PII by construction. Seeds the
         * frame-level duplicate rule.
         */
        PII_ID(1),

        /**
         * The node id is in `PII_ID_SUFFIXES` only (exact, after the last `/`) — the intake list, which
         * also covers instruction bodies. Withholds the field; does NOT seed the frame-level rule (CC3).
         */
        PII_ID_INTAKE(1),

        /** The trimmed value is longer than [MAX_TOKEN_LENGTH]. */
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
        platform: String,
        day: String,
    ): UiSkeletonDto? = (outcome(tree, windowTitle, meta, platform, day) as? Outcome.Built)?.skeleton

    /**
     * Build the skeleton of [tree] (+ [windowTitle]) for [platform] on [day] (`YYYY-MM-DD`), stamping
     * the five [ReplayMetadata] version fields. Never throws on a well-formed tree; every refusal is a
     * reason, never text.
     */
    fun outcome(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: String,
        day: String,
    ): Outcome = try {
        buildOutcome(tree, windowTitle, meta, platform, day)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        Outcome.Refused(Refusal.BUILD_FAILED)
    }

    private fun buildOutcome(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: String,
        day: String,
    ): Outcome {
        // The whole-frame drop runs on the RAW tree and the RAW title, before anything is built.
        if (SensitiveTextMarkers.findMarker(tree) != null) return Outcome.Refused(Refusal.SENSITIVE_FRAME)
        if (!windowTitle.isNullOrBlank() && SensitiveTextMarkers.findMarker(windowTitle) != null) {
            return Outcome.Refused(Refusal.SENSITIVE_TITLE)
        }

        // Frame-level duplicate rule (ADR-0011 §2; #1160 reviews AA1, CC3). Pass 1 runs the per-field
        // filter ONCE per field (memoized per frame, review CC5) and seeds the frame's caught set.
        val frame = FrameFilter()
        val pending = try {
            frame.scan(tree)
        } catch (_: IllegalArgumentException) {
            return Outcome.Refused(Refusal.INVALID_TREE)
        }
        val title = frame.field(windowTitle, IdClass.NONE)

        // Pass 2: emit, withholding every field whose trimmed value was caught anywhere in the frame.
        val root = try {
            frame.emit(pending)
        } catch (_: IllegalArgumentException) {
            return Outcome.Refused(Refusal.INVALID_TREE)
        }
        val fingerprint = CensusFingerprint.of(root) ?: return Outcome.Refused(Refusal.FINGERPRINT_FAILED)
        val item = try {
            UiSkeletonDto(
                schemaId = SkeletonSchema.SCHEMA_ID,
                hashDomain = CensusHash.HASH_DOMAIN,
                filterRev = FILTER_REV,
                fingerprint = fingerprint,
                platform = platform,
                platformAppVersion = stamp(meta.platformAppVersion),
                appVersion = stamp(meta.appVersion),
                rulesetReleaseTag = stamp(meta.rulesetReleaseTag),
                engineVersion = meta.engineVersion,
                rulesetFormatVersion = meta.rulesetFormatVersion,
                day = day,
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

    /**
     * An OPTIONAL version stamp, bounded for the wire (#1160 review CC2): the observed app's
     * `versionName` is arbitrary third-party text, and an over-long one must not refuse every frame of
     * that platform. Truncated to [UiSkeletonDto.MAX_VERSION_LENGTH]; null when not well-formed (a
     * truncation that splits a surrogate pair included). The DTO keeps its hard `require` for decode.
     */
    private fun stamp(value: String?): String? =
        value?.take(UiSkeletonDto.MAX_VERSION_LENGTH)?.takeIf { WireStrings.isWellFormed(it) }

    /** How the §2 step-1 id check classified a node's RAW id (review CC3). */
    private enum class IdClass {
        /** In `CustomerTextMarkers.ID_MARKERS`: the node's VALUE IS PII by construction. */
        PII_VALUE,

        /** In `PII_ID_SUFFIXES` only (the intake list; it also covers instruction BODIES). */
        INTAKE_ONLY,

        NONE,
    }

    private fun idClassOf(id: String?): IdClass = when {
        CustomerTextMarkers.hasIdMarkerSuffix(id) -> IdClass.PII_VALUE
        PiiShapes.hasPiiIdSuffix(id) -> IdClass.INTAKE_ONLY
        else -> IdClass.NONE
    }

    /** One non-blank field after pass 1: its trimmed value, and whether its own id withholds it. */
    private class Field(val trimmed: String, val idWithholds: Boolean)

    /** A node after pass 1: its validated class/id, flags, and fields by wire key. */
    private class Pending(
        val className: String?,
        val id: String?,
        val node: UiNode,
        val fields: List<Pair<String, Field>>,
        val children: List<Pending>,
    )

    /**
     * The per-frame filter state (review CC5): the value-only steps (2–8) and the value's own slot are
     * computed once per distinct trimmed value; step 1 once per node. [caught] is the frame-level set.
     */
    private class FrameFilter {
        private val valueSteps = HashMap<String, FilterStep?>()
        private val valueSlots = HashMap<String, TextSlot>()
        private val caught = HashSet<String>()

        fun scan(node: UiNode): Pending {
            // Reviews BB1/BB2/CC1: validate the RAW class and id BEFORE the grammar gates, which would
            // otherwise drop a malformed value to null unseen. Refused as INVALID_TREE.
            node.className?.let { require(WireStrings.isWellFormed(it)) { "malformed class name" } }
            node.viewIdResourceName?.let { require(WireStrings.isWellFormed(it)) { "malformed view id" } }
            val idClass = idClassOf(node.viewIdResourceName)
            val fields = ArrayList<Pair<String, Field>>()
            for ((field, value) in node.scrubbableStrings()) {
                field(value, idClass)?.let { fields += field.wire to it }
            }
            return Pending(
                className = ClassNameGrammar.staticOrNull(node.className),
                id = ResourceIdGrammar.staticOrNull(node.viewIdResourceName),
                node = node,
                fields = fields,
                children = node.children.map { scan(it) },
            )
        }

        /** Pass 1 for one field: filter it, and seed [caught] per the frame-level rule. */
        fun field(value: String?, idClass: IdClass): Field? {
            if (value.isNullOrBlank()) return null
            val trimmed = value.trim()
            val step = valueStep(trimmed)
            // Seeds: the value-judging steps 3, 4, 5, 7, 8 — and step 1 ONLY when the id's VALUE is PII by
            // construction (ID_MARKERS). Not the length cap (a duplicate is itself over-length), not an
            // intake-only id (its list also covers chrome-bearing instruction bodies, review CC3).
            if ((step != null && step != FilterStep.LENGTH_CAP) || idClass == IdClass.PII_VALUE) caught += trimmed
            return Field(trimmed, idWithholds = idClass != IdClass.NONE)
        }

        /** Steps 2–8 of [withholdingStep], memoized; a filter failure withholds (fail closed). */
        private fun valueStep(trimmed: String): FilterStep? = valueSteps.getOrPut(trimmed) {
            try {
                withholdingStep(trimmed, nodeId = null)
            } catch (_: Exception) {
                FilterStep.PII_SHAPE
            }
        }

        /** Pass 2 for one field: the constant `withheld`, or the value's own (memoized) slot. */
        fun slot(field: Field): TextSlot {
            if (field.idWithholds || field.trimmed in caught || valueStep(field.trimmed) != null) return TextSlot.WITHHELD
            return valueSlots.getOrPut(field.trimmed) { unfilteredSlot(field.trimmed) }
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
                isChecked = p.node.isChecked.takeIf { it in 0..2 } ?: 0,
                text = text,
                children = p.children.map { emit(it) },
            )
        }
    }

    /**
     * Step 6 and the hash, for a value no withholding step caught: only `words:1..8` hash; a digest
     * failure withholds. PRIVATE (review CC5): every caller goes through the frame-level rule.
     */
    private fun unfilteredSlot(trimmed: String): TextSlot = try {
        val shape = KindClassifier.shapeKind(trimmed)
        if (!shape.hashable) {
            TextSlot(kind = shape.wire)
        } else {
            CensusHash.of(trimmed)?.let { TextSlot(h = it, kind = shape.wire) } ?: TextSlot.WITHHELD
        }
    } catch (_: Exception) {
        TextSlot.WITHHELD
    }

    /**
     * The FIRST withholding §2 step that fires on [trimmed] (the trimmed canonical value), or null when
     * none does. ADR order; the length cap (step 2) precedes every text predicate, so steps 3–8 only
     * ever see ≤ [MAX_TOKEN_LENGTH] characters. Internal for the tests.
     */
    internal fun withholdingStep(trimmed: String, nodeId: String?): FilterStep? = when {
        CustomerTextMarkers.hasIdMarkerSuffix(nodeId) -> FilterStep.PII_ID
        PiiShapes.hasPiiIdSuffix(nodeId) -> FilterStep.PII_ID_INTAKE
        trimmed.length > MAX_TOKEN_LENGTH -> FilterStep.LENGTH_CAP
        CustomerTextMarkers.unredactedMarker(trimmed) != null -> FilterStep.CUSTOMER_MARKER
        PiiShapes.customerLeadIn(trimmed) != null -> FilterStep.LEAD_IN
        PiiShapes.containsMask(trimmed) -> FilterStep.MASK
        PiiShapes.hasNameShape(trimmed) -> FilterStep.NAME_SHAPE
        PiiShapes.VALUE_SHAPES.any { it.hits(trimmed) } -> FilterStep.PII_SHAPE
        else -> null
    }
}
