package com.appdwg.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Alto contraste shading, layer-chip checked state, and the IFC MIME. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TabletUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun sunContrastIsOnlyBlackOnWhite() {
        assertTrue(sunContrast(Color.White.toArgb(), Color.Black.toArgb()))
        assertFalse(sunContrast(Color(0xFFFBFCFD).toArgb(), Color(0xFF15212B).toArgb()))
        assertFalse(sunContrast(Color(0xFF11181C).toArgb(), Color.White.toArgb()))
    }

    @Test
    fun ifcFilesKeepAnIfcName() {
        assertEquals("application/octet-stream", DownloadNames.mime("rede.ifc"))
        assertEquals("application/octet-stream", DownloadNames.mime("REDE.IFC"))
        assertEquals("PRIM - KM-drenagem.ifc", DownloadNames.ifcName("PRIM - KM.pdf"))
        assertEquals("obra-drenagem.ifc", DownloadNames.ifcName("obra-drenagem.ifc"))
        assertFalse(DownloadNames.ifcName("relatorio.pdf").contains("pdf"))
    }

    @Test
    fun selectedDeviceBarOffersEditAndDelete() {
        rule.setContent {
            GromaticTheme(ThemeChoice.SUN) {
                DrainagePickBar(
                    title = "CCT",
                    detail = "caixa",
                    canUndo = false,
                    onUndo = {},
                    onFinish = {},
                    onCancel = {},
                    onMore = {},
                    finishLabel = "Fechar",
                    onEdit = {},
                    onDelete = {},
                )
            }
        }
        rule.onNodeWithText("Fechar").assertIsDisplayed()
        rule.onNodeWithText("Editar").assertIsDisplayed()
        rule.onNodeWithText("Excluir").assertIsDisplayed()
        rule.onNodeWithText("Mais").assertIsDisplayed()
        rule.onAllNodesWithText("Cancelar").assertCountEquals(0)
    }

    @Test
    fun launchBarKeepsUndoAndMore() {
        rule.setContent {
            GromaticTheme(ThemeChoice.SUN) {
                DrainagePickBar("Lançar STC", "Pontos do caminho: 1", canUndo = true, onUndo = {}, onFinish = {}, onCancel = {}, onMore = {})
            }
        }
        rule.onNodeWithText("Desfazer").assertIsDisplayed()
        rule.onNodeWithText("Concluir").assertIsDisplayed()
        rule.onNodeWithText("Cancelar").assertIsDisplayed()
        rule.onNodeWithText("Mais").assertIsDisplayed()
    }

    @Test
    fun layerChipExposesACheckedState() {
        rule.setContent {
            GromaticTheme(ThemeChoice.SUN) {
                LayerChip("Escavação", true) {}
            }
        }
        val node = rule.onNodeWithText("Escavação").fetchSemanticsNode()
        assertEquals(ToggleableState.On, node.config.getOrNull(SemanticsProperties.ToggleableState))
    }
}
