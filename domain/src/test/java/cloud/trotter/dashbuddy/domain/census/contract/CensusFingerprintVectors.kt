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
 * Golden vectors for [CensusFingerprint] (ADR-0011 §8). They live with the contract's TESTS (#1160
 * review GG9 — never shipped in the APK); a future server consumer (#1157) takes them as a published
 * test-fixtures artifact. The pinned hex values were computed by an INDEPENDENT implementation of the §8 byte
 * form (a short Python script, not this Kotlin), so they check the algorithm, not a snapshot of it.
 * Regenerated for the length-prefixed encoding (#1160 review AA2).
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
        Pinned("single node", title, "412ea97cebef7fce763ae82bef6aa9b32c14bbfa17d847a4c1d6e49b83a43496"),
        Pinned("null id", NULL_ID, "b953104254b69ae14bff2b0ebbc5bdabff72faad70068fc7ea49a3b7aad0b340"),
        Pinned("empty id", EMPTY_ID, "a91614370e3ff0e734b49b46533e149941518e42a7062916f2eb80663bf91517"),
        Pinned("flat", FLAT, "865c0a09f9ef2510a99b3a1285bc29a22e6b47a8bdcd256d3446c8321a7ed66f"),
        Pinned("multi-child wrapper", MULTI_CHILD_WRAPPER, "865c0a09f9ef2510a99b3a1285bc29a22e6b47a8bdcd256d3446c8321a7ed66f"),
        Pinned("nested", NESTED, "e8f5491c07ebf95c25f659b6c65def1eed40cc580dd945ff759ada113d183e71"),
        Pinned("siblings", SIBLINGS, "a9b4278727edbe586287cb4451f97c228f14c3e3f6ebf57507f8ad4f5e1aacc9"),
        Pinned("wrapper root", WRAPPER_ROOT, "412ea97cebef7fce763ae82bef6aa9b32c14bbfa17d847a4c1d6e49b83a43496"),
        Pinned("empty wrapper", EMPTY_WRAPPER, "67185dad87a164435a104b86d05fc30ed43d5687be0df40ea544a63d369663c3"),
        Pinned(
            "wrapper class WITH an id is not transparent",
            WRAPPER_CLASS_WITH_ID,
            "6dfe76bcd1329afdabbf3393d94f42798ff591b5a47324a8bea6d9b5a7bda83b",
        ),
        Pinned(
            "UTF-8 id bytes",
            node(TEXT_CLASS, "com.example:id/café"),
            "03c247acf1220e3284014331a15827779ffb289e8f9dbc4168296fcdc61c0ef2",
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
