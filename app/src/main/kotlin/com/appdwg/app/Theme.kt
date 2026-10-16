package com.appdwg.app

import com.appdwg.core.tr

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Look the user picks in Settings › Appearance; [AUTO] follows the system's dark mode. */
enum class ThemeChoice(private val labelPt: String, private val hintPt: String) {
    AUTO("Automático", "Campo Escuro com o sistema escuro, Engenharia Clara de dia"),
    FIELD_DARK("Campo Escuro", "Escuro, para pouca luz e economia de bateria"),
    ENGINEERING("Engenharia Clara", "Claro e sóbrio, como um desenho técnico"),
    EARTH("Terra", "Tons de terra, cantos arredondados e painéis flutuantes"),
    SUN("Alto Contraste (sol)", "Preto e amarelo, para sol forte; alvos de toque maiores");

    val label: String get() = tr(labelPt)
    val hint: String get() = tr(hintPt)
}

/** Every colour, font and size the screens read from the theme, beyond Material's own. */
@Immutable
data class GTheme(
    val choice: ThemeChoice,
    val dark: Boolean,
    val bg: Color,
    val panel: Color,
    val card: Color,
    val chip: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val cut: Color,
    val fill: Color,
    val gps: Color,
    val topBar: Color,
    val onTopBar: Color,
    val rail: Color,
    val onRail: Color,
    val railActive: Color,
    val onRailActive: Color,
    val footer: Color,
    val onFooter: Color,
    val planBg: Color,
    val contour: Color,
    val contourMajor: Color,
    val axis: Color,
    val points: Color,
    /** Corner radius of cards and panels; buttons use [buttonRadius]. */
    val radius: Dp,
    val buttonRadius: Dp,
    /** Panels float with a margin (Terra) instead of touching the edges. */
    val floating: Boolean,
    val borderWidth: Dp,
    /** Smallest touch target. */
    val target: Dp,
    val iconStroke: Float,
    /** Selected tab is underlined instead of filled (Engenharia Clara). */
    val underlineTabs: Boolean,
    val sans: FontFamily,
    val mono: FontFamily,
    /** Logo colours: contour lines, survey triangle and road axis. */
    val markContour: Color = Color(0xFF7FD1AE),
    val markTriangle: Color = Color(0xFFE8EDF0),
    val markAxis: Color = Color(0xFFF2A93B),
)

private val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal), Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold), Font(R.font.barlow_bold, FontWeight.Bold),
)
private val JetBrainsMono = FontFamily(Font(R.font.jetbrainsmono_400, FontWeight.Normal), Font(R.font.jetbrainsmono_500, FontWeight.Medium),
    Font(R.font.jetbrainsmono_600, FontWeight.SemiBold), Font(R.font.jetbrainsmono_700, FontWeight.Bold))
private val PlexSans = FontFamily(Font(R.font.plexsans_400, FontWeight.Normal), Font(R.font.plexsans_500, FontWeight.Medium),
    Font(R.font.plexsans_600, FontWeight.SemiBold), Font(R.font.plexsans_700, FontWeight.Bold))
private val PlexMono = FontFamily(Font(R.font.plexmono_regular, FontWeight.Normal), Font(R.font.plexmono_semibold, FontWeight.SemiBold))
private val Manrope = FontFamily(Font(R.font.manrope_400, FontWeight.Normal), Font(R.font.manrope_500, FontWeight.Medium),
    Font(R.font.manrope_600, FontWeight.SemiBold), Font(R.font.manrope_700, FontWeight.Bold), Font(R.font.manrope_800, FontWeight.ExtraBold))
private val DmMono = FontFamily(Font(R.font.dmmono_regular, FontWeight.Normal), Font(R.font.dmmono_medium, FontWeight.Medium))
private val Atkinson = FontFamily(Font(R.font.atkinson_regular, FontWeight.Normal), Font(R.font.atkinson_bold, FontWeight.Bold))

val FieldDark = GTheme(
    ThemeChoice.FIELD_DARK, dark = true,
    bg = Color(0xFF0F1417), panel = Color(0xFF161D22), card = Color(0xFF1E272E), chip = Color(0xFF1E272E), border = Color(0xFF2A353D),
    text = Color(0xFFE8EDF0), muted = Color(0xFF8FA1AD), accent = Color(0xFFF2A93B), onAccent = Color(0xFF1A1206),
    cut = Color(0xFFF08A63), fill = Color(0xFF6FB2F2), gps = Color(0xFF2E9E5B),
    topBar = Color(0xFF161D22), onTopBar = Color(0xFFE8EDF0), rail = Color(0xFF161D22), onRail = Color(0xFF8FA1AD),
    railActive = Color(0x24F2A93B), onRailActive = Color(0xFFF2A93B), footer = Color(0xFF161D22), onFooter = Color(0xFF8FA1AD),
    planBg = Color(0xFF11181C), contour = Color(0xFF2C3A43), contourMajor = Color(0xFF4A5F6C), axis = Color(0xFFF2A93B), points = Color(0xFFF2A93B),
    radius = 10.dp, buttonRadius = 8.dp, floating = false, borderWidth = 1.dp, target = 48.dp, iconStroke = 1.8f, underlineTabs = false,
    sans = Barlow, mono = JetBrainsMono,
    markContour = Color(0xFF7FD1AE), markTriangle = Color(0xFFE8EDF0), markAxis = Color(0xFFF2A93B),
)

val Engineering = GTheme(
    ThemeChoice.ENGINEERING, dark = false,
    bg = Color(0xFFF4F6F8), panel = Color(0xFFFFFFFF), card = Color(0xFFF1F4F7), chip = Color(0xFFF1F4F7), border = Color(0xFFDCE2E8),
    text = Color(0xFF15212B), muted = Color(0xFF5B6B78), accent = Color(0xFF1F4FA0), onAccent = Color.White,
    cut = Color(0xFFB23A0A), fill = Color(0xFF1D4FC4), gps = Color(0xFF15803D),
    topBar = Color.White, onTopBar = Color(0xFF15212B), rail = Color.White, onRail = Color(0xFF5B6B78),
    railActive = Color(0xFFE6EDF8), onRailActive = Color(0xFF1F4FA0), footer = Color.White, onFooter = Color(0xFF5B6B78),
    planBg = Color(0xFFFBFCFD), contour = Color(0xFFD3DBE3), contourMajor = Color(0xFFA9B7C4), axis = Color(0xFF1F4FA0), points = Color(0xFF1F4FA0),
    radius = 6.dp, buttonRadius = 6.dp, floating = false, borderWidth = 1.dp, target = 48.dp, iconStroke = 1.7f, underlineTabs = true,
    sans = PlexSans, mono = PlexMono,
    markContour = Color(0xFF2E8B6A), markTriangle = Color(0xFF15212B), markAxis = Color(0xFF1F4FA0),
)

val Earth = GTheme(
    ThemeChoice.EARTH, dark = false,
    bg = Color(0xFFEEEBE3), panel = Color(0xFFF7F4EC), card = Color(0xFFECE6D8), chip = Color(0xFFE2DCCD), border = Color(0xFFD9D1BF),
    text = Color(0xFF26291C), muted = Color(0xFF5F5E4F), accent = Color(0xFFA9521A), onAccent = Color.White,
    cut = Color(0xFF9A4715), fill = Color(0xFF2F6690), gps = Color(0xFF3F7A3A),
    topBar = Color(0xFFEEEBE3), onTopBar = Color(0xFF26291C), rail = Color(0xFF26291C), onRail = Color(0xFFCFCBB8),
    railActive = Color(0xFFF0B27A), onRailActive = Color(0xFF26291C), footer = Color(0xFFEEEBE3), onFooter = Color(0xFF5F5E4F),
    planBg = Color(0xFFF7F4EC), contour = Color(0xFFDDD3BE), contourMajor = Color(0xFFB9AC8D), axis = Color(0xFFA9521A), points = Color(0xFF26291C),
    radius = 20.dp, buttonRadius = 25.dp, floating = true, borderWidth = 0.dp, target = 48.dp, iconStroke = 1.8f, underlineTabs = false,
    sans = Manrope, mono = DmMono,
    markContour = Color(0xFF9CC9A0), markTriangle = Color(0xFFF7F4EC), markAxis = Color(0xFFF0B27A),
)

val Sun = GTheme(
    ThemeChoice.SUN, dark = false,
    bg = Color.White, panel = Color.White, card = Color.White, chip = Color.White, border = Color.Black,
    text = Color.Black, muted = Color(0xFF333333), accent = Color(0xFFFFD400), onAccent = Color.Black,
    cut = Color(0xFFB23A0A), fill = Color(0xFF1D4ED8), gps = Color(0xFF0B7A3B),
    topBar = Color.Black, onTopBar = Color.White, rail = Color.White, onRail = Color.Black,
    railActive = Color(0xFFFFD400), onRailActive = Color.Black, footer = Color.Black, onFooter = Color.White,
    planBg = Color.White, contour = Color(0xFF9A9A9A), contourMajor = Color(0xFF444444), axis = Color(0xFFB23A0A), points = Color.Black,
    radius = 6.dp, buttonRadius = 6.dp, floating = false, borderWidth = 2.dp, target = 52.dp, iconStroke = 2.2f, underlineTabs = false,
    sans = Atkinson, mono = JetBrainsMono,
    markContour = Color(0xFF7FD1AE), markTriangle = Color(0xFFFFFFFF), markAxis = Color(0xFFFFD400),
)

val LocalG = staticCompositionLocalOf { FieldDark }

/** The theme to use for [choice]; [AUTO] picks by the system's dark mode. */
fun themeFor(choice: ThemeChoice, systemDark: Boolean): GTheme = when (choice) {
    ThemeChoice.AUTO -> if (systemDark) FieldDark else Engineering
    ThemeChoice.FIELD_DARK -> FieldDark
    ThemeChoice.ENGINEERING -> Engineering
    ThemeChoice.EARTH -> Earth
    ThemeChoice.SUN -> Sun
}

/** Theme choice kept on the device. */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var choice by mutableStateOf(ThemeChoice.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeChoice.FIELD_DARK)
        private set

    fun choose(c: ThemeChoice) { choice = c; prefs.edit { putString("theme", c.name) } }

    /** Whether the properties panel (right side; the bottom card on phones) is open; kept between sessions. */
    var panelOpen by mutableStateOf(prefs.getBoolean("panelOpen", true))
        private set

    fun togglePanel() { panelOpen = !panelOpen; prefs.edit { putBoolean("panelOpen", panelOpen) } }
}

private fun scheme(g: GTheme): ColorScheme {
    val base = if (g.dark) darkColorScheme() else lightColorScheme()
    val sun = g.choice == ThemeChoice.SUN
    return base.copy(
        primary = if (sun) Color.Black else g.accent, onPrimary = if (sun) g.accent else g.onAccent,
        primaryContainer = g.railActive, onPrimaryContainer = g.onRailActive,
        secondary = g.accent, onSecondary = g.onAccent,
        secondaryContainer = if (g.choice == ThemeChoice.SUN) g.accent else g.accent.copy(alpha = 0.22f).compositeOver(g.panel),
        onSecondaryContainer = if (g.choice == ThemeChoice.SUN) g.onAccent else g.text,
        tertiary = g.fill, onTertiary = Color.White,
        background = g.bg, onBackground = g.text,
        surface = g.panel, onSurface = g.text,
        surfaceVariant = g.card, onSurfaceVariant = g.muted,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = g.panel, surfaceContainerLow = g.panel, surfaceContainer = g.panel,
        surfaceContainerHigh = g.panel, surfaceContainerHighest = g.card,
        inverseSurface = g.text, inverseOnSurface = g.panel,
        outline = g.border, outlineVariant = g.border,
        error = g.cut, onError = Color.White,
    )
}

private fun Color.compositeOver(under: Color): Color {
    val a = alpha
    return Color(red * a + under.red * (1 - a), green * a + under.green * (1 - a), blue * a + under.blue * (1 - a), 1f)
}

private fun typography(f: FontFamily, sun: Boolean): Typography {
    val t = Typography()
    fun TextStyle.f(bold: Boolean = false) = copy(fontFamily = f, fontWeight = if (sun || bold) FontWeight.Bold else fontWeight)
    return Typography(
        displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
        headlineLarge = t.headlineLarge.f(true), headlineMedium = t.headlineMedium.f(true), headlineSmall = t.headlineSmall.f(true),
        titleLarge = t.titleLarge.f(true), titleMedium = t.titleMedium.f(true), titleSmall = t.titleSmall.f(true),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f(true), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}

@Composable
fun GromaticTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val g = themeFor(choice, isSystemInDarkTheme())
    val r = g.radius
    MaterialTheme(
        colorScheme = scheme(g),
        typography = typography(g.sans, g.choice == ThemeChoice.SUN),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(minOf(r, 8.dp)), small = RoundedCornerShape(minOf(r, 12.dp)),
            medium = RoundedCornerShape(r), large = RoundedCornerShape(r + 4.dp), extraLarge = RoundedCornerShape(r + 8.dp),
        ),
    ) {
        CompositionLocalProvider(LocalG provides g, LocalMinimumInteractiveComponentSize provides g.target, content = content)
    }
}

/** Units the user picked in Aparência; [AUTO] is imperial only in the United States. */
enum class UnitChoice(private val labelPt: String, private val hintPt: String) {
    AUTO("Automático", "Métrico em português; imperial só em inglês dos EUA"),
    METRIC("Métrico", "m, m², ha, m³"),
    IMPERIAL("Imperial", "pé internacional (ft), ft², acre, jarda cúbica (yd³)");

    val label: String get() = tr(labelPt)
    val hint: String get() = tr(hintPt)
}

/** Units choice kept on the device; applying it sets [com.appdwg.core.Display]. */
class UnitStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var choice by mutableStateOf(UnitChoice.entries.firstOrNull { it.name == prefs.getString("units", null) } ?: UnitChoice.AUTO)
        private set

    fun choose(c: UnitChoice) { choice = c; prefs.edit { putString("units", c.name) } }

    /** Sets the shown units and number format for [locale]; returns the system in use. */
    fun apply(locale: java.util.Locale): com.appdwg.core.UnitSystem {
        com.appdwg.core.Display.locale = locale
        val u = when (choice) {
            UnitChoice.AUTO -> com.appdwg.core.Display.defaultUnits(locale)
            UnitChoice.METRIC -> com.appdwg.core.UnitSystem.METRIC
            UnitChoice.IMPERIAL -> com.appdwg.core.UnitSystem.IMPERIAL
        }
        com.appdwg.core.Display.units = u
        return u
    }
}

/** Language picked in Aparência. [AUTO] follows the device. */
enum class LanguageChoice(private val labelPt: String, val tag: String?) {
    AUTO("Padrão do sistema", null),
    PORTUGUESE("Português", "pt-BR"),
    ENGLISH("English", "en-US");

    val label: String get() = tr(labelPt)
}

/**
 * The app's language, stored with AppCompat per-app locales (and Android's LocaleManager on
 * API 33+). The same choice is in Configurações > Apps > Gromatic > Idioma. Applying it
 * recreates the screen; the preference is kept for the next launch.
 */
class LanguageStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var choice by mutableStateOf(read())
        private set

    private fun read(): LanguageChoice {
        val applied = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val tag = if (!applied.isEmpty) applied[0]?.toLanguageTag() else prefs.getString("language", null)
        return LanguageChoice.entries.firstOrNull { c -> c.tag != null && tag != null && java.util.Locale.forLanguageTag(c.tag).language == java.util.Locale.forLanguageTag(tag).language }
            ?: LanguageChoice.AUTO
    }

    /** Reads the choice again (it can change in Android's settings while the app is away). */
    fun refresh() { choice = read() }

    /** Applies a language saved before AppCompat was asked, once, at process start. */
    fun applyStored() {
        val saved = prefs.getString("language", null) ?: return
        if (!androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().isEmpty) return
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(saved),
        )
        choice = read()
    }

    fun choose(c: LanguageChoice) {
        choice = c
        prefs.edit { putString("language", c.tag) }
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            if (c.tag == null) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
            else androidx.core.os.LocaleListCompat.forLanguageTags(c.tag),
        )
    }

    /** Locale for numbers and automatic units: the chosen one, else [system]. */
    fun locale(system: java.util.Locale): java.util.Locale = choice.tag?.let { java.util.Locale.forLanguageTag(it) } ?: system
}

/** Applies the saved language before the first screen. */
class GromaticApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        CoilPictures.install(this)
        LanguageStore(this).applyStored()
    }
}
