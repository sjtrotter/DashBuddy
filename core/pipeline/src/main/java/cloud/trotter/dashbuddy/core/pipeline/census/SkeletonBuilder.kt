package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.census.contract.CensusFingerprint
import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.KindClassifier
import cloud.trotter.dashbuddy.domain.census.contract.ResourceIdGrammar
import cloud.trotter.dashbuddy.domain.census.contract.SkeletonSchema
import cloud.trotter.dashbuddy.domain.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.census.contract.UiSkeletonDto
import cloud.trotter.dashbuddy.domain.census.contract.UiSkeletonNodeDto
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
        /** The node id is in `ID_MARKERS` (suffix) ∪ `PII_ID_SUFFIXES` (exact, after the last `/`). */
        PII_ID(1),

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

        // Frame-level duplicate rule (ADR-0011 §2, #1160 review AA1), pass 1: every trimmed value a
        // withholding step caught ANYWHERE in the frame. The corpus intake's replacements are
        // document-wide, so a value it masks where the id marks it is masked where the id does not.
        val caught = HashSet<String>()
        collectCaught(tree, caught)
        collectCaughtValue(windowTitle, nodeId = null, caught)

        // Pass 2: build, withholding every field whose trimmed value was caught somewhere.
        val root = try {
            skeletonOf(tree, caught)
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
                platformAppVersion = meta.platformAppVersion,
                appVersion = meta.appVersion,
                rulesetReleaseTag = meta.rulesetReleaseTag,
                engineVersion = meta.engineVersion,
                rulesetFormatVersion = meta.rulesetFormatVersion,
                day = day,
                windowTitle = frameSlot(windowTitle, nodeId = null, caught),
                root = root,
            )
        } catch (_: IllegalArgumentException) {
            return Outcome.Refused(Refusal.INVALID_ENVELOPE)
        }
        val json = SkeletonSchema.serialize(item)
        val bytes = json.toByteArray(Charsets.UTF_8).size
        if (bytes > SkeletonSchema.MAX_ITEM_BYTES) return Outcome.Refused(Refusal.OVERSIZE)
        return Outcome.Built(item, json, bytes)
    }

    private fun collectCaught(node: UiNode, caught: MutableSet<String>) {
        for ((_, value) in node.scrubbableStrings()) collectCaughtValue(value, node.viewIdResourceName, caught)
        node.children.forEach { collectCaught(it, caught) }
    }

    /**
     * Pass-1 membership: steps 1, 3, 4, 5, 7, 8. The length cap (step 2) is excluded — a duplicate of
     * an over-length value is itself over-length. A filter failure counts as caught (fail closed).
     */
    private fun collectCaughtValue(value: String?, nodeId: String?, caught: MutableSet<String>) {
        if (value.isNullOrBlank()) return
        val trimmed = value.trim()
        val step = try {
            withholdingStep(trimmed, nodeId)
        } catch (_: Exception) {
            FilterStep.PII_SHAPE
        }
        if (step != null && step != FilterStep.LENGTH_CAP) caught += trimmed
    }

    /** [slotFor] under the frame-level duplicate rule. */
    private fun frameSlot(value: String?, nodeId: String?, caught: Set<String>): TextSlot? {
        val slot = slotFor(value, nodeId) ?: return null
        return if (value!!.trim() in caught) TextSlot.WITHHELD else slot
    }

    /**
     * One text field → its [TextSlot], or null when the field is null/blank (OMITTED, never emitted —
     * including on a node whose id would withhold it). [nodeId] is the owning node's view id (null for
     * the window title, which has no node). Fail closed: an unexpected failure withholds.
     */
    fun slotFor(value: String?, nodeId: String?): TextSlot? {
        if (value.isNullOrBlank()) return null
        return try {
            val trimmed = value.trim()
            if (withholdingStep(trimmed, nodeId) != null) return TextSlot.WITHHELD
            // Step 6: the grammar runs on the (already capped) trimmed value; only words:1..8 hash.
            val shape = KindClassifier.shapeKind(trimmed)
            if (!shape.hashable) return TextSlot(kind = shape.wire)
            val h = CensusHash.of(trimmed) ?: return TextSlot.WITHHELD
            TextSlot(h = h, kind = shape.wire)
        } catch (_: Exception) {
            TextSlot.WITHHELD
        } catch (_: StackOverflowError) {
            TextSlot.WITHHELD
        }
    }

    /**
     * The FIRST withholding §2 step that fires on [trimmed] (the trimmed canonical value), or null when
     * none does. ADR order; the length cap (step 2) precedes every text predicate, so steps 3–8 only
     * ever see ≤ [MAX_TOKEN_LENGTH] characters.
     */
    fun withholdingStep(trimmed: String, nodeId: String?): FilterStep? = when {
        CustomerTextMarkers.hasIdMarkerSuffix(nodeId) || PiiShapes.hasPiiIdSuffix(nodeId) -> FilterStep.PII_ID
        trimmed.length > MAX_TOKEN_LENGTH -> FilterStep.LENGTH_CAP
        CustomerTextMarkers.unredactedMarker(trimmed) != null -> FilterStep.CUSTOMER_MARKER
        PiiShapes.customerLeadIn(trimmed) != null -> FilterStep.LEAD_IN
        PiiShapes.containsMask(trimmed) -> FilterStep.MASK
        PiiShapes.hasNameShape(trimmed) -> FilterStep.NAME_SHAPE
        PiiShapes.VALUE_SHAPES.any { it.hits(trimmed) } -> FilterStep.PII_SHAPE
        else -> null
    }

    /** The skeleton of one node and its subtree: structure + flags + per-field slots, no bounds. */
    private fun skeletonOf(node: UiNode, caught: Set<String>): UiSkeletonNodeDto {
        val text = LinkedHashMap<String, TextSlot>()
        for ((field, value) in node.scrubbableStrings()) {
            frameSlot(value, node.viewIdResourceName, caught)?.let { text[field.wire] = it }
        }
        return UiSkeletonNodeDto(
            className = node.className,
            // ADR §1 / review AA10: only a STATIC resource name travels (and keys the fingerprint); a
            // dynamic id (a per-frame UUID test tag) is absent. The §2 PII-id step above used the RAW id.
            id = ResourceIdGrammar.staticOrNull(node.viewIdResourceName),
            isClickable = node.isClickable,
            isEnabled = node.isEnabled,
            isChecked = node.isChecked.takeIf { it in 0..2 } ?: 0,
            text = text,
            children = node.children.map { skeletonOf(it, caught) },
        )
    }
}
