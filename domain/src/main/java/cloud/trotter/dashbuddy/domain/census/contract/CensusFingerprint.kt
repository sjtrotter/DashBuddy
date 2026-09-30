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
 * Census wire contract (ADR-0011). This package depends on nothing but the JDK,
 * kotlinx-serialization and `domain.util.sha256OrNull`, so that extracting it to a
 * standalone Apache-2.0 `census-contract/` build is a move, not a rewrite.
 */
package cloud.trotter.dashbuddy.domain.census.contract

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The census CLUSTER key (ADR-0011 §8): a full sha256 (64 hex) over one canonical byte form of the
 * skeleton's structure. The server RECOMPUTES it from the skeleton it received rather than trusting
 * the client's, which is why it lives in the wire contract.
 *
 * It is NOT `UiNode.stableHash` (a collidable 32-bit `Int`, the type of `FrameGate.admit`'s content
 * hash, which must not change) and it is a different algorithm: here an anonymous wrapper
 * ([AnonymousWrappers]) is SPLICED away — its children join its parent's child sequence in order, an
 * empty wrapper contributes nothing — whereas `stableHash` folds a wrapper's children as a nested
 * group. Only the wrapper class set is shared.
 *
 * Canonical form, after wrapper-to-forest normalization and with the normalized forest ALWAYS under
 * one synthetic root (class `""`, null id, the spliced count) — so `fingerprint(A) == fingerprint(W(A))`
 * — pre-order, per node:
 * `"C"` + class UTF-8 (`""` when null) + `0x00` + (`"I"` + id UTF-8 | `"N"` when the id is null)
 * + `0x00` + the spliced child count in ASCII decimal + `0x00`, then the children in order.
 * A null id and an empty id therefore differ.
 */
object CensusFingerprint {

    /** Full sha256 hex length. */
    const val HEX_LENGTH: Int = 64

    /** The fingerprint of the tree under [root], or null if the digest failed (fail closed). */
    fun of(root: UiSkeletonNodeDto): String? = try {
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes(root))
            .joinToString("") { b -> "%02x".format(java.util.Locale.ROOT, b) }
    } catch (_: Exception) {
        null
    }

    /** True when [s] is [HEX_LENGTH] lowercase hex. */
    fun isWellFormed(s: String): Boolean =
        s.length == HEX_LENGTH && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** The canonical byte form [of] digests (exposed for the shared vectors and the server). */
    fun canonicalBytes(root: UiSkeletonNodeDto): ByteArray {
        val syntheticRoot = Shape(className = "", id = null, children = normalize(root))
        val out = ByteArrayOutputStream()
        write(syntheticRoot, out)
        return out.toByteArray()
    }

    /** A node after wrapper-to-forest normalization: structure only. */
    private class Shape(val className: String?, val id: String?, val children: List<Shape>)

    /** Wrapper-to-forest: a wrapper contributes its (normalized) children; anything else is one node. */
    private fun normalize(node: UiSkeletonNodeDto): List<Shape> {
        val children = node.children.flatMap { normalize(it) }
        return if (AnonymousWrappers.isAnonymousWrapper(node.className, node.id)) {
            children
        } else {
            listOf(Shape(node.className, node.id, children))
        }
    }

    private fun write(node: Shape, out: ByteArrayOutputStream) {
        out.write('C'.code)
        out.write((node.className ?: "").toByteArray(Charsets.UTF_8))
        out.write(0)
        if (node.id == null) {
            out.write('N'.code)
        } else {
            out.write('I'.code)
            out.write(node.id.toByteArray(Charsets.UTF_8))
        }
        out.write(0)
        out.write(node.children.size.toString().toByteArray(Charsets.US_ASCII))
        out.write(0)
        node.children.forEach { write(it, out) }
    }
}
