package com.rook.novalore

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Novalore palette. The dark values are the original v1 colours; light is new and follows the system setting. */
data class SbColors(
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val ink: Color,
    val dim: Color,
    val faint: Color,
    val accent: Color,      // amber used for text and icons
    val accentFill: Color,  // amber used as a filled background
    val onAccent: Color,
    val danger: Color,
    val starTrack: Color,
    val cardTints: List<Color>
)

val DarkSb = SbColors(
    bg = Color(0xFF000000),
    panel = Color(0xFF141419),
    panel2 = Color(0xFF1C1C22),
    ink = Color(0xFFF7F4EA),
    dim = Color(0xFFC9C4B8),
    faint = Color(0xFF8E897C),
    accent = Color(0xFFFFC53D),
    accentFill = Color(0xFFFFC53D),
    onAccent = Color(0xFF1A1400),
    danger = Color(0xFFFF7A6B),
    starTrack = Color(0xFF3A3A42),
    cardTints = listOf(
        Color(0xFF22301F), Color(0xFF33231F), Color(0xFF1F2838), Color(0xFF3A2A16),
        Color(0xFF2B2138), Color(0xFF173330), Color(0xFF38202A), Color(0xFF2E2E1A)
    )
)

val LightSb = SbColors(
    bg = Color(0xFFFBF8F1),
    panel = Color(0xFFF1ECE0),
    panel2 = Color(0xFFE6DFCF),
    ink = Color(0xFF1D1B16),
    dim = Color(0xFF4A463C),
    faint = Color(0xFF5F5A4D),
    accent = Color(0xFF7A5300),     // darker amber so text keeps 4.5:1 contrast on the light background
    accentFill = Color(0xFFFFC53D),
    onAccent = Color(0xFF1A1400),
    danger = Color(0xFFB3261E),
    starTrack = Color(0xFFD5CEBD),
    cardTints = listOf(
        Color(0xFFDDE8D8), Color(0xFFEBDAD3), Color(0xFFD7DFEC), Color(0xFFEDDDC4),
        Color(0xFFE2D9EC), Color(0xFFD2E6E2), Color(0xFFEBD5DD), Color(0xFFE6E5CC)
    )
)

val LocalSb = staticCompositionLocalOf { DarkSb }

// Short names used throughout the UI. They read the current palette, so they work in both themes.
val Bg: Color @Composable @ReadOnlyComposable get() = LocalSb.current.bg
val Panel: Color @Composable @ReadOnlyComposable get() = LocalSb.current.panel
val Panel2: Color @Composable @ReadOnlyComposable get() = LocalSb.current.panel2
val Ink: Color @Composable @ReadOnlyComposable get() = LocalSb.current.ink
val Dim: Color @Composable @ReadOnlyComposable get() = LocalSb.current.dim
val Faint: Color @Composable @ReadOnlyComposable get() = LocalSb.current.faint
val Amber: Color @Composable @ReadOnlyComposable get() = LocalSb.current.accent
val AmberFill: Color @Composable @ReadOnlyComposable get() = LocalSb.current.accentFill
val OnAmber: Color @Composable @ReadOnlyComposable get() = LocalSb.current.onAccent
val Danger: Color @Composable @ReadOnlyComposable get() = LocalSb.current.danger

// ---------- Appearance settings ----------

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** What the user picked on the Appearance screen. The defaults are the original OLED black and amber look. */
data class Appearance(
    val mode: ThemeMode = ThemeMode.DARK,
    val darkBackground: String = "oled",
    val lightBackground: String = "paper",
    val accent: String = "amber",
    val text: String = "warm"
)

class BackgroundSwatch(val id: String, @StringRes val label: Int, val bg: Color, val panel: Color, val panel2: Color)

class TextShades(val ink: Color, val dim: Color, val faint: Color)

class TextSwatch(val id: String, @StringRes val label: Int, val onDark: TextShades, val onLight: TextShades)

/** [onDark] is used for text and icons in dark mode and for filled buttons in both; [onLight] is darker for contrast. */
class AccentSwatch(val id: String, @StringRes val label: Int, val onDark: Color, val onLight: Color, val onFill: Color)

/**
 * The preset choices. Every text shade and accent has at least 4.5:1 contrast on every background and panel of its
 * mode (checked in AppearanceTest), so no combination the user picks can make text hard to read.
 */
object Looks {
    val darkBackgrounds = listOf(
        BackgroundSwatch("oled", R.string.bg_oled, DarkSb.bg, DarkSb.panel, DarkSb.panel2),
        BackgroundSwatch("charcoal", R.string.bg_charcoal, Color(0xFF101010), Color(0xFF1A1A1A), Color(0xFF212121)),
        BackgroundSwatch("midnight", R.string.bg_midnight, Color(0xFF0A0F1C), Color(0xFF141B2D), Color(0xFF1A2133)),
        BackgroundSwatch("forest", R.string.bg_forest, Color(0xFF0A120D), Color(0xFF132019), Color(0xFF17251D)),
        BackgroundSwatch("plum", R.string.bg_plum, Color(0xFF120A14), Color(0xFF1F1423), Color(0xFF2A1C2F))
    )

    val lightBackgrounds = listOf(
        BackgroundSwatch("paper", R.string.bg_paper, LightSb.bg, LightSb.panel, LightSb.panel2),
        BackgroundSwatch("white", R.string.bg_white, Color(0xFFFFFFFF), Color(0xFFF2F2F4), Color(0xFFE6E6EA)),
        BackgroundSwatch("sepia", R.string.bg_sepia, Color(0xFFF4ECD8), Color(0xFFEADFC6), Color(0xFFE3D8BF)),
        BackgroundSwatch("mist", R.string.bg_mist, Color(0xFFF3F6F9), Color(0xFFE6ECF2), Color(0xFFD8E0E8))
    )

    val texts = listOf(
        TextSwatch(
            "warm", R.string.text_warm,
            TextShades(DarkSb.ink, DarkSb.dim, DarkSb.faint),
            TextShades(LightSb.ink, LightSb.dim, LightSb.faint)
        ),
        TextSwatch(
            "neutral", R.string.text_neutral,
            TextShades(Color(0xFFFFFFFF), Color(0xFFCCCCCC), Color(0xFF939393)),
            TextShades(Color(0xFF000000), Color(0xFF3D3D3D), Color(0xFF575757))
        ),
        TextSwatch(
            "cool", R.string.text_cool,
            TextShades(Color(0xFFEAF0F7), Color(0xFFB8C2CE), Color(0xFF8792A0)),
            TextShades(Color(0xFF141A22), Color(0xFF3C4652), Color(0xFF56606C))
        ),
        TextSwatch(
            "cream", R.string.text_cream,
            TextShades(Color(0xFFF3E3C3), Color(0xFFCDBB98), Color(0xFF9A8A69)),
            TextShades(Color(0xFF2B1F10), Color(0xFF54432C), Color(0xFF655438))
        ),
        TextSwatch(
            "mint", R.string.text_mint,
            TextShades(Color(0xFFD9F7D9), Color(0xFFA9CCA9), Color(0xFF7E9C7E)),
            TextShades(Color(0xFF0F2410), Color(0xFF2F4A30), Color(0xFF455F46))
        )
    )

    val accents = listOf(
        AccentSwatch("amber", R.string.accent_amber, DarkSb.accent, LightSb.accent, DarkSb.onAccent),
        AccentSwatch("orange", R.string.accent_orange, Color(0xFFFF8A3D), Color(0xFF8F3D00), Color(0xFF111111)),
        AccentSwatch("rose", R.string.accent_rose, Color(0xFFFF7AA8), Color(0xFF9E1749), Color(0xFF111111)),
        AccentSwatch("violet", R.string.accent_violet, Color(0xFFB79CFF), Color(0xFF5B2FC2), Color(0xFF111111)),
        AccentSwatch("blue", R.string.accent_blue, Color(0xFF6CB4FF), Color(0xFF0A57A3), Color(0xFF111111)),
        AccentSwatch("teal", R.string.accent_teal, Color(0xFF3FD6C4), Color(0xFF00665B), Color(0xFF111111)),
        AccentSwatch("green", R.string.accent_green, Color(0xFF7EDB6A), Color(0xFF2B6620), Color(0xFF111111))
    )

    private fun <T> pick(list: List<T>, id: String, idOf: (T) -> String): T = list.firstOrNull { idOf(it) == id } ?: list.first()

    fun background(a: Appearance, dark: Boolean): BackgroundSwatch =
        if (dark) pick(darkBackgrounds, a.darkBackground) { it.id } else pick(lightBackgrounds, a.lightBackground) { it.id }

    fun text(a: Appearance): TextSwatch = pick(texts, a.text) { it.id }

    fun accent(a: Appearance): AccentSwatch = pick(accents, a.accent) { it.id }

    /** Builds the full palette. The defaults reproduce [DarkSb] and [LightSb] exactly. */
    fun palette(a: Appearance, dark: Boolean): SbColors {
        val base = if (dark) DarkSb else LightSb
        val bg = background(a, dark)
        val t = text(a).let { if (dark) it.onDark else it.onLight }
        val ac = accent(a)
        return base.copy(
            bg = bg.bg, panel = bg.panel, panel2 = bg.panel2,
            ink = t.ink, dim = t.dim, faint = t.faint,
            accent = if (dark) ac.onDark else ac.onLight,
            accentFill = ac.onDark,
            onAccent = ac.onFill
        )
    }
}

@Composable
fun Appearance.isDark(): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** Saves the appearance choices in SharedPreferences and exposes them as Compose state. */
class AppearanceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    var appearance: Appearance by mutableStateOf(load())
        private set

    private fun load(): Appearance {
        val d = Appearance()
        return Appearance(
            mode = ThemeMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: d.mode,
            darkBackground = prefs.getString("darkBackground", null) ?: d.darkBackground,
            lightBackground = prefs.getString("lightBackground", null) ?: d.lightBackground,
            accent = prefs.getString("accent", null) ?: d.accent,
            text = prefs.getString("text", null) ?: d.text
        )
    }

    fun set(a: Appearance) {
        appearance = a
        prefs.edit()
            .putString("mode", a.mode.name)
            .putString("darkBackground", a.darkBackground)
            .putString("lightBackground", a.lightBackground)
            .putString("accent", a.accent)
            .putString("text", a.text)
            .apply()
    }
}

@Composable
fun NovaloreTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val dark = appearance.isDark()
    val sb = Looks.palette(appearance, dark)
    val scheme = if (dark) {
        darkColorScheme(
            background = sb.bg, surface = sb.panel, onBackground = sb.ink, onSurface = sb.ink,
            primary = sb.accentFill, onPrimary = sb.onAccent, surfaceVariant = sb.panel2, onSurfaceVariant = sb.dim,
            error = sb.danger
        )
    } else {
        lightColorScheme(
            background = sb.bg, surface = sb.panel, onBackground = sb.ink, onSurface = sb.ink,
            primary = sb.accent, onPrimary = Color.White, surfaceVariant = sb.panel2, onSurfaceVariant = sb.dim,
            error = sb.danger
        )
    }
    CompositionLocalProvider(LocalSb provides sb) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
