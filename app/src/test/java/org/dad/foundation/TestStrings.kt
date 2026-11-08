package org.dad.foundation

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Resolves [UiText] against the real `strings.xml` on the JVM, so model tests can keep asserting the exact English
 * a donor reads without Robolectric or a Context. Plural selection follows English rules (`one` for 1, else `other`).
 */
object TestStrings {
    private val stringNames: Map<Int, String> = idsOf(R.string::class.java)
    private val pluralNames: Map<Int, String> = idsOf(R.plurals::class.java)
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml")).documentElement
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            when (node.tagName) {
                "string" -> strings[node.getAttribute("name")] = unescape(node.textContent)
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    plurals[node.getAttribute("name")] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
    }

    fun text(value: UiText): String = when (value) {
        is UiText.Plain -> value.value
        is UiText.Res -> format(strings.getValue(stringNames.getValue(value.id)), value.args)
        is UiText.Plural -> {
            val forms = plurals.getValue(pluralNames.getValue(value.id))
            format(forms.getValue(if (value.quantity == 1) "one" else "other"), value.args)
        }
    }

    fun string(name: String): String = strings.getValue(name)

    val stringKeys: Set<String> get() = strings.keys

    private fun format(pattern: String, args: List<Any>): String =
        String.format(Locale.US, pattern, *args.map { (it as? UiText)?.let(::text) ?: it }.toTypedArray())

    private fun idsOf(type: Class<*>): Map<Int, String> = type.fields.associate { it.getInt(null) to it.name }

    private fun unescape(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'u' -> { out.append(raw.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

fun UiText.text(): String = TestStrings.text(this)
fun UiText?.textOrNull(): String? = this?.let(TestStrings::text)
