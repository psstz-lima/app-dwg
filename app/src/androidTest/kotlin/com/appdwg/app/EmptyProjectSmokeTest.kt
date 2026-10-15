package com.appdwg.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.appdwg.core.tr
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The empty project screen comes up and offers to open a file. */
@RunWith(AndroidJUnit4::class)
class EmptyProjectSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun emptyProjectShowsOpenAction() {
        // tr() follows the same display language the activity just applied.
        rule.onNodeWithText(tr("Abrir")).assertIsDisplayed()
    }
}
