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
 * kotlinx-serialization and `domain.util.sha256OrNull`, so that extracting it to a
 * standalone Apache-2.0 `census-contract/` build is a move, not a rewrite.
 */
package cloud.trotter.dashbuddy.domain.census.contract

/**
 * Golden vectors for [KindClassifier.shapeKind] (ADR-0011 §1) — shared by the client tests AND the
 * census server (#1157), so the two implementations of the grammar cannot disagree silently.
 *
 * Each vector is the trimmed, capped INPUT and the SHAPE kind the grammar yields for it. The shape is
 * not always what is emitted: the §2 filter may still withhold (e.g. `Apt 12` is `mixed` by shape and
 * `withheld` once the APT pattern runs — that end-to-end vector lives with the builder).
 */
object KindClassifierVectors {

    data class Vector(val input: String, val expectedWire: String, val note: String)

    val VECTORS: List<Vector> = listOf(
        Vector("\$45.66", KindClassifier.MIXED, "money is mixed, never its own class"),
        Vector("4821", KindClassifier.DIGITS, "all decimal digits"),
        Vector("48 21", KindClassifier.DIGITS, "whitespace does not break digits"),
        Vector("Pickup & delivery", "words:2", "a symbol-only run is dropped before counting"),
        Vector("O'Brien", "words:1", "interior apostrophe kept, one run"),
        Vector("Drop-off", "words:1", "interior hyphen kept, one run"),
        Vector("→", KindClassifier.MIXED, "every run dropped: N = 0 is mixed"),
        Vector("aa bb cc dd ee ff gg hh ii", KindClassifier.WORDS_OVER, "nine runs is body text"),
        Vector("aa bb cc dd ee ff gg hh", "words:8", "eight runs is the hashable ceiling"),
        Vector("Apt 12", KindClassifier.MIXED, "a digit-bearing run makes the token mixed"),
        Vector("Accept", "words:1", "a plain chrome label"),
        Vector("Hand it to me", "words:4", "a sentence of chrome"),
        Vector("Hand it to me:", "words:4", "trailing punctuation stripped"),
        Vector("\"Accept\"", "words:1", "leading and trailing quotes stripped"),
        Vector("7:45 PM", KindClassifier.MIXED, "a clock time is mixed"),
        Vector("3.2 mi", KindClassifier.MIXED, "a distance is mixed"),
        Vector("Order #1234", KindClassifier.MIXED, "an order number is mixed"),
        Vector("٤٨٢١", KindClassifier.DIGITS, "Arabic-Indic digits are Unicode decimal digits"),
        Vector("𐐀bc", "words:1", "a supplementary-plane letter counts (code-point test)"),
        Vector("--", KindClassifier.MIXED, "punctuation only"),
        Vector("🚗 Go", "words:1", "an emoji-only run is dropped"),
    )
}
