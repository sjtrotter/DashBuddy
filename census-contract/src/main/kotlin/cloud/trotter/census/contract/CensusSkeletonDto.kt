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

/** The shared envelope of the two explicitly supported census schemas. */
sealed interface CensusSkeletonDto {
    val kind: SkeletonKind
    val schemaId: String
    val hashDomain: Int
    val filterRev: Int
    val fingerprint: String
    val platform: String
    val platformAppVersion: String?
    val appVersion: String?
    val rulesetReleaseTag: String?
    val engineVersion: Int
    val rulesetFormatVersion: Int?
    val day: String
}
