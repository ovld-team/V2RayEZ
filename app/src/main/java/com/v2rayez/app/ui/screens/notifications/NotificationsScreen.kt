package com.v2rayez.app.ui.screens.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.ActivityType
import com.v2rayez.app.ui.components.ActivityRow
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.theme.ChartUpload
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.Warning
import com.v2rayez.app.ui.viewmodel.NotificationsViewModel

@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()

    Column(Modifier.fillMaxSize()) {
        V2BackTopBar(title = stringResource(R.string.settings_notifications), onBack = onBack)

        if (items.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    val (icon, accent) = when (item.type) {
                        ActivityType.CONNECTED -> Icons.Filled.CheckCircle to Connected
                        ActivityType.DURATION -> Icons.Filled.Speed to MaterialTheme.colorScheme.primary
                        ActivityType.DOWNLOAD -> Icons.Filled.ArrowDownward to ChartUpload
                        ActivityType.UPLOAD -> Icons.Filled.ArrowUpward to Warning
                    }
                    CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        ActivityRow(
                            icon = icon,
                            title = item.title,
                            time = item.time,
                            accent = accent,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.NotificationsNone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpacer(8)
            Text(
                stringResource(R.string.notifications_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview
@Composable
private fun NotificationsScreenPreview() {
    V2RayEzTheme { NotificationsScreen(onBack = {}, viewModel = NotificationsViewModel()) }
}
