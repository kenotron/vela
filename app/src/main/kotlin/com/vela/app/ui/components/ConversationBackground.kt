package com.vela.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * Full-bleed conversation background — deep-space star field.
 *
 * Dark mode  : mid-navy base (#1E2A3E → #18202E) with 180 crisp white stars.
 *              Lighter than before so message bubbles pop against it.
 * Light mode : warm off-white gradient, very faint cool-grey dots.
 *
 * Stars are seeded with a fixed value ("VELA" = 0x56454C41) so they never
 * shift position on recomposition.
 *
 * Intended usage: place as the FIRST child of the outermost Box so it fills
 * the entire screen behind the Scaffold (top bar, content, and composer area).
 */
@Composable
fun ConversationBackground(modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()

    data class Star(val x: Float, val y: Float, val t: Float)

    // 180 stars — more density to feel present even in message-heavy chats
    val stars = remember {
        val rng = Random(0x56454C41)
        List(180) { Star(rng.nextFloat(), rng.nextFloat(), rng.nextFloat()) }
    }

    Canvas(modifier.fillMaxSize()) {

        // ── Base gradient ─────────────────────────────────────────────────
        // Dark:  mid-navy — noticeably lighter than before; bubbles contrast clearly.
        // Light: warm off-white — same temperature as the message surfaces.
        drawRect(
            brush = if (isDark) {
                Brush.verticalGradient(
                    0f to Color(0xFF1E2A3E),
                    1f to Color(0xFF18202E),
                )
            } else {
                Brush.verticalGradient(
                    0f to Color(0xFFF2F3F5),
                    1f to Color(0xFFECEEF0),
                )
            },
        )

        // ── Dot field ─────────────────────────────────────────────────────
        // Dark: brighter range so stars read against the lighter navy base.
        // Light: kept subtle.
        val dotColor   = if (isDark) Color.White else Color(0xFF000000)
        val alphaRange = if (isDark) 0.08f..0.45f else 0.02f..0.07f
        val dpRange    = if (isDark) 0.5f..1.4f   else 0.3f..0.7f

        stars.forEach { star ->
            val alpha  = alphaRange.start + star.t * (alphaRange.endInclusive - alphaRange.start)
            val radius = (dpRange.start + star.t * (dpRange.endInclusive - dpRange.start)).dp.toPx()
            drawCircle(
                color  = dotColor.copy(alpha = alpha),
                radius = radius,
                center = Offset(star.x * size.width, star.y * size.height),
            )
        }

        // ── Subtle radial bloom from top-centre ───────────────────────────
        // Echoes the Vela icon's V-beam glow. Very faint in dark mode, absent in light.
        if (isDark) {
            drawCircle(
                brush  = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF3D5A80).copy(alpha = 0.18f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.5f, 0f),
                    radius = size.width * 0.8f,
                ),
                radius = size.width * 0.8f,
                center = Offset(size.width * 0.5f, 0f),
            )
        }
    }
}
