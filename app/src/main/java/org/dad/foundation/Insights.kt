package org.dad.foundation

import androidx.annotation.StringRes
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The optional `insights` panel of the donor dashboard: a layout drawn from the shared UI catalog. It is held as
 * parsed JSON because a newer server may send node types this build has never heard of, and those must be
 * dropped without ever failing the dashboard. [dashboard] is the whole response; binds resolve against it.
 */
data class InsightsPanel(val catalogVersion: Long, val specId: String, val spec: JsonArray, val dashboard: JsonObject)

/** Reads the panel out of a dashboard response; any malformed shape is "no panel", never an error. */
internal fun parseInsightsPanel(dashboard: JsonObject): InsightsPanel? {
    val panel = (dashboard.get("insights") as? JsonObject) ?: return null
    val version = panel.get("catalog_version")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.let { runCatching { it.asBigDecimal.longValueExact() }.getOrNull() } ?: return null
    val spec = panel.get("spec")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val specId = panel.get("spec_id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
    return InsightsPanel(version, specId, spec, dashboard)
}

data class UiIssue(val path: String, val code: String)

/** What a bind pointer points at. [Absent] is a valid field with no value now (preparing state, null, omitted). */
sealed interface BindShape {
    data object Syntax : BindShape
    data object Unresolved : BindShape
    data object Absent : BindShape
    data class Found(val kind: String, val scalar: Boolean) : BindShape
}

fun interface BindResolver { fun resolve(pointer: String): BindShape }

/** Everything a rendered insights panel can draw, already resolved against the dashboard. */
sealed interface InsightBlock {
    data class Heading(val text: UiText) : InsightBlock
    data class Body(val text: UiText) : InsightBlock
    data class Metrics(val cells: List<MetricCell>) : InsightBlock
    data class Callout(val tone: String, val title: String, val body: UiText) : InsightBlock
    data class Rows(val rows: List<InsightBlock>) : InsightBlock
    data class Link(val destination: AccountDestination, val label: UiText) : InsightBlock
}

data class MetricCell(val value: UiText, val caption: UiText?)

/**
 * The closed component set from the vendored `ui-catalog.json`, plus the validator and resolver that turn a spec
 * into [InsightBlock]s. Mirrors the reference validator in DAD-Main (`scripts/ui-catalog.mjs`) and the web
 * renderer: a node of an unknown type, a bind with no value, or a value of the wrong type draws nothing, and a
 * spec that fails validation or targets another `catalog_version` draws no panel.
 */
class InsightsCatalog(catalogJson: String, linksJson: String) {
    private val catalog = JsonParser.parseString(catalogJson).asJsonObject
    val version: Long = catalog.get("catalog_version").asLong
    private val limits = catalog.getAsJsonObject("limits")
    private val components = catalog.getAsJsonObject("components")
    private val formats = catalog.getAsJsonObject("formats").keySet()
    private val captions = catalog.getAsJsonObject("metric_captions").keySet()
    private val allowedPaths: Set<String> = catalog.getAsJsonObject("navigate").getAsJsonArray("path_lists").flatMap { list ->
        JsonParser.parseString(linksJson).asJsonObject.getAsJsonArray(list.asString)?.map { it.asString }.orEmpty()
    }.toSet()

    /** The blocks to draw, or empty when there is no panel to show. */
    fun blocks(panel: InsightsPanel?): List<InsightBlock> {
        if (panel == null || panel.catalogVersion != version || panel.spec.size() == 0) return emptyList()
        val resolver = dashboardResolver(panel.dashboard)
        if (validate(panel.spec, resolver).isNotEmpty()) return emptyList()
        return panel.spec.mapNotNull { (it as? JsonObject)?.let { node -> block(node, panel.dashboard, nested = false) } }
    }

    fun validate(spec: JsonElement, resolver: BindResolver): List<UiIssue> {
        val issues = mutableListOf<UiIssue>()
        val counted = HashSet<String>()
        var nodes = 0
        var nodeLimitReported = false
        fun fail(path: String, code: String) { issues += UiIssue(path, code) }
        fun count(path: String) {
            counted += path
            nodes++
            if (nodes > limits.get("max_nodes").asInt && !nodeLimitReported) { nodeLimitReported = true; fail(path, "TOO_MANY_NODES") }
        }
        fun string(value: JsonElement) = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        lateinit var validateNode: (JsonElement, String, Int) -> Unit

        fun checkBind(path: String, value: JsonElement, target: String) {
            val pointer = string(value) ?: return fail(path, "BAD_TYPE")
            when (val shape = resolver.resolve(pointer)) {
                BindShape.Syntax -> fail(path, "BIND_SYNTAX")
                BindShape.Unresolved -> fail(path, "BIND_UNRESOLVED")
                BindShape.Absent -> Unit
                is BindShape.Found -> when {
                    target == "array" -> if (shape.kind != "array") fail(path, "BIND_NOT_ARRAY")
                    !shape.scalar -> fail(path, "BIND_NOT_SCALAR")
                    target == "integer" && shape.kind != "integer" -> fail(path, "BIND_NOT_INTEGER")
                }
            }
        }

        fun checkChildren(at: String, value: JsonElement, definition: JsonObject, component: String) {
            if (!value.isJsonArray) return fail(at, "BAD_TYPE")
            val children = value.asJsonArray
            if (children.size() == 0) return fail(at, "CHILDREN_EMPTY")
            if (children.size() > limits.get(definition.get("max").asString).asInt) {
                fail(at, if (component == "MetricGrid") "GRID_TOO_LARGE" else "LIST_TOO_LARGE")
            }
            val allowed = definition.getAsJsonArray("allowed").map { it.asString }
            children.forEachIndexed { index, child ->
                val childPath = "$at/$index"
                val type = (child as? JsonObject)?.get("type")?.let(::string)
                if (type != null && components.has(type)) {
                    count(childPath)
                    if (type !in allowed) {
                        fail(childPath, "CHILD_NOT_ALLOWED")
                        if (child.asJsonObject.get("children")?.isJsonArray == true) fail("$childPath/children", "TOO_DEEP")
                        return@forEachIndexed
                    }
                }
                validateNode(child, childPath, 2)
            }
        }

        fun checkProp(path: String, name: String, definition: JsonObject, value: JsonElement, component: String) {
            val at = "$path/$name"
            when (definition.get("kind").asString) {
                "label" -> {
                    val problem = labelProblem(value)
                    if (problem != null) return fail(at, problem)
                    if (string(value)!!.let { it.codePointCount(0, it.length) } > limits.get(definition.get("max").asString).asInt) fail(at, "LABEL_TOO_LONG")
                }
                "bind" -> checkBind(at, value, definition.get("target").asString)
                "enum" -> {
                    val text = string(value) ?: return fail(at, "BAD_TYPE")
                    if (definition.getAsJsonArray("values").none { it.asString == text }) fail(at, "BAD_ENUM")
                }
                "format" -> { val text = string(value) ?: return fail(at, "BAD_TYPE"); if (text !in formats) fail(at, "BAD_FORMAT") }
                "caption" -> { val text = string(value) ?: return fail(at, "BAD_TYPE"); if (text !in captions) fail(at, "BAD_CAPTION") }
                "navigate" -> { val text = string(value) ?: return fail(at, "BAD_TYPE"); if (text !in allowedPaths) fail(at, "NAV_FORBIDDEN") }
                "children" -> checkChildren(at, value, definition, component)
            }
        }

        validateNode = { node, path, depth ->
            if (path !in counted) count(path)
            val obj = node as? JsonObject
            val type = obj?.get("type")?.let(::string)
            if (obj == null) fail(path, "NODE_INVALID")
            else if (type.isNullOrEmpty()) fail(path, "MISSING_TYPE")
            // An unknown type is valid and draws nothing; its contents are never inspected.
            else if (components.has(type)) {
                val definition = components.getAsJsonObject(type)
                val props = definition.getAsJsonObject("props")
                if (depth > limits.get("max_depth").asInt) fail(path, "TOO_DEEP")
                else {
                    for (key in obj.keySet()) if (key != "type" && !props.has(key)) fail("$path/$key", "UNKNOWN_PROP")
                    definition.getAsJsonArray("required")?.forEach { if (!obj.has(it.asString)) fail("$path/${it.asString}", "MISSING_PROP") }
                    definition.getAsJsonArray("exactly_one_of")?.let { group ->
                        if (group.count { obj.has(it.asString) } != 1) fail(path, "ONE_OF")
                    }
                    for ((name, prop) in props.entrySet()) if (obj.has(name)) checkProp(path, name, prop.asJsonObject, obj.get(name), type)
                }
            }
        }

        when {
            !spec.isJsonArray -> fail("", "SPEC_NOT_ARRAY")
            spec.asJsonArray.size() == 0 -> fail("", "SPEC_EMPTY")
            else -> spec.asJsonArray.forEachIndexed { index, node -> validateNode(node, "/$index", 1) }
        }
        return issues
    }

    private fun block(node: JsonObject, dashboard: JsonObject, nested: Boolean): InsightBlock? = when (node.optStr("type")) {
        "Heading" -> (node.optStr("title")?.takeIf { it.isNotBlank() }?.let(UiText::Plain) ?: node.scalar("bind", dashboard))?.let(InsightBlock::Heading)
        "Text" -> node.scalar("bind", dashboard)?.let(InsightBlock::Body)
        "Metric" -> metric(node, dashboard)?.let { InsightBlock.Metrics(listOf(it)) }
        "MetricGrid" -> node.children().mapNotNull { child -> child.takeIf { it.optStr("type") == "Metric" }?.let { metric(it, dashboard) } }
            .takeIf { it.isNotEmpty() }?.let(InsightBlock::Metrics)
        "Callout" -> {
            val title = node.optStr("title")?.takeIf { it.isNotBlank() }
            val body = node.optStr("body")?.takeIf { it.isNotBlank() }?.let(UiText::Plain) ?: node.scalar("bind", dashboard)
            if (title == null || body == null) null else InsightBlock.Callout(node.optStr("tone") ?: "info", title, body)
        }
        "List" -> {
            val items = pointer(dashboard, node.optStr("bind"))
            if (nested || items !is JsonArray || items.size() == 0) null
            else node.children().take(LIST_ROW_LIMIT).mapNotNull { row -> if (row.optStr("type") in ROW_TYPES) block(row, dashboard, nested = true) else null }
                .takeIf { it.isNotEmpty() }?.let(InsightBlock::Rows)
        }
        "LinkButton" -> node.optStr("navigate")?.let { path ->
            AccountDestination.from(path)?.takeIf { path in allowedPaths }?.let { InsightBlock.Link(it, UiText.res(navigateLabelRes.getValue(it))) }
        }
        else -> null
    }

    private fun metric(node: JsonObject, dashboard: JsonObject): MetricCell? {
        val value = pointer(dashboard, node.optStr("bind"))?.takeIf { it.isWholeNumber() }?.asLong ?: return null
        val text = when (node.optStr("format")) {
            "integer" -> UiText.Plain(String.format(Locale.US, "%,d", value))
            "usd_minor" -> UiText.Plain(formatMinorUsd(value))
            "days" -> UiText.plural(R.plurals.insights_days, value.toQuantity(), value)
            "percent" -> UiText.res(R.string.insights_percent, value)
            else -> return null
        }
        return MetricCell(text, node.optStr("caption")?.let { captionRes[it] }?.let { UiText.res(it) })
    }

    private fun JsonObject.scalar(key: String, dashboard: JsonObject): UiText? = scalarText(pointer(dashboard, optStr(key)))
    private fun JsonObject.children(): List<JsonObject> = (get("children") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    companion object {
        /** The catalog shipped in the app, or null when its assets are unreadable (no panel is then ever drawn). */
        fun load(assets: android.content.res.AssetManager): InsightsCatalog? = runCatching {
            fun asset(name: String) = assets.open(name).bufferedReader().use { it.readText() }
            InsightsCatalog(asset("ui-catalog.json"), asset("links.json"))
        }.getOrNull()

        private const val LIST_ROW_LIMIT = 8
        private val ROW_TYPES = setOf("Text", "LinkButton")
        private const val SELF_FIELD = "insights"

        private val captionRes: Map<String, Int> = mapOf(
            "given" to R.string.insights_caption_given,
            "initiatives_funded" to R.string.insights_caption_initiatives_funded,
            "events_volunteered" to R.string.insights_caption_events_volunteered,
            "referral_total" to R.string.insights_caption_referral_total,
            "referral_link" to R.string.insights_caption_referral_link,
            "referral_irl" to R.string.insights_caption_referral_irl,
            "active_network" to R.string.insights_caption_active_network,
            "annualized_network" to R.string.insights_caption_annualized_network,
            "streak" to R.string.insights_caption_streak,
            "days_to_next" to R.string.insights_caption_days_to_next,
            "last_milestone" to R.string.insights_caption_last_milestone,
            "national_percentile" to R.string.insights_caption_national_percentile,
            "city_rank" to R.string.insights_caption_city_rank,
        )

        @StringRes private fun navigateRes(destination: AccountDestination): Int = when (destination) {
            AccountDestination.DASHBOARD -> R.string.insights_link_dashboard
            AccountDestination.PROFILE -> R.string.insights_link_profile
            AccountDestination.PAYMENT_METHOD -> R.string.insights_link_payment_method
            AccountDestination.GIFTS -> R.string.insights_link_gifts
        }
        private val navigateLabelRes: Map<AccountDestination, Int> = AccountDestination.entries.associateWith(::navigateRes)

        internal val captionIds: Set<String> get() = captionRes.keys
        internal fun captionResource(id: String): Int? = captionRes[id]
        internal fun linkLabelResource(path: String): Int? = AccountDestination.from(path)?.let(navigateLabelRes::get)

        /** RFC 6901 syntax as the reference validator checks it: a leading `/`, no empty segment, `~` only as `~0` or `~1`. */
        fun parsePointer(pointer: String): List<String>? {
            if (!pointer.startsWith("/")) return null
            val segments = pointer.substring(1).split("/")
            if (segments.any { it.isEmpty() || BAD_ESCAPE.containsMatchIn(it) || it.endsWith("~") }) return null
            return segments.map { it.replace("~1", "/").replace("~0", "~") }
        }

        /** The value a pointer reaches in the dashboard response, or null. A layout never reads its own field. */
        fun pointer(dashboard: JsonObject, pointer: String?): JsonElement? {
            val segments = pointer?.let(::parsePointer) ?: return null
            if (segments.first() == SELF_FIELD) return null
            var current: JsonElement = dashboard
            for (segment in segments) {
                current = (current as? JsonObject)?.get(segment) ?: return null
            }
            return current.takeUnless { it.isJsonNull }
        }

        /** Validates binds by the value that is actually there; a field with no value now is [BindShape.Absent], not an error. */
        fun dashboardResolver(dashboard: JsonObject) = BindResolver { pointer ->
            val segments = parsePointer(pointer)
            when {
                segments == null -> BindShape.Syntax
                segments.first() == SELF_FIELD -> BindShape.Unresolved
                else -> {
                    var current: JsonElement? = dashboard
                    for (segment in segments) current = (current as? JsonObject)?.get(segment)
                    when {
                        current == null || current.isJsonNull -> BindShape.Absent
                        current.isJsonArray -> BindShape.Found("array", scalar = false)
                        current.isJsonObject -> BindShape.Found("object", scalar = false)
                        current.asJsonPrimitive.isBoolean -> BindShape.Found("boolean", scalar = true)
                        current.asJsonPrimitive.isNumber -> BindShape.Found(if (current.isWholeNumber()) "integer" else "number", scalar = true)
                        else -> BindShape.Found("string", scalar = true)
                    }
                }
            }
        }

        private val BAD_ESCAPE = Regex("~(?![01])")
        private val SCHEME_LIKE = Regex("(?:^|[^a-z0-9])(?:[a-z][a-z0-9+.-]{1,30}://|(?:https?|javascript|data|mailto|tel|sms|file|ftp|blob|vbscript):|www\\.)", RegexOption.IGNORE_CASE)
        private val MARKDOWN_LINK = Regex("\\]\\s*\\(|\\]\\s*\\[|^\\s*\\[[^\\]]*\\]:\\s", RegexOption.MULTILINE)
        private val CONTROL_OR_FORMAT = Regex("[<>]|[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
        private val ISO_INSTANT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")

        /** The reason a literal label is refused (`null` when acceptable), by the catalog's text policy. */
        fun labelProblem(value: JsonElement): String? {
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return "BAD_TYPE"
            val text = value.asString
            return when {
                text.isBlank() -> "LABEL_EMPTY"
                CONTROL_OR_FORMAT.containsMatchIn(text) || SCHEME_LIKE.containsMatchIn(text) || MARKDOWN_LINK.containsMatchIn(text) -> "LABEL_CONTENT"
                else -> null
            }
        }

        /** A scalar as display text, or null for null, objects and arrays. */
        fun scalarText(value: JsonElement?): UiText? {
            val primitive = value?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
            return when {
                primitive.isString -> {
                    val text = primitive.asString
                    val instant = if (ISO_INSTANT.containsMatchIn(text)) try { OffsetDateTime.parse(text) } catch (_: DateTimeParseException) { null } else null
                    UiText.Plain(instant?.let { friendlyDate(it) } ?: text)
                }
                primitive.isBoolean -> UiText.res(if (primitive.asBoolean) R.string.insights_yes else R.string.insights_no)
                value.isWholeNumber() -> UiText.Plain(String.format(Locale.US, "%,d", primitive.asLong))
                else -> primitive.asBigDecimal.toDouble().takeIf { it.isFinite() }?.let { UiText.Plain(String.format(Locale.US, "%,.3f", it).trimEnd('0').trimEnd('.')) }
            }
        }
    }
}

private val WHOLE_NUMBER = Regex("-?\\d{1,18}")

/** A JSON number written as a whole number that fits a [Long] safely (the reference validator's `integer`). */
internal fun JsonElement.isWholeNumber(): Boolean =
    isJsonPrimitive && asJsonPrimitive.isNumber && WHOLE_NUMBER.matches(asString)
