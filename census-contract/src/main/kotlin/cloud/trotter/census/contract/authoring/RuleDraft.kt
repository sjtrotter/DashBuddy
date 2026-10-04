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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.Locale

data class PathRef(val path: List<Int>)
data class FieldAssignment(
    val node: PathRef,
    val field: String,
    val transform: List<String>? = null,
    val stripPrefix: String? = null,
)
data class BindAssignment(val node: PathRef, val target: String)
data class Constant(val name: String, val value: JsonPrimitive)
data class Selections(
    val screenClass: String,
    val shape: String,
    val intent: String,
    val priority: Int,
    val modeHint: String? = null,
    val offerSurface: String? = null,
    val anchors: List<PathRef>,
    val fields: List<FieldAssignment> = emptyList(),
    val binds: List<BindAssignment> = emptyList(),
    val redacts: List<PathRef> = emptyList(),
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

    private val digitOrCurrency = Regex("""[\p{Nd}\p{Sc}]""")
    private val currency = Regex("""\p{Sc}""")
    private val customerName = Regex(Vocabulary.FIRST_LAST_INITIAL_EMBEDDED, RegexOption.IGNORE_CASE)
    private val capitalizedWords = Regex("""\b\p{Lu}\p{Ll}{2,}\s+\p{Lu}\p{Ll}{2,}\b""")
    private val shapes: Map<String, Regex> = Vocabulary.VALUE_SHAPES_BY_TRANSFORM.values.distinct()
        .associateWith { Regex(it, RegexOption.IGNORE_CASE) }
    private val dayPattern = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val versionPattern = Regex("^[0-9A-Za-z.+_-]{1,40}$")
    private val platformPattern = Regex("^[a-z_][a-z0-9_]{0,31}$")

    fun generate(
        envelope: JsonObject,
        selections: Selections,
        platform: String,
        platformAppVersion: String?,
        day: String,
    ): DraftResult {
        val draft = Draft(envelope, selections)
        if (!dayPattern.matches(day)) draft.errors += "invalid day"
        if (platformAppVersion != null && !versionPattern.matches(platformAppVersion)) draft.errors += "invalid platformAppVersion"
        if (!platformPattern.matches(platform)) draft.errors += "invalid platform"
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
        private val nodes = (envelope["payload"] as? JsonObject)?.let {
            EnvelopeWalk.walk(it).also { result ->
                if (result.truncated) errors += "envelope exceeds the walk bounds"
            }.nodes
        }
            ?: emptyList<WalkedNode>().also { errors += "envelope has no payload object" }
        private val byPath = nodes.associateBy { it.path }
        private val specs = Vocabulary.FIELDS_BY_SHAPE[selections.shape].orEmpty().associateBy { it.name }
        private val taskConstants = if (selections.shape == "task") {
            Vocabulary.TASK_CONSTANTS_BY_CLASS[selections.screenClass]?.let {
                mapOf("phase" to JsonPrimitive(it.first), "subFlow" to JsonPrimitive(it.second))
            }.orEmpty()
        } else emptyMap()

        private val redacts = mutableListOf<JsonElement>()

        fun build(platform: String): JsonObject {
            validateSelections()
            val anchors = selections.anchors.mapNotNull { predicate(it, Role.ANCHOR) }
            if (selections.anchors.size == 1) {
                val node = byPath[selections.anchors.single().path]
                if (node != null && node.idSuffix == null && (node.displayText != null || node.displayDesc != null)) {
                    warnings += "one anchor; verify against the negative corpus"
                }
            }
            val fields = linkedMapOf<String, JsonElement>()
            fields.putAll(taskConstants)
            selections.constants.forEach { fields[it.name] = it.value }
            for (ref in selections.redacts) {
                predicate(ref, Role.REDACT)?.let { find ->
                    redacts += buildJsonObject { put("find", find); put("plainMask", true) }
                }
            }
            for (assignment in selections.fields) {
                fieldEntry(assignment)?.let { fields[assignment.field] = it }
            }
            val binds = buildJsonObject {
                for (assignment in selections.binds) {
                    val find = predicate(assignment.node, Role.BIND)
                    val node = byPath[assignment.node.path]
                    if (node != null && !node.takesClick) {
                        if (!node.takesClickAncestor) errors += "bind ${assignment.target} is not clickable and has no clickable ancestor"
                        else warnings += "bind ${assignment.target} relies on a clickable ancestor"
                    }
                    if (find != null) put(assignment.target, buildJsonObject {
                        put("find", find)
                        put("optional", true)
                    })
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

        private fun fieldEntry(assignment: FieldAssignment): JsonObject? {
            val node = byPath[assignment.node.path]
            val spec = specs[assignment.field]
            // Hash fields cannot opt out of normalization/hashing or transform the digest later.
            val transforms = if (spec?.kind == FieldKind.PLAIN) {
                assignment.transform?.takeIf { it.isNotEmpty() } ?: spec.defaultTransform
            } else spec?.defaultTransform.orEmpty()
            if (spec != null && spec.kind != FieldKind.PLAIN && assignment.transform != null) {
                warnings += "transform on ${assignment.field} ignored: hash fields use their fixed chain"
            }
            val siblingDesc = node?.precedingSiblingDesc?.takeIf {
                node.idSuffix == null && node.displayPrecedingSiblingText == null && node.displayPrecedingSiblingDesc != null
            }
            val selector = if (siblingDesc != null) {
                if (descPeers(siblingDesc).size > 1) {
                    errors += "ambiguous sibling label at ${assignment.node.path}"
                }
                buildJsonObject {
                    assignment.node.validateLiteral("hasDesc", siblingDesc)
                    put("siblingOf", atom("hasDesc", siblingDesc))
                    put("offset", 1)
                }
            } else predicate(assignment.node, Role.FIELD, transforms, assignment.field)?.let { find ->
                buildJsonObject { put("find", find) }
            }
            val read = when {
                spec?.read == "viewIdResourceName" -> spec.read.takeIf { node?.viewId != null }
                node?.displayText != null -> "text"
                node?.displayDesc != null -> "contentDescription"
                else -> null
            }
            assignment.stripPrefix?.let { prefix ->
                assignment.node.validateLiteral("stripPrefix", prefix)
                val value = when (read) {
                    "text" -> node?.text
                    "contentDescription" -> node?.desc
                    else -> null
                }
                if (value?.startsWith(prefix) != true) {
                    errors += "stripPrefix is not a prefix of the value"
                }
            }
            if (node != null && read == null) {
                errors += when {
                    spec?.read == "viewIdResourceName" -> "field ${assignment.field} has no viewIdResourceName slot"
                    spec != null && spec.kind != FieldKind.PLAIN -> "hash field ${assignment.field} has no text/desc slot"
                    else -> "field ${assignment.field} has no text/desc slot"
                }
            }
            if (spec != null) {
                val resultType = transforms.lastOrNull()?.let { Vocabulary.TRANSFORM_RESULT_TYPE[it] }
                    ?: if (transforms.isEmpty()) FieldType.STRING else null
                if (resultType != null && resultType != spec.type) {
                    errors += "transform chain for ${assignment.field} yields $resultType, the field is ${spec.type}"
                }
            }
            if (selector == null || read == null || spec == null) return null
            val entry = buildJsonObject {
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
                if (selector["find"] == null) {
                    errors += "hash field ${assignment.field} has no stable redact predicate (the parse selector was valid)"
                    return entry
                }
                val find = predicate(assignment.node, Role.REDACT) ?: return entry
                redacts += buildJsonObject {
                    put("find", find)
                    assignment.stripPrefix?.let { put("keepPrefix", strings(listOf(it))) }
                    if (spec.kind == FieldKind.CUSTOMER_NAME_HASH) put("normalize", "customerName")
                }
            }
            return entry
        }

        private fun validateSelections() {
            if (selections.screenClass !in Vocabulary.SCREEN_CLASSES) errors += "unknown screenClass"
            if (selections.shape !in Vocabulary.SHAPES) errors += "unknown shape"
            if (selections.screenClass in Vocabulary.SCREEN_CLASSES &&
                selections.shape !in Vocabulary.LEGAL_SHAPES_BY_CLASS[selections.screenClass].orEmpty()) {
                errors += "shape ${selections.shape} is not legal for class ${selections.screenClass}"
            }
            selections.comment?.let { PathRef(emptyList()).validateLiteral("comment", it) }
            if (!Vocabulary.INTENT.matches(selections.intent)) errors += "invalid intent"
            if (selections.priority !in Vocabulary.PRIORITY_RANGE) errors += "priority must be in 1..998"
            if (selections.modeHint != null && selections.modeHint !in Vocabulary.MODES) errors += "unknown modeHint"
            if (selections.modeHint != null && selections.screenClass in listOf("sensitive", "noise")) {
                errors += "modeHint is not allowed for class ${selections.screenClass}"
            }
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
                if (constant.name in listOf("phase", "subFlow") &&
                    selections.screenClass !in Vocabulary.TASK_CONSTANTS_BY_CLASS) {
                    errors += "${constant.name} requires a phased task class"
                }
                val spec = specs[constant.name]
                if (spec == null) errors += "unknown constant ${constant.name} for shape ${selections.shape}"
                else if (spec.kind != FieldKind.PLAIN) errors += "hash field ${constant.name} requires a node assignment"
                else if (spec.type !in listOf(FieldType.BOOLEAN, FieldType.INT) && enumValues(spec.name) == null) {
                    errors += "constant ${constant.name}: only flags, counts and enum values may be constants"
                } else if (!validConstant(spec, constant.value)) errors += "invalid constant type for ${constant.name}"
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

        private fun PathRef.validateLiteral(kind: String, text: String) {
            val comment = kind == "comment"
            val prefix = kind == "stripPrefix"
            if (text.isBlank() || text.length > (if (comment) 200 else Vocabulary.ANCHOR_TEXT_MAX) ||
                (if (comment) currency.containsMatchIn(text) else digitOrCurrency.containsMatchIn(text)) ||
                (prefix && !text.endsWith(" ")) || text.contains("[redacted", ignoreCase = true) ||
                SensitiveMarkerScan.findMarker(text) != null) {
                errors += "unsafe anchor literal at $path"
            }
            if (prefix && !Vocabulary.ANCHOR_LEAD_INS.any { lead ->
                    text.startsWith(lead) && text.endsWith(" ") &&
                        text.substring(lead.length).all { it in 'a'..'z' || it == ' ' }
                }) {
                errors += "stripPrefix must be an approved lead-in (optionally followed by lowercase chrome)"
            }
            if (!prefix && (Vocabulary.ANCHOR_LEAD_INS.any { text.trimStart().startsWith(it.trimEnd(), ignoreCase = true) } ||
                Vocabulary.ANCHOR_GATED_LEAD_INS.any { text.trimStart().startsWith(it, ignoreCase = true) } ||
                customerName.containsMatchIn(text))) {
                errors += "anchor literal looks like customer text"
            }
            if (capitalizedWords.containsMatchIn(text)) {
                warnings += "anchor literal has a name-like shape — confirm it is chrome"
            }
        }

        /** Mirrors PredicateCompiler.hasIdSuffix: case-insensitive endsWith, including resource boundary. */
        private fun idPeers(suffix: String): List<WalkedNode> =
            nodes.filter { it.viewId?.endsWith(suffix, ignoreCase = true) == true }

        /** Mirrors PredicateCompiler.hasClassNameEndsWith at shared-id and shape peer sites. */
        private fun classPeers(peers: List<WalkedNode>, simpleClass: String?): List<WalkedNode> =
            if (simpleClass == null) emptyList() else peers.filter { it.className?.endsWith(simpleClass, ignoreCase = true) == true }

        /** Mirrors PredicateCompiler.hasDesc for the envelope-wide siblingOf label search. */
        private fun descPeers(label: String): List<WalkedNode> =
            nodes.filter { it.desc?.equals(label, ignoreCase = true) == true }

        /** Mirrors PredicateCompiler.hasClassNameEndsWith and hasPrecedingSiblingText, without trimming. */
        private fun siblingPeers(simpleClass: String?, label: String): List<WalkedNode> =
            classPeers(nodes, simpleClass).filter { it.precedingSiblingText?.equals(label, ignoreCase = true) == true }

        private fun predicate(
            ref: PathRef,
            role: Role,
            transforms: List<String> = emptyList(),
            fieldName: String? = null,
        ): JsonObject? {
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
            /** Mirrors PredicateCompiler.hasTextMatchesRegex: case-insensitive containsMatchIn on raw text. */
            fun shapePredicate(peers: List<WalkedNode>): JsonObject? {
                if (role != Role.FIELD) return null
                val shape = transforms.firstNotNullOfOrNull { Vocabulary.VALUE_SHAPES_BY_TRANSFORM[it] }
                    ?: return null
                val regex = shapes.getValue(shape)
                if (node.text?.let { regex.containsMatchIn(it) } != true) return null
                if (peers.count { it.text?.let(regex::containsMatchIn) == true } != 1) return null
                return atom("hasTextMatchesRegex", shape)
            }
            val suffix = node.idSuffix
            if (suffix != null) {
                val resourceSuffix = ":id/$suffix"
                val id = atom("hasIdSuffix", resourceSuffix)
                val peers = idPeers(resourceSuffix)
                if (role == Role.REDACT || peers.size == 1) return id
                val cls = classPredicate() ?: return null
                val predicates = mutableListOf(id, cls)
                if (role == Role.ANCHOR) {
                    when {
                        node.displayText != null -> {
                            val text = requireNotNull(node.text)
                            ref.validateLiteral("hasText", text)
                            predicates += atom("hasText", text)
                        }
                        node.displayDesc != null -> {
                            val desc = requireNotNull(node.desc)
                            ref.validateLiteral("hasDesc", desc)
                            predicates += atom("hasDesc", desc)
                        }
                        else -> errors += "ambiguous id at ${ref.path}"
                    }
                } else if (classPeers(peers, node.simpleClass).size > 1) {
                    // A find returns the FIRST node: id + class must actually identify the selected value.
                    val shape = shapePredicate(classPeers(peers, node.simpleClass))
                    if (shape != null) {
                        predicates += shape
                        val name = requireNotNull(fieldName)
                        warnings += "field $name identity rests on a value shape among same-id peers — verify on more frames"
                    } else errors += "ambiguous id for ${role.name.lowercase(Locale.ROOT)} at ${ref.path}"
                }
                return all(predicates)
            }
            if (role == Role.ANCHOR || role == Role.BIND) {
                return when {
                    node.displayText != null -> classPredicate()?.let { cls ->
                        val text = requireNotNull(node.text)
                        ref.validateLiteral("hasText", text)
                        all(listOf(cls, atom("hasText", text)))
                    }
                    node.displayDesc != null -> {
                        val desc = requireNotNull(node.desc)
                        ref.validateLiteral("hasDesc", desc)
                        atom("hasDesc", desc)
                    }
                    else -> null.also { errors += "class-only anchor refused at ${ref.path}" }
                }
            }
            val preceding = node.precedingSiblingText?.takeIf { node.displayPrecedingSiblingText != null }
            if (preceding == null) {
                errors += "${role.name.lowercase(Locale.ROOT)} has no stable anchor at ${ref.path}"
                return null
            }
            val cls = classPredicate() ?: return null
            if (role != Role.REDACT && siblingPeers(node.simpleClass, preceding).size > 1) {
                errors += "ambiguous sibling anchor at ${ref.path}"
            }
            ref.validateLiteral("hasPrecedingSiblingText", preceding)
            return all(listOf(cls, atom("hasPrecedingSiblingText", preceding)))
        }
    }

    private fun enumValues(name: String): List<String>? = when (name) {
        "phase" -> Vocabulary.TASK_PHASES
        "subFlow" -> Vocabulary.TASK_SUB_FLOWS
        "sessionType" -> Vocabulary.SESSION_TYPES
        else -> null
    }

    private fun validConstant(spec: FieldSpec, value: JsonPrimitive): Boolean {
        if (value == JsonNull) return false
        if (spec.type == FieldType.STRING) return value.isString && value.content in enumValues(spec.name).orEmpty()
        if (value.isString) return false
        return when (spec.type) {
            FieldType.INT -> value.intOrNull != null
            FieldType.BOOLEAN -> value.booleanOrNull != null
            else -> false
        }
    }

    private fun atom(key: String, value: String): JsonObject = buildJsonObject { put(key, value) }
    private fun all(predicates: List<JsonObject>): JsonObject = buildJsonObject { put("all", JsonArray(predicates)) }
    private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
}
