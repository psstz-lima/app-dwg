package com.appdwg.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.appdwg.core.Display
import com.appdwg.core.drainage.CatalogLoader
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The device library has to stay inside a 320 dp phone at font scale 1.3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h2400dp")
class DrainageLibraryTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun portugueseAtFontScaleOnePointThree() = check(Locale.forLanguageTag("pt-BR"), 1.3f, "Em revisão", "Lançar")

    @Test
    fun englishAtFontScaleOnePointThree() = check(Locale.US, 1.3f, "Under review", "Place")

    private fun check(locale: Locale, scale: Float, badge: String, place: String) {
        val saved = Display.locale
        try {
            Display.locale = locale
            rule.setContent {
                GromaticTheme(ThemeChoice.FIELD_DARK) {
                    CompositionLocalProvider(LocalDensity provides Density(density = 2.75f, fontScale = scale)) {
                        Column(Modifier.width(320.dp).height(720.dp).testTag("drainage")) {
                            DrainageLibraryBody(
                                isPro = false,
                                onPro = {},
                                modifier = Modifier.fillMaxSize(),
                                catalog = CatalogLoader.bundled(),
                                catalogReady = true,
                                catalogNote = null,
                            )
                        }
                    }
                }
            }
            rule.onNodeWithTag("drainage").assertIsDisplayed()
            assertNoOverflow()
            assertTouchTargets()
            rule.onNodeWithTag("drainage-search").performTextInput("STC 88-20")
            rule.waitForIdle()
            rule.onNodeWithTag("device-STC 88-20").performClick()
            rule.waitForIdle()
            rule.onNodeWithText(badge).assertIsDisplayed()
            rule.onNodeWithText(place).assertIsNotEnabled()
            assertNoOverflow()
            assertTouchTargets()
        } finally {
            Display.locale = saved
        }
    }

    private fun assertNoOverflow() {
        val root = rule.onNodeWithTag("drainage").fetchSemanticsNode()
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
                // boundsInRoot is clipped to the viewport, so a too-wide row can look in-bounds.
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

    private fun assertTouchTargets() {
        val minPx = 48f * 2.75f
        var seen = 0
        fun walk(node: SemanticsNode) {
            val role = node.config.getOrNull(SemanticsProperties.Role)
            val clickable = node.config.contains(SemanticsActions.OnClick) || role == Role.Button || role == Role.Tab
            if (clickable) {
                // boundsInRoot is the visible sliver. A row that starts at the bottom of the
                // 720 dp viewport is only a few pixels tall there, while its layout is full size.
                val height = node.size.height.toFloat()
                val width = node.size.width.toFloat()
                if (width > 0f || height > 0f) {
                    seen++
                    check(height + 1f >= minPx) { "touch target ${width}x${height}px < 48dp ${node.config}" }
                }
            }
            node.children.forEach { walk(it) }
        }
        walk(rule.onNodeWithTag("drainage").fetchSemanticsNode())
        check(seen >= 4) { "expected placed buttons, found $seen" }
    }
}
