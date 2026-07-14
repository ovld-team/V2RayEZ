package com.v2rayez.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v2rayez.app.R
import com.v2rayez.app.ui.tor.TorConflictUi

/** Confirm dialog: disable Tor (or MITM) and continue the pending incompatible action. */
@Composable
fun TorConflictDialog(
    state: TorConflictUi?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(state.titleRes)) },
        text = { Text(stringResource(state.messageRes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(state.confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
