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
 * The ONE owner of the glyph folds (#1160 reviews NN5, OO1): NFKC (fullwidth letters/digits, NBSP and
 * other compatibility forms → their plain forms), `Character.FORMAT` stripped (zero-width space/joiners,
 * BOM — a marker split by an invisible char rejoins), and the Unicode dashes U+2010–U+2015 and U+2212
 * folded to ASCII `-` — in two pinned ORDERS: [foldForCensus] (FORMAT strip first, a fixed point) and
 * [foldGlyphs] (NFKC first, the sensitive-marker scan's legacy order).
 *
 * `SensitiveTextMarkers.normalize` delegates to [foldGlyphs] and its behaviour is pinned
 * (`SensitiveTextMarkersNormalizePinTest`). Whitespace handling and
 * case are the CALLER's: the marker scan maps each whitespace char to a space and lowercases; the census
 * trims and collapses whitespace runs ([CensusHash.canonical]).
 */
object TextFold {

    /**
     * The census's ORDERED fold (review OO1): FORMAT code points stripped FIRST, then NFKC, then the dash
     * fold — so a combining mark hidden behind a zero-width joiner composes in the one NFKC pass and the
     * census canonical form is a fixed point. Code-point based. Distinct from [foldGlyphs], whose
     * NFKC-first order is pinned byte-for-byte for the sensitive-marker scan.
     */
    fun foldForCensus(value: String): String {
        val stripped = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (Character.getType(cp) != Character.FORMAT.toInt()) stripped.appendCodePoint(cp)
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
     * The sensitive-marker scan's fold — NFKC first, then FORMAT strip and dash fold, by code point. Its
     * behaviour is pinned (`SensitiveTextMarkersNormalizePinTest`): the pre-#1160 loop, deliberately
     * widened by review PP5 to strip supplementary-plane FORMAT chars too; the census uses [foldForCensus].
     */
    fun foldGlyphs(value: String): String {
        val nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            when {
                // Review PP5: by CODE POINT, so a supplementary-plane FORMAT char (the U+E0020–E007F tag
                // chars, U+E0001, U+1D173–1D17A) is stripped too — per UTF-16 unit it was a surrogate pair,
                // never FORMAT, and still split a marker.
                Character.getType(cp) == Character.FORMAT.toInt() -> {} // strip zero-width / format
                cp in 0x2010..0x2015 || cp == 0x2212 -> sb.append('-') // unicode dashes → hyphen
                else -> sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
