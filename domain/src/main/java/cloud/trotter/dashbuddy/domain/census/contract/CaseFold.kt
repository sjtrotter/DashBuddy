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

import java.util.Locale

/**
 * The ONE Unicode case fold the census uses for case-insensitive token comparison (#1160 review HH1).
 *
 * Lowercasing alone is not a case-insensitive comparison: `ß` has no single-letter uppercase-lowercase
 * round trip (`GROSS` vs `Groß`), and Greek sigma has a final form (`ς`) and a medial one (`σ`). The fold
 * is `uppercase(ROOT)` then `lowercase(ROOT)` (ß → SS → ss; ς/σ → Σ), with any remaining final sigma
 * mapped to the medial form so a run's position in its string cannot change its fold.
 */
object CaseFold {

    fun fold(value: String): String =
        value.uppercase(Locale.ROOT).lowercase(Locale.ROOT).replace('ς', 'σ')
}
