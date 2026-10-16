package com.appdwg.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.appdwg.core.Display
import com.appdwg.core.drainage.CatalogLoader
import com.appdwg.core.drainage.DeviceCategory
import com.appdwg.core.drainage.DeviceTakeoff
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Placement and the selection card have to stay inside a 320 dp phone at font scale 1.3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h2400dp")
class DrainagePlaceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun placeCardInPortuguese() = placeCard(Locale.forLanguageTag("pt-BR"), "Lançar", "Desenhar", "Eixo", "Terreno (topo)")

    @Test
    fun placeCardInEnglish() = placeCard(Locale.US, "Place", "Draw", "Existing line", "Terrain (top)")

    private fun placeCard(locale: Locale, launch: String, draw: String, path: String, grade: String) {
        val device = CatalogLoader.bundled().devices.first { it.placeable && it.category != DeviceCategory.POINT }
        val saved = Display.locale
        try {
            show(locale, 1.3f) {
                var draft by remember { mutableStateOf(draftFor(device)) }
                DrainagePlaceCard(
                    draft, listOf("Eixo 1"), listOf("Greide"), device.parameters,
                    onChange = { draft = it }, onUndoPoint = {}, onCommit = {}, onCancel = {},
                    Modifier.fillMaxSize(),
                )
            }
            rule.onNodeWithText("$launch ${device.code}").assertIsDisplayed()
            rule.onNodeWithText(draw).performClick()
            rule.onNodeWithText(path).performClick()
            rule.onNodeWithText(grade).performClick()
            rule.waitForIdle()
            assertNoOverflow()
            assertTouchTargets()
        } finally {
            Display.locale = saved
        }
    }

    @Test
    fun selectionCardInPortuguese() {
        val saved = Display.locale
        try {
            showSelection(Locale.forLanguageTag("pt-BR"), pro = true)
            rule.onNodeWithText("Geometria simplificada").assertIsDisplayed()
            rule.onNodeWithText("Este dispositivo").assertIsDisplayed()
            rule.onNodeWithText("Total do projeto").assertIsDisplayed()
            assertNoOverflow()
            assertTouchTargets()
        } finally {
            Display.locale = saved
        }
    }

    @Test
    fun selectionCardInEnglish() {
        val saved = Display.locale
        try {
            showSelection(Locale.US, pro = false)
            rule.onNodeWithText("Simplified geometry").assertIsDisplayed()
            rule.onNodeWithText("Discover Gromatic Pro…").assertIsDisplayed()
            assertNoOverflow()
            assertTouchTargets(2)
        } finally {
            Display.locale = saved
        }
    }

    private fun showSelection(locale: Locale, pro: Boolean) {
        val row = DeviceTakeoff("a", "VPCG 160-30", 100.0, 39.0, 12.0, 40.0, true)
        show(locale, 1.3f) {
            DrainageSelectionCard(
                "VPCG 160-30", "Valeta de proteção de corte em concreto, gramada", true,
                row, row, moving = pro, canUndo = pro, isPro = pro,
                onEdit = {}, onMove = {}, onDelete = {}, onUndo = {}, onPro = {}, onClose = {},
                Modifier.fillMaxSize(),
            )
        }
    }

    private fun show(locale: Locale, scale: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        Display.locale = locale
        rule.setContent {
            GromaticTheme(ThemeChoice.FIELD_DARK) {
                CompositionLocalProvider(LocalDensity provides Density(density = 2.75f, fontScale = scale)) {
                    Column(Modifier.width(320.dp).height(720.dp).testTag("place")) {
                        content()
                    }
                }
            }
        }
        rule.onNodeWithTag("place").assertIsDisplayed()
    }

    private fun assertNoOverflow() {
        val root = rule.onNodeWithTag("place").fetchSemanticsNode()
        val box = root.boundsInRoot
        fun scrolls(node: SemanticsNode, horizontal: Boolean): Boolean {
            val key = if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange
            var parent = node.parent
            while (parent != null) {
                if (parent.config.getOrNull(key) != null) return true
                parent = parent.parent
            }
            return false
        }
        fun walk(node: SemanticsNode) {
            val bounds = node.boundsInRoot
            val slop = 2f
            if (!scrolls(node, horizontal = true)) {
                check(bounds.left >= box.left - slop) { "left overflow ${bounds.left} < ${box.left} ${node.config}" }
                check(bounds.right <= box.right + slop) { "right overflow ${bounds.right} > ${box.right} ${node.config}" }
                check(node.size.width <= box.width + slop) { "width ${node.size.width}px > ${box.width} ${node.config}" }
            }
            if (!scrolls(node, horizontal = false)) {
                check(bounds.top >= box.top - slop) { "top overflow ${bounds.top} < ${box.top} ${node.config}" }
                check(bounds.bottom <= box.bottom + slop) { "bottom overflow ${bounds.bottom} > ${box.bottom} ${node.config}" }
            }
            node.children.forEach { walk(it) }
        }
        walk(root)
    }

    private fun assertTouchTargets(minSeen: Int = 4) {
        val minPx = 48f * 2.75f
        var seen = 0
        fun walk(node: SemanticsNode) {
            val role = node.config.getOrNull(SemanticsProperties.Role)
            val clickable = node.config.contains(SemanticsActions.OnClick) || role == Role.Button || role == Role.Tab
            if (clickable) {
                val height = node.size.height.toFloat()
                val width = node.size.width.toFloat()
                if (width > 0f || height > 0f) {
                    seen++
                    check(height + 1f >= minPx) { "touch target ${width}x${height}px < 48dp ${node.config}" }
                }
            }
            node.children.forEach { walk(it) }
        }
        walk(rule.onNodeWithTag("place").fetchSemanticsNode())
        check(seen >= minSeen) { "expected placed buttons, found $seen" }
    }
}
