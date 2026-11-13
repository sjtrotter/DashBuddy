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

/**
 * The census skeleton wire versions (ADR-0011 §1, §13). One DTO type carries both versions of each kind; the
 * [UiSkeletonDto] and [NotificationSkeletonDto] constructors refuse a v1 item that uses a v2 field.
 *
 * - [V1]: `uinode.skeleton.v1` / `notification.skeleton.v1`, as the debug build has sent them since #1146.
 * - [V2]: adds the [KindClassifier.SHAPE] slot kind ([ShapeTemplate]), the node flags `hasClickAction` and
 *   `isVisibleToUser`, and a notification's action labels as slots (#1619). Fingerprints ignore all of it, so a
 *   screen keeps its cluster across the versions.
 *
 * The app sends the NEWEST version the server's policy lists in `acceptedSchemaIds` ([newestScreen],
 * [newestNotification]), so a server that does not yet accept v2 keeps receiving v1. A v1 item is the v2 build
 * projected through `toV1()`, never a second builder.
 */
enum class SkeletonVersion(val screenSchemaId: String, val notificationSchemaId: String) {
    V1(SkeletonSchema.SCHEMA_ID, NotificationSkeletonSchema.SCHEMA_ID),
    V2(SkeletonSchema.SCHEMA_ID_V2, NotificationSkeletonSchema.SCHEMA_ID_V2),
    ;

    companion object {
        fun ofScreenSchema(schemaId: String): SkeletonVersion? = entries.firstOrNull { it.screenSchemaId == schemaId }

        fun ofNotificationSchema(schemaId: String): SkeletonVersion? =
            entries.firstOrNull { it.notificationSchemaId == schemaId }

        /** The newest version whose screen schema [accepted] lists, or null when it lists none. */
        fun newestScreen(accepted: Collection<String>): SkeletonVersion? =
            entries.lastOrNull { it.screenSchemaId in accepted }

        /** The newest version whose notification schema [accepted] lists, or null when it lists none. */
        fun newestNotification(accepted: Collection<String>): SkeletonVersion? =
            entries.lastOrNull { it.notificationSchemaId in accepted }
    }
}
