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
 * The static class-name grammar (ADR-0011 §1, #1160 review CC1) — the `class` twin of
 * [ResourceIdGrammar]. `className` is app-settable (Compose, Flutter, WebView and custom views can
 * report any string), so it is chrome only when it has the shape of a Java binary class name
 * (`[A-Za-z_$][A-Za-z0-9_$]*` segments joined by `.`), is ≤ [MAX_LENGTH] characters, and carries no run
 * of 8+ hex digits and no run of 4+ decimal digits. Anything else is treated as ABSENT — null on the
 * wire, `""` in the fingerprint — never rewritten.
 */
object ClassNameGrammar {

    const val MAX_LENGTH: Int = 128

    private val SHAPE = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
    private val HEX_RUN = Regex("[0-9a-fA-F]{8}")
    private val DECIMAL_RUN = Regex("[0-9]{4}")

    fun isStatic(className: String): Boolean =
        className.length <= MAX_LENGTH && SHAPE.matches(className) &&
            !HEX_RUN.containsMatchIn(className) && !DECIMAL_RUN.containsMatchIn(className)

    /** [className] when it is static, else null (absent). */
    fun staticOrNull(className: String?): String? = className?.takeIf { isStatic(it) }
}
