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

import java.text.Normalizer

/**
 * The ONE owner of the glyph folds (#1160 reviews NN5, OO1, RR1, UU8): NFKC (fullwidth letters/digits, NBSP
 * and other compatibility forms → their plain forms), `Character.FORMAT` stripped (zero-width space/joiners,
 * BOM, tag chars — a marker split by an invisible char rejoins), and the Unicode dashes U+2010–U+2015 and
 * U+2212 folded to ASCII `-`. TWO pinned folds:
 * - [foldForCensus] — FORMAT stripped by code point FIRST, then NFKC, then dashes: the census canonical form
 *   (a fixed point) ONLY — not a sensitive-scan form (review WW1);
 * - [foldGlyphsPreservingSupplementary] — byte-for-byte the pre-#1160 normalizer (NFKC first, per UTF-16
 *   unit, supplementary FORMAT chars KEPT): the sensitive scan's boundary-preserving form (review RR1:
 *   the order matters for a shape pattern's `\b`); the scan's stripped form is this output minus its
 *   supplementary FORMAT code points (review WW1).
 * Whitespace handling and case are the CALLER's.
 */
object TextFold {

    /** The ONE "is this a `Character.FORMAT` code point" predicate (#1160 review AB7). */
    fun isFormat(cp: Int): Boolean = Character.getType(cp) == Character.FORMAT.toInt()

    /** A supplementary-plane FORMAT code point (a tag char, …) — the RR1/WW1 dual-form trigger (review AB7). */
    fun isSupplementaryFormat(cp: Int): Boolean = cp >= 0x10000 && isFormat(cp)

    /**
     * The census's ORDERED fold (review OO1): FORMAT code points stripped FIRST, then NFKC, then the dash
     * fold — so a combining mark hidden behind a zero-width joiner composes in the one NFKC pass and the
     * census canonical form is a fixed point. Code-point based. The census's own fold — never a
     * sensitive-scan form (review WW1).
     */
    fun foldForCensus(value: String): String {
        val stripped = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (!isFormat(cp)) stripped.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        val nfkc = Normalizer.normalize(stripped, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            if (ch in '\u2010'..'\u2015' || ch == '\u2212') sb.append('-') else sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * The sensitive-marker scan's BOUNDARY-PRESERVING fold — exactly the pre-#1160 loop, per UTF-16 unit:
     * a BMP FORMAT char is stripped, a supplementary-plane one (a surrogate pair — never FORMAT per unit)
     * is KEPT. Review RR1: stripping it can REMOVE a word boundary a shape pattern relies on
     * (`x<U+E0020>123-45-6789` → `x123-45-6789` defeats the SSN's `\b`), so the scan runs this form AND
     * the same output minus its supplementary FORMAT code points, and drops on either hit.
     */
    fun foldGlyphsPreservingSupplementary(value: String): String {
        val nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when {
                isFormat(ch.code) -> {}
                ch in '\u2010'..'\u2015' || ch == '\u2212' -> sb.append('-')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

}
