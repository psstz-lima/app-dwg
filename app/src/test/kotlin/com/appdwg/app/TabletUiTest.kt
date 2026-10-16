package com.appdwg.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
    fun ifcFilesUseTheStepMime() {
        assertEquals("application/x-step", DownloadNames.mime("rede.ifc"))
        assertEquals("application/x-step", DownloadNames.mime("REDE.IFC"))
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
