package com.appdwg.core.drainage

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R8 full mode moves [CatalogLoader] to another package. The release crash was a relative
 * `getResourceAsStream("ipr736.json")` looking beside that new name. This runs the same
 * repackaging and parses the album from the minified classes.
 */
class MinifiedCatalogTest {
    @Test
    fun repackagedProgramParsesAllDevices() {
        val work = kotlin.io.path.createTempDirectory("catalog-r8").toFile()
        try {
            val program = File(work, "program.jar")
            packProgram(program)
            val rules = File(work, "rules.pro")
            rules.writeText(
                """
                -dontoptimize
                -dontwarn **
                -repackageclasses r8test
                -keep class com.appdwg.core.drainage.CatalogReleaseProbe {
                  public static int deviceCount();
                  public static int englishCount();
                  public static int idfCount();
                }
                """.trimIndent(),
            )
            val mapping = File(work, "mapping.txt")
            val out = File(work, "out.jar")
            val log = File(work, "r8.log")
            val command = listOf(
                javaBin(),
                "-cp", r8Jar().absolutePath,
                "com.android.tools.r8.R8",
                "--classfile", "--release",
                "--output", out.absolutePath,
                "--pg-map-output", mapping.absolutePath,
                "--pg-conf", rules.absolutePath,
                "--lib", System.getProperty("java.home"),
                "--lib", androidJar().absolutePath,
                "--lib", kotlinJar().absolutePath,
                program.absolutePath,
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            val finished = process.waitFor(180, TimeUnit.SECONDS)
            assertTrue(finished, "R8 did not finish\n${log.readText().take(2000)}")
            assertEquals(0, process.exitValue(), "R8 failed\n${log.readText().take(4000)}")
            val renamed = mapping.readLines().first { it.startsWith("com.appdwg.core.drainage.CatalogLoader -> ") }
            val newName = renamed.substringAfter(" -> ").trim().removeSuffix(":")
            val newPackage = newName.substringBeforeLast('.', missingDelimiterValue = "")
            assertTrue(
                newPackage != "com.appdwg.core.drainage",
                "R8 left CatalogLoader in its own package ($newName), so this would not catch the release crash",
            )
            URLClassLoader(arrayOf(kotlinJar().toURI().toURL()), null).use { parent ->
                URLClassLoader(arrayOf(out.toURI().toURL()), parent).use { minified ->
                    val probe = minified.loadClass("com.appdwg.core.drainage.CatalogReleaseProbe")
                    assertEquals(396, probe.getMethod("deviceCount").invoke(null))
                    assertTrue((probe.getMethod("englishCount").invoke(null) as Int) > 1000)
                    assertTrue((probe.getMethod("idfCount").invoke(null) as Int) >= 120)
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun packProgram(destination: File) {
        val present = HashSet<String>()
        JarOutputStream(destination.outputStream()).use { jar ->
            fun add(file: File, name: String) {
                val entry = name.replace('\\', '/')
                if (!present.add(entry)) return
                jar.putNextEntry(ZipEntry(entry))
                file.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
            fun addTree(root: File) {
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    add(file, root.toPath().relativize(file.toPath()).toString())
                }
            }
            addTree(codeRoot(CatalogLoader::class.java))
            addTree(resourceRoot())
            val tests = codeRoot(CatalogReleaseProbe::class.java)
            tests.walkTopDown().filter { it.isFile && it.name.startsWith("CatalogReleaseProbe") }.forEach { file ->
                add(file, tests.toPath().relativize(file.toPath()).toString())
            }
        }
    }

    private fun codeRoot(type: Class<*>): File {
        val url = type.protectionDomain.codeSource.location
        return File(url.toURI()).canonicalFile
    }

    private fun resourceRoot(): File {
        val url = CatalogLoader::class.java.getResource("/com/appdwg/core/drainage/ipr736.json")
            ?: error("ipr736.json is not on the test classpath")
        var file = File(url.toURI()).canonicalFile
        for (part in "com/appdwg/core/drainage/ipr736.json".split("/").asReversed()) {
            check(file.name == part) { "resource path ended at ${file.path}, expected $part" }
            file = file.parentFile
        }
        return file
    }

    private fun javaBin(): String {
        val home = File(System.getProperty("java.home"))
        val bin = File(home, "bin/java")
        return if (bin.isFile) bin.absolutePath else "java"
    }

    private fun kotlinJar(): File {
        val url = kotlin.Lazy::class.java.protectionDomain.codeSource.location
        return File(url.toURI()).canonicalFile.also { check(it.isFile) { "kotlin stdlib is not a jar: $it" } }
    }

    private fun androidJar(): File {
        val sdk = sdkDir()
        val platforms = File(sdk, "platforms")
        val jar = platforms.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("android-") }
            .maxByOrNull { it.name.removePrefix("android-").toIntOrNull() ?: 0 }
            ?.resolve("android.jar")
        return jar?.takeIf { it.isFile } ?: error("android.jar not found under $platforms")
    }

    private fun sdkDir(): File {
        System.getenv("ANDROID_SDK_ROOT")?.let { return File(it) }
        System.getenv("ANDROID_HOME")?.let { return File(it) }
        val root = repoRoot()
        val local = File(root, "local.properties")
        if (local.isFile) {
            val line = local.readLines().firstOrNull { it.startsWith("sdk.dir=") }
            if (line != null) return File(line.substringAfter("sdk.dir=").trim())
        }
        error("Android SDK not found (ANDROID_SDK_ROOT, ANDROID_HOME, or local.properties)")
    }

    private fun r8Jar(): File {
        val home = System.getenv("GRADLE_USER_HOME")?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".gradle")
        val builders = File(home, "caches/modules-2/files-2.1/com.android.tools.build/builder")
        val jars = builders.walkTopDown().filter { it.isFile && it.name.startsWith("builder-") && it.extension == "jar" }.toList()
        val withR8 = jars.filter { jar ->
            runCatching { ZipFile(jar).use { it.getEntry("com/android/tools/r8/R8.class") != null } }.getOrDefault(false)
        }
        return withR8.maxByOrNull { versionOf(it) } ?: error("R8 was not found under $builders")
    }

    private fun versionOf(jar: File): String {
        val parts = jar.absolutePath.split(File.separator)
        val index = parts.indexOf("builder")
        return if (index >= 0 && index + 1 < parts.size) parts[index + 1] else jar.name
    }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile && File(it, "app").isDirectory }
}
