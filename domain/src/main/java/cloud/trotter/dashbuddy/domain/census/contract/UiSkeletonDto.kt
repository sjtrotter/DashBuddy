/*
 * Copyright 2026 Stephen Trotter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Census wire contract (ADR-0011). This package depends on nothing but the JDK,
 * kotlinx-serialization, `domain.util.sha256OrNull` and
 * `domain.model.accessibility.AnonymousWrappers`, so that extracting it to a standalone
 * Apache-2.0 `census-contract/` build is a move, not a rewrite.
 */
package cloud.trotter.dashbuddy.domain.census.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One text field of a skeleton node (or the window title), ADR-0011 §1: a coarse [kind] and, only
 * when the §2 filter admitted a hash, [h]. **There is no plaintext slot** — the value itself never
 * enters this type, so a text leak is a type error rather than a policy breach.
 *
 * Invariants checked at construction (so a decoded item that violates them is rejected, too):
 * [kind] is a wire kind of [KindClassifier]; [h] is present IFF [kind] is `words:1..8`; [h] is
 * [CensusHash.HEX_LENGTH] lowercase hex.
 */
@Serializable
data class TextSlot(
    val h: String? = null,
    val kind: String,
) {
    init {
        require(KindClassifier.isWireKind(kind)) { "unknown census kind" }
        require((h != null) == KindClassifier.isHashableKind(kind)) {
            "a hash is present iff the kind is words:1..8"
        }
        if (h != null) require(CensusHash.isWellFormed(h)) { "a census hash is 16 lowercase hex" }
    }

    companion object {
        /** The constant emitted when a withholding step fired: no hash, no length, no count. */
        val WITHHELD: TextSlot = TextSlot(kind = KindClassifier.WITHHELD)
    }
}

/**
 * One node of the `uinode.skeleton.v1` tree (ADR-0011 §1): class, view id (the platform's own resource
 * name), the three flags, the per-field [text] map and the children — in tree ORDER only.
 *
 * **No bounds, no other string field.** [text] is keyed by the `UiNodeTextField` wire key (`text`,
 * `desc`, `state`, `pane`, … `uid`); a blank or null field is OMITTED, never emitted. Every value has
 * the same [TextSlot] shape, which is why a new enum entry is a new key rather than a schema bump.
 */
@Serializable
data class UiSkeletonNodeDto(
    @SerialName("class") val className: String? = null,
    val id: String? = null,
    val isClickable: Boolean = false,
    val isEnabled: Boolean = false,
    /** The `UiNode` tri-state: 0 unchecked, 1 checked, 2 partial. */
    val isChecked: Int = 0,
    val text: Map<String, TextSlot> = emptyMap(),
    val children: List<UiSkeletonNodeDto> = emptyList(),
) {
    init {
        require(isChecked in 0..2) { "isChecked is the 0/1/2 tri-state" }
        // ADR-0011 §8: class/id must be well-formed UTF-16 with no U+0000 ([WireStrings]) — a lone
        // surrogate encodes to the same UTF-8 bytes as `?`, which would collide two fingerprints.
        require(className == null || WireStrings.isWellFormed(className)) { "a class name must be well-formed UTF-16 without U+0000" }
        require(id == null || WireStrings.isWellFormed(id)) { "a view id must be well-formed UTF-16 without U+0000" }
        // Review II2: the static class/id gates have ONE owner and run at construction AND decode, so a
        // forked or old client cannot post a UUID id or a free-text class the server would cluster on.
        require(className == null || ClassNameGrammar.isStatic(className)) { "a class name must be a static binary class name" }
        require(id == null || ResourceIdGrammar.isStaticShape(id)) { "a view id must have the static resource-name shape" }
        // Review UU1: a text-map KEY is a field name, never free text. Pinned to the wire-key SHAPE (every
        // `UiNodeTextField.wire` matches); an unknown key of that shape stays accepted, so a new enum entry
        // is still not a schema bump (ADR-0011 §1).
        require(text.keys.all { TEXT_KEY_SHAPE.matches(it) }) { "a text-map key must have the wire-key shape" }
    }

    companion object {
        /** The wire-key shape of a text-map key (review UU1). */
        private val TEXT_KEY_SHAPE = Regex("^[a-z][A-Za-z0-9]{0,15}$")
    }
}

/**
 * One census item — a `uinode.skeleton.v1` skeleton with its envelope (ADR-0011 §1).
 *
 * The envelope is exactly the §7(a) allowlist: the window title as a [TextSlot]; the strings
 * [schemaId], [fingerprint], [platform], [platformAppVersion], [appVersion], [rulesetReleaseTag],
 * [day]; the integers [filterRev], [hashDomain], [engineVersion], [rulesetFormatVersion]. Five of
 * those are `ReplayMetadata`'s own names; the rest are census-own. [root] carries the tree. There is
 * NO install id (the M3 uploader adds it at the transport), NO device fingerprint, NO millisecond
 * timestamp.
 */
@Serializable
data class UiSkeletonDto(
    val schemaId: String,
    val hashDomain: Int,
    val filterRev: Int,
    val fingerprint: String,
    val platform: String,
    val platformAppVersion: String? = null,
    val appVersion: String? = null,
    val rulesetReleaseTag: String? = null,
    val engineVersion: Int,
    val rulesetFormatVersion: Int? = null,
    val day: String,
    val windowTitle: TextSlot? = null,
    val root: UiSkeletonNodeDto,
) {
    init {
        require(schemaId == SkeletonSchema.SCHEMA_ID) { "not a ${SkeletonSchema.SCHEMA_ID} item" }
        require(hashDomain == CensusHash.HASH_DOMAIN) { "unsupported hash domain" }
        require(filterRev >= 1) { "filterRev is mandatory and positive" }
        require(CensusFingerprint.isWellFormed(fingerprint)) { "fingerprint is 64 lowercase hex" }
        require(isPlatformWire(platform)) { "platform is a short lowercase wire id" }
        require(isDay(day)) { "day is YYYY-MM-DD" }
        listOfNotNull(platformAppVersion, appVersion, rulesetReleaseTag).forEach {
            require(it.length <= MAX_VERSION_LENGTH) { "a version stamp is at most $MAX_VERSION_LENGTH chars" }
            require(WireStrings.isWellFormed(it)) { "a version stamp must be well-formed UTF-16 without U+0000" }
        }
    }

    companion object {
        /** A version stamp is a short release fact, never free text. */
        const val MAX_VERSION_LENGTH: Int = 64

        private const val MAX_PLATFORM_LENGTH = 32

        private fun isPlatformWire(s: String): Boolean =
            s.isNotEmpty() && s.length <= MAX_PLATFORM_LENGTH &&
                s.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

        /**
         * `YYYY-MM-DD` naming a real calendar date (the day bucket at rest; no finer time ever rides the
         * item). Reviews EE3/GG7: a shape-only check admitted `2026-99-99` and `2026-02-31`.
         */
        private fun isDay(s: String): Boolean {
            if (s.length != 10 || s[4] != '-' || s[7] != '-') return false
            if (!(0 until 10).filter { it != 4 && it != 7 }.all { s[it] in '0'..'9' }) return false
            // Review GG7: a REAL calendar date (ISO_LOCAL_DATE resolves STRICT — `2026-02-31` is refused).
            return try {
                java.time.LocalDate.parse(s)
                true
            } catch (_: java.time.format.DateTimeParseException) {
                false
            }
        }
    }
}
