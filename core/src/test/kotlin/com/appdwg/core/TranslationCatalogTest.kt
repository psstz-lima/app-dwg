package com.appdwg.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every text passed to tr() in the app and the core has an English entry with the same placeholders. */
class TranslationCatalogTest {
    private val call = Regex("""(?<![A-Za-z0-9_])tr\("((?:[^"\\$]|\\.)*)"[,)]""")

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\$", "$").replace("\\\\", "\\")

    private fun sources(): List<File> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }
        return listOf("core/src/main/kotlin", "app/src/main/kotlin").flatMap { File(root, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
    }

    @Test
    fun everyTextHasAnEnglishEntry() {
        val missing = sources().flatMap { f -> call.findAll(f.readText()).map { unescape(it.groupValues[1]) }.filter { it !in I18n.english }.map { "${f.name}: $it" } }
        assertTrue(missing.isEmpty(), "Missing English for:\n" + missing.joinToString("\n"))
    }

    @Test
    fun placeholdersMatch() {
        val ph = Regex("""\{\d+\}""")
        val bad = I18n.english.filter { (pt, en) -> ph.findAll(pt).map { it.value }.toSet() != ph.findAll(en).map { it.value }.toSet() }
        assertTrue(bad.isEmpty(), "Placeholders differ:\n" + bad.entries.joinToString("\n"))
    }

    @Test
    fun englishIsUsedOutsidePortuguese() {
        val saved = Display.locale
        try {
            Display.locale = java.util.Locale.US
            kotlin.test.assertEquals("Close", tr("Fechar"))
            kotlin.test.assertEquals("Version 2", tr("Versão {0}", 2))
            Display.locale = java.util.Locale.forLanguageTag("pt-BR")
            kotlin.test.assertEquals("Fechar", tr("Fechar"))
        } finally { Display.locale = saved }
    }
}
