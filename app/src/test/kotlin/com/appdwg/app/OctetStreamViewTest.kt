package com.appdwg.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drive's Open with on a .gmt arrives as a content VIEW of application/octet-stream.
 * Sharing a zip must not offer the app, and a file that is not a .gmt must not replace the open project.
 */
class OctetStreamViewTest {
    @Test
    fun octetStreamIsViewOnlyOnTheContentScheme() {
        val manifest = File(root(), "app/src/main/AndroidManifest.xml").readText()
        val filters = Regex("<intent-filter>[\\s\\S]*?</intent-filter>").findAll(manifest).map { it.value }.toList()
        val octet = filters.filter { it.contains("application/octet-stream") }
        assertTrue(octet.size == 1)
        assertTrue(octet[0].contains("android.intent.action.VIEW"))
        assertTrue(octet[0].contains("android:scheme=\"content\""))
        assertFalse(octet[0].contains("android.intent.action.SEND"))
        assertFalse(manifest.contains("application/zip"))
    }

    @Test
    fun aForeignOctetStreamKeepsTheOpenProject() {
        val model = File(root(), "app/src/main/kotlin/com/appdwg/app/ProjectViewModel.kt").readText()
        val activity = File(root(), "app/src/main/kotlin/com/appdwg/app/MainActivity.kt").readText()
        assertTrue(activity.contains("intent.action == Intent.ACTION_VIEW"))
        assertTrue(activity.contains("""intent.resolveType(this) == "application/octet-stream""""))
        assertTrue(activity.contains("uri.scheme == \"content\""))
        val gate = model.indexOf("if (claimedOctetStream)")
        val refuse = model.indexOf(
            "if (!gmt) {\n                    val message = tr(\"Este arquivo não é um projeto .gmt do Gromatic.\")\n" +
                "                    if (projectOpen) projectMessage = message else state = ProjectState.Failed(displayName(uri), message)\n" +
                "                    return@launch",
        )
        val attach = model.indexOf("PickRoute.ATTACH_IMAGE")
        assertTrue(gate > 0)
        assertTrue(refuse > gate)
        assertTrue(attach > refuse)
        assertTrue(model.contains("GmtArchive.isProjectZip"))
    }

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }
}
