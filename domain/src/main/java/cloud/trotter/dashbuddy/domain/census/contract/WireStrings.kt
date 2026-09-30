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
 * The wire-string validity rule for a skeleton node's `class` and `id` (ADR-0011 §8, #1160 review
 * BB1/BB2) — the ONE helper the DTO `init` (construction AND decode) and the builder share.
 *
 * Well-formed means: no U+0000, and well-formed UTF-16 (every high surrogate immediately followed by a
 * low surrogate, no lone low surrogate). `String.toByteArray(UTF_8)` silently replaces an unpaired
 * surrogate with `?`, so `"\uD800"` and `"?"` would encode to identical fingerprint bytes; rejecting
 * malformed UTF-16 keeps the length-prefixed byte form injective over the strings that are accepted.
 */
object WireStrings {

    fun isWellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\u0000' -> return false
                Character.isHighSurrogate(c) -> {
                    if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                    i += 2
                    continue
                }
                Character.isLowSurrogate(c) -> return false
            }
            i++
        }
        return true
    }
}
