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

import cloud.trotter.census.contract.AnonymousWrappers
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.contract.CensusSkeletonDto
import cloud.trotter.census.contract.sha256OrNull
import java.io.ByteArrayOutputStream

/** One text slot of a skeleton, named the way the census server names it (census#80). */
data class SkeletonSlot(
    /** `windowTitle`, `root/<child index>/…/<text key>`, a notification field's wire name, or `action/<index>`. */
    val id: String,
    /** The node's class as the skeleton carries it; null for the window title, a notification field or an absent class. */
    val className: String?,
    /** The text key (`text`, `desc`, …), `windowTitle`, a notification field's wire name, or `action`. */
    val key: String,
    val slot: TextSlot,
)

/**
 * The slot ids and the layout id of a skeleton, byte for byte as the census server computes them (its `SlotWalk`,
 * census#80/#81, `docs/API.md` "Slot constancy" and `docs/CLIENT.md` "Contributor tier"), so a contributor phone
 * (gig.log#1626) names the slots of the skeleton it uploaded exactly as the server stored them.
 *
 * - Slot ids: the window title first (`windowTitle`), then every node in pre-order from `root`, each step down the
 *   child index in the tree AS SENT (anonymous wrappers included), then the node's text keys in sorted order:
 *   `root/text`, `root/0/2/desc`. A notification: its fields in [NotifTextField] order by wire name, then
 *   `action/<index>`.
 * - The layout id: lowercase hex sha256 over the raw tree's shape, never its values (see [layout]).
 */
object ContributorSlots {
    const val WINDOW_TITLE: String = "windowTitle"
    private const val ROOT = "root"
    private const val ACTION = "action"

    /** Every slot of [item] in the server's order. */
    fun slots(item: CensusSkeletonDto): List<SkeletonSlot> {
        val out = ArrayList<SkeletonSlot>()
        when (item) {
            is UiSkeletonDto -> {
                item.windowTitle?.let { out += SkeletonSlot(WINDOW_TITLE, null, WINDOW_TITLE, it) }
                // Iterative pre-order, like the server's walk: a recursion would be the deepest stack on a 64-level tree.
                val pending = ArrayDeque<Pair<UiSkeletonNodeDto, String>>()
                pending.addLast(item.root to ROOT)
                while (pending.isNotEmpty()) {
                    val (node, path) = pending.removeLast()
                    for ((key, value) in node.text.toSortedMap()) out += SkeletonSlot("$path/$key", node.className, key, value)
                    node.children.indices.reversed().forEach { pending.addLast(node.children[it] to "$path/$it") }
                }
            }
            is NotificationSkeletonDto -> {
                NotifTextField.entries.forEach { field ->
                    item.slots[field]?.let { out += SkeletonSlot(field.wire, null, field.wire, it) }
                }
                item.actions.forEachIndexed { index, value -> out += SkeletonSlot("$ACTION/$index", null, ACTION, value) }
            }
        }
        return out
    }

    /**
     * The raw layout id: lowercase hex sha256. A screen writes `L1`, then per node in pre-order: the length-prefixed
     * class (empty when absent), `N` for no id or `I` and the length-prefixed id, the child count in ASCII decimal and
     * a 0 byte, then for an anonymous wrapper (no id, a wrapper class) `W` and its text keys, sorted, as a count and
     * length-prefixed keys, else `-`. Length-prefixed is the UTF-8 byte count in ASCII decimal, a 0 byte, the bytes.
     * A notification writes `N1` and its action count. Null only if the digest fails.
     */
    fun layout(item: CensusSkeletonDto): String? {
        val out = ByteArrayOutputStream()
        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write(bytes.size.toString().toByteArray(Charsets.US_ASCII)); out.write(0); out.write(bytes)
        }
        fun number(value: Int) { out.write(value.toString().toByteArray(Charsets.US_ASCII)); out.write(0) }
        when (item) {
            is UiSkeletonDto -> {
                out.write("L1".toByteArray(Charsets.US_ASCII))
                val pending = ArrayDeque<UiSkeletonNodeDto>().apply { addLast(item.root) }
                while (pending.isNotEmpty()) {
                    val node = pending.removeLast()
                    text(node.className ?: "")
                    val id = node.id
                    if (id == null) out.write('N'.code) else { out.write('I'.code); text(id) }
                    number(node.children.size)
                    if (AnonymousWrappers.isAnonymousWrapper(node.className, node.id)) {
                        out.write('W'.code)
                        val keys = node.text.keys.sorted()
                        number(keys.size)
                        keys.forEach { text(it) }
                    } else {
                        out.write('-'.code)
                    }
                    node.children.asReversed().forEach { pending.addLast(it) }
                }
            }
            is NotificationSkeletonDto -> {
                out.write("N1".toByteArray(Charsets.US_ASCII))
                number(item.actions.size)
            }
        }
        return sha256OrNull(out.toByteArray())
    }
}
