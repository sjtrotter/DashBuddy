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

/** Golden vectors for [ResourceIdGrammar] (ADR-0011 §1), shared with the census server (#1157). */
object ResourceIdGrammarVectors {

    /** Static ids (from the committed corpus, digit-bearing ones included) — shipped in the clear. */
    val STATIC: List<String> = listOf(
        "com.doordash.driverapp:id/customer_name",
        "com.doordash.driverapp:id/address_line_2",
        "widget_bc25_divider_text",
        "otp_5_input_field",
        "com.ubercab.driver:id/ub__intercom_composer_edit_text_v2",
        "Tooltip-0",
        "FieldWrapper-0",
        "com.ubercab.driver:id/ub__map_controls_map_type_selection_container_top_right",
        "a11y_clock",
        "bc25_fab",
    )

    /** Dynamic / text-like ids — treated as absent. The first three are the committed DoorDash frames. */
    val DYNAMIC: List<String> = listOf(
        "PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a",
        "PRIMARY_BUTTON_62132347-ff07-4f36-988d-db9d3cfa4dbd",
        "PRIMARY_BUTTON_9db4e2af-5a58-4a43-ba63-295126ceddef",
        "Artwork Image",
        "row_1234",
        "",
        "com.x:id/",
        "x".repeat(65),
        "Com.Upper:id/name",
    )
}
