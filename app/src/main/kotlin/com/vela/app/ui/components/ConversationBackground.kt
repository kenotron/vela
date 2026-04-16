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
 * Conversation background — pulled from the Vela app icon's visual language:
 *   • Base: deep navy-to-black gradient matching the icon's #070A14 background
 *   • Glow: soft radial bloom from the top-centre, echoing the V-beam
 *   • Stars: 120 deterministic dot particles scattered across the field
 *
 * Light mode tones everything down to a near-white gradient with very faint
 * cool-grey dots — same constellation, different sky.
 *
 * Stars are seeded with a fixed value ("VELA" = 0x56454C41) so they never
 * shift position on recomposition.
 */
@Composable
fun ConversationBackground(modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()

    data class Star(val x: Float, val y: Float, val t: Float)   // t in 0..1

    val stars = remember {
        val rng = Random(0x56454C41)  // "VELA" as seed — stable across recompositions
        List(120) { Star(rng.nextFloat(), rng.nextFloat(), rng.nextFloat()) }
    }

    Canvas(modifier.fillMaxSize()) {

        // ── 1. Base — almost flat, barely-there gradient ──────────────────────
        // Dark: neutral charcoal, no blue tint fighting the message bubbles.
        // Light: warm off-white, same temperature as the message bubbles.
        drawRect(
            brush = if (isDark) {
                Brush.verticalGradient(
                    0f to Color(0xFF111418),
                    1f to Color(0xFF0E1115),
                )
            } else {
                Brush.verticalGradient(
                    0f to Color(0xFFF2F3F5),
                    1f to Color(0xFFECEEF0),
                )
            },
        )

        // ── 2. Dot field — texture only, not decoration ───────────────────────
        // Opacity so low you feel it more than see it.
        val dotColor  = if (isDark) Color.White else Color(0xFF000000)
        val alphaRange = if (isDark) 0.03f..0.12f else 0.02f..0.06f
        val dpRange    = if (isDark) 0.3f..1.0f   else 0.3f..0.7f

        stars.forEach { star ->
            val alpha  = alphaRange.start + star.t * (alphaRange.endInclusive - alphaRange.start)
            val radius = (dpRange.start + star.t * (dpRange.endInclusive - dpRange.start)).dp.toPx()
            drawCircle(
                color  = dotColor.copy(alpha = alpha),
                radius = radius,
                center = Offset(star.x * size.width, star.y * size.height),
            )
        }
    }
}
