package org.dad.foundation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the shared catalog vectors through the validator, then the renderer's rules against a real dashboard shape. */
class InsightsCatalogTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }
    private fun contract(name: String) = File("../contracts/foundation/$name").readText()
    private val catalog = InsightsCatalog(resource("ui-catalog.json"), resource("links.json"))
    private val openapi = JsonParser.parseString(contract("openapi.json")).asJsonObject

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject
    private fun panel(spec: String, dashboard: JsonObject, version: Long = 1) =
        InsightsPanel(version, "spec", JsonParser.parseString(spec).asJsonArray, dashboard)

    @Test fun everySharedVectorIsAcceptedOrRejectedWithTheExpectedCodes() {
        val vectors = JsonParser.parseString(contract("ui-catalog-vectors.json")).asJsonObject.getAsJsonArray("vectors")
        val resolver = SchemaResolver(openapi)
        assertTrue(vectors.size() > 0)
        for (vector in vectors.map { it.asJsonObject }) {
            val codes = catalog.validate(vector.get("spec"), resolver).map { it.code }
            val name = vector.get("name").asString
            if (vector.get("valid").asBoolean) assertEquals("$name should be valid", emptyList<String>(), codes)
            else assertEquals(name, vector.getAsJsonArray("codes").map { it.asString }, codes)
        }
    }

    @Test fun theBundledCaptionAndLinkTablesCoverTheCatalogInEnglish() {
        val shipped = obj(resource("ui-catalog.json"))
        val captions = shipped.getAsJsonObject("metric_captions")
        assertEquals(captions.keySet(), InsightsCatalog.captionIds)
        for ((id, text) in captions.entrySet()) {
            assertEquals(id, text.asString, TestStrings.text(UiText.res(InsightsCatalog.captionResource(id)!!)))
        }
        for ((path, text) in shipped.getAsJsonObject("navigate").getAsJsonObject("labels").entrySet()) {
            assertEquals(path, text.asString, TestStrings.text(UiText.res(InsightsCatalog.linkLabelResource(path)!!)))
        }
    }

    private val dashboard = obj(
        """{"state":"ready","founding_donor":true,"impact":{"given_minor":124000,"initiatives_funded":4,"events_volunteered":1},
           "streak":{"current":1,"ladder":{"days":18,"days_to_next":1}},"network":null,"rank":{"city":"Austin"},"computed_at":"2026-10-30T12:00:00Z"}""",
    )

    @Test fun aValidPanelDrawsEveryComponentInItsFormat() {
        val spec = """[
          {"type":"Heading","title":"Your impact"},
          {"type":"Text","bind":"/rank/city"},
          {"type":"MetricGrid","children":[
            {"type":"Metric","bind":"/impact/given_minor","format":"usd_minor","caption":"given"},
            {"type":"Metric","bind":"/streak/ladder/days","format":"days","caption":"streak"},
            {"type":"Metric","bind":"/streak/ladder/days_to_next","format":"days"},
            {"type":"Metric","bind":"/impact/events_volunteered","format":"percent"},
            {"type":"Metric","bind":"/impact/initiatives_funded","format":"integer"}]},
          {"type":"Callout","tone":"success","title":"Nice","bind":"/founding_donor"},
          {"type":"LinkButton","navigate":"/account/gifts"}]"""
        val blocks = catalog.blocks(panel(spec, dashboard))
        assertEquals(5, blocks.size)
        assertEquals("Your impact", (blocks[0] as InsightBlock.Heading).text.text())
        assertEquals("Austin", (blocks[1] as InsightBlock.Body).text.text())
        val cells = (blocks[2] as InsightBlock.Metrics).cells
        assertEquals(listOf("$1,240", "18 days", "1 day", "1%", "4"), cells.map { it.value.text() })
        assertEquals(listOf("Total given", "Current streak", null, null, null), cells.map { it.caption.textOrNull() })
        val callout = blocks[3] as InsightBlock.Callout
        assertEquals("success", callout.tone)
        assertEquals("Yes", callout.body.text())
        val link = blocks[4] as InsightBlock.Link
        assertEquals(AccountDestination.GIFTS, link.destination)
        assertEquals("Your gifts", link.label.text())
    }

    @Test fun nodesWithoutAValueDrawNothingAndAnUnknownTypeIsDropped() {
        val spec = """[
          {"type":"Metric","bind":"/network/active_count","format":"integer"},
          {"type":"Text","bind":"/network"},
          {"type":"Sparkline","points":[1,2,3],"bind":"not looked at"},
          {"type":"Text","bind":"/rank/city"}]"""
        val blocks = catalog.blocks(panel(spec, dashboard))
        assertEquals(listOf("Austin"), blocks.map { (it as InsightBlock.Body).text.text() })
    }

    @Test fun aListDrawsOnlyRowsThatResolveAndRefusesAnEmptyOrMissingArray() {
        val withItems = obj("""{"state":"ready","items":[1],"rank":{"city":"Austin"}}""")
        val spec = """[{"type":"List","bind":"/items","children":[{"type":"Text","bind":"/rank/city"},{"type":"Text","bind":"/rank/nope"},{"type":"LinkButton","navigate":"/account/profile"}]}]"""
        val rows = (catalog.blocks(panel(spec, withItems)).single() as InsightBlock.Rows).rows
        assertEquals(2, rows.size)
        assertTrue(catalog.blocks(panel(spec, obj("""{"items":[],"rank":{"city":"A"}}"""))).isEmpty())
        assertTrue(catalog.blocks(panel(spec, obj("""{"rank":{"city":"A"}}"""))).isEmpty())
    }

    @Test fun noPanelForAnotherCatalogVersionAnEmptySpecOrAFailedValidation() {
        val ok = """[{"type":"Text","bind":"/rank/city"}]"""
        assertEquals(1, catalog.blocks(panel(ok, dashboard)).size)
        assertTrue(catalog.blocks(panel(ok, dashboard, version = 2)).isEmpty())
        assertTrue(catalog.blocks(panel("[]", dashboard)).isEmpty())
        assertTrue(catalog.blocks(panel("""[{"type":"Text","bind":"/rank/city"},{"type":"LinkButton","navigate":"/partner"}]""", dashboard)).isEmpty())
        assertTrue(catalog.blocks(panel("""[{"type":"Heading","title":"<b>x</b>"}]""", dashboard)).isEmpty())
        assertTrue(catalog.blocks(null).isEmpty())
    }

    @Test fun aLayoutCannotReadItsOwnFieldAndLinkButtonsRefuseNonAccountPaths() {
        val self = obj("""{"insights":{"spec":[]},"rank":{"city":"A"}}""")
        assertTrue(catalog.blocks(panel("""[{"type":"Text","bind":"/insights/spec_id"}]""", self)).isEmpty())
        for (path in listOf("/partner", "/account/claim", "https://example.com/account/gifts", "/account/gifts/")) {
            val issues = catalog.validate(JsonParser.parseString("""[{"type":"LinkButton","navigate":"$path"}]"""), InsightsCatalog.dashboardResolver(dashboard))
            assertEquals(path, listOf("NAV_FORBIDDEN"), issues.map { it.code })
        }
    }

    @Test fun parsePanelNeverThrowsOnMalformedInput() {
        assertNull(parseInsightsPanel(obj("""{}""")))
        assertNull(parseInsightsPanel(obj("""{"insights":null}""")))
        assertNull(parseInsightsPanel(obj("""{"insights":"x"}""")))
        assertNull(parseInsightsPanel(obj("""{"insights":{"catalog_version":"1","spec":[]}}""")))
        assertNull(parseInsightsPanel(obj("""{"insights":{"catalog_version":1.5,"spec":[]}}""")))
        assertNull(parseInsightsPanel(obj("""{"insights":{"catalog_version":1,"spec":{}}}""")))
        assertNotNull(parseInsightsPanel(obj("""{"insights":{"catalog_version":1,"spec_id":"s","spec":[1,null,"x"]}}""")))
        assertTrue(catalog.blocks(parseInsightsPanel(obj("""{"insights":{"catalog_version":1,"spec":[1,null,"x"]}}"""))).isEmpty())
    }

    /** Resolves binds against `DonorDashboard` in the vendored openapi.json, as the reference validator does. */
    private class SchemaResolver(private val openapi: JsonObject) : BindResolver {
        private data class Alt(val type: String?, val properties: JsonObject?)

        private fun target(ref: String) = openapi.getAsJsonObject("components").getAsJsonObject("schemas").getAsJsonObject(ref.substringAfterLast('/'))

        private fun alternatives(schema: JsonElement?): List<Alt> {
            val s = schema as? JsonObject ?: return emptyList()
            s.get("\$ref")?.let { return alternatives(target(it.asString)) }
            (s.get("oneOf") ?: s.get("anyOf"))?.let { return it.asJsonArray.flatMap(::alternatives) }
            s.get("allOf")?.let { all ->
                val parts = all.asJsonArray.flatMap(::alternatives)
                val properties = JsonObject().also { merged -> parts.forEach { p -> p.properties?.entrySet()?.forEach { (k, v) -> merged.add(k, v) } } }
                return listOf(Alt(parts.firstOrNull { it.type != null }?.type, properties.takeIf { it.size() > 0 }))
            }
            val types = when (val t = s.get("type")) { is JsonArray -> t.map { it.asString }; null -> listOf(null); else -> listOf(t.asString) }
            return types.filter { it != "null" }.map { Alt(it, s.getAsJsonObject("properties")) }
        }

        override fun resolve(pointer: String): BindShape {
            val segments = InsightsCatalog.parsePointer(pointer) ?: return BindShape.Syntax
            if (segments.first() == "insights") return BindShape.Unresolved
            var current = alternatives(JsonObject().apply { addProperty("\$ref", "#/components/schemas/DonorDashboard") })
            for (segment in segments) {
                current = current.filter { it.type == "object" && it.properties?.has(segment) == true }.flatMap { alternatives(it.properties!!.get(segment)) }
                if (current.isEmpty()) return BindShape.Unresolved
            }
            val kinds = current.map { it.type }.toSet()
            val kind = kinds.singleOrNull() ?: "mixed"
            return BindShape.Found(kind, kinds.all { it in setOf("string", "integer", "number", "boolean") })
        }
    }
}
