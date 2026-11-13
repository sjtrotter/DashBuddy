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

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The v2-only wire keys, and the decode check that keeps a v1 item strict (ADR-0011 §1, §13; #1619 review S2).
 *
 * One DTO type carries both versions, and a v2 field's default value serializes as nothing, so the DTO constructor
 * alone cannot tell a v1 item that SENDS `"hasClickAction":false` from one that omits it. The old v1 decoder refused
 * any key it did not know; [requireV1Keys] restores that: a v1 item carrying a v2 key is refused whatever the key's
 * value (`false`, `true`, `null`, `[]`). It runs on the parsed JSON before the typed decode.
 */
internal object V2WireKeys {
    /** v2 node flags. */
    val NODE: Set<String> = setOf("hasClickAction", "isVisibleToUser")

    /** v2 slot key. */
    const val SLOT: String = "shape"

    /** v2 notification key. */
    const val ACTIONS: String = "actions"

    /** Throws when [item] names a v1 schema and carries any v2 key anywhere. A non-v1 item passes untouched. */
    fun requireV1Keys(item: JsonElement) {
        val obj = item as? JsonObject ?: return
        val schema = (obj["schemaId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val carries = when (schema) {
            SkeletonSchema.SCHEMA_ID -> screenCarriesV2Key(obj)
            NotificationSkeletonSchema.SCHEMA_ID -> notificationCarriesV2Key(obj)
            else -> false
        }
        if (carries) throw SerializationException("a v1 census item carries a v2 key")
    }

    private fun slotCarries(slot: JsonElement?): Boolean = slot is JsonObject && SLOT in slot

    private fun notificationCarriesV2Key(item: JsonObject): Boolean =
        ACTIONS in item || (item["slots"] as? JsonObject)?.values?.any { slotCarries(it) } == true

    /** Iterative over the node tree, so a deep tree cannot overflow the stack before the typed decode refuses it. */
    private fun screenCarriesV2Key(item: JsonObject): Boolean {
        if (slotCarries(item["windowTitle"])) return true
        val pending = ArrayDeque<JsonElement>()
        item["root"]?.let { pending.addLast(it) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast() as? JsonObject ?: continue
            if (node.keys.any { it in NODE }) return true
            if ((node["text"] as? JsonObject)?.values?.any { slotCarries(it) } == true) return true
            (node["children"] as? JsonArray)?.forEach { pending.addLast(it) }
        }
        return false
    }
}
