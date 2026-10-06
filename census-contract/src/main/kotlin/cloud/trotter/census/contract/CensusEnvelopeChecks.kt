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

/** Shared checks deliberately preserve the legacy screen envelope's acceptance. */
internal fun validateEnvelope(item: CensusSkeletonDto) {
    with(item) {
        require(hashDomain == CensusHash.HASH_DOMAIN) { "unsupported hash domain" }
        require(filterRev >= 1) { "filterRev is mandatory and positive" }
        require(CensusFingerprint.isWellFormed(fingerprint)) { "fingerprint is 64 lowercase hex" }
        require(isPlatformWire(platform)) { "platform is a short lowercase wire id" }
        require(isDay(day)) { "day is YYYY-MM-DD" }
        listOfNotNull(platformAppVersion, appVersion, rulesetReleaseTag).forEach {
            require(it.length <= MAX_VERSION_LENGTH) { "a version stamp is at most $MAX_VERSION_LENGTH chars" }
            require(WireStrings.isWellFormed(it)) { "a version stamp must be well-formed UTF-16 without U+0000" }
        }
    }
}

private const val MAX_VERSION_LENGTH = UiSkeletonDto.MAX_VERSION_LENGTH
private const val MAX_PLATFORM_LENGTH = 32

private fun isPlatformWire(s: String): Boolean =
    s.isNotEmpty() && s.length <= MAX_PLATFORM_LENGTH &&
        s.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

/** A real calendar date with the four-digit year form. */
private fun isDay(s: String): Boolean =
    s.length == 10 && runCatching { java.time.LocalDate.parse(s) }.isSuccess
