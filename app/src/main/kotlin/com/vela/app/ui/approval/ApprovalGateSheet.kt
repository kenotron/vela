package com.vela.app.ui.approval

    import androidx.compose.foundation.background
    import androidx.compose.foundation.border
    import androidx.compose.foundation.layout.Arrangement
    import androidx.compose.foundation.layout.Box
    import androidx.compose.foundation.layout.Column
    import androidx.compose.foundation.layout.Row
    import androidx.compose.foundation.layout.Spacer
    import androidx.compose.foundation.layout.fillMaxWidth
    import androidx.compose.foundation.layout.height
    import androidx.compose.foundation.layout.heightIn
    import androidx.compose.foundation.layout.padding
    import androidx.compose.foundation.layout.size
    import androidx.compose.foundation.rememberScrollState
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.foundation.verticalScroll
    import androidx.compose.material3.Button
    import androidx.compose.material3.ButtonDefaults
    import androidx.compose.material3.ExperimentalMaterial3Api
    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.ModalBottomSheet
    import androidx.compose.material3.OutlinedButton
    import androidx.compose.material3.Text
    import androidx.compose.material3.rememberModalBottomSheetState
    import androidx.compose.runtime.Composable
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.text.font.FontFamily
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import com.vela.app.ui.theme.VelaColors

    /**
     * Approval Gate bottom sheet (Screen 7). Rises from the bottom when a session
     * pauses for human input. Dismissing the sheet is equivalent to Deny.
     *
     * @param request   The pending approval request to display.
     * @param onApprove Called when the user taps Approve.
     * @param onDeny    Called when the user taps Deny or dismisses the sheet.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ApprovalGateSheet(
        request: ApprovalSheetViewModel.ApprovalRequest,
        onApprove: () -> Unit,
        onDeny: () -> Unit,
    ) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = onDeny,
            sheetState       = sheetState,
            containerColor   = VelaColors.SurfacePeak,
            shape            = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            dragHandle       = {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .background(VelaColors.TextTertiary, RoundedCornerShape(2.dp)),
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 20.dp, bottom = 20.dp),
            ) {
                // ── Eyebrow: "APPROVAL REQUIRED" ─────────────────────────────────────────────
                Text(
                    text  = "APPROVAL REQUIRED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                    ),
                    color = VelaColors.Waiting,
                )

                Spacer(Modifier.height(10.dp))

                // ── Serif title — the question moment ────────────────────────────────────────
                Text(
                    text  = request.question,
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = 25.sp),
                    color = VelaColors.TextPrimary,
                )

                // ── Optional context block ───────────────────────────────────────────────────
                if (request.contextText != null) {
                    Spacer(Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .background(VelaColors.SurfaceRaised, RoundedCornerShape(12.dp))
                            .border(1.dp, VelaColors.StrokeHair, RoundedCornerShape(12.dp)),
                    ) {
                        Text(
                            text       = request.contextText,
                            style      = MaterialTheme.typography.bodyMedium,
                            color      = VelaColors.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            modifier   = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ── Button row: Deny (left) + Approve (right) ────────────────────────────────
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Deny — transparent background, Error-colored border and label
                    OutlinedButton(
                        onClick  = onDeny,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape  = RoundedCornerShape(26.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            VelaColors.Error.copy(alpha = 0.30f),
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = VelaColors.Error,
                        ),
                    ) {
                        Text(
                            text  = "DENY",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    // Approve — Accent fill, Abyss label
                    Button(
                        onClick  = onApprove,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape  = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VelaColors.Accent,
                            contentColor   = VelaColors.Abyss,
                        ),
                    ) {
                        Text(
                            text  = "APPROVE",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
    