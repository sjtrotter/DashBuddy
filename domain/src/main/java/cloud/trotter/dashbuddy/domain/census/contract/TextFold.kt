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
 * The ONE glyph fold shared by the census canonical form and the sensitive-marker scan (#1160 review
 * NN5): NFKC (fullwidth letters/digits, NBSP and other compatibility forms → their plain forms), then
 * every `Character.FORMAT` char stripped (zero-width space/joiners, BOM — a marker split by an invisible
 * char rejoins), then the Unicode dashes U+2010–U+2015 and U+2212 folded to ASCII `-`.
 *
 * Char-based on purpose: `SensitiveTextMarkers.normalize` delegates here and its behaviour is pinned
 * byte-for-byte to the pre-#1160 loop (`SensitiveTextMarkersNormalizePinTest`). Whitespace handling and
 * case are the CALLER's: the marker scan maps each whitespace char to a space and lowercases; the census
 * trims and collapses whitespace runs ([CensusHash.canonical]).
 */
object TextFold {

    fun foldGlyphs(value: String): String {
        val nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when {
                Character.getType(ch) == Character.FORMAT.toInt() -> {} // strip zero-width / format
                ch in '\u2010'..'\u2015' || ch == '\u2212' -> sb.append('-') // unicode dashes → hyphen
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
