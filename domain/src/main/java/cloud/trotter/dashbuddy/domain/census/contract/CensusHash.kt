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

import cloud.trotter.dashbuddy.domain.util.sha256OrNull

/**
 * The census token hash (ADR-0011 §3): `sha256("census.v1:" + canonical)`, first [HEX_LENGTH] hex, where
 * [canonical] is the trimmed, whitespace-normalized value.
 *
 * Unsalted on purpose — cross-install equality is what the server's k rule counts. The `census.v1:`
 * prefix separates the digest DOMAIN from the parse-side `customerNameHash` and the redact masks
 * (`[redacted:<4hex>]`), so a census hash never EQUALS either; it does not make them unjoinable by
 * dictionary (ADR residual risk 1), which is why the §2 filter withholds low-entropy PII shapes
 * BEFORE anything is hashed.
 *
 * FAIL CLOSED (#362): a digest failure returns null and the caller emits `withheld` — the plaintext
 * is never echoed. Callers hash only what the §2 filter admitted; this object does not filter.
 */
object CensusHash {

    /** The digest-domain prefix (hash version 1). */
    const val PREFIX: String = "census.v1:"

    /** The explicit wire discriminator for [PREFIX] (`hashDomain` in the envelope). */
    const val HASH_DOMAIN: Int = 1

    /** Hex characters kept from the full sha256. */
    const val HEX_LENGTH: Int = 16

    /**
     * The CANONICAL value (ADR-0011 §2 "Inputs and predicates", §3; #1160 reviews EE2, NN5, OO1): the
     * census glyph fold ([TextFold.foldForCensus]: FORMAT strip FIRST, then NFKC, then the dash fold),
     * then every run of code points the classifier treats as whitespace (`Character.isWhitespace ||
     * isSpaceChar` — NBSP, thin space, tab, newline…) collapsed to ONE ASCII space, then trimmed. Every
     * filter step, the grammar and the hash run on this, so the JVM (whose regex `\s` excludes NBSP) and
     * ART/ICU (whose `\s` includes `\p{Z}`) take the same decision and produce the same hash.
     *
     * A FIXED POINT (reviews OO1, SS8): a pass is applied at most [MAX_PASSES] times; the value is
     * canonical when a pass leaves it unchanged (pass k+1 == pass k for some k < [MAX_PASSES]). Stripping
     * FORMAT before NFKC lets a combining mark hidden behind a zero-width joiner compose in the first pass
     * (`A\u200D\u030Adam` → `Ådam`). A value that has not reached a fixed point within [MAX_PASSES] passes
     * has NO canonical form: `null` — the builder withholds it and `isStaticId` treats it as not static.
     */
    fun canonical(text: String): String? {
        var current = canonicalPass(text)
        repeat(MAX_PASSES - 1) {
            val next = canonicalPass(current)
            if (next == current) return current
            current = next
        }
        return null
    }

    /** The most canonicalization passes [canonical] applies. */
    const val MAX_PASSES: Int = 3

    /** One canonicalization pass (glyph fold + whitespace collapse + trim). */
    internal fun canonicalPass(text: String): String {
        val folded = TextFold.foldForCensus(text)
        val sb = StringBuilder(folded.length)
        var pendingSpace = false
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            if (KindClassifier.isWhitespace(cp)) {
                pendingSpace = true
            } else {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** The census hash of [text]'s [canonical] form, or null (no canonical form, or digest failure). */
    fun of(text: String): String? = canonical(text)?.let { ofCanonical(it) }

    /**
     * The census hash of an ALREADY-canonical value, WITHOUT re-canonicalizing (review OO1): the builder
     * hashes exactly the string its filter judged, so the judged form and the hashed form can never
     * differ.
     */
    fun ofCanonical(canonical: String): String? = ofCanonical(canonical, ::sha256OrNull)

    /** Test seam: the same computation over an injected digest, so the null path is provable. */
    internal fun ofCanonical(canonical: String, digest: (String) -> String?): String? {
        val hex = digest(PREFIX + canonical) ?: return null
        if (hex.length < HEX_LENGTH) return null
        return hex.substring(0, HEX_LENGTH)
    }

    /** Test seam over raw text (kept for the digest-failure tests). */
    internal fun of(text: String, digest: (String) -> String?): String? = canonical(text)?.let { ofCanonical(it, digest) }

    /** True when [h] has the wire shape of a census hash: exactly [HEX_LENGTH] lowercase hex. */
    fun isWellFormed(h: String): Boolean = WireStrings.isLowerHex(h, HEX_LENGTH)
}
