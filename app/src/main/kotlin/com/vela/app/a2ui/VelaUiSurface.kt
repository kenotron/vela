package com.vela.app.a2ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose renderer for [VelaUiPayload] — the Vela-UI / A2UI-inspired structured response format.
 *
 * Maps A2UI v0.8 component semantics to Material 3 Compose widgets.
 * When the official Jetpack Compose A2UI renderer ships (planned Q2 2025), this can be replaced
 * with a direct A2UI renderer integration.
 *
 * A2UI component → Compose mapping:
 *   Card      → Material3 Card with title + subtitle
 *   Step      → Numbered circle badge + text row
 *   Item      → Bullet (•) + text row
 *   BodyText  → plain Text composable
 *   Tip       → amber-tinted Surface box with small text
 *   Code      → dark Surface with monospace Text
 */
@Composable
fun VelaUiSurface(
    payload: VelaUiPayload,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        payload.components.forEach { component ->
            when (component) {
                is VelaUiComponent.Card     -> VelaCard(component)
                is VelaUiComponent.Step     -> VelaStep(component)
                is VelaUiComponent.Item     -> VelaBulletItem(component)
                is VelaUiComponent.BodyText -> VelaBodyText(component)
                is VelaUiComponent.Tip      -> VelaTip(component)
                is VelaUiComponent.Code     -> VelaCode(component)
            }
        }
    }
}

@Composable
private fun VelaCard(component: VelaUiComponent.Card) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = component.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            component.subtitle?.let { sub ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun VelaStep(component: VelaUiComponent.Step) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // Numbered circle badge
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = component.n.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = component.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VelaBulletItem(component: VelaUiComponent.Item) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 1.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = component.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VelaBodyText(component: VelaUiComponent.BodyText) {
    Text(
        text = component.text,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun VelaTip(component: VelaUiComponent.Tip) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "💡",
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 1.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = component.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VelaCode(component: VelaUiComponent.Code) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            component.lang?.let { lang ->
                Text(
                    text = lang,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = component.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
