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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Explicit dispatch: no polymorphic discriminator is injected into legacy screens. */
object CensusSkeletonSchema {
    val SUPPORTED_SCHEMA_IDS: List<String> = listOf(SkeletonSchema.SCHEMA_ID, NotificationSkeletonSchema.SCHEMA_ID)

    fun serialize(item: CensusSkeletonDto): String = when (item) {
        is UiSkeletonDto -> SkeletonSchema.serialize(item)
        is NotificationSkeletonDto -> NotificationSkeletonSchema.serialize(item)
    }

    fun deserialize(text: String): CensusSkeletonDto {
        val obj = SkeletonSchema.json.parseToJsonElement(text) as? JsonObject
            ?: throw SerializationException("expected a census object")
        val schema = (obj["schemaId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (schema) {
            SkeletonSchema.SCHEMA_ID -> SkeletonSchema.deserialize(text)
            NotificationSkeletonSchema.SCHEMA_ID -> NotificationSkeletonSchema.deserialize(text)
            else -> throw SerializationException("unsupported census schema")
        }
    }

    fun measure(item: CensusSkeletonDto): SkeletonSchema.Measured = when (item) {
        is UiSkeletonDto -> SkeletonSchema.measure(item)
        is NotificationSkeletonDto -> NotificationSkeletonSchema.measure(item)
    }

    fun slots(item: CensusSkeletonDto): List<TextSlot> = when (item) {
        is NotificationSkeletonDto -> NotifTextField.entries.map { item.slots.getValue(it) }
        is UiSkeletonDto -> buildList {
            item.windowTitle?.let { add(it) }
            fun visit(node: UiSkeletonNodeDto) {
                addAll(node.text.values)
                node.children.forEach { visit(it) }
            }
            visit(item.root)
        }
    }
}
