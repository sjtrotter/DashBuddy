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
 */
package cloud.trotter.census.contract.contributor

import cloud.trotter.census.contract.CensusSkeletonDto
import cloud.trotter.census.contract.SkeletonKind
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a field means, from the census server's closed list (census#81, `POST /v1/contributor/review`). A role tag
 * names the field and never carries its value.
 */
enum class ContributorRole(val wire: String) {
    TOTAL_PAY("total_pay"),
    TIP("tip"),
    BASE_PAY("base_pay"),
    DEADLINE("deadline"),
    DISTANCE("distance"),
    ITEM_COUNT("item_count"),
    CUSTOMER_INFO("customer_info"),
    STORE_NAME("store_name"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(wire: String): ContributorRole? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A slot's class from the day's release (census#80/#81, `POST /v1/contributor/classes`). [UNKNOWN] is never on the
 * wire: the server leaves a slot out below k installs, and the phone reads every slot it did not get back as unknown.
 */
enum class SlotClass(val wire: String?) {
    CONSTANT("constant"),
    REGIONAL("regional"),
    VARYING("varying"),
    WITHHELD("withheld"),
    SHAPE("shape"),
    UNKNOWN(null),
    ;

    companion object {
        /** An answer outside the list reads as [UNKNOWN] (fail closed: only [CONSTANT] may show a word). */
        fun fromWire(wire: String?): SlotClass = entries.firstOrNull { it.wire != null && it.wire == wire } ?: UNKNOWN
    }
}

/**
 * The cluster variant every per-slot contributor request is scoped to (census#81): the skeleton's public fingerprint
 * and kind, its `platformAppVersion` (`unknown` when it carried none), `hashDomain`, `filterRev` and its
 * [ContributorSlots.layout].
 */
data class ContributorVariant(
    val kind: SkeletonKind,
    val fingerprint: String,
    val platformAppVersion: String,
    val hashDomain: Int,
    val filterRev: Int,
    val layout: String,
) {
    /** The variant object, keys in the order the server documents. */
    fun json(): String =
        "{\"kind\":${JsonPrimitive(kind.wire)},\"fingerprint\":${JsonPrimitive(fingerprint)}," +
            "\"platformAppVersion\":${JsonPrimitive(platformAppVersion)},\"hashDomain\":$hashDomain," +
            "\"filterRev\":$filterRev,\"layout\":${JsonPrimitive(layout)}}"

    companion object {
        const val UNKNOWN_VERSION: String = "unknown"

        /** Null only when the layout digest fails. */
        fun of(item: CensusSkeletonDto): ContributorVariant? = ContributorSlots.layout(item)?.let { layout ->
            ContributorVariant(
                kind = item.kind,
                fingerprint = item.fingerprint,
                platformAppVersion = item.platformAppVersion ?: UNKNOWN_VERSION,
                hashDomain = item.hashDomain,
                filterRev = item.filterRev,
                layout = layout,
            )
        }
    }
}
