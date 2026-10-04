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
package cloud.trotter.census.contract.authoring

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Screen coordinates in the capture's own coordinate system. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class WalkedNode(
    val path: List<Int>,
    val className: String?,
    val simpleClass: String?,
    val viewId: String?,
    val idSuffix: String?,
    val text: String?,
    val desc: String?,
    val hint: String?,
    val pane: String?,
    val clickable: Boolean,
    val hasClickAction: Boolean,
    val visible: Boolean,
    val bounds: Bounds?,
    val precedingSiblingText: String?,
    val clickableAncestor: Boolean,
    val precedingSiblingDesc: String? = null,
)

/** Paths keep original child indices, including holes left by malformed children. */
fun List<WalkedNode>.at(path: List<Int>): WalkedNode? = firstOrNull { it.path == path }

/** One bounded pre-order walk shared by the authoring form and the draft generator. */
object EnvelopeWalk {
    private data class Level(
        val children: List<JsonElement>,
        val parentPath: List<Int>?,
        val clickableAncestor: Boolean,
        var index: Int = 0,
    )

    fun walk(payload: JsonObject, maxNodes: Int = 2_000, maxDepth: Int = 64): List<WalkedNode> {
        if (maxNodes <= 0 || maxDepth < 0) return emptyList()
        val result = mutableListOf<WalkedNode>()
        val stack = ArrayDeque<Level>()
        stack.addLast(Level(listOf(payload), null, false))
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val level = stack.last()
            if (level.index >= level.children.size) {
                stack.removeLast()
                continue
            }
            val index = level.index++
            visited++ // Malformed entries consume budget too; width cannot bypass the bound.
            val node = level.children[index] as? JsonObject ?: continue
            if (!wellTyped(node)) continue
            val path = level.parentPath?.plus(index) ?: emptyList()
            val className = node.string("class")
            val viewId = node.string("id")
            val clickable = node.flag("isClickable") ?: false
            result += WalkedNode(
                path = path,
                className = className,
                simpleClass = className?.substringAfterLast('.')?.takeIf { it.isNotBlank() },
                viewId = viewId,
                idSuffix = viewId?.substringAfter(":id/", "")?.takeIf { it.isNotEmpty() },
                text = node.string("text"),
                desc = node.string("desc"),
                hint = node.string("hint"),
                pane = node.string("pane"),
                clickable = clickable,
                hasClickAction = node.flag("clickAction") ?: false,
                visible = node.flag("visible") ?: true,
                bounds = (node["bounds"] as? JsonObject)?.let { bounds ->
                    val coordinates = listOf("left", "top", "right", "bottom").map {
                        (bounds[it] as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull
                    }
                    if (coordinates.any { it == null }) null else Bounds(
                        requireNotNull(coordinates[0]), requireNotNull(coordinates[1]),
                        requireNotNull(coordinates[2]), requireNotNull(coordinates[3]),
                    )
                },
                precedingSiblingText = if (index > 0) {
                    (level.children[index - 1] as? JsonObject)?.takeIf { wellTyped(it) }?.string("text")
                } else null,
                precedingSiblingDesc = if (index > 0) {
                    (level.children[index - 1] as? JsonObject)?.takeIf { wellTyped(it) }?.string("desc")
                } else null,
                clickableAncestor = level.clickableAncestor,
            )
            if (path.size < maxDepth) {
                val children = node["children"] as? JsonArray
                if (!children.isNullOrEmpty()) {
                    stack.addLast(Level(children, path, level.clickableAncestor || clickable))
                }
            }
        }
        return result.toList()
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()

    private fun JsonObject.flag(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun wellTyped(node: JsonObject): Boolean {
        if (node.isEmpty()) return false
        for (key in listOf("class", "id", "text", "desc", "hint", "pane")) {
            val value = node[key] ?: continue
            if (value != JsonNull && (value !is JsonPrimitive || !value.isString)) return false
        }
        for (key in listOf("isClickable", "clickAction", "visible")) {
            if (key in node && node.flag(key) == null) return false
        }
        return "children" !in node || node["children"] is JsonArray
    }
}
