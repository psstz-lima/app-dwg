package com.appdwg.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.appdwg.core.Display
import com.appdwg.core.tr
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phone widths must not clip a control. 320 dp at font scale 1.3 is the tight case Paulo hit
 * when the Redmi's display size is turned up; the other widths and scales use the same check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h2400dp")
class PhoneLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun portugueseAtFontScaleOnePointThree() = check(Locale.forLanguageTag("pt-BR"), 1.3f)

    @Test
    fun englishAtFontScaleOnePointThree() = check(Locale.US, 1.3f)

    @Test
    fun widerPhonesAndLargerText() {
        val saved = Display.locale
        var width by mutableIntStateOf(320)
        var scale by mutableFloatStateOf(1f)
        var tick by mutableIntStateOf(0)
        try {
            rule.setContent {
                key(tick) {
                    GromaticTheme(ThemeChoice.FIELD_DARK) {
                        CompositionLocalProvider(LocalDensity provides Density(density = 2.75f, fontScale = scale)) {
                            PhoneChrome(width)
                        }
                    }
                }
            }
            for (locale in listOf(Locale.forLanguageTag("pt-BR"), Locale.US)) {
                for (w in listOf(320, 360, 393, 411)) {
                    for (s in listOf(1.0f, 1.3f, 2.0f)) {
                        Display.locale = locale
                        width = w
                        scale = s
                        tick++
                        rule.waitForIdle()
                        rule.onNodeWithTag("phone").assertIsDisplayed()
                        assertNoOverflow()
                        assertTouchTargets()
                    }
                }
            }
        } finally {
            Display.locale = saved
        }
    }

    private fun check(locale: Locale, scale: Float) {
        val saved = Display.locale
        try {
            Display.locale = locale
            show(320, scale)
            assertNoOverflow()
            assertTouchTargets()
        } finally {
            Display.locale = saved
        }
    }

    private fun show(widthDp: Int, fontScale: Float) {
        rule.setContent {
            GromaticTheme(ThemeChoice.FIELD_DARK) {
                CompositionLocalProvider(LocalDensity provides Density(density = 2.75f, fontScale = fontScale)) {
                    PhoneChrome(widthDp)
                }
            }
        }
        rule.onNodeWithTag("phone").assertIsDisplayed()
    }

    private fun assertNoOverflow() {
        val root = rule.onNodeWithTag("phone").fetchSemanticsNode()
        val box = root.boundsInRoot
        fun scrolls(node: SemanticsNode, horizontal: Boolean): Boolean {
            var p = node.parent
            while (p != null) {
                val key = if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange
                if (p.config.getOrNull(key) != null) return true
                p = p.parent
            }
            return false
        }
        fun walk(node: SemanticsNode) {
            val b = node.boundsInRoot
            // Touch targets may grow a few dp into a bar's own padding. Past the screen is the failure.
            val slop = 2f
            if (!scrolls(node, horizontal = true)) {
                check(b.left >= box.left - slop) { "left overflow ${b.left} < ${box.left} ${node.config}" }
                check(b.right <= box.right + slop) { "right overflow ${b.right} > ${box.right} ${node.config}" }
            }
            if (!scrolls(node, horizontal = false)) {
                check(b.top >= box.top - slop) { "top overflow ${b.top} < ${box.top} ${node.config}" }
                check(b.bottom <= box.bottom + slop) { "bottom overflow ${b.bottom} > ${box.bottom} ${node.config}" }
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
                val b = node.boundsInRoot
                // A merged duplicate can report an empty box; the placed control is the one with a size.
                if (b.width > 0f || b.height > 0f) {
                    seen++
                    check(b.height + 1f >= minPx) { "touch target ${b.width}x${b.height}px < 48dp ($minPx) ${node.config}" }
                }
            }
            node.children.forEach { walk(it) }
        }
        walk(rule.onNodeWithTag("phone").fetchSemanticsNode())
        check(seen >= 8) { "expected placed buttons, found $seen" }
    }
}

/** The bars and button rows that overflowed a phone, in one column of a given width. */
@Composable
private fun PhoneChrome(widthDp: Int) {
    val g = LocalG.current
    Column(Modifier.width(widthDp.dp).wrapContentHeight(unbounded = true).testTag("phone")) {
        GTopBar("ortofoto-loteamento-centro-2026.gmt", compact = true) {
            BarIcon(GIcon.SAVE, tr("Salvar"), g.onTopBar) {}
            BarIcon(GIcon.SETTINGS, tr("Configurações"), g.onTopBar) {}
            BarIcon(GIcon.MORE, tr("Mais opções"), g.onTopBar) {}
            BarIcon(GIcon.CLOSE, tr("Fechar projeto"), g.onTopBar) {}
        }
        FitRow(Modifier.fillMaxWidth().padding(8.dp)) {
            ViewSwitch(
                listOf(tr("Planta") to true, "3D" to false, tr("Seção") to false, tr("Perfil") to false),
                onPick = {},
            )
            GButton("Z ×2", {})
            GButton(tr("máxima"), {}, icon = GIcon.IMAGE, description = tr("Imagem: {0}", tr("máxima")))
        }
        FitRow(Modifier.fillMaxWidth().padding(8.dp)) {
            GButton(tr("Abrir arquivo"), {}, primary = true)
            GButton(tr("☁ Abrir do Google Drive"), {}, outlined = true)
        }
        FitRow(Modifier.fillMaxWidth().padding(8.dp)) {
            GButton(tr("Restaurar compra"), {}, outlined = true)
            GButton(tr("Gerenciar assinatura"), {}, outlined = true)
        }
        FitRow(Modifier.fillMaxWidth().padding(8.dp)) {
            GButton(tr("Minha localização (GPS)"), {}, outlined = true, icon = GIcon.GPS)
            GButton(tr("Digitar coordenadas…"), {}, outlined = true)
        }
        FitRow(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(tr("Versão grátis: abrir e ver a planta."), Modifier.weight(1f))
            OutlinedButton(onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(tr("Conhecer o Pro"), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        FitRow(Modifier.fillMaxWidth().padding(8.dp)) {
            GButton(tr("Calibrar"), {})
            GButton(tr("Limpar"), {})
            GButton(tr("Fechar"), {})
        }
        Spacer(Modifier.padding(4.dp))
        ToolBar(
            listOf(
                RailTool(GIcon.MEASURE, tr("Medir")) {},
                RailTool(GIcon.POINTS, tr("Pontos")) {},
                RailTool(GIcon.LINES, tr("Linhas")) {},
                RailTool(GIcon.LAYERS, tr("Camadas")) {},
                RailTool(GIcon.ROAD, tr("Estrada")) {},
                RailTool(GIcon.PLATFORM, tr("Plataforma")) {},
                RailTool(GIcon.GPS, "GPS") {},
                RailTool(GIcon.EXPORT, tr("Exportar")) {},
            ),
        )
        StatusFooter(
            items = listOf(FooterItem("E 123.456,78   N 7.654.321,00   Z 123,456") {}),
            right = listOf(FooterItem("1:1.000") {}, FooterItem(tr("Camadas")) {}),
        )
    }
}
