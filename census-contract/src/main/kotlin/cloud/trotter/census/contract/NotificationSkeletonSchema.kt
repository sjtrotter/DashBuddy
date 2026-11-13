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

/** Strict notification wire schema, sharing the screen JSON settings and item-size limit. */
object NotificationSkeletonSchema {
    /** The v1 notification schema ([SkeletonVersion.V1]). */
    const val SCHEMA_ID: String = "notification.skeleton.v1"

    /** The v2 notification schema ([SkeletonVersion.V2]): shape slots and the action labels as slots (#1619). */
    const val SCHEMA_ID_V2: String = "notification.skeleton.v2"

    fun serialize(item: NotificationSkeletonDto): String = SkeletonSchema.json.encodeToString(
        NotificationSkeletonDto.serializer(),
        item.copy(slots = NotifTextField.entries.associateWith { item.slots.getValue(it) }),
    )

    /** Strict decode; a v1 item carrying any v2 key is refused whatever its value ([V2WireKeys]). */
    fun deserialize(text: String): NotificationSkeletonDto {
        // The guard reads a parsed copy; the typed decode stays the strict string decoder it always was.
        V2WireKeys.requireV1Keys(SkeletonSchema.json.parseToJsonElement(text))
        return SkeletonSchema.json.decodeFromString(NotificationSkeletonDto.serializer(), text)
    }

    /** Measured against [SkeletonSchema.MAX_ITEM_BYTES] by the publication/ingest gate. */
    fun measure(item: NotificationSkeletonDto): SkeletonSchema.Measured {
        val json = serialize(item)
        return SkeletonSchema.Measured(json, json.toByteArray(Charsets.UTF_8).size)
    }
}
