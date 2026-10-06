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

/** Bounded inbound rejection vocabulary. bad_kind refers to TextSlot; expired is never inbound. */
enum class SkeletonRejectionReason(val wire: String) {
    BAD_ITEM("bad_item"),
    UNKNOWN_SCHEMA("unknown_schema"),
    UNKNOWN_FIELD("unknown_field"),
    PLAINTEXT_FIELD("plaintext_field"),
    TOO_DEEP("too_deep"),
    TOO_MANY_NODES("too_many_nodes"),
    BAD_KIND("bad_kind"),
    BAD_HASH("bad_hash"),
    HASH_ON_WITHHELD_KIND("hash_on_withheld_kind"),
    MISSING_HASH("missing_hash"),
    BAD_ID("bad_id"),
    BAD_CLASS("bad_class"),
    HASH_DOMAIN_MISMATCH("hash_domain_mismatch"),
    BAD_PLATFORM("bad_platform"),
    BAD_DAY("bad_day"),
    STALE_DAY("stale_day"),
    BAD_VERSION("bad_version"),
    FINGERPRINT_MISMATCH("fingerprint_mismatch"),
    TOO_LARGE("too_large"),
    UNKNOWN_SKELETON_KIND("unknown_skeleton_kind"),
    KIND_SCHEMA_MISMATCH("kind_schema_mismatch"),
    BAD_CHANNEL("bad_channel"),
    ;

    companion object {
        fun fromWire(wire: String): SkeletonRejectionReason? = entries.firstOrNull { it.wire == wire }
    }
}
