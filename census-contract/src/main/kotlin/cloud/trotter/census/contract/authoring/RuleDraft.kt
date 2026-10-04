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

import cloud.trotter.census.contract.SensitiveMarkerScan
import cloud.trotter.census.contract.authoring.RuleAuthoringVocabulary as Vocabulary
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Locale

data class NodeRef(val path: List<Int>)
data class FieldAssignment(
    val node: NodeRef,
    val field: String,
    val transform: List<String>? = null,
    val stripPrefix: String? = null,
)
data class BindAssignment(val node: NodeRef, val target: String)
data class Constant(val name: String, val value: JsonPrimitive)
data class Selections(
    val screenClass: String,
    val shape: String,
    val intent: String,
    val priority: Int,
    val modeHint: String? = null,
    val offerSurface: String? = null,
    val anchors: List<NodeRef>,
    val fields: List<FieldAssignment> = emptyList(),
    val binds: List<BindAssignment> = emptyList(),
    val redacts: List<NodeRef> = emptyList(),
    val constants: List<Constant> = emptyList(),
    val comment: String? = null,
)

sealed interface DraftResult {
    data class Ok(val fragment: JsonObject, val json5: String, val warnings: List<String>) : DraftResult
    data class Refused(val errors: List<String>) : DraftResult
}

/** Pure authoring: no capture metadata, clocks, application models or generated identifiers. */
object RuleDraft {
    @OptIn(ExperimentalSerializationApi::class)
    private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun generate(
        envelope: JsonObject,
        selections: Selections,
        platform: String,
        platformAppVersion: String?,
        day: String,
    ): DraftResult {
        val draft = Draft(envelope, selections)
        val rule = draft.build(platform)
        if (draft.errors.isNotEmpty()) return DraftResult.Refused(draft.errors.toList())
        val fragment = buildJsonObject {
            put("\$schema", "../../../docs/rules.fragment.schema.json")
            put("screens", JsonArray(listOf(rule)))
        }
        val heading = "// Drafted from a census capture — $platform " +
            "${platformAppVersion ?: "unknown version"} · $day"
        return DraftResult.Ok(
            fragment,
            heading + "\n" + prettyJson.encodeToString(JsonObject.serializer(), fragment),
            draft.warnings.toList(),
        )
    }

    private enum class Role { ANCHOR, FIELD, BIND, REDACT }

    private class Draft(envelope: JsonObject, private val selections: Selections) {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        private val nodes = (envelope["payload"] as? JsonObject)?.let { EnvelopeWalk.walk(it) }
            ?: emptyList<WalkedNode>().also { errors += "envelope has no payload object" }
        private val byPath = nodes.associateBy { it.path }
        private val specs = Vocabulary.FIELDS_BY_SHAPE[selections.shape].orEmpty().associateBy { it.name }
        private val taskConstants = if (selections.shape == "task") {
            Vocabulary.TASK_CONSTANTS_BY_CLASS[selections.screenClass]?.let {
                mapOf("phase" to JsonPrimitive(it.first), "subFlow" to JsonPrimitive(it.second))
            }.orEmpty()
        } else emptyMap()

        fun build(platform: String): JsonObject {
            validateSelections()
            val anchors = selections.anchors.mapNotNull { predicate(it, Role.ANCHOR) }
            if (selections.anchors.size == 1) {
                val node = byPath[selections.anchors.single().path]
                if (node != null && node.idSuffix == null && (node.text != null || node.desc != null)) {
                    warnings += "one anchor; verify against the negative corpus"
                }
            }
            val fields = linkedMapOf<String, JsonElement>()
            fields.putAll(taskConstants)
            selections.constants.forEach { fields[it.name] = it.value }
            val redacts = mutableListOf<JsonElement>()
            for (assignment in selections.fields) {
                val node = byPath[assignment.node.path]
                val spec = specs[assignment.field]
                // Hash fields cannot opt out of normalization/hashing or transform the digest later.
                val transforms = if (spec?.kind == FieldKind.PLAIN) {
                    assignment.transform ?: spec.defaultTransform
                } else spec?.defaultTransform.orEmpty()
                val siblingDesc = node?.precedingSiblingDesc?.takeIf {
                    node.idSuffix == null && node.precedingSiblingText == null
                }
                val selector = if (siblingDesc != null) {
                    if (nodes.count { it.desc?.equals(siblingDesc, ignoreCase = true) == true } > 1) {
                        errors += "ambiguous sibling label at ${assignment.node.path}"
                    }
                    buildJsonObject {
                        put("siblingOf", literal(assignment.node, "hasDesc", siblingDesc))
                        put("offset", 1)
                    }
                } else predicate(assignment.node, Role.FIELD, transforms)?.let { find ->
                    buildJsonObject { put("find", find) }
                }
                assignment.stripPrefix?.let { prefix ->
                    literal(assignment.node, "stripPrefix", prefix)
                    if (node?.text?.startsWith(prefix) != true) {
                        errors += "stripPrefix is not a prefix of the value"
                    }
                }
                val read = when {
                    node?.text != null -> "text"
                    node?.desc != null -> "contentDescription"
                    else -> null
                }
                if (node != null && read == null) {
                    errors += if (spec != null && spec.kind != FieldKind.PLAIN) {
                        "hash field ${assignment.field} has no text/desc slot"
                    } else "field ${assignment.field} has no text/desc slot"
                }
                if (selector == null || read == null || spec == null) continue
                fields[assignment.field] = buildJsonObject {
                    selector.forEach { (key, value) -> put(key, value) }
                    put("read", read)
                    if (assignment.stripPrefix != null) {
                        put("transform", JsonArray(listOf(buildJsonObject {
                            put("stripPrefixes", strings(listOf(assignment.stripPrefix)))
                        }) + transforms.map { JsonPrimitive(it) }))
                    } else if (transforms.size == 1) put("transform", transforms.single())
                    else if (transforms.isNotEmpty()) put("transform", strings(transforms))
                }
                if (spec.kind != FieldKind.PLAIN) {
                    // Redaction needs a node predicate, not a sibling parse expression.
                    val find = selector["find"] ?: predicate(assignment.node, Role.REDACT) ?: continue
                    redacts += buildJsonObject {
                        put("find", find)
                        assignment.stripPrefix?.let { put("keepPrefix", strings(listOf(it))) }
                        if (spec.kind == FieldKind.CUSTOMER_NAME_HASH) put("normalize", "customerName")
                    }
                }
            }
            val binds = buildJsonObject {
                for (assignment in selections.binds) {
                    val find = predicate(assignment.node, Role.BIND)
                    val node = byPath[assignment.node.path]
                    if (node != null && !node.clickable) {
                        if (!node.clickableAncestor) errors += "bind ${assignment.target} is not clickable and has no clickable ancestor"
                        else warnings += "bind ${assignment.target} relies on a clickable ancestor"
                    }
                    if (find != null) put(assignment.target, buildJsonObject {
                        put("find", find)
                        put("optional", true)
                    })
                }
            }
            for (ref in selections.redacts) {
                predicate(ref, Role.REDACT)?.let { find ->
                    redacts += buildJsonObject { put("find", find); put("plainMask", true) }
                }
            }
            if (selections.shape == "offer") warnings += "offer draft has no orders[] — store names will be absent"
            return buildJsonObject {
                put("id", "$platform.screen.${selections.intent}")
                put("priority", selections.priority)
                if (selections.screenClass == "sensitive") put("overrideable", false)
                selections.comment?.let { put("comment", it) }
                val enables = selections.binds.mapNotNull { Vocabulary.BIND_TARGETS[it.target] }.distinct().sorted()
                if (enables.isNotEmpty()) put("enables", strings(enables))
                if (selections.screenClass !in listOf("sensitive", "noise")) put("state", buildJsonObject {
                    put("flow", selections.screenClass)
                    selections.modeHint?.let { put("modeHint", it) }
                    selections.offerSurface?.let { put("offerSurface", it) }
                })
                if (binds.isNotEmpty()) put("bind", binds)
                if (redacts.isNotEmpty()) put("redact", JsonArray(redacts))
                val exists = anchors.map { buildJsonObject { put("exists", it) } }
                put("require", if (exists.size == 1) exists.single() else all(exists))
                put("parse", buildJsonObject {
                    put("as", selections.shape)
                    if (selections.shape !in listOf("sensitive", "noise", "none")) put("fields", JsonObject(fields))
                })
            }
        }

        private fun validateSelections() {
            if (selections.screenClass !in Vocabulary.SCREEN_CLASSES) errors += "unknown screenClass"
            if (selections.shape !in Vocabulary.SHAPES) errors += "unknown shape"
            if (!Vocabulary.INTENT.matches(selections.intent)) errors += "invalid intent"
            if (selections.priority !in Vocabulary.PRIORITY_RANGE) errors += "priority must be in 1..998"
            if (selections.modeHint != null && selections.modeHint !in Vocabulary.MODES) errors += "unknown modeHint"
            if (selections.offerSurface != null) {
                if (selections.offerSurface !in Vocabulary.OFFER_SURFACES) errors += "unknown offerSurface"
                if (selections.screenClass != "offer:presented") errors += "offerSurface requires offer:presented"
            }
            if (selections.anchors.isEmpty()) errors += "at least one anchor is required"
            if (selections.screenClass in listOf("sensitive", "noise") &&
                (selections.fields.isNotEmpty() || selections.binds.isNotEmpty() || selections.constants.isNotEmpty())) {
                errors += "sensitive/noise class cannot declare fields or binds"
            }
            for (special in listOf("sensitive", "noise")) {
                if ((selections.screenClass == special) != (selections.shape == special)) {
                    errors += "$special class and shape must agree"
                }
            }
            val declared = taskConstants.keys.toList() + selections.constants.map { it.name } + selections.fields.map { it.field }
            for (name in declared.groupingBy { it }.eachCount().filterValues { it > 1 }.keys) {
                errors += "duplicate field $name"
            }
            for (assignment in selections.fields) {
                val spec = specs[assignment.field]
                if (spec == null) errors += "unknown field ${assignment.field} for shape ${selections.shape}"
                for (transform in assignment.transform.orEmpty()) {
                    if (transform !in Vocabulary.TRANSFORMS) errors += "unknown transform $transform"
                    if (transform == "normalizeCustomerName" && spec?.kind != FieldKind.CUSTOMER_NAME_HASH) {
                        errors += "normalizeCustomerName requires a customer-name hash field"
                    }
                    if (transform == "sha256" && (spec == null || spec.kind == FieldKind.PLAIN)) {
                        errors += "sha256 requires a hash field"
                    }
                }
            }
            for (constant in selections.constants) {
                val spec = specs[constant.name]
                if (spec == null) errors += "unknown constant ${constant.name} for shape ${selections.shape}"
                else if (spec.kind != FieldKind.PLAIN) errors += "hash field ${constant.name} requires a node assignment"
                else if (!validConstant(spec.type, constant.value)) errors += "invalid constant type for ${constant.name}"
            }
            for (required in Vocabulary.REQUIRED_FIELDS_BY_SHAPE[selections.shape].orEmpty()) {
                if (required !in declared) errors += "missing required field $required"
            }
            for (group in Vocabulary.REQUIRED_ONE_OF_BY_SHAPE[selections.shape].orEmpty()) {
                if (group.none { it in declared }) errors += "missing one of: ${group.joinToString(", ")}"
            }
            for (bind in selections.binds) {
                if (bind.target !in Vocabulary.BIND_TARGETS) errors += "unknown bind target ${bind.target}"
            }
            for (target in selections.binds.groupingBy { it.target }.eachCount().filterValues { it > 1 }.keys) {
                errors += "duplicate bind target $target"
            }
        }

        private fun literal(ref: NodeRef, key: String, text: String): JsonObject {
            if (text.isBlank() || text.length > Vocabulary.ANCHOR_TEXT_MAX || '$' in text ||
                Regex("\\p{Nd}{3,}").containsMatchIn(text) || text.contains("[redacted", ignoreCase = true) ||
                SensitiveMarkerScan.findMarker(text) != null) {
                errors += "unsafe anchor literal at ${ref.path}"
            }
            return atom(key, text)
        }

        private fun predicate(ref: NodeRef, role: Role, transforms: List<String> = emptyList()): JsonObject? {
            val node = byPath[ref.path]
            if (node == null) {
                errors += "node path does not resolve: ${ref.path}"
                return null
            }
            fun classPredicate(): JsonObject? {
                val simpleClass = node.simpleClass
                if (simpleClass == null) errors += "node has no simple class: ${ref.path}"
                return simpleClass?.let { atom("hasClassNameEndsWith", it) }
            }
            fun literal(key: String, text: String): JsonObject = literal(ref, key, text)
            fun shapePredicate(peers: List<WalkedNode>): JsonObject? {
                if (role != Role.FIELD) return null
                val shape = transforms.firstNotNullOfOrNull { Vocabulary.VALUE_SHAPES_BY_TRANSFORM[it] }
                    ?: return null
                val regex = Regex(shape)
                if (node.text?.let { regex.matches(it) } != true) return null
                if (peers.count { it.text?.let(regex::matches) == true } != 1) return null
                return atom("hasTextMatchesRegex", shape)
            }
            val suffix = node.idSuffix
            if (suffix != null) {
                val id = atom("hasIdSuffix", suffix)
                val peers = nodes.filter { it.idSuffix == suffix }
                if (peers.size == 1) return id
                val cls = classPredicate() ?: return null
                val predicates = mutableListOf(id, cls)
                if (role == Role.ANCHOR) {
                    when {
                        node.text != null -> predicates += literal("hasText", node.text)
                        node.desc != null -> predicates += literal("hasDesc", node.desc)
                        else -> errors += "ambiguous id at ${ref.path}"
                    }
                } else if (peers.count { it.simpleClass == node.simpleClass } > 1) {
                    // A find returns the FIRST node: id + class must actually identify the selected value.
                    val shape = shapePredicate(peers.filter { it.simpleClass == node.simpleClass })
                    if (shape != null) predicates += shape
                    else errors += "ambiguous id for ${role.name.lowercase(Locale.ROOT)} at ${ref.path}"
                }
                return all(predicates)
            }
            if (role == Role.ANCHOR || role == Role.BIND) {
                return when {
                    node.text != null -> classPredicate()?.let { all(listOf(it, literal("hasText", node.text))) }
                    node.desc != null -> literal("hasDesc", node.desc)
                    else -> null.also { errors += "class-only anchor refused at ${ref.path}" }
                }
            }
            val preceding = node.precedingSiblingText
            if (preceding == null) {
                val peers = nodes.filter {
                    it.path.size == node.path.size && it.path.dropLast(1) == node.path.dropLast(1) &&
                        it.simpleClass == node.simpleClass
                }
                val shape = shapePredicate(peers)
                // The emitted find searches the envelope, not just this parent. Another parent's
                // matching value would silently select the wrong node, so retain the refusal there.
                val envelopeShape = if (shape != null) shapePredicate(nodes.filter {
                    node.simpleClass?.let { cls -> it.className?.endsWith(cls) } == true
                }) else null
                if (envelopeShape != null) return classPredicate()?.let { all(listOf(it, envelopeShape)) }
                errors += "field has no stable anchor at ${ref.path}"
                return null
            }
            val cls = classPredicate() ?: return null
            if (nodes.count { it.simpleClass == node.simpleClass && it.precedingSiblingText == preceding } > 1) {
                errors += "ambiguous sibling anchor at ${ref.path}"
            }
            return all(listOf(cls, literal("hasPrecedingSiblingText", preceding)))
        }
    }

    private fun validConstant(type: FieldType, value: JsonPrimitive): Boolean {
        if (value == JsonNull) return false
        if (type == FieldType.STRING) return value.isString
        if (value.isString) return false
        return when (type) {
            FieldType.STRING -> false
            FieldType.DOUBLE -> value.doubleOrNull?.isFinite() == true
            FieldType.LONG -> value.longOrNull != null
            FieldType.INT -> value.intOrNull != null
            FieldType.BOOLEAN -> value.booleanOrNull != null
        }
    }

    private fun atom(key: String, value: String): JsonObject = buildJsonObject { put(key, value) }
    private fun all(predicates: List<JsonObject>): JsonObject = buildJsonObject { put("all", JsonArray(predicates)) }
    private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
}
