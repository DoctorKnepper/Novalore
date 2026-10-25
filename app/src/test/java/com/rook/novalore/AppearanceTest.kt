package com.rook.novalore

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class AppearanceTest {
    private fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun defaultsMatchOriginalPalettes() {
        assertEquals(DarkSb, Looks.palette(Appearance(), dark = true))
        assertEquals(LightSb, Looks.palette(Appearance(), dark = false))
    }

    @Test
    fun everyCombinationIsReadable() {
        for (dark in listOf(true, false)) {
            val backgrounds = if (dark) Looks.darkBackgrounds else Looks.lightBackgrounds
            for (bg in backgrounds) for (text in Looks.texts) for (accent in Looks.accents) {
                val a = Appearance(darkBackground = bg.id, lightBackground = bg.id, text = text.id, accent = accent.id)
                val p = Looks.palette(a, dark)
                for (surface in listOf(p.bg, p.panel, p.panel2)) {
                    for ((role, fg) in listOf("ink" to p.ink, "dim" to p.dim, "faint" to p.faint, "accent" to p.accent, "danger" to p.danger)) {
                        val r = contrast(fg, surface)
                        assertTrue("$role on ${bg.id}/${text.id}/${accent.id} dark=$dark is $r", r >= 4.5)
                    }
                }
                assertTrue("button text on ${accent.id}", contrast(p.onAccent, p.accentFill) >= 4.5)
            }
        }
    }
}
