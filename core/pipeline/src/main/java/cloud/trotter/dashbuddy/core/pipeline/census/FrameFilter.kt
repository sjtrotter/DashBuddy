package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.FilterStep
import cloud.trotter.census.contract.CaseFold
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.ClassNameGrammar
import cloud.trotter.census.contract.ResourceIdGrammar
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.contract.WireStrings
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes

/** How the §2 step-1 id check classified a node's RAW id (reviews CC3, EE1, LL1). */
internal enum class IdClass {
    /** An `ID_MARKER_TABLE` row: its node's own fields are withheld; what it seeds is its kind's. */
    PII_MARKER,

    NONE,
}

/**
 * One non-blank field after pass 1: its RAW trimmed value (the verdict memo key), its canonical value (the
 * hash/grammar input and the frame-wide key), and whether its own id withholds it. Review AH7: [canonical]
 * is null for a value provably over the cap or with no fixed point — such a field is always withheld, and
 * no consumer can hash or compare its raw string.
 */
internal class Field(val trimmed: String, val canonical: String?, val idWithholds: Boolean)

/** A node after pass 1: its validated class/id, flags, and fields by wire key. */
internal class Pending(
    val className: String?,
    val id: String?,
    val node: UiNode,
    val fields: List<Pair<String, Field>>,
    val children: List<Pending>,
)

/**
 * The per-frame filter state (ADR-0011 §2; review CC5; split from `SkeletonBuilder` by review AD10): the
 * value-only steps (2–8) and the value's own slot are computed once per distinct canonical value; step 1
 * once per node. [judge] is the value-only filter (steps 2–8); a test can count its evaluations.
 */
internal class FrameFilter(
    private val judge: (String) -> FilterStep?,
    private val unfiltered: (String) -> TextSlot = SkeletonBuilder::unfilteredSlot,
    /** False ONLY for the test-fixture `DiagnosticSkeletonBuilder` (reviews OO2, UU7, AD4). */
    private val frameLevel: Boolean = true,
    /** The canonical fold; a seam so a test can count folds (review AB8). */
    private val canonicalize: (String) -> String? = CensusHash::canonical,
    /** The id-path PII judgement for id-shaped values; a seam so a test can count calls (review AK3). */
    private val idShapedPii: (String) -> Boolean = IdPathJudgement::namePartCarriesPii,
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
        runCache.getOrPut(canonical to minLetters) { LetterRuns.letterRuns(canonical, minLetters) }

    /** Canonical values a VALUE-judging step caught anywhere in the frame (exact equality). */
    private val caught = HashSet<String>()

    /** Letter runs (≥ the kind's `minRunLetters`, folded) of run-seeding identity ids' text/desc (GG1, LL1, AD2, AF1). */
    private val identityRuns = HashSet<String>()

    /**
     * Every run an ID is judged against (reviews TT2, VV1, XX3, ZZ3, AB3, AC1): [identityRuns] plus the
     * WHOLE value of every `idProtect` row (case-folded code-point letters, ≥ [MIN_WHOLE_VALUE_RUN]) —
     * `user_name` "Riley" nulls `chipRiley`, `tvTitle` "Search" nulls `search_bar` (the accepted recall
     * cost). An ADDRESS adds none. [idSeedLengths] lets the join search skip lengths no seed has (AD6).
     */
    private val idSeeds = HashSet<String>()
    private val idSeedLengths = HashSet<Int>()

    /** The runs a CLASS is judged against (review AC2): only kinds whose `runsGuardClasses` is set (NAME). */
    private val classSeeds = HashSet<String>()
    private val classSeedLengths = HashSet<Int>()

    /** Nodes whose CLASS was malformed (NUL / lone surrogate) and emitted ABSENT (reviews AD5, AE1). */
    var malformedClass: Int = 0
        private set

    /** Canonical form per raw trimmed value, memoized per frame (review SS7); null = no fixed point. */
    private val canonicals = HashMap<String, Canon>()

    /** A memoized canonical form, wrapped so a stored null ("no fixed point") is not read as absent. */
    private class Canon(val canonical: String?)

    private fun canonicalOf(trimmed: String): String? = canonicals.getOrPut(trimmed) { Canon(canonicalize(trimmed)) }.canonical


    /** The id/class join-search verdicts per candidate (reviews AD6). */
    private val idJoinHits = HashMap<String, Boolean>()
    private val classJoinHits = HashMap<String, Boolean>()

    fun scan(node: UiNode): Pending {
        // Reviews AD5, AE1 (refine BB1/BB2), split by field. A malformed CLASS (NUL, lone surrogate) carries
        // no identity semantics: it is emitted ABSENT and counted — null never enters the byte form, so the
        // fingerprint stays injective, and one bad node does not refuse its surface. A malformed VIEW ID
        // REFUSES the frame (INVALID_TREE): its identity classification cannot be verified (a corrupted
        // `customer_name\u0000` misses every suffix lookup and would ship its name — fail closed).
        val classOk = node.className?.let { WireStrings.isWellFormed(it) } ?: true
        node.viewIdResourceName?.let { if (!WireStrings.isWellFormed(it)) throw SkeletonBuilder.InvalidTree("malformed view id") }
        if (!classOk) malformedClass++
        if (node.isChecked !in 0..2) throw SkeletonBuilder.InvalidTree("isChecked outside the 0/1/2 tri-state")
        // AD7: the marker row is looked up ONCE per node.
        val marker = CustomerTextMarkers.idMarkerFor(node.viewIdResourceName)
        // AL3: ONE list — the intake-only ids are CONTENT/NEVER rows of the same table.
        // #919 (fable review): a TEXT INPUT — or any node under one (a composite input renders its draft in a
        // child) — withholds every field exactly like a PII id row: the runtime UNKNOWN scrub and the census
        // must make the SAME decision about the same node, or the skeleton ships as word slots what the
        // envelope masked. Seeds nothing (free text is not an identity).
        val underInput = generateSequence(node) { it.parent }.any { it.isTextInput }
        val idClass = if (marker != null || underInput) IdClass.PII_MARKER else IdClass.NONE
        val fields = ArrayList<Pair<String, Field>>()
        var textField: Field? = null
        var descField: Field? = null
        for ((field, value) in node.scrubbableStrings()) {
            val f = field(value, idClass, idShaped = field == UiNodeTextField.UNIQUE_ID) ?: continue
            fields += field.wire to f
            if (field == UiNodeTextField.TEXT) textField = f
            if (field == UiNodeTextField.CONTENT_DESCRIPTION) descField = f
        }
        seedIdentity(textField, descField, marker)
        return Pending(
            className = node.className?.takeIf { classOk }?.let { ClassNameGrammar.staticOrNull(it) },
            // AH1: a GRAMMAR-dynamic id is null (rejected identically on every frame of the surface); a static-
            // shaped id the PII judgement withholds (`chip_Riley_S`) is the sentinel, exactly like a frame-rule
            // withholding, so a wrapper-class node is never spliced for one customer and kept for the next.
            id = node.viewIdResourceName?.let { raw ->
                when (IdPathJudgement.verdict(raw)) { // AJ6: one judgement call per node
                    IdPathJudgement.IdVerdict.DYNAMIC -> null
                    IdPathJudgement.IdVerdict.STATIC -> raw
                    IdPathJudgement.IdVerdict.PII_WITHHELD -> ResourceIdGrammar.FRAME_WITHHELD_ID
                }
            },
            node = node,
            fields = fields,
            children = node.children.map { scan(it) },
        )
    }

    /**
     * Pass 1 for one field: canonicalize (reviews EE2, NN5), filter both forms (FF1), and seed [caught]
     * with the field's exact canonical value when a VALUE-judging step (3, 4, 7, 8) caught it. A mask
     * NEVER seeds (review LL1). Not the length cap (a duplicate is itself over-length). The id-based
     * seeds are [seedIdentity]'s.
     */
    fun field(value: String?, idClass: IdClass, idShaped: Boolean = false): Field? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        // Reviews AB8, OO1, AH4, AH7: the ONE pass-1 rule (`SkeletonBuilder.canonicalFormOf`, over the frame's
        // memoized fold). A value provably over the cap, or with no fixed point, is withheld with a NULL
        // canonical and seeds nothing (a duplicate of an over-cap value is itself over-length).
        val canonical = (SkeletonBuilder.canonicalFormOf(trimmed, ::canonicalOf) as? SkeletonBuilder.CanonicalForm.Of)?.value
            ?: return Field(trimmed, null, idWithholds = true)
        // AJ2: a value that canonicalizes to NOTHING (FORMAT-only, e.g. a lone ZWSP) is dropped — never a
        // phantom `mixed` slot that splits the text map on nothing visible.
        if (canonical.isEmpty()) return null
        // AJ1 + AK1–AK3: an id-shaped value (the `uid` test tag) is judged by the ID path on its BOUNDED
        // CANONICAL form — after the over-cap pre-check (AK3: an over-cap tag never builds suffix strings)
        // and after the fold (AK1: `chip＿Riley＿S` with fullwidth low lines, or a zero-width char at a
        // camel boundary, must not slip past on the raw form). A hit seeds the frame-wide EXACT set like a
        // value-judging step (AK2 — the same string as the node's text, or elsewhere, is withheld too).
        if (idShaped && canonical.length <= SkeletonBuilder.MAX_TOKEN_LENGTH && idShapedPii(canonical)) {
            if (!PiiShapes.containsMask(canonical)) caught += canonical
            return Field(trimmed, canonical, idWithholds = true)
        }
        val step = valueStep(trimmed, canonical)
        if (step != null && step != FilterStep.LENGTH_CAP && !PiiShapes.containsMask(canonical)) caught += canonical
        return Field(trimmed, canonical, idWithholds = idClass != IdClass.NONE)
    }

    /**
     * Step-1 seeds of an identity id (reviews EE1, LL1, NN3, PP2, PP6, AB1, AD2), from its rendered value
     * only (TEXT / CONTENT_DESCRIPTION — never role/hint/tooltip/click-label/uid/pane); a mask never seeds.
     * What a kind seeds is the kind table's (`IdentityKind`):
     * - `seedsExactValue`: the EXACT canonical value of the text and of the desc;
     * - `idProtect` (the row): the whole value as ONE id-only run;
     * - `seedsRunsFrom`: letter runs from the usable TEXT, else the usable DESC ([SkeletonBuilder.nameRunSource])
     *   — so a name rendered only in a desc still propagates ("Adam's order"), while a TalkBack desc
     *   "Customer name Adam" beside text "Adam" seeds no `customer`/`name`.
     */
    private fun seedIdentity(textField: Field?, descField: Field?, marker: CustomerTextMarkers.IdMarker?) {
        if (marker == null || !marker.kind.seedsExactValue) return
        // SS7: the already-built Fields' canonicals — never re-canonicalized.
        val text = textField?.canonical
        val desc = descField?.canonical
        listOfNotNull(text, desc).filterNot { PiiShapes.containsMask(it) }.forEach { caught += it }
        // UU6 / AC4 / AF8: the ONE source rule (usable text, else usable desc; it owns the mask rule) feeds
        // BOTH the whole-value id seed (AF3 — a TalkBack label-only desc "Customer name" beside text "Adam"
        // must not seed `customername` and turn the node's own id into `~`) and the letter runs.
        val source = SkeletonBuilder.nameRunSource(text, desc) ?: return
        // Reviews VV1, XX3, ZZ3, AB2, AB4: an `idProtect` row adds its whole value's code-point letters as an
        // id run — a single token included, no name-shape gate (fail closed) — at ≥ MIN_WHOLE_VALUE_RUN
        // letters, so a short value never nulls chrome.
        if (marker.idProtect) {
            val letters = LetterRuns.plainLetterRuns(source).joinToString("")
            if (letters.codePointCount(0, letters.length) >= MIN_WHOLE_VALUE_RUN) addIdSeed(CaseFold.fold(letters))
        }
        if (!marker.kind.seedsRunsFrom(source)) return
        // AF1: the kind's own run floor (NAME 2 — "Li"; PERSON_OR_MERCHANT 3).
        runsOf(source, minLetters = marker.kind.minRunLetters).forEach { run ->
            identityRuns += run
            addIdSeed(run)
            if (marker.kind.runsGuardClasses && classSeeds.add(run)) classSeedLengths += run.length
        }
    }

    private fun addIdSeed(run: String) {
        if (idSeeds.add(run)) idSeedLengths += run.length
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
                        ?: if (trimmed != canonical && trimmed.length <= SkeletonBuilder.MAX_TOKEN_LENGTH) judge(trimmed) else null
                } catch (_: Exception) {
                    FilterStep.PII_SHAPE
                },
            )
        }.step

    /** Pass 2 for one field: the constant `withheld`, or the value's own (memoized) slot. */
    fun slot(field: Field): TextSlot {
        val canonical = field.canonical ?: return TextSlot.WITHHELD
        if (field.idWithholds || valueStep(field.trimmed, canonical) != null) return TextSlot.WITHHELD
        if ((frameLevel && canonical in caught) || containsIdentityRun(canonical)) return TextSlot.WITHHELD
        return valueSlots.getOrPut(canonical) { unfiltered(canonical) }
    }

    /**
     * The frame-level containment rule's ONE owner (reviews GG1, JJ1, AC1): does [candidate] carry an
     * identity run of this frame? A text slot ([idForm] false) is split into plain letter runs and judged
     * against the NAME runs; an id's name part ([idForm] true) is judged on every CONTIGUOUS join of its
     * camel segments ACROSS separators against the NAME runs AND the whole-value seeds — `row_mc_kenna`
     * beside "McKenna Smith", `row_mary_jo` beside "Mary Jo".
     */
    fun containsIdentityRun(candidate: String, idForm: Boolean = false): Boolean {
        if (!frameLevel) return false
        if (!idForm) return identityRuns.isNotEmpty() && runsOf(candidate).any { it in identityRuns }
        if (idSeeds.isEmpty()) return false
        return idJoinHits.getOrPut(candidate) { LetterRuns.anyJoinIn(LetterRuns.foldedSegments(candidate), idSeeds, idSeedLengths) }
    }

    /**
     * The CLASS containment check (review AC2, narrowing ZZ3): a third-party-set class name is withheld
     * when a join carries a run of a class-guarding kind (NAME: `com.x.RileyButton` beside `customer_name`
     * "Riley") — never a title or merchant word, so `SearchView` beside `tvTitle` "Search" stays. A KNOWN
     * framework class ([FrameworkClasses.KNOWN], reviews AF2, AG2 — an exact binary name, never a prefix: an
     * app can name its own class `androidx.RileyButton`) is never checked, so "Chip" never nulls
     * `com.google.android.material.chip.Chip` and forks the fingerprint per customer. The set holds every
     * wrapper class, so wrapper eligibility — the fingerprint's structure — never depends on the customer.
     */
    fun classCarriesNameRun(className: String): Boolean {
        if (!frameLevel || classSeeds.isEmpty() || className in FrameworkClasses.KNOWN) return false
        return classJoinHits.getOrPut(className) { LetterRuns.anyJoinIn(LetterRuns.foldedSegments(className), classSeeds, classSeedLengths) }
    }

    fun emit(p: Pending): UiSkeletonNodeDto {
        val text = LinkedHashMap<String, TextSlot>()
        for ((wire, field) in p.fields) text[wire] = slot(field)
        return UiSkeletonNodeDto(
            // ADR §1 / reviews AA10, CC1: only a STATIC class / resource name travels (and keys the
            // fingerprint); anything else is absent. The §2 PII-id step used the RAW id.
            // JJ1 + AC3: a static id that carries an identity run of THIS frame is replaced by the reserved
            // `ResourceIdGrammar.FRAME_WITHHELD_ID` ("~") — on the wire and in the fingerprint — so the
            // node's wrapper status (the tree STRUCTURE) never depends on the customer.
            // AC2: a class carrying a class-guarding run is absent (null) — see [classCarriesNameRun].
            className = p.className?.takeIf { !classCarriesNameRun(it) },
            id = p.id?.let {
                if (it == ResourceIdGrammar.FRAME_WITHHELD_ID ||
                    containsIdentityRun(ResourceIdGrammar.namePart(it), idForm = true)
                ) {
                    ResourceIdGrammar.FRAME_WITHHELD_ID
                } else {
                    it
                }
            },
            isClickable = p.node.isClickable,
            isEnabled = p.node.isEnabled,
            isChecked = p.node.isChecked,
            text = text,
            children = p.children.map { emit(it) },
        )
    }

    private companion object {
        /**
         * A whole-value id seed needs at least this many letters (review AB4): three, so a short value
         * ("Ok", "No", "Hi") never nulls `ok_button` / `no_thanks_button` / `hi_res_image`. NAME word runs
         * keep their kind's floor, 2 ("Li" still protects `chipLi` through its letter run).
         */
        const val MIN_WHOLE_VALUE_RUN = 3
    }
}
