package com.appdwg.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** GLES 3.00 rejects `patch`. Every shader used by the app is checked, and by glslang when it is installed. */
class ShaderSourceTest {
    @Test
    fun reservedWordsStayOutOfEveryShader() {
        assertEquals(emptyList<String>(), GlslCheck.problems(GlShaders.VERTEX, 300))
        assertEquals(emptyList<String>(), GlslCheck.problems(GlShaders.FRAGMENT, 300))
        assertEquals(emptyList<String>(), GlslCheck.problems(GlShaders.AR_VERTEX, 100))
        assertEquals(emptyList<String>(), GlslCheck.problems(GlShaders.AR_FRAGMENT, 100))
        assertTrue(GlslCheck.problems("float patch = 1.0;", 300).any { "patch" in it })
    }

    @Test
    fun glslangCompilesEs300AndEs100() {
        val tool = glslang()
        assumeTrue("glslangValidator is not installed", tool != null)
        val validator = tool!!
        compile(validator, GlShaders.VERTEX, "vert")
        compile(validator, GlShaders.FRAGMENT, "frag")
        compile(validator, GlShaders.AR_VERTEX, "vert")
        compile(validator, GlShaders.AR_FRAGMENT, "frag")
    }

    private fun compile(tool: String, source: String, stage: String) {
        val file = File.createTempFile("gromatic-", ".$stage")
        file.writeText(source)
        file.deleteOnExit()
        val proc = ProcessBuilder(tool, "-S", stage, file.absolutePath)
            .redirectErrorStream(true)
            .start()
        val log = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        val onlyExtension = log.lines().all { line ->
            val bad = "ERROR" in line
            !bad || "extension" in line.lowercase() || "samplerExternalOES" in line
        }
        assertTrue("$stage failed ($code):\n$log", code == 0 || onlyExtension)
    }

    private fun glslang(): String? {
        val env = System.getenv("GLSLANG_VALIDATOR")
        if (!env.isNullOrBlank() && File(env).canExecute()) return env
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        return path.firstNotNullOfOrNull { dir ->
            File(dir, "glslangValidator").takeIf { it.canExecute() }?.absolutePath
        }
    }
}
