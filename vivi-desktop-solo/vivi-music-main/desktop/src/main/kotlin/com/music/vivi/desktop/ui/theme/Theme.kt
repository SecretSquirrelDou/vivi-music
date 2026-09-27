package com.music.vivi.desktop.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Color de marca de VIVI (morado del icono). */
val ViviPurple = Color(0xFF673BB2)

/**
 * Tema "Material You" de escritorio: igual que en Android, los colores cambian según
 * la carátula de la canción que suena (color dominante extraído de la imagen).
 */
@Composable
fun ViviTheme(dark: Boolean, seed: Color, content: @Composable () -> Unit) {
    val target = schemeFromSeed(seed, dark)
    val primary by animateColorAsState(target.primary, tween(600))
    val primaryContainer by animateColorAsState(target.primaryContainer, tween(600))
    val secondaryContainer by animateColorAsState(target.secondaryContainer, tween(600))
    val surfaceVariant by animateColorAsState(target.surfaceVariant, tween(600))
    val background by animateColorAsState(target.background, tween(600))
    val surfaceContainer by animateColorAsState(target.surfaceContainer, tween(600))
    val scheme = target.copy(
        primary = primary,
        primaryContainer = primaryContainer,
        secondaryContainer = secondaryContainer,
        surfaceVariant = surfaceVariant,
        background = background,
        surface = background,
        surfaceContainer = surfaceContainer,
    )
    MaterialTheme(colorScheme = scheme, typography = ViviTypography, content = content)
}

private val ViviTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    )
}

fun schemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val (h, s, _) = seed.toHsl()
    val sat = s.coerceIn(0.35f, 0.85f)
    fun c(l: Float, sMul: Float = 1f) = hsl(h, (sat * sMul).coerceIn(0f, 1f), l)
    return if (dark) {
        darkColorScheme(
            primary = c(0.74f),
            onPrimary = c(0.16f),
            primaryContainer = c(0.30f),
            onPrimaryContainer = c(0.90f),
            secondary = c(0.72f, 0.5f),
            onSecondary = c(0.15f, 0.5f),
            secondaryContainer = c(0.24f, 0.45f),
            onSecondaryContainer = c(0.90f, 0.4f),
            tertiary = hsl((h + 60f) % 360f, sat, 0.74f),
            background = c(0.065f, 0.35f),
            onBackground = Color(0xFFE8E3EA),
            surface = c(0.065f, 0.35f),
            onSurface = Color(0xFFE8E3EA),
            surfaceVariant = c(0.16f, 0.3f),
            onSurfaceVariant = Color(0xFFC9C3CE),
            surfaceContainer = c(0.10f, 0.35f),
            surfaceContainerHigh = c(0.13f, 0.35f),
            surfaceContainerHighest = c(0.17f, 0.35f),
            surfaceContainerLow = c(0.085f, 0.35f),
            surfaceContainerLowest = c(0.05f, 0.35f),
            outline = Color(0xFF938F99),
            outlineVariant = c(0.25f, 0.2f),
        )
    } else {
        lightColorScheme(
            primary = c(0.40f),
            onPrimary = Color.White,
            primaryContainer = c(0.88f),
            onPrimaryContainer = c(0.12f),
            secondary = c(0.40f, 0.5f),
            secondaryContainer = c(0.88f, 0.45f),
            onSecondaryContainer = c(0.12f, 0.4f),
            tertiary = hsl((h + 60f) % 360f, sat, 0.40f),
            background = c(0.975f, 0.4f),
            surface = c(0.975f, 0.4f),
            surfaceVariant = c(0.90f, 0.3f),
            surfaceContainer = c(0.94f, 0.35f),
            surfaceContainerHigh = c(0.92f, 0.35f),
            surfaceContainerHighest = c(0.90f, 0.35f),
            surfaceContainerLow = c(0.96f, 0.35f),
            surfaceContainerLowest = Color.White,
        )
    }
}

// ───────────────────────── utilidades de color ─────────────────────────

fun Color.toHsl(): Triple<Float, Float, Float> {
    val r = red; val g = green; val b = blue
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    if (mx == mn) return Triple(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f))
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } * 60f
    return Triple(h, s, l)
}

fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}

/** Color dominante "vivo" de una imagen (equivalente simplificado a Palette de Android). */
fun ImageBitmap.dominantColor(fallback: Color = ViviPurple): Color {
    val pm = toPixelMap()
    val step = max(1, min(width, height) / 40)
    val buckets = HashMap<Int, FloatArray>() // hue bucket → [peso, r, g, b]
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val px = pm[x, y]
            val (h, s, l) = px.toHsl()
            if (s > 0.25f && l in 0.2f..0.8f) {
                val key = (h / 20f).toInt()
                val w = s * (1f - abs(l - 0.5f))
                val arr = buckets.getOrPut(key) { FloatArray(4) }
                arr[0] += w; arr[1] += px.red * w; arr[2] += px.green * w; arr[3] += px.blue * w
            }
            x += step
        }
        y += step
    }
    val best = buckets.values.maxByOrNull { it[0] } ?: return fallback
    if (best[0] <= 0f) return fallback
    return Color(best[1] / best[0], best[2] / best[0], best[3] / best[0])
}

@Suppress("unused")
fun Color.hex(): String = "#%06X".format(0xFFFFFF and toArgb())
