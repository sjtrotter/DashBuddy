package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextFold
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.WireStrings
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.UiTextBounds
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

        /**
         * A node failed the raw-tree validation: a malformed view id (NUL / lone surrogate — its identity
         * classification cannot be verified, review AE1) or an `isChecked` outside the tri-state. A malformed
         * CLASS does not refuse the frame: it is emitted ABSENT and counted in [Outcome.Built.malformedClass]
         * (review AD5).
         */
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
        /**
         * [json] is the canonical serialization [itemBytes] measured — the caller never re-serializes.
         * [malformedClass] counts nodes whose class was malformed and emitted absent (reviews AD5, AE1) —
         * a counter #1146's publisher can read; never text.
         */
        data class Built(val skeleton: UiSkeletonDto, val json: String, val itemBytes: Int, val malformedClass: Int = 0) : Outcome
        data class Refused(val reason: Refusal) : Outcome
    }

    /**
     * The VALUE-judging §2 steps that WITHHOLD (emit the constant `withheld`), in ADR order. Step 1 (the
     * node's own PII id) is not a value judgement and lives only in `IdClass` (review GG4); step 6 (only
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
     * [outcome] with the tree's sensitive-marker verdict the caller ALREADY computed (review PP8), so a
     * caller that scanned the same raw tree need not have it scanned twice. NOT YET WIRED: #1146's publisher
     * passes `null` (the builder scans itself) because `CaptureWriter` does not surface its verdict — #1172
     * item 2 tracks the hand-off. `null` from any caller makes the builder scan itself. The window title is
     * always scanned here (it is one short string).
     */
    fun outcome(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
        sensitive: SensitiveVerdict?,
    ): Outcome = outcome(tree, windowTitle, meta, platform, day, FrameFilter(::withholdingStep), sensitive)

    /**
     * A tree's sensitive-marker verdict, as `SensitiveTextMarkers.findMarker` reports it (reviews PP8, SS2).
     * BOUND to the exact tree it was computed on ([tree], referential identity): the builder honours a
     * verdict only for that same `UiNode` instance, and otherwise scans itself — a stale or mismatched
     * `Clear` can never skip the whole-frame drop (fail closed on the argument).
     */
    sealed interface SensitiveVerdict {
        /** The scanned tree (identity-compared). */
        val tree: UiNode

        data class Clear(override val tree: UiNode) : SensitiveVerdict

        /** A marker hit. [markerId] is the marker's log id — never frame text. */
        data class Hit(override val tree: UiNode, val markerId: String) : SensitiveVerdict

        /** The scan itself failed (its fail-closed sentinel) — a build failure, not a sensitive frame. */
        data class ScanFailed(override val tree: UiNode) : SensitiveVerdict

        /** The three outcomes of a marker scan. */
        enum class Kind { CLEAR, HIT, SCAN_FAILED }

        companion object {
            /** The ONE mapping of a `SensitiveTextMarkers.findMarker` result (review UU9). */
            fun classify(marker: String?): Kind = when (marker) {
                null -> Kind.CLEAR
                SensitiveTextMarkers.NORMALIZE_FAILED -> Kind.SCAN_FAILED
                else -> Kind.HIT
            }

            /** Map a `SensitiveTextMarkers.findMarker(tree)` result to a verdict bound to [tree]. */
            fun of(tree: UiNode, marker: String?): SensitiveVerdict = when (classify(marker)) {
                Kind.CLEAR -> Clear(tree)
                Kind.HIT -> Hit(tree, marker!!)
                Kind.SCAN_FAILED -> ScanFailed(tree)
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

    /** Thrown ONLY by the raw-tree validation in `FrameFilter.scan`: the input, not the builder, is bad. */
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
        val verdict = sensitive?.takeIf { it.tree === tree } ?: SensitiveVerdict.of(tree, SensitiveTextMarkers.findMarker(tree))
        when (verdict) {
            is SensitiveVerdict.Clear -> Unit
            is SensitiveVerdict.Hit -> return Outcome.Refused(Refusal.SENSITIVE_FRAME)
            is SensitiveVerdict.ScanFailed -> return Outcome.Refused(Refusal.BUILD_FAILED)
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
        return Outcome.Built(item, measured.json, measured.bytes, frame.malformedClass)
    }

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    /** The title path through the ONE marker → verdict mapping ([SensitiveVerdict.classify], review UU9). */
    private fun sensitivity(marker: String?, refusal: Refusal): Refusal? = when (SensitiveVerdict.classify(marker)) {
        SensitiveVerdict.Kind.CLEAR -> null
        SensitiveVerdict.Kind.HIT -> refusal
        SensitiveVerdict.Kind.SCAN_FAILED -> Refusal.BUILD_FAILED
    }

    /**
     * An OPTIONAL version stamp, bounded for the wire (#1160 review CC2): the observed app's
     * `versionName` is arbitrary third-party text, and an over-long one must not refuse every frame of
     * that platform. Truncated to [UiSkeletonDto.MAX_VERSION_LENGTH]; null when not well-formed (a
     * truncation that splits a surrogate pair included). The DTO keeps its hard `require` for decode.
     */
    internal fun stamp(value: String?): String? =
        // Reviews PP7, AL4: the ONE code-point-safe cut (`UiTextBounds.cap`); AL5: an empty stamp is absent.
        value?.let { UiTextBounds.cap(it, UiSkeletonDto.MAX_VERSION_LENGTH) }?.takeIf { it.isNotEmpty() && WireStrings.isWellFormed(it) }

    /**
     * The NAME-run source rule's ONE owner (reviews NN3, PP2, UU6, AC4): the usable canonical TEXT, else the
     * usable canonical DESC — "usable" = present (a canonical form exists, the value was not blank) and not a
     * mask. Public so the corpus test mirrors call the same rule rather than re-deriving it.
     */
    fun nameRunSource(textCanonical: String?, descCanonical: String?): String? =
        textCanonical?.takeIf { !PiiShapes.containsMask(it) } ?: descCanonical?.takeIf { !PiiShapes.containsMask(it) }

    /**
     * The canonical form a RAW rendered value contributes to [nameRunSource], exactly as pass 1 derives it
     * (review AC4): null when blank, provably over the cap (AB8 — seeds nothing), or with no fixed point.
     */
    fun seedCanonicalOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return (canonicalFormOf(raw.trim()) as? CanonicalForm.Of)?.value
    }

    /** What pass 1 makes of a trimmed value (review AH4): the ONE owner of the over-cap → fixed-point order. */
    internal sealed interface CanonicalForm {
        /** Provably longer than the cap (AB8): LENGTH_CAP, never folded. */
        data object OverCap : CanonicalForm

        /** No fixed point within the bounded passes (OO1): withheld outright. */
        data object NoFixedPoint : CanonicalForm

        data class Of(val value: String) : CanonicalForm
    }

    /**
     * [trimmed]'s pass-1 form through [canonicalize] (the frame's memoized fold, or the plain one): over-cap
     * first (AB8 — never folded), then the fixed point. Called by `FrameFilter.field` and [seedCanonicalOf].
     */
    internal fun canonicalFormOf(trimmed: String, canonicalize: (String) -> String? = CensusHash::canonical): CanonicalForm =
        when {
            provablyOverCap(trimmed) -> CanonicalForm.OverCap
            else -> canonicalize(trimmed)?.let { CanonicalForm.Of(it) } ?: CanonicalForm.NoFixedPoint
        }

    /**
     * The most input code points one canonical code point can absorb (review AB8): the canonical fold
     * (FORMAT strip → NFKC → dashes → whitespace collapse) never drops a counted code point to nothing
     * (every non-FORMAT, non-whitespace code point's NFKD keeps one) and NFKC composition merges at most
     * the longest canonical decomposition — 4 code points (Greek `ᾂ`; Hangul LVT is 3). Both premises are
     * checked exhaustively over every code point by `SkeletonLengthBoundTest` on the running JVM.
     */
    internal const val MAX_COMPOSITION_RATIO = 4

    /**
     * True when [trimmed] has more than [MAX_COMPOSITION_RATIO] × [MAX_TOKEN_LENGTH] non-FORMAT,
     * non-whitespace code points — so its canonical form is certainly longer than the cap (review AB8).
     * Stops counting at the bound: O(bound), never O(value).
     */
    internal fun provablyOverCap(trimmed: String): Boolean {
        val bound = MAX_COMPOSITION_RATIO * MAX_TOKEN_LENGTH
        if (trimmed.length <= bound) return false
        var counted = 0
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            if (!TextFold.isFormat(cp) && !KindClassifier.isWhitespace(cp) && ++counted > bound) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * Step 6 and the hash, for a value no withholding step caught: only `words:1..8` hash; a digest
     * failure withholds. PRIVATE (review CC5): every caller goes through the frame-level rule.
     */
    internal fun unfilteredSlot(canonical: String): TextSlot = try {
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
     * [MAX_TOKEN_LENGTH] characters. Step 1 is `IdClass` (review GG4). Internal for the tests.
     */
    internal fun withholdingStep(value: String): FilterStep? =
        if (value.length > MAX_TOKEN_LENGTH) FilterStep.LENGTH_CAP else valueJudgingStep(value)

    /**
     * Does a value-judging step (3–8, the cap-less projection of [withholdingStep]) fire on [value]? Public
     * (review AJ7) so the corpus tests call the builder's own predicates rather than re-deriving them.
     */
    fun judgesValue(value: String): Boolean = valueJudgingStep(value) != null

    /** Steps 3–8 — the ONE owner both [withholdingStep] and [judgesValue] read. */
    private fun valueJudgingStep(value: String): FilterStep? = when {
        CustomerTextMarkers.unredactedMarker(value) != null -> FilterStep.CUSTOMER_MARKER
        PiiShapes.customerLeadIn(value) != null -> FilterStep.LEAD_IN
        PiiShapes.containsMask(value) -> FilterStep.MASK
        PiiShapes.hasNameShape(value) -> FilterStep.NAME_SHAPE
        PiiShapes.VALUE_SHAPES.any { it.hits(value) } -> FilterStep.PII_SHAPE
        else -> null
    }
}
