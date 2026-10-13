package com.appdwg.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's mark: the launcher icon's drawing (contours, survey triangle, road axis), the same
 * paths as ic_launcher_foreground, cropped to 24,26–84,86 and coloured by the theme.
 */
@Composable
fun AppMark(modifier: Modifier = Modifier.size(34.dp)) {
    val g = LocalG.current
    val v = remember(g.markContour, g.markTriangle, g.markAxis) {
        androidx.compose.ui.graphics.vector.ImageVector.Builder("mark", 60.dp, 60.dp, 60f, 60f)
            .addGroup(translationX = -24f, translationY = -26f)
            .apply {
                fun p(d: String, c: Color, w: Float, join: Boolean = false) = addPath(
                    androidx.compose.ui.graphics.vector.addPathNodes(d), fill = null, stroke = androidx.compose.ui.graphics.SolidColor(c),
                    strokeLineWidth = w, strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = if (join) androidx.compose.ui.graphics.StrokeJoin.Round else androidx.compose.ui.graphics.StrokeJoin.Miter,
                )
                p("M30,44 C40,36 52,40 60,36 C68,32 74,36 78,40", g.markContour, 2.2f)
                p("M30,54 C42,47 54,52 62,47 C70,43 75,47 78,50", g.markContour, 2.2f)
                p("M30,64 C42,58 56,63 64,58 C71,54 75,58 78,60", g.markContour, 2.2f)
                p("M36,74 L54,32 L72,74 Z", g.markTriangle, 2f, join = true)
                p("M28,80 C44,70 58,72 80,58", g.markAxis, 4f)
            }
            .clearGroup().build()
    }
    if (g.choice == ThemeChoice.EARTH) {
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF26291C)), contentAlignment = Alignment.Center) {
            Icon(v, null, tint = Color.Unspecified, modifier = Modifier.fillMaxSize().padding(3.dp))
        }
    } else Icon(v, null, tint = Color.Unspecified, modifier = modifier)
}

/** Button in the theme's style: filled with the accent, or outlined/tonal. */
/** [outlined] keeps a border in every theme (Terra draws its buttons without one otherwise). */
@Composable
fun GButton(text: String, onClick: () -> Unit, primary: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false) {
    val g = LocalG.current
    val shape = RoundedCornerShape(g.buttonRadius)
    val bg = when {
        primary -> g.accent
        g.choice == ThemeChoice.EARTH -> g.chip
        else -> Color.Transparent
    }
    val fg = when {
        primary -> g.onAccent
        g.choice == ThemeChoice.SUN && g.topBar == Color.Black -> g.text
        else -> g.text
    }
    val border = when {
        primary && g.choice == ThemeChoice.SUN -> BorderStroke(2.dp, g.accent)
        outlined && !primary -> BorderStroke(maxOf(g.borderWidth, 1.dp), if (g.choice == ThemeChoice.EARTH) g.muted else g.border)
        primary || g.choice == ThemeChoice.EARTH -> null
        else -> BorderStroke(maxOf(g.borderWidth, 1.dp), g.border)
    }
    Box(
        modifier.height(maxOf(g.target - 4.dp, 40.dp)).clip(shape).background(bg.copy(alpha = if (enabled) bg.alpha else bg.alpha * 0.4f))
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg.copy(alpha = if (enabled) 1f else 0.4f), fontWeight = if (primary || g.choice == ThemeChoice.SUN) FontWeight.Bold else FontWeight.Medium,
            fontSize = 15.sp, maxLines = 1)
    }
}

/** A recent file as a full-width button: its type on the left, the name, and an arrow to open. */
@Composable
fun RecentButton(name: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val g = LocalG.current
    val shape = RoundedCornerShape(g.buttonRadius)
    val type = when {
        name.endsWith(".gromatic", true) || name.endsWith(".appdwg", true) -> "PROJETO"
        name.endsWith(".dxf", true) -> "DXF"
        name.contains(".aecc.pnt", true) -> "PONTOS"
        name.endsWith(".xml", true) -> "LANDXML"
        else -> name.substringAfterLast('.', "").uppercase().take(7)
    }
    Row(
        modifier.height(maxOf(g.target, 48.dp)).clip(shape).background(g.card)
            .border(BorderStroke(maxOf(g.borderWidth, 1.dp), g.border), shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(g.accent).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(type, color = g.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(name, color = g.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("›", color = g.muted, fontSize = 22.sp)
    }
}

/** Top bar: the mark, the app's name, the open file and the file actions. */
@Composable
fun GTopBar(fileName: String?, compact: Boolean = false, actions: @Composable RowScope.() -> Unit) {
    val g = LocalG.current
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(g.topBar)
            .then(if (!g.floating && g.choice != ThemeChoice.SUN) Modifier.drawBottomBorder(g.border, g.borderWidth) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppMark(Modifier.size(if (g.choice == ThemeChoice.EARTH) 42.dp else 38.dp))
        Text("Gromatic", color = g.onTopBar, fontSize = 22.sp, fontWeight = if (g.choice == ThemeChoice.EARTH) FontWeight.ExtraBold else FontWeight.Bold)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { if (fileName != null && !compact) {
            if (g.choice == ThemeChoice.EARTH) {
                Box(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(g.chip).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(fileName, color = g.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else Text(fileName, color = if (g.choice == ThemeChoice.SUN) Color(0xFFE0E0E0) else g.muted, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } }
        // On the black bar of the high-contrast theme the outlined buttons are white on black.
        if (g.choice == ThemeChoice.SUN) {
            val onBar = g.copy(text = Color.White, border = Color.White)
            androidx.compose.runtime.CompositionLocalProvider(LocalG provides onBar) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

private fun Modifier.drawBottomBorder(c: Color, w: Dp) = this.then(Modifier.drawBehindLine(c, w, top = false))
private fun Modifier.drawTopBorder(c: Color, w: Dp) = this.then(Modifier.drawBehindLine(c, w, top = true))
private fun Modifier.drawBehindLine(c: Color, w: Dp, top: Boolean) = Modifier.drawBehind {
    val y = if (top) 0f else size.height - w.toPx() / 2
    drawLine(c, Offset(0f, y), Offset(size.width, y), w.toPx())
}

/** One tool of the rail: icon over its name; [active] while the tool is on. */
data class RailTool(val icon: GIcon, val label: String, val active: Boolean = false, val onClick: () -> Unit)

/** Tool bar along the bottom on phones: the rail's buttons in a scrolling row. */
@Composable
fun ToolBar(tools: List<RailTool>, modifier: Modifier = Modifier) {
    val g = LocalG.current
    val scroll = rememberScrollState()
    Row(
        modifier.fillMaxWidth().background(g.rail)
            .then(if (!g.floating) Modifier.drawTopBorder(g.border, g.borderWidth) else Modifier)
            .drawWithContent {
                drawContent()
                val w = 36.dp.toPx()
                if (scroll.canScrollForward) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, g.rail), size.width - w, size.width),
                    Offset(size.width - w, 0f), Size(w, size.height))
                if (scroll.canScrollBackward) drawRect(Brush.horizontalGradient(listOf(g.rail, Color.Transparent), 0f, w), Offset.Zero, Size(w, size.height))
            }
            .horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (t in tools) RailButton(t)
    }
}

/** Vertical tool rail with icon and label, scrolling when the tools do not fit. */
@Composable
fun ToolRail(tools: List<RailTool>, modifier: Modifier = Modifier) {
    val g = LocalG.current
    val shape = if (g.floating) RoundedCornerShape(20.dp) else RoundedCornerShape(0.dp)
    val scroll = rememberScrollState()
    Column(
        modifier.width(if (g.choice == ThemeChoice.SUN) 92.dp else 84.dp).then(if (g.floating) Modifier.padding(start = 12.dp, bottom = 12.dp) else Modifier.fillMaxHeight())
            .clip(shape).background(g.rail)
            .then(if (!g.floating) Modifier.drawRightBorder(g.border, g.borderWidth) else Modifier)
            .drawWithContent {
                drawContent()
                // Fade the cut edge so it reads as "more tools this way".
                val h = 36.dp.toPx()
                if (scroll.canScrollForward) drawRect(Brush.verticalGradient(listOf(Color.Transparent, g.rail), size.height - h, size.height),
                    Offset(0f, size.height - h), Size(size.width, h))
                if (scroll.canScrollBackward) drawRect(Brush.verticalGradient(listOf(g.rail, Color.Transparent), 0f, h), Offset.Zero, Size(size.width, h))
            }
            .verticalScroll(scroll).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (t in tools) RailButton(t)
    }
}

private fun Modifier.drawRightBorder(c: Color, w: Dp) = this.then(Modifier.drawBehind {
    val x = size.width - w.toPx() / 2
    drawLine(c, Offset(x, 0f), Offset(x, size.height), w.toPx())
})

@Composable
private fun RailButton(t: RailTool) {
    val g = LocalG.current
    val shape = RoundedCornerShape(if (g.floating) 14.dp else if (g.choice == ThemeChoice.SUN) 6.dp else 10.dp)
    val icon = remember(t.icon, g.iconStroke) { t.icon.vector(g.iconStroke) }
    val fg = if (t.active) g.onRailActive else g.onRail
    Column(
        Modifier.width(if (g.choice == ThemeChoice.SUN) 80.dp else 70.dp).height(if (g.choice == ThemeChoice.SUN) 62.dp else 58.dp).clip(shape)
            .background(if (t.active) g.railActive else Color.Transparent)
            .then(if (t.active && g.choice == ThemeChoice.SUN) Modifier.border(2.dp, Color.Black, shape) else Modifier)
            .clickable(role = Role.Button, onClick = t.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (g.choice == ThemeChoice.SUN) 24.dp else 22.dp))
        Spacer(Modifier.height(3.dp))
        Text(t.label, color = fg, fontSize = if (g.choice == ThemeChoice.EARTH) 11.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (t.active || g.choice == ThemeChoice.SUN) FontWeight.Bold else FontWeight.Medium)
    }
}

/** Segmented switch over the plan: Planta / 3D / Seção / Perfil. */
@Composable
fun ViewSwitch(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val g = LocalG.current
    val earth = g.choice == ThemeChoice.EARTH
    val outer = RoundedCornerShape(if (earth) 24.dp else if (g.choice == ThemeChoice.SUN) 8.dp else 10.dp)
    Row(
        modifier.clip(outer).background(if (earth) g.rail else g.panel)
            .then(if (!earth) Modifier.border(maxOf(g.borderWidth, 1.dp), g.border, outer) else Modifier).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, (label, on) ->
            val inner = RoundedCornerShape(if (earth) 19.dp else if (g.choice == ThemeChoice.SUN) 5.dp else 7.dp)
            val bg = when {
                !on -> Color.Transparent
                g.underlineTabs -> Color.Transparent
                earth -> g.railActive
                g.choice == ThemeChoice.SUN -> Color.Black
                else -> g.accent
            }
            val fg = when {
                on && g.underlineTabs -> g.accent
                on && earth -> g.rail
                on && g.choice == ThemeChoice.SUN -> g.accent
                on -> g.onAccent
                earth -> Color(0xFFE9E5D6)
                else -> g.text
            }
            Box(
                Modifier.height(if (g.choice == ThemeChoice.SUN) 44.dp else 38.dp).clip(inner).background(bg)
                    .then(if (on && g.underlineTabs) Modifier.drawBottomBorder(g.accent, 2.dp) else Modifier)
                    .selectable(on, role = Role.Tab) { onPick(i) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, fontSize = 14.sp, fontWeight = if (on || g.choice == ThemeChoice.SUN) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

/** Zoom in, out and fit, at the plan's lower right. */
@Composable
fun ZoomButtons(onIn: () -> Unit, onOut: () -> Unit, onFit: () -> Unit, fullScreen: Boolean, onFullScreen: () -> Unit, modifier: Modifier = Modifier, plusMinus: Boolean = true) {
    val g = LocalG.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((icon, desc, act) in listOf(
            Triple(if (fullScreen) GIcon.EXIT_FULLSCREEN else GIcon.FULLSCREEN, if (fullScreen) "Sair da tela cheia" else "Tela cheia", onFullScreen),
            Triple(GIcon.PLUS, "Aproximar", onIn), Triple(GIcon.MINUS, "Afastar", onOut), Triple(GIcon.FIT, "Ver tudo", onFit),
        ).filter { plusMinus || (it.first != GIcon.PLUS && it.first != GIcon.MINUS) }) {
            val shape = if (g.choice == ThemeChoice.EARTH) CircleShape else RoundedCornerShape(if (g.choice == ThemeChoice.SUN) 8.dp else 10.dp)
            val bg = if (g.choice == ThemeChoice.EARTH) g.rail else g.panel
            val fg = if (g.choice == ThemeChoice.EARTH) g.panel else g.text
            val vec = remember(icon, g.iconStroke) { icon.vector(g.iconStroke + 0.4f) }
            Box(
                Modifier.size(g.target + 4.dp).clip(shape).background(bg)
                    .then(if (g.choice != ThemeChoice.EARTH) Modifier.border(maxOf(g.borderWidth, 1.dp), g.border, shape) else Modifier)
                    .clickable(role = Role.Button, onClickLabel = desc, onClick = act),
                contentAlignment = Alignment.Center,
            ) { Icon(vec, desc, tint = fg, modifier = Modifier.size(22.dp)) }
        }
    }
}

/** North arrow (the plan is never rotated, so north is always up). */
@Composable
fun NorthArrow(modifier: Modifier = Modifier) {
    val g = LocalG.current
    val c = if (g.dark) g.text else g.text
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(28.dp, 32.dp)) {
            val p = Path().apply {
                moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(size.width / 2, size.height * 0.78f); lineTo(0f, size.height); close()
            }
            drawPath(p, c)
        }
        Text("N", color = c, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** Side panel on the right: holds the cards of what is selected or being done. */
@Composable
fun SidePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalG.current
    val shape = if (g.floating) RoundedCornerShape(20.dp) else RoundedCornerShape(0.dp)
    Column(
        modifier.width(340.dp).then(if (g.floating) Modifier.padding(end = 12.dp, bottom = 12.dp) else Modifier).fillMaxHeight()
            .clip(shape).background(g.panel)
            .then(if (!g.floating) Modifier.drawLeftBorder(g.border, g.borderWidth) else Modifier)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

private fun Modifier.drawLeftBorder(c: Color, w: Dp) = this.then(Modifier.drawBehind {
    drawLine(c, Offset(w.toPx() / 2, 0f), Offset(w.toPx() / 2, size.height), w.toPx())
})

/** Bottom line: coordinates of the tapped point (or GPS), station, scale and the GPS fix. */
@Composable
fun StatusFooter(items: List<String>, gpsText: String?, gpsColor: Color) {
    val g = LocalG.current
    Row(
        Modifier.fillMaxWidth().height(if (g.choice == ThemeChoice.SUN) 46.dp else 42.dp).background(g.footer)
            .then(if (!g.floating && g.choice != ThemeChoice.SUN) Modifier.drawTopBorder(g.border, g.borderWidth) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            for (s in items) Text(s, color = g.onFooter, fontFamily = g.mono, fontSize = if (g.choice == ThemeChoice.SUN) 14.sp else 13.sp, maxLines = 1,
                fontWeight = if (g.choice == ThemeChoice.SUN) FontWeight.Medium else FontWeight.Normal)
        }
        run {
            val chip = g.choice == ThemeChoice.EARTH
            Row(
                Modifier.then(if (chip) Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(g.rail).padding(horizontal = 12.dp) else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (gpsText != null) gpsColor else g.muted))
                Text(gpsText ?: "GPS desligado", color = if (chip) g.panel else if (g.choice == ThemeChoice.SUN) Color.White else if (gpsText == null) g.muted else g.text, fontFamily = g.mono, fontSize = 13.sp,
                    fontWeight = if (g.choice == ThemeChoice.SUN) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** Settings › Appearance: the theme, with a small preview of each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceDialog(current: ThemeChoice, onPick: (ThemeChoice) -> Unit, onAbout: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurações › Aparência") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in ThemeChoice.entries) {
                    Row(
                        Modifier.fillMaxWidth().selectable(c == current, role = Role.RadioButton) { onPick(c) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(c == current, onClick = null)
                        ThemePreview(c)
                        Column(Modifier.weight(1f)) {
                            Text(c.label, fontWeight = FontWeight.Bold)
                            Text(c.hint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = onAbout) { Text("Sobre o Gromatic") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

/** Miniature of a theme: bar, rail, plan with a road, side panel and footer. */
@Composable
private fun ThemePreview(c: ThemeChoice) {
    val auto = c == ThemeChoice.AUTO
    val left = themeFor(if (auto) ThemeChoice.FIELD_DARK else c, false)
    val right = if (auto) Engineering else left
    Row(Modifier.size(width = 132.dp, height = 82.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Color(0x55888888), RoundedCornerShape(8.dp))) {
        for ((g, w) in if (auto) listOf(left to 66.dp, right to 66.dp) else listOf(left to 132.dp)) {
            Column(Modifier.width(w).fillMaxHeight().background(g.bg)) {
                Box(Modifier.fillMaxWidth().height(12.dp).background(g.topBar)) {
                    Box(Modifier.padding(start = 4.dp, top = 3.dp).size(6.dp).clip(CircleShape).background(g.accent))
                }
                Row(Modifier.weight(1f)) {
                    Box(Modifier.width(14.dp).fillMaxHeight().background(g.rail)) {
                        Box(Modifier.padding(start = 3.dp, top = 8.dp).size(8.dp).clip(RoundedCornerShape(2.dp)).background(g.railActive))
                    }
                    Canvas(Modifier.weight(1f).fillMaxHeight().background(g.planBg)) {
                        for (k in 1..3) drawLine(g.contour, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4 - 6f), 2f)
                        drawLine(g.cut.copy(alpha = 0.35f), Offset(0f, size.height), Offset(size.width, 0f), 12f)
                        drawLine(g.axis, Offset(0f, size.height), Offset(size.width, 0f), 2.5f)
                        drawCircle(g.gps, 4f, Offset(size.width * 0.6f, size.height * 0.35f))
                    }
                    if (!auto) Box(Modifier.width(30.dp).fillMaxHeight().background(g.panel)) {
                        Box(Modifier.padding(4.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(2.dp)).background(g.card))
                    }
                }
                Box(Modifier.fillMaxWidth().height(8.dp).background(g.footer))
            }
        }
    }
}
