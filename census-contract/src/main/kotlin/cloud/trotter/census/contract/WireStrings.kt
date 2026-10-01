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
 * Census wire contract (ADR-0011, #1173). This Apache-2.0 included build depends
 * on nothing but the JDK and kotlinx-serialization.
 */
package cloud.trotter.census.contract

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

    /**
     * True when [s] is exactly [length] LOWERCASE hex characters — the one shape check for the census
     * hash (16) and the cluster fingerprint (64) (#1160 review CC6).
     */
    fun isLowerHex(s: String, length: Int): Boolean =
        s.length == length && s.all { it in '0'..'9' || it in 'a'..'f' }
}
