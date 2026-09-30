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
 * The static resource-name grammar (ADR-0011 §1, #1160 review AA10) — the ONE owner of "may this view
 * id travel in the clear and key the cluster fingerprint?".
 *
 * `id` is chrome by construction only when it is a STATIC resource name. DoorDash's Compose test tags
 * are not always static: three committed frames carry `PRIMARY_BUTTON_<per-frame UUID>`, which would
 * make that surface's fingerprint unique per frame (never reaching k) and ship an unshaped value. A
 * dynamic id is therefore treated as ABSENT — `id = null` on the wire AND in the fingerprint — never
 * rewritten or suffix-stripped.
 *
 * Static means: optional `<package>:id/` prefix (`[A-Za-z][A-Za-z0-9_.]*` — an applicationId may carry
 * upper case — ≤ [MAX_PACKAGE_LENGTH]); a name `[A-Za-z_][A-Za-z0-9_.-]*` with at most ONE internal
 * space (static GoPuff test tags such as `Artwork Image`; dotted Compose test tags) of ≤
 * [MAX_NAME_LENGTH] characters; and in the name no dynamic run ([DynamicRuns]: 8+ hex with a digit, or 4+ decimal
 * digits) — the dynamic-id rule. `bc25_fab`, `a11y_clock`, `otp_5_input_field`, `Tooltip-0` pass
 * (#1160 review CC7 widened the shape; the run rule is unchanged). The builder still runs the §2 PII-id step on
 * the RAW id, so gating the wire never weakens withholding.
 */
object ResourceIdGrammar {

    const val MAX_NAME_LENGTH: Int = 64
    const val MAX_PACKAGE_LENGTH: Int = 128

    /**
     * The reserved id a node carries when its STATIC-shaped id was withheld by the builder — by the frame
     * rule, or by the frame-free PII judgement of the id itself (ADR-0011 §1/§8, #1160 reviews AC3, AH1) —
     * the one allowed non-grammar id value. It keeps the node a non-wrapper, so
     * the fingerprint's tree STRUCTURE never depends on which customer is on the frame (a nulled
     * `LinearLayout` id would otherwise become a spliced wrapper). A grammar-rejected (dynamic) id stays
     * null: it is rejected identically on every frame. Not a static shape (`~` is outside the grammar).
     */
    const val FRAME_WITHHELD_ID: String = "~"

    /** A wire id the DTO and the server accept: a static shape, or [FRAME_WITHHELD_ID] (review AC3). */
    fun isWireId(id: String): Boolean = id == FRAME_WITHHELD_ID || isStaticShape(id)

    private val SHAPE = Regex("^(?:([A-Za-z][A-Za-z0-9_.]*):id/)?([A-Za-z_][A-Za-z0-9_.-]*(?: [A-Za-z0-9_.-]+)?)$")

    /**
     * True when [id] has the STATIC resource-name SHAPE. Necessary, not sufficient (#1160 review II3):
     * the shape cannot tell `chip_Gold` from a name-shaped Compose test tag, so the client builder ALSO
     * judges the id's name part through the customer-PII value predicates (which live in
     * `:core:pipeline`, outside this contract) before an id travels. The DTO and the server enforce the
     * shape; the PII judgement is client-side.
     */
    fun isStaticShape(id: String): Boolean {
        // Review NN6: bound the input BEFORE the regex (the class grammar's order): prefix + ":id/" + name.
        if (id.length > MAX_PACKAGE_LENGTH + 4 + MAX_NAME_LENGTH) return false
        val m = SHAPE.matchEntire(id) ?: return false
        val pkg = m.groupValues[1]
        val name = m.groupValues[2]
        if (pkg.length > MAX_PACKAGE_LENGTH || name.length > MAX_NAME_LENGTH) return false
        return !DynamicRuns.hasDynamicRun(name)
    }

    /** The resource NAME part of [id] (after `:id/`, or the whole id when it has no prefix). */
    fun namePart(id: String): String = id.substringAfter(":id/")
}
