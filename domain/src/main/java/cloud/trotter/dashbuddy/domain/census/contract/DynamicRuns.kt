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

/**
 * The ONE dynamic-value rule both static grammars share (#1160 reviews AJ4, AL1): a maximal run of 8+ hex
 * characters that contains at least ONE decimal digit, or a run of 4+ decimal digits, marks a per-frame /
 * per-install value (a UUID, a counter), so a class or id carrying one is not static. A letter-only hex run
 * is a word, not a value (`HapticFeedbackConstants` → `cFeedbac`, `AddedBadgeView` → `AddedBad`).
 * [ClassNameGrammar] judges the whole class name, [ResourceIdGrammar] the id's name part.
 */
object DynamicRuns {

    /**
     * Review AM2: a UUID-shaped token anywhere is dynamic REGARDLESS of digits — its hex groups can all be
     * letters (`deadbeef-acde-4abc-…` still has the version digit, but `…-acde-acde-…` groups may not).
     */
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun hasDynamicRun(value: String): Boolean {
        if (UUID.containsMatchIn(value)) return true
        var hexRun = 0
        var hexDigits = 0
        var decimalRun = 0
        for (ch in value) {
            val decimal = ch in '0'..'9'
            val hex = decimal || ch in 'a'..'f' || ch in 'A'..'F'
            decimalRun = if (decimal) decimalRun + 1 else 0
            if (decimalRun >= 4) return true
            if (hex) {
                hexRun++
                if (decimal) hexDigits++
                if (hexRun >= 8 && hexDigits > 0) return true
            } else {
                hexRun = 0
                hexDigits = 0
            }
        }
        return false
    }
}
