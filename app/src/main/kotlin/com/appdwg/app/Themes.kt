package com.appdwg.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The looks the user can pick in Aparência; AUTO follows the system's dark mode (A at night, B by day). */
enum class AppTheme(val label: String, val hint: String) {
    AUTO("Automático", "Campo Escuro no modo escuro do aparelho, Engenharia Clara no claro"),
    CAMPO_ESCURO("A · Campo Escuro", "Fundo escuro com destaque âmbar; cansa menos a vista"),
    ENGENHARIA_CLARA("B · Engenharia Clara", "Branco e azul, com cara de software de projeto"),
    TERRA("C · Terra", "Areia e terracota, cantos arredondados"),
    ALTO_CONTRASTE("D · Alto Contraste", "Preto, branco e amarelo, botões maiores; para sol forte"),
}

/** Colors that Material's scheme has no slot for: the drawing, cut/fill, GPS and the logo. */
data class GromaticPalette(
    val dark: Boolean,
    val background: Color,
    val panel: Color,
    val card: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val cut: Color,
    val fill: Color,
    val gps: Color,
    val canvas: Color,
    val contour: Color,
    val contourIndex: Color,
    val alignment: Color,
    val points: Color,
    val marker: Color,
    val logoContours: Color,
    val logoTriangle: Color,
    val logoAxis: Color,
    /** Drawn behind the logo (theme C puts it on a dark tile), or null. */
    val logoTile: Color?,
    val mono: FontFamily,
    /** Minimum height of the main buttons; theme D is bigger for gloves and sun. */
    val touch: Dp,
)

private val barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: FontWeight) = FontFamily(
    weights.map { Font(res, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight))) },
)

private val jetbrainsMono = variable(R.font.jetbrains_mono, FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold)
private val plexSans = variable(R.font.ibm_plex_sans, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
private val plexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)
private val manrope = variable(R.font.manrope, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
private val dmMono = FontFamily(
    Font(R.font.dm_mono_regular, FontWeight.Normal),
    Font(R.font.dm_mono_medium, FontWeight.Medium),
)
private val atkinson = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)

private val campoEscuro = GromaticPalette(
    dark = true, background = Color(0xFF0F1417), panel = Color(0xFF161D22), card = Color(0xFF1E272E), line = Color(0xFF2A353D),
    text = Color(0xFFE8EDF0), muted = Color(0xFF8FA1AD), accent = Color(0xFFF2A93B), onAccent = Color(0xFF1A1206),
    cut = Color(0xFFF08A63), fill = Color(0xFF6FB2F2), gps = Color(0xFF2E9E5B),
    canvas = Color(0xFF11181C), contour = Color(0xFF2C3A43), contourIndex = Color(0xFF3E515D),
    alignment = Color(0xFFF2A93B), points = Color(0xFF6FB2F2), marker = Color(0xFFFF8A3D),
    logoContours = Color(0xFF7FD1AE), logoTriangle = Color(0xFFE8EDF0), logoAxis = Color(0xFFF2A93B), logoTile = null,
    mono = jetbrainsMono, touch = 44.dp,
)

private val engenhariaClara = GromaticPalette(
    dark = false, background = Color(0xFFF4F6F8), panel = Color(0xFFFFFFFF), card = Color(0xFFF1F4F7), line = Color(0xFFDCE2E8),
    text = Color(0xFF15212B), muted = Color(0xFF5B6B78), accent = Color(0xFF1F4FA0), onAccent = Color(0xFFFFFFFF),
    cut = Color(0xFFB23A0A), fill = Color(0xFF1D4FC4), gps = Color(0xFF15803D),
    canvas = Color(0xFFFBFCFD), contour = Color(0xFFD3DBE3), contourIndex = Color(0xFFA9B7C4),
    alignment = Color(0xFFC2410C), points = Color(0xFF1F4FA0), marker = Color(0xFFC2410C),
    logoContours = Color(0xFF2E8B6A), logoTriangle = Color(0xFF15212B), logoAxis = Color(0xFF1F4FA0), logoTile = null,
    mono = plexMono, touch = 44.dp,
)

private val terra = GromaticPalette(
    dark = false, background = Color(0xFFEEEBE3), panel = Color(0xFFF7F4EC), card = Color(0xFFECE6D8), line = Color(0xFFDDD2C2),
    text = Color(0xFF26291C), muted = Color(0xFF5F5E4F), accent = Color(0xFFA9521A), onAccent = Color(0xFFFFFFFF),
    cut = Color(0xFF9A4715), fill = Color(0xFF2F6690), gps = Color(0xFF3F7A3A),
    canvas = Color(0xFFF7F4EC), contour = Color(0xFFDDD3BE), contourIndex = Color(0xFFB9AC8D),
    alignment = Color(0xFFA9521A), points = Color(0xFF2F6690), marker = Color(0xFFA9521A),
    logoContours = Color(0xFF9CC9A0), logoTriangle = Color(0xFFF7F4EC), logoAxis = Color(0xFFF0B27A), logoTile = Color(0xFF26291C),
    mono = dmMono, touch = 48.dp,
)

private val altoContraste = GromaticPalette(
    dark = false, background = Color(0xFFFFFFFF), panel = Color(0xFFFFFFFF), card = Color(0xFFF2F2F2), line = Color(0xFF000000),
    text = Color(0xFF000000), muted = Color(0xFF333333), accent = Color(0xFFFFD400), onAccent = Color(0xFF000000),
    cut = Color(0xFFB23A0A), fill = Color(0xFF1D4ED8), gps = Color(0xFF0B7A3B),
    canvas = Color(0xFFFFFFFF), contour = Color(0xFF9A9A9A), contourIndex = Color(0xFF444444),
    alignment = Color(0xFFD9480F), points = Color(0xFF1D4ED8), marker = Color(0xFFD9480F),
    logoContours = Color(0xFF2E8B6A), logoTriangle = Color(0xFF000000), logoAxis = Color(0xFFE0A800), logoTile = null,
    mono = jetbrainsMono, touch = 52.dp,
)

fun AppTheme.palette(systemDark: Boolean): GromaticPalette = when (this) {
    AppTheme.AUTO -> if (systemDark) campoEscuro else engenhariaClara
    AppTheme.CAMPO_ESCURO -> campoEscuro
    AppTheme.ENGENHARIA_CLARA -> engenhariaClara
    AppTheme.TERRA -> terra
    AppTheme.ALTO_CONTRASTE -> altoContraste
}

private fun AppTheme.body(systemDark: Boolean): FontFamily = when (this) {
    AppTheme.AUTO -> if (systemDark) barlow else plexSans
    AppTheme.CAMPO_ESCURO -> barlow
    AppTheme.ENGENHARIA_CLARA -> plexSans
    AppTheme.TERRA -> manrope
    AppTheme.ALTO_CONTRASTE -> atkinson
}

private fun AppTheme.corner(systemDark: Boolean): Dp = when (palette(systemDark)) {
    terra -> 18.dp
    campoEscuro -> 10.dp
    else -> 6.dp
}

private fun gromaticScheme(p: GromaticPalette): ColorScheme {
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    // Theme D keeps black as the strong color so yellow only marks what is selected.
    val primary = if (p === altoContraste) Color.Black else p.accent
    val onPrimary = if (p === altoContraste) p.accent else p.onAccent
    return base.copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = p.accent.copy(alpha = if (p.dark) 0.18f else 0.14f), onPrimaryContainer = p.text,
        secondary = p.accent, onSecondary = p.onAccent,
        secondaryContainer = p.card, onSecondaryContainer = p.text,
        tertiary = p.gps, onTertiary = Color.White,
        background = p.background, onBackground = p.text,
        surface = p.panel, onSurface = p.text,
        surfaceVariant = p.card, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.panel, surfaceContainerLow = p.panel, surfaceContainer = p.panel,
        surfaceContainerHigh = p.card, surfaceContainerHighest = p.card,
        outline = p.line, outlineVariant = if (p === altoContraste) Color(0xFF666666) else p.line,
        error = p.cut,
    )
}

private fun typography(family: FontFamily, bigger: Boolean): Typography {
    val t = Typography()
    val k = if (bigger) 1.08f else 1f
    fun TextStyle.f() = copy(fontFamily = family, fontSize = fontSize * k)
    return t.copy(
        displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
        headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
        titleLarge = t.titleLarge.f().copy(fontWeight = FontWeight.Bold), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f().copy(fontWeight = if (bigger) FontWeight.Bold else FontWeight.SemiBold),
        labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}

val LocalGromatic = staticCompositionLocalOf { campoEscuro }

@Composable
fun GromaticTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val p = theme.palette(systemDark)
    val r = theme.corner(systemDark)
    CompositionLocalProvider(LocalGromatic provides p) {
        MaterialTheme(
            colorScheme = gromaticScheme(p),
            typography = typography(theme.body(systemDark), bigger = p === altoContraste),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(r / 2), small = RoundedCornerShape(r),
                medium = RoundedCornerShape(r), large = RoundedCornerShape(r * 1.5f), extraLarge = RoundedCornerShape(r * 2),
            ),
            content = content,
        )
    }
}

/** Where the choice lives: on the device only. */
object ThemeStore {
    private const val PREFS = "aparencia"
    private const val KEY = "tema"
    fun load(context: Context): AppTheme =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { name -> AppTheme.entries.firstOrNull { it.name == name } } ?: AppTheme.CAMPO_ESCURO
    fun save(context: Context, theme: AppTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.name).apply()
    }
}

// The launcher icon's drawing (ic_launcher_foreground), so the logo in the app has the same silhouette.
private val logoContours = listOf(
    "M30,44 C40,36 52,40 60,36 C68,32 74,36 78,40",
    "M30,54 C42,47 54,52 62,47 C70,43 75,47 78,50",
    "M30,64 C42,58 56,63 64,58 C71,54 75,58 78,60",
).map { PathParser().parsePathString(it).toPath() }
private val logoTriangle = PathParser().parsePathString("M36,74 L54,32 L72,74 Z").toPath()
private val logoAxis = PathParser().parsePathString("M28,80 C44,70 58,72 80,58").toPath()

/** The Gromatic mark in the colors of [p]; same drawing in every theme. */
@Composable
fun GromaticLogo(modifier: Modifier = Modifier, p: GromaticPalette = LocalGromatic.current) {
    val tile = p.logoTile
    Box(
        modifier.then(if (tile != null) Modifier.background(tile, RoundedCornerShape(22)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            // The drawing spans about 24..84 of the icon's 108 units; show that square.
            val inset = if (tile != null) 0.12f else 0f
            val side = size.minDimension * (1 - 2 * inset)
            val k = side / 60f
            translate(size.width / 2 - 54f * k, size.height / 2 - 56f * k) {
                scale(k, k, pivot = Offset.Zero) {
                    val round = { w: Float -> Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
                    for (c in logoContours) drawPath(c, p.logoContours, style = round(2.6f))
                    drawPath(logoTriangle, p.logoTriangle, style = round(2.4f))
                    drawPath(logoAxis, p.logoAxis, style = round(4.6f))
                }
            }
        }
    }
}

/** Configurações > Aparência: pick a look, with a small preview of each. */
@Composable
fun AppearanceDialog(current: AppTheme, onPick: (AppTheme) -> Unit, onDismiss: () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    var chosen by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aparência") },
        confirmButton = { TextButton(onClick = { onPick(chosen); onDismiss() }) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in AppTheme.entries) {
                    val p = t.palette(systemDark)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = chosen == t, role = Role.RadioButton, onClick = { chosen = t })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = chosen == t, onClick = null)
                        ThemePreview(p, Modifier.width(112.dp).height(64.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.titleSmall)
                            Text(t.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    )
}

/** A thumbnail of the main screen in [p]: top bar with the logo, tool rail, plan with the road, side panel. */
@Composable
private fun ThemePreview(p: GromaticPalette, modifier: Modifier) {
    Column(modifier.border(1.dp, p.line, RoundedCornerShape(6.dp)).background(p.background, RoundedCornerShape(6.dp)).padding(1.dp)) {
        Row(Modifier.fillMaxWidth().height(14.dp).background(if (p === altoContraste) Color.Black else p.panel), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(3.dp))
            GromaticLogo(Modifier.size(11.dp), p)
            Spacer(Modifier.weight(1f))
            Box(Modifier.padding(end = 3.dp).size(16.dp, 7.dp).background(p.accent, RoundedCornerShape(2.dp)))
        }
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(12.dp).fillMaxHeight().background(if (p === terra) p.text else p.panel).padding(2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(4) { i -> Box(Modifier.size(8.dp, 4.dp).background(if (i == 2) p.accent else p.muted.copy(alpha = 0.5f), RoundedCornerShape(1.dp))) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().background(p.canvas)) {
                val h = size.height
                val w = size.width
                for (i in 1..3) drawLine(p.contour, Offset(0f, h * i / 4f), Offset(w, h * i / 4f - 6f), strokeWidth = 1.5f)
                drawLine(p.cut.copy(alpha = 0.35f), Offset(4f, h - 4f), Offset(w - 4f, 6f), strokeWidth = 14f)
                drawLine(p.muted, Offset(4f, h - 4f), Offset(w - 4f, 6f), strokeWidth = 6f)
                drawLine(p.alignment, Offset(4f, h - 4f), Offset(w - 4f, 6f), strokeWidth = 1.5f)
                drawCircle(p.gps, 3.5f, Offset(w * 0.62f, h * 0.38f))
            }
            Column(Modifier.width(26.dp).fillMaxHeight().background(p.panel).padding(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.fillMaxWidth().height(9.dp).background(p.card, RoundedCornerShape(2.dp)))
                Box(Modifier.fillMaxWidth().height(9.dp).background(p.card, RoundedCornerShape(2.dp)))
                Box(Modifier.fillMaxWidth().height(6.dp).background(p.accent, RoundedCornerShape(2.dp)))
            }
        }
    }
}

/** Stroke icons for the tool rail, drawn from 24×24 path data like the mockups. */
object RailIcons {
    const val PLAN = "M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z M9 4v14 M15 6v14"
    const val CUBE = "M12 3l8 4.5v9L12 21l-8-4.5v-9z M12 12l8-4.5 M12 12v9 M12 12L4 7.5"
    const val SECTION = "M2 18h20 M5 18l4-7h6l4 7"
    const val PROFILE = "M2 16c3 0 4-8 7-8s4 6 7 6 3-4 6-4 M2 20h20"
    const val MEASURE = "M3 17L17 3l4 4L7 21z M7 13l2 2 M10 10l2 2 M13 7l2 2"
    const val LAYERS = "M12 3l9 5-9 5-9-5z M3 13l9 5 9-5"
    const val POINTS = "M6 5a2 2 0 1 0 0.01 0z M17 7a2 2 0 1 0 0.01 0z M10 15a2 2 0 1 0 0.01 0z"
    const val LINES = "M3 18l5-9 6 5 7-10"
    const val ROAD = "M8 3L5 21 M16 3l3 18 M12 4v3 M12 10v4 M12 17v3"
    const val PLATFORM = "M3 17l5-8h8l5 8z M8 9l-2-4 M16 9l2-4"
    const val CONTOURS = "M3 8c4-3 6 3 9 0s5-3 9 0 M3 14c4-3 6 3 9 0s5-3 9 0 M3 20c4-3 6 3 9 0s5-3 9 0"
    const val COMPARE = "M4 5h7v14H4z M13 5h7v14h-7z M6 15l3-4 2 2 M15 9l3 3 2-2"
    const val IMAGE = "M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6"
    const val MODELS = "M4 7l8-4 8 4v10l-8 4-8-4z M4 7l8 4 8-4 M12 11v10"
    const val NOTE = "M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11z M12 8a2 2 0 1 0 0.01 0z"
    const val GPS = "M12 5a7 7 0 1 0 0.01 0z M12 10a2 2 0 1 0 0.01 0z M12 2v3 M12 19v3 M2 12h3 M19 12h3"
    const val BASEMAP = "M12 3a9 9 0 1 0 0.01 0z M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18"
    const val CALC = "M5 3h14v18H5z M8 7h8 M8 12h2 M14 12h2 M8 16h2 M14 16h2"
    const val GOTO = "M4 12h14 M13 6l6 6-6 6"
    const val EXPORT = "M12 15V3 M7 8l5-5 5 5 M4 15v6h16v-6"
    const val REFERENCES = "M6 3h9l4 4v14H6z M15 3v4h4 M9 12h7 M9 16h7"
    const val WARNING = "M12 3l10 18H2z M12 10v5 M12 18v0.5"
    const val EXAGGERATION = "M12 3v18 M8 7l4-4 4 4 M8 17l4 4 4-4"
}

/** One tool on the side rail: icon over a short label; the active one is tinted with the accent. */
@Composable
fun RailButton(label: String, icon: String, active: Boolean = false, badge: Boolean = false, onClick: () -> Unit) {
    val p = LocalGromatic.current
    val path = remember(icon) { PathParser().parsePathString(icon).toPath() }
    val fg = if (active) (if (p.accent == Color(0xFFFFD400)) Color.Black else p.accent) else p.muted
    val bg = if (active) (if (p.accent == Color(0xFFFFD400)) p.accent else p.accent.copy(alpha = if (p.dark) 0.16f else 0.12f)) else Color.Transparent
    androidx.compose.material3.Surface(
        onClick = onClick,
        color = bg,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.width(72.dp).height(maxOf(p.touch + 12.dp, 56.dp)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box {
                Canvas(Modifier.size(22.dp)) {
                    val k = size.width / 24f
                    scale(k, k, pivot = Offset.Zero) {
                        drawPath(path, fg, style = Stroke(width = if (p.touch > 48.dp) 2.2f else 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                if (badge) Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(p.gps, RoundedCornerShape(50)))
            }
            Spacer(Modifier.height(3.dp))
            Text(label, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
