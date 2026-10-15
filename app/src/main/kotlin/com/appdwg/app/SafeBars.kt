package com.appdwg.app

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Keeps a dialog inside the status bar, navigation bar, display cutout and keyboard.
 *
 * Android 15 (API 35) ignores `setDecorFitsSystemWindows`, so a dialog window is laid out
 * edge-to-edge and its content can slide under the bars on phones and tablets. Older
 * releases still inset the window, so this modifier adds nothing there and does not
 * double-pad. [WindowInsets.safeDrawing] updates when the bars or the keyboard move.
 */
@Composable
fun Modifier.safeBars(): Modifier =
    if (Build.VERSION.SDK_INT < 35) this else windowInsetsPadding(WindowInsets.safeDrawing)
