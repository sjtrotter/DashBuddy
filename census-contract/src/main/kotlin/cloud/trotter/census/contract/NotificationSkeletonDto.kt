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

/** Notification shape only: no source text, package, actions or timestamp. */
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
) : CensusSkeletonDto {
    init {
        require(schemaId == NotificationSkeletonSchema.SCHEMA_ID) { "unsupported notification schema" }
        require(kind == SkeletonKind.NOTIFICATION) { "notification kind/schema mismatch" }
        validateEnvelope(this)
        require(CHANNEL_ID_REGEX.matches(channelId)) { "invalid notification channel" }
        require(slots.keys == NotifTextField.entries.toSet()) { "exactly five notification slots required" }
    }

    companion object {
        const val CHANNEL_ID_PATTERN: String = "[A-Za-z0-9_.-]{1,64}"
        private val CHANNEL_ID_REGEX = Regex(CHANNEL_ID_PATTERN)
    }
}
