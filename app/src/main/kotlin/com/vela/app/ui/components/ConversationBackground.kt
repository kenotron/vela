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

        // ── 1. Base gradient ──────────────────────────────────────────────────
        drawRect(
            brush = if (isDark) {
                Brush.verticalGradient(
                    0f   to Color(0xFF0D1626),
                    0.5f to Color(0xFF090E1B),
                    1f   to Color(0xFF060810),
                )
            } else {
                Brush.verticalGradient(
                    0f to Color(0xFFF7FAFF),
                    1f to Color(0xFFEDF2FC),
                )
            },
        )

        // ── 2. V-beam: soft bloom from top-centre (dark mode only) ────────────
        if (isDark) {
            // Primary bloom — cool blue-white, tight
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF2A4870).copy(alpha = 0.45f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.5f, 0f),
                    radius = size.height * 0.55f,
                ),
            )
            // Secondary bloom — wider, adds depth
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1A3050).copy(alpha = 0.20f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.5f, 0f),
                    radius = size.height * 0.95f,
                ),
            )
        }

        // ── 3. Star / dot field ───────────────────────────────────────────────
        val starColor  = if (isDark) Color.White else Color(0xFF5A6A90)
        val alphaRange = if (isDark) 0.15f..0.90f else 0.03f..0.14f
        val dpRange    = if (isDark) 0.5f..2.0f   else 0.4f..1.4f

        stars.forEach { star ->
            val alpha  = alphaRange.start + star.t * (alphaRange.endInclusive - alphaRange.start)
            val radius = (dpRange.start + star.t * (dpRange.endInclusive - dpRange.start)).dp.toPx()
            drawCircle(
                color  = starColor.copy(alpha = alpha),
                radius = radius,
                center = Offset(star.x * size.width, star.y * size.height),
            )
        }
    }
}
