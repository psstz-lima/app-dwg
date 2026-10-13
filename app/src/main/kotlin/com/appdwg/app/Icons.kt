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
    SETTINGS("M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6 M12 2v3 M12 19v3 M4.2 4.2l2.1 2.1 M17.7 17.7l2.1 2.1 M2 12h3 M19 12h3 M4.2 19.8l2.1-2.1 M17.7 6.3l2.1-2.1"),
    ASBUILT("M9 12l2 2 4-4 M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0"),
    FIT("M3 8V3h5 M16 3h5v5 M21 16v5h-5 M8 21H3v-5 M9 9h6v6H9z"),
    FULLSCREEN("M14 4h6v6 M20 4l-6 6 M10 20H4v-6 M4 20l6-6"),
    EXIT_FULLSCREEN("M20 10h-6V4 M14 10l6-6 M4 14h6v6 M10 14l-6 6"),
    PLUS("M12 5v14 M5 12h14"),
    MINUS("M5 12h14"),
    ;

    fun vector(stroke: Float, color: Color = Color.Black): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(path), fill = null, stroke = SolidColor(color), strokeLineWidth = stroke,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
}
