package com.appdwg.app

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Draws behind the system bars on every API this app supports.
 *
 * Android 15 deprecates [Window.setStatusBarColor], [Window.setNavigationBarColor],
 * [Window.setDecorFitsSystemWindows] and the cutout modes `SHORT_EDGES` / `DEFAULT`.
 * None of those are called. From API 35 the platform already lays a targetSdk 35+
 * window out edge-to-edge, so only the supported cutout mode and the icon appearance
 * are set. Older releases still need the layout flags to extend the content behind
 * the bars; insets then keep every control clear of them.
 *
 * [lightStatus] / [lightNavigation] are true when the icons should be dark.
 */
fun Window.enableGromaticEdgeToEdge(lightStatus: Boolean, lightNavigation: Boolean) {
    // ALWAYS is supported since API 30. The field itself exists only from API 28,
    // so it is not touched on older devices.
    if (Build.VERSION.SDK_INT >= 30) {
        val attrs = attributes
        if (attrs.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            attributes = attrs
        }
    }
    if (Build.VERSION.SDK_INT < 35) {
        @Suppress("DEPRECATION")
        decorView.systemUiVisibility = decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    WindowInsetsControllerCompat(this, decorView).apply {
        isAppearanceLightStatusBars = lightStatus
        isAppearanceLightNavigationBars = lightNavigation
    }
}
