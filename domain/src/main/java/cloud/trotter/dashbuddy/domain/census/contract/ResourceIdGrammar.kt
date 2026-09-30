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
 * The static resource-name grammar (ADR-0011 §1, #1160 review AA10) — the ONE owner of "may this view
 * id travel in the clear and key the cluster fingerprint?".
 *
 * `id` is chrome by construction only when it is a STATIC resource name. DoorDash's Compose test tags
 * are not always static: three committed frames carry `PRIMARY_BUTTON_<per-frame UUID>`, which would
 * make that surface's fingerprint unique per frame (never reaching k) and ship an unshaped value. A
 * dynamic id is therefore treated as ABSENT — `id = null` on the wire AND in the fingerprint — never
 * rewritten or suffix-stripped.
 *
 * Static means: optional `<package>:id/` prefix (`[a-z][a-z0-9_.]*`, ≤ [MAX_PACKAGE_LENGTH]); a name
 * `[A-Za-z_][A-Za-z0-9_-]*` of ≤ [MAX_NAME_LENGTH] characters (no spaces — a space-bearing id reads as
 * text); and in the name no run of 8+ hex digits and no run of 4+ decimal digits. `bc25_fab`,
 * `a11y_clock`, `otp_5_input_field`, `Tooltip-0` pass. The builder still runs the §2 PII-id step on
 * the RAW id, so gating the wire never weakens withholding.
 */
object ResourceIdGrammar {

    const val MAX_NAME_LENGTH: Int = 64
    const val MAX_PACKAGE_LENGTH: Int = 128

    private val SHAPE = Regex("^(?:([a-z][a-z0-9_.]*):id/)?([A-Za-z_][A-Za-z0-9_-]*)$")
    private val HEX_RUN = Regex("[0-9a-fA-F]{8}")
    private val DECIMAL_RUN = Regex("[0-9]{4}")

    /** True when [id] may be shipped in the clear and used in the fingerprint. */
    fun isStatic(id: String): Boolean {
        val m = SHAPE.matchEntire(id) ?: return false
        val pkg = m.groupValues[1]
        val name = m.groupValues[2]
        if (pkg.length > MAX_PACKAGE_LENGTH || name.length > MAX_NAME_LENGTH) return false
        return !HEX_RUN.containsMatchIn(name) && !DECIMAL_RUN.containsMatchIn(name)
    }

    /** [id] when it is static, else null (absent) — the builder's one call. */
    fun staticOrNull(id: String?): String? = id?.takeIf { isStatic(it) }
}
