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

import kotlinx.serialization.Serializable

/**
 * Notification shape only (ADR-0011 §10): no source text, package or timestamp. v2 (ADR-0011 §13, #1619) adds shape
 * slots and [actions], the action labels in order, each a [TextSlot] judged by the same census filter as the five
 * text slots (a hash, a coarse kind, a shape or `withheld`), never a label in clear. [CensusFingerprint]
 * ignores both, so a notification keeps its cluster across the versions.
 */
@Serializable
data class NotificationSkeletonDto(
    override val kind: SkeletonKind,
    override val schemaId: String,
    override val hashDomain: Int,
    override val filterRev: Int,
    override val fingerprint: String,
    override val platform: String,
    override val platformAppVersion: String? = null,
    override val appVersion: String? = null,
    override val rulesetReleaseTag: String? = null,
    override val engineVersion: Int,
    override val rulesetFormatVersion: Int? = null,
    override val day: String,
    val channelId: String,
    val slots: Map<NotifTextField, TextSlot>,
    /** v2 only: the action labels' slots, in the notification's order, at most [MAX_ACTIONS]. */
    val actions: List<TextSlot> = emptyList(),
) : CensusSkeletonDto {
    /** The wire version [schemaId] names. */
    val version: SkeletonVersion get() = requireNotNull(SkeletonVersion.ofNotificationSchema(schemaId))

    init {
        val version = SkeletonVersion.ofNotificationSchema(schemaId)
        require(version != null) { "unsupported notification schema" }
        require(kind == SkeletonKind.NOTIFICATION) { "notification kind/schema mismatch" }
        validateEnvelope(this)
        require(CHANNEL_ID_REGEX.matches(channelId)) { "invalid notification channel" }
        require(slots.keys == NotifTextField.entries.toSet()) { "exactly five notification slots required" }
        require(actions.size <= MAX_ACTIONS) { "at most $MAX_ACTIONS notification actions" }
        if (version == SkeletonVersion.V1) {
            require(actions.isEmpty() && slots.values.none { it.isV2Only }) { "a v1 notification carries no v2 field" }
        }
    }

    /** This item as `notification.skeleton.v1`: shapes read `mixed`, the actions drop. The fingerprint is unchanged. */
    fun toV1(): NotificationSkeletonDto = if (version == SkeletonVersion.V1) this else copy(
        schemaId = NotificationSkeletonSchema.SCHEMA_ID,
        slots = slots.mapValues { it.value.toV1() },
        actions = emptyList(),
    )

    companion object {
        /** Android shows three actions; a longer list is cut to this many by the builder. */
        const val MAX_ACTIONS: Int = 8

        const val CHANNEL_ID_PATTERN: String = "[A-Za-z0-9_.-]{1,64}"
        private val CHANNEL_ID_REGEX = Regex(CHANNEL_ID_PATTERN)
    }
}
