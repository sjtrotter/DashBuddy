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
 * The ONE dynamic-value rule both static grammars share (#1160 review AJ4): a run of 8+ hex digits or 4+
 * decimal digits marks a per-frame / per-install value (a UUID, a counter), so a class or id carrying one
 * is not static. [ClassNameGrammar] judges the whole class name, [ResourceIdGrammar] the id's name part.
 */
object DynamicRuns {

    private val HEX_RUN = Regex("[0-9a-fA-F]{8}")
    private val DECIMAL_RUN = Regex("[0-9]{4}")

    fun hasDynamicRun(value: String): Boolean = HEX_RUN.containsMatchIn(value) || DECIMAL_RUN.containsMatchIn(value)
}
