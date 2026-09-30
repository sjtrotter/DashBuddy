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
 * Golden vectors for [CensusFingerprint] (ADR-0011 §8), shared by the client tests and the census
 * server (#1157). The pinned hex values were computed by an INDEPENDENT implementation of the §8 byte
 * form (a short Python script, not this Kotlin), so they check the algorithm, not a snapshot of it.
 *
 * Required cases: null vs empty id, an empty wrapper, a multi-child wrapper, a wrapper root, and the
 * two nesting cases (`A(B(C)) != A(B, C)`; `A(W(C1, C2)) == A(C1, C2)`).
 */
object CensusFingerprintVectors {

    data class Pinned(val name: String, val tree: UiSkeletonNodeDto, val expectedHex: String)

    data class Relation(val name: String, val left: UiSkeletonNodeDto, val right: UiSkeletonNodeDto, val equal: Boolean)

    private const val LIST_CLASS = "android.widget.ScrollView"
    private const val LIST_ID = "com.example:id/list"
    private const val TEXT_CLASS = "android.widget.TextView"
    private const val BUTTON_CLASS = "android.widget.Button"
    private const val WRAPPER_CLASS = "android.widget.FrameLayout"
    private const val TITLE_ID = "com.example:id/title"

    private fun node(className: String?, id: String?, vararg children: UiSkeletonNodeDto) =
        UiSkeletonNodeDto(className = className, id = id, children = children.toList())

    private val title = node(TEXT_CLASS, TITLE_ID)
    private val button = node(BUTTON_CLASS, null)

    val FLAT: UiSkeletonNodeDto = node(LIST_CLASS, LIST_ID, title, button)
    val MULTI_CHILD_WRAPPER: UiSkeletonNodeDto = node(LIST_CLASS, LIST_ID, node(WRAPPER_CLASS, null, title, button))
    val NESTED: UiSkeletonNodeDto = node(LIST_CLASS, LIST_ID, node(BUTTON_CLASS, null, title))
    val SIBLINGS: UiSkeletonNodeDto = node(LIST_CLASS, LIST_ID, node(BUTTON_CLASS, null), title)
    val WRAPPER_ROOT: UiSkeletonNodeDto = node(WRAPPER_CLASS, null, title)
    val EMPTY_WRAPPER: UiSkeletonNodeDto = node(LIST_CLASS, LIST_ID, node(WRAPPER_CLASS, null), title)
    val EMPTY_WRAPPER_REFERENCE: UiSkeletonNodeDto = node(LIST_CLASS, LIST_ID, title)
    val NULL_ID: UiSkeletonNodeDto = node(TEXT_CLASS, null)
    val EMPTY_ID: UiSkeletonNodeDto = node(TEXT_CLASS, "")
    val WRAPPER_CLASS_WITH_ID: UiSkeletonNodeDto =
        node(LIST_CLASS, LIST_ID, node(WRAPPER_CLASS, "com.example:id/frame", title, button))

    val PINNED: List<Pinned> = listOf(
        Pinned("single node", title, "93fa814cb93ea9275e8041edd8a4f3b8af20470dc8ef8f6cc1c645dbda712e81"),
        Pinned("null id", NULL_ID, "0a80cfd6bab01c665402cdd10e621c5fc9f3de0072da2add9b65fbac8dd8e98c"),
        Pinned("empty id", EMPTY_ID, "81d3c52e650801db0b1fb4967611373ce78cd2ddb4a0d97193b600073c9457d5"),
        Pinned("flat", FLAT, "e8f54e959044b1ff46b14a8a8cf28e6c32f388f6ee2077968bcfb1477c0436dd"),
        Pinned("multi-child wrapper", MULTI_CHILD_WRAPPER, "e8f54e959044b1ff46b14a8a8cf28e6c32f388f6ee2077968bcfb1477c0436dd"),
        Pinned("nested", NESTED, "1bd91b3ce0eed4e8d271a5ffe43b6109bcda74773a78dbc6a7b90fd9464a3760"),
        Pinned("siblings", SIBLINGS, "4de4beb4c5b67f97312d3ad8fb0ef959cfbcd8e1b1a86af680fa2fdf76027ac5"),
        Pinned("wrapper root", WRAPPER_ROOT, "93fa814cb93ea9275e8041edd8a4f3b8af20470dc8ef8f6cc1c645dbda712e81"),
        Pinned("empty wrapper", EMPTY_WRAPPER, "f5965eed6590dab8c5b45baeafdabfa52814c135adcafcda9aa6ddb96693f959"),
        Pinned(
            "wrapper class WITH an id is not transparent",
            WRAPPER_CLASS_WITH_ID,
            "d55ca17c04310dd67dcf9884ea3fd9aaddd7c26f7388d30789c0828f30fa985c",
        ),
        Pinned(
            "UTF-8 id bytes",
            node(TEXT_CLASS, "com.example:id/café"),
            "741fc9c412e91b9d1d898c3fdf35b867b043220ac8f3a707f915e8c85ac4a65b",
        ),
    )

    val RELATIONS: List<Relation> = listOf(
        Relation("null id differs from empty id", NULL_ID, EMPTY_ID, equal = false),
        Relation("A(W(C1, C2)) == A(C1, C2)", MULTI_CHILD_WRAPPER, FLAT, equal = true),
        Relation("A(B(C)) != A(B, C)", NESTED, SIBLINGS, equal = false),
        Relation("fingerprint(A) == fingerprint(W(A))", WRAPPER_ROOT, title, equal = true),
        Relation("an empty wrapper contributes nothing", EMPTY_WRAPPER, EMPTY_WRAPPER_REFERENCE, equal = true),
        Relation("a wrapper class with an id is a real node", WRAPPER_CLASS_WITH_ID, FLAT, equal = false),
    )
}
