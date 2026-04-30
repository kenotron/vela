package com.vela.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Vela app theme — dark-only, dynamic color permanently disabled.
 *
 * Dynamic color is disabled so the DESIGN.md palette actually renders on all devices.
 * Enabling it on Android 12+ would override our tokens with the system wallpaper palette.
 *
 * All color tokens come from [VelaColors] via the package-level aliases in Color.kt.
 */
@Composable
fun VelaTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        background            = Abyss,
        surface               = SurfaceSub,
        surfaceVariant        = SurfaceRaised,
        primary               = Accent,
        onPrimary             = Color(0xFF003731),
        primaryContainer      = RunningContainer,
        onPrimaryContainer    = RunningOnContainer,
        secondary             = Waiting,
        onSecondary           = WaitingOn,
        secondaryContainer    = WaitingContainer,
        onSecondaryContainer  = WaitingOnContainer,
        tertiary              = Done,
        onTertiary            = DoneOn,
        tertiaryContainer     = DoneContainer,
        onTertiaryContainer   = DoneOnContainer,
        error                 = Error,
        onError               = ErrorOn,
        errorContainer        = ErrorContainer,
        onErrorContainer      = ErrorOnContainer,
        onBackground          = TextPrimary,
        onSurface             = TextPrimary,
        onSurfaceVariant      = TextSecondary,
        outline               = StrokeEdge,
        outlineVariant        = StrokeHair,
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = VelaTypography,
        content     = content,
    )
}
