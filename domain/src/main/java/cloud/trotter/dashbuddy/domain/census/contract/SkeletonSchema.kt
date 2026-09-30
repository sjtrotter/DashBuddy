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

import kotlinx.serialization.json.Json

/**
 * The `uinode.skeleton.v1` wire schema (ADR-0011 §1; ADR-0003 versioning rules apply) — the census
 * sibling of `UiNodeSchema`. Deliberately NOT a `CaptureSchema`: the contract package depends on
 * nothing outside the JDK, kotlinx-serialization and `sha256OrNull`.
 *
 * Encoding is canonical by construction: declaration-order properties, defaults and nulls omitted,
 * and the builder inserts each node's text keys in `UiNodeTextField` order. Decoding is STRICT — an
 * unknown field is rejected (the server rejects unknown fields everywhere except the per-node text
 * map's keys, §1).
 */
object SkeletonSchema {

    const val SCHEMA_ID: String = "uinode.skeleton.v1"

    /** Size cap on one item: uncompressed UTF-8 bytes of its serialized JSON, metadata included. */
    const val MAX_ITEM_BYTES: Int = 65_536

    val json: Json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun serialize(item: UiSkeletonDto): String = json.encodeToString(UiSkeletonDto.serializer(), item)

    fun deserialize(text: String): UiSkeletonDto = json.decodeFromString(UiSkeletonDto.serializer(), text)

    /** The serialized item's size in uncompressed UTF-8 bytes — what [MAX_ITEM_BYTES] caps. */
    fun itemBytes(item: UiSkeletonDto): Int = serialize(item).toByteArray(Charsets.UTF_8).size
}
