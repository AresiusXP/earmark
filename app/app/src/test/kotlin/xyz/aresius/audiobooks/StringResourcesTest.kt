package xyz.aresius.audiobooks

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/** Every string of `values/` (English) exists in `values-nl/` and `values-es/` with the same format arguments, and nothing is left over. */
class StringResourcesTest {
    private val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    private fun load(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val nodes = doc.documentElement.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().also { els ->
            assertTrue(els.all { it.tagName == "string" }, "$dir: only <string> is supported by the parity check (add plurals handling first)")
        }.associate { it.getAttribute("name") to it.textContent }
    }

    private val args = Regex("%(?:\\d+\\$)?[a-zA-Z%]")
    private fun argsOf(s: String) = args.findAll(s).map { it.value }.sorted().toList()

    private val en = load("values")

    @Test fun dutchAndSpanishHaveExactlyTheEnglishKeys() {
        for (lang in listOf("values-nl", "values-es")) {
            val other = load(lang)
            assertEquals(emptyList(), (en.keys - other.keys).sorted(), "$lang is missing keys")
            assertEquals(emptyList(), (other.keys - en.keys).sorted(), "$lang has orphan keys")
        }
    }

    @Test fun formatArgumentsMatchEnglish() {
        for (lang in listOf("values-nl", "values-es")) {
            val other = load(lang)
            val bad = en.filter { (k, v) -> other[k]?.let { argsOf(it) != argsOf(v) } == true }.keys.sorted()
            assertEquals(emptyList(), bad, "$lang: format arguments differ from English")
        }
    }

    @Test fun spanishIsActuallyTranslated() {
        val es = load("values-es")
        val untranslated = en.filter { (k, v) -> es[k] == v && args.replace(v, "").trim().let { it.length > 12 && ' ' in it } }.keys
        assertEquals(emptyList(), untranslated.sorted(), "sentences identical to English: forgotten translation?")
        assertTrue(es.values.none { it.isBlank() })
        assertEquals("Buenos días, %1\$s", es["greeting_morning"])
        assertEquals("Buenas tardes, %1\$s", es["greeting_afternoon"])
        assertEquals("Buenas noches, %1\$s", es["greeting_evening"])
        assertEquals("Buenos días", es["greeting_morning_anon"])
        assertEquals("Buenas tardes", es["greeting_afternoon_anon"])
        assertEquals("Buenas noches", es["greeting_evening_anon"])
    }

    @Test fun theLocaleListOffersEveryTranslation() {
        val declared = Regex("android:name=\"([^\"]+)\"").findAll(File(res, "xml/locales_config.xml").readText()).map { it.groupValues[1] }.toSet()
        val folders = res.listFiles().orEmpty().filter { it.name.startsWith("values-") && File(it, "strings.xml").isFile }.map { it.name.removePrefix("values-") }.toSet()
        assertEquals(folders + "en", declared)
    }
}
