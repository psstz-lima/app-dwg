package com.appdwg.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons (24 × 24, stroked) for the tool rail; the stroke width follows the theme. */
enum class GIcon(val path: String) {
    PLAN("M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z M9 4v14 M15 6v14"),
    CUBE("M12 3l8 4.5v9L12 21l-8-4.5v-9z M12 12l8-4.5 M12 12v9 M12 12L4 7.5"),
    SECTION("M2 18h20 M5 18l4-7h6l4 7"),
    PROFILE("M2 16c3 0 4-8 7-8s4 6 7 6 3-4 6-4 M2 20h20"),
    POINTS("M4 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M15 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M8 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
    LINES("M3 18l5-9 6 5 7-10"),
    ROAD("M8 3L5 21 M16 3l3 18 M12 4v3 M12 10v4 M12 17v3"),
    GPS("M5 12a7 7 0 1 0 14 0a7 7 0 1 0 -14 0 M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M12 2v3 M12 19v3 M2 12h3 M19 12h3"),
    MEASURE("M3 17L17 3l4 4L7 21z M7 13l2 2 M10 10l2 2 M13 7l2 2"),
    LAYERS("M12 3l9 5-9 5-9-5z M3 13l9 5 9-5 M3 17.5l9 5 9-5"),
    CONTOURS("M3 8c4-3 8 2 12-1s5 0 6 1 M3 13c4-3 8 2 12-1s5 0 6 1 M3 18c4-3 8 2 12-1s5 0 6 1"),
    COMPARE("M4 18l5-9 4 5 3-3 4 7z M4 6h7 M15 6h5 M17.5 3.5v5"),
    PLATFORM("M3 17l4-6h10l4 6z M7 11l2-4h6l2 4"),
    MODELS("M4 20V9l8-5 8 5v11 M4 9l8 5 8-5 M12 14v6"),
    NOTE("M12 21s-7-6.5-7-12a7 7 0 0 1 14 0c0 5.5-7 12-7 12z M9.5 9a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0"),
    CALC("M5 3h14v18H5z M8 7h8 M8 12h2 M12 12h2 M16 12v6 M8 16h2 M12 16h2"),
    MAP("M3 7l6-3 6 3 6-3v13l-6 3-6-3-6 3z M9 4v13 M15 7v13 M5 11l3-1 M17 14l2-1"),
    GOTO("M12 3v4 M12 17v4 M3 12h4 M17 12h4 M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0"),
    EXPORT("M12 3v12 M7 8l5-5 5 5 M4 15v5h16v-5"),
    IMAGE("M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6 M15 9a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0"),
    REFS("M8 4h9l3 3v13H8z M4 8v12h10 M11 10h6 M11 14h6"),
    WARNING("M12 3l10 18H2z M12 10v5 M12 18v0.5"),
    SETTINGS("M12 15a3 3 0 1 0 0-6a3 3 0 0 0 0 6z M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33a1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"),
    ASBUILT("M9 12l2 2 4-4 M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0"),
    CALIBRATE("M12 2v4 M12 18v4 M2 12h4 M18 12h4 M12 8l4 4-4 4-4-4z"),
    VOLUME("M4 18l8-12 8 12z M8 12h8"),
    BULLETIN("M6 3h9l3 3v15H6z M9 9h6 M9 13h6 M9 17h4"),
    SLOPE("M3 20h18 M3 20L19 6 M14 20a6 6 0 0 0-1.5-4"),
    IDEA("M9 18h6 M10 21h4 M12 3a6 6 0 0 0-4 10.5c.8.8 1 1.5 1 2.5h6c0-1 .2-1.7 1-2.5A6 6 0 0 0 12 3z"),
    BASIN("M12 3c-4 5-6 8-6 11a6 6 0 0 0 12 0c0-3-2-6-6-11z M9 15a3 3 0 0 0 3 3"),
    EDIT_SURFACE("M3 21h18 M14 4l5 5-9 9H5v-5z"),
    FIT("M3 8V3h5 M16 3h5v5 M21 16v5h-5 M8 21H3v-5 M9 9h6v6H9z"),
    FULLSCREEN("M14 4h6v6 M20 4l-6 6 M10 20H4v-6 M4 20l6-6"),
    EXIT_FULLSCREEN("M20 10h-6V4 M14 10l6-6 M4 14h6v6 M10 14l-6 6"),
    PLUS("M12 5v14 M5 12h14"),
    MINUS("M5 12h14"),
    DOWNLOAD("M12 3v12 M7 10l5 5 5-5 M5 20h14"),
    LOCK("M6 11h12v10H6z M8.5 11V7.5a3.5 3.5 0 0 1 7 0V11"),
    ;

    fun vector(stroke: Float, color: Color = Color.Black): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(path), fill = null, stroke = SolidColor(color), strokeLineWidth = stroke,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
}
