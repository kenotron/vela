package com.vela.app.ui.nodedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.theme.MonoMedium
import com.vela.app.ui.theme.VelaColors

/**
 * Project card — displayed in the Node Detail project list.
 *
 * Design spec: DESIGN.md §8 (Screen 2)
 * - Background: SurfaceSub, 20dp corner radius, 16dp padding
 * - 4dp leading status stripe (Accent at 0.4 — idle in Phase 2, no project sessions yet)
 * - Project name: titleLarge (Inter 18sp/600) — NOT serif. Below 22sp threshold.
 * - Bundle tag: JetBrains Mono 10sp, TextTertiary
 * - Tap → onTap callback (caller navigates to session list)
 */
@Composable
fun ProjectCard(
    projectName: String,
    bundleTag: String = "",
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick  = onTap,
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(20.dp),
        color    = VelaColors.SurfaceSub,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {

            // 4dp leading status stripe — Accent at 0.4 (idle, no sessions in Phase 2)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(VelaColors.Accent.copy(alpha = 0.4f)),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                // Project name — Inter 700 18sp (titleLarge). NOT serif. (DESIGN.md §9.14)
                Text(
                    text  = projectName,
                    style = MaterialTheme.typography.titleLarge,
                    color = VelaColors.TextPrimary,
                )
                if (bundleTag.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    // Bundle tag — JetBrains Mono 10sp (DESIGN.md §8)
                    Text(
                        text  = "bundle: $bundleTag",
                        style = MonoMedium.copy(fontSize = 10.sp),
                        color = VelaColors.TextTertiary,
                    )
                }
            }
        }
    }
}
