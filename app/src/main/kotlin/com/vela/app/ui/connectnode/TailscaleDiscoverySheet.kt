package com.vela.app.ui.connectnode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.tailscale.TailscaleDevice
import com.vela.app.ui.theme.VelaColors

/**
 * Bottom sheet for discovering and importing Tailscale nodes.
 *
 * Flow:
 * 1. User pastes their Tailscale API key
 * 2. Tap "Find devices" → loads device list from Tailscale API
 * 3. Tap a device → pre-fills the SSH form and dismisses the sheet
 */
@Composable
fun TailscaleDiscoverySheet(
    apiKey:       String,
    onApiKeyChange: (String) -> Unit,
    devices:      List<TailscaleDevice>?,   // null = not loaded, empty = no devices
    isLoading:    Boolean,
    errorMessage: String?,
    onFindDevices: () -> Unit,
    onDeviceSelected: (TailscaleDevice) -> Unit,
    onDismiss:    () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text  = "Discover from Tailscale",
            style = MaterialTheme.typography.titleLarge,
            color = VelaColors.TextPrimary,
        )
        Text(
            text  = "Enter a Tailscale API key to list devices on your tailnet.",
            style = MaterialTheme.typography.bodySmall,
            color = VelaColors.TextSecondary,
        )

        OutlinedTextField(
            value         = apiKey,
            onValueChange = onApiKeyChange,
            label         = { Text("Tailscale API key", color = VelaColors.TextSecondary) },
            placeholder   = { Text("tskey-api-...", color = VelaColors.TextTertiary) },
            singleLine    = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = VelaColors.Accent,
                unfocusedBorderColor = VelaColors.StrokeEdge,
                focusedTextColor     = VelaColors.TextPrimary,
                unfocusedTextColor   = VelaColors.TextPrimary,
                cursorColor          = VelaColors.Accent,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction    = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(
            onClick  = onFindDevices,
            enabled  = apiKey.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color    = VelaColors.Accent,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    "FIND DEVICES",
                    color      = VelaColors.Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 13.sp,
                )
            }
        }

        errorMessage?.let {
            Text(
                text  = it,
                style = MaterialTheme.typography.bodySmall,
                color = VelaColors.Error,
            )
        }

        if (devices != null) {
            if (devices.isEmpty()) {
                Text(
                    text  = "No devices found on this tailnet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VelaColors.TextSecondary,
                )
            } else {
                Text(
                    text  = "TAP A DEVICE TO IMPORT",
                    style = MaterialTheme.typography.labelSmall,
                    color = VelaColors.TextTertiary,
                    letterSpacing = 1.5.sp,
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(240.dp),
                ) {
                    items(devices, key = { it.id }) { device ->
                        TailscaleDeviceRow(
                            device   = device,
                            onSelect = { onDeviceSelected(device) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TailscaleDeviceRow(
    device:   TailscaleDevice,
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        shape   = RoundedCornerShape(12.dp),
        color   = VelaColors.SurfaceSub,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = device.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = VelaColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text  = "${device.tailscaleIp} · ${device.os}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VelaColors.TextSecondary,
                )
            }
            // Online indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (device.isOnline) VelaColors.Accent else VelaColors.TextTertiary,
                        shape = CircleShape,
                    )
            )
        }
    }
}
