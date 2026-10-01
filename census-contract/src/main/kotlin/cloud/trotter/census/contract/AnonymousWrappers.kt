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
package cloud.trotter.census.contract

/**
 * The anonymous-wrapper class set (ADR-0011 §8) — the ONE constant shared by the census
 * [CensusFingerprint] (which SPLICES such a wrapper's children into its parent) and `UiNode.stableHash`
 * (which folds them as a nested group and never splices). Only the CLASS SET is shared; the two
 * algorithms are deliberately different and `stableHash` is untouched.
 *
 * A node is an anonymous wrapper when its view id is NULL AND its class is in [WRAPPER_CLASSES]: Compose
 * recomposition adds and removes such generic containers without changing what the screen shows, so
 * neither a frame identity nor a census cluster may split on them.
 *
 * Owned by the Apache-2.0 census contract build (#1173); `UiNode` imports this predicate,
 * never the contract's wire types.
 *
 * The two callers pass DIFFERENT ids, deliberately (#1160 review II4): `stableHash` passes the RAW view
 * id, while `CensusFingerprint` sees the WIRE id — after the census id gate, so a container whose only id
 * was dynamic (a per-frame UUID test tag) or empty arrives with a null id and IS spliced there, while
 * `stableHash` treats it as identity-bearing. The server can only recompute from the wire tree, so the
 * fingerprint cannot do otherwise.
 */
object AnonymousWrappers {

    val WRAPPER_CLASSES: Set<String> = setOf(
        "android.view.View",
        "android.view.ViewGroup",
        "android.widget.FrameLayout",
        "android.widget.LinearLayout",
    )

    /** True when a node with this [className] and view [id] is a transparent anonymous wrapper. */
    fun isAnonymousWrapper(className: String?, id: String?): Boolean =
        id == null && className != null && className in WRAPPER_CLASSES
}
