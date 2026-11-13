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

import kotlinx.serialization.json.Json

/**
 * The `uinode.skeleton.v1` / `uinode.skeleton.v2` wire schema (ADR-0011 §1, §13; ADR-0003 versioning rules apply;
 * the versions are [SkeletonVersion]) — the census
 * sibling of `UiNodeSchema`. Deliberately NOT a `CaptureSchema`: the contract package depends on
 * nothing outside the JDK, kotlinx-serialization and `sha256OrNull`.
 *
 * Encoding is canonical by construction: declaration-order properties, defaults and nulls omitted,
 * and the builder inserts each node's text keys in `UiNodeTextField` order. Decoding is STRICT — an
 * unknown field is rejected (the server rejects unknown fields everywhere except the per-node text
 * map's keys, §1).
 */
object SkeletonSchema {

    /** The v1 screen schema ([SkeletonVersion.V1]). */
    const val SCHEMA_ID: String = "uinode.skeleton.v1"

    /** The v2 screen schema ([SkeletonVersion.V2]): shape slots and the click/visibility node flags (#1619). */
    const val SCHEMA_ID_V2: String = "uinode.skeleton.v2"

    /** Size cap on one item: uncompressed UTF-8 bytes of its serialized JSON, metadata included. */
    const val MAX_ITEM_BYTES: Int = 65_536

    val json: Json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun serialize(item: UiSkeletonDto): String = json.encodeToString(UiSkeletonDto.serializer(), item)

    /** Strict decode; a v1 item carrying any v2 key is refused whatever its value ([V2WireKeys]). */
    fun deserialize(text: String): UiSkeletonDto {
        // The guard reads a parsed copy; the typed decode stays the strict string decoder it always was.
        V2WireKeys.requireV1Keys(json.parseToJsonElement(text))
        return json.decodeFromString(UiSkeletonDto.serializer(), text)
    }

    /** A serialized item and its size in uncompressed UTF-8 bytes — what [MAX_ITEM_BYTES] caps. */
    data class Measured(val json: String, val bytes: Int)

    /** Serialize [item] ONCE and measure it (#1160 review CC6: the one owner of the size rule). */
    fun measure(item: UiSkeletonDto): Measured {
        val json = serialize(item)
        return Measured(json, json.toByteArray(Charsets.UTF_8).size)
    }
}
