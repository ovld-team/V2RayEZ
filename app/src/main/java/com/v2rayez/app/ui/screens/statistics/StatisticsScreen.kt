package com.v2rayez.app.ui.screens.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.v2rayez.app.R
import com.v2rayez.app.domain.model.StatsRange
import com.v2rayez.app.domain.model.TopServer
import com.v2rayez.app.ui.components.CardSurface
import com.v2rayez.app.ui.components.CountryFlag
import com.v2rayez.app.ui.components.DonutChart
import com.v2rayez.app.ui.components.HSpacer
import com.v2rayez.app.ui.components.SectionHeader
import com.v2rayez.app.ui.components.StatTile
import com.v2rayez.app.ui.components.TrafficAreaChart
import com.v2rayez.app.ui.components.UsageBar
import com.v2rayez.app.ui.components.VSpacer
import com.v2rayez.app.ui.components.V2BackTopBar
import com.v2rayez.app.ui.theme.ChartUpload
import com.v2rayez.app.ui.theme.Connected
import com.v2rayez.app.ui.theme.V2RayEzTheme
import com.v2rayez.app.ui.theme.Warning
import com.v2rayez.app.ui.viewmodel.StatisticsViewModel

@Composable
fun StatisticsScreen(onBack: () -> Unit = {}, viewModel: StatisticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        V2BackTopBar(
            title = stringResource(R.string.stats_title),
            onBack = onBack,
            actions = { RangeSelector(selected = state.range, onSelect = viewModel::setRange) }
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Icons.Filled.ArrowDownward, stringResource(R.string.stats_total_download), state.totals.totalDownload, Modifier.weight(1f), ChartUpload)
                StatTile(Icons.Filled.ArrowUpward, stringResource(R.string.stats_total_upload), state.totals.totalUpload, Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            }
            VSpacer(12)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Icons.Filled.Speed, stringResource(R.string.stats_average_speed), state.totals.averageSpeed, Modifier.weight(1f), Connected)
                StatTile(Icons.Filled.NetworkCheck, stringResource(R.string.stats_average_ping), state.totals.averagePing, Modifier.weight(1f), Warning)
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.stats_traffic))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Legend(MaterialTheme.colorScheme.primary, stringResource(R.string.stats_download))
                        Legend(ChartUpload, stringResource(R.string.stats_upload))
                    }
                    VSpacer(12)
                    TrafficAreaChart(points = state.weeklyTraffic)
                }
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.stats_top_servers))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.topServers.isEmpty()) {
                        Text(stringResource(R.string.stats_no_data), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.topServers.forEach { TopServerRow(it) }
                }
            }
            VSpacer(20)

            SectionHeader(title = stringResource(R.string.stats_data_usage))
            CardSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Box(Modifier.padding(20.dp)) {
                    DonutChart(slices = state.dataUsage)
                }
            }
            VSpacer(24)
        }
    }
}

@Composable
private fun RangeSelector(selected: StatsRange, onSelect: (StatsRange) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(statsRangeLabel(selected), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StatsRange.entries.forEach { range ->
                DropdownMenuItem(
                    text = { Text(statsRangeLabel(range)) },
                    onClick = { onSelect(range); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun statsRangeLabel(range: StatsRange): String = stringResource(
    when (range) {
        StatsRange.TODAY -> R.string.stats_range_today
        StatsRange.WEEK -> R.string.stats_range_week
        StatsRange.MONTH -> R.string.stats_range_month
        StatsRange.ALL -> R.string.stats_range_all
    }
)

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopServerRow(server: TopServer) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CountryFlag(server.countryCode, size = 32)
            HSpacer(12)
            Text(server.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(server.usageLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        VSpacer(8)
        UsageBar(fraction = server.fraction)
    }
}

@Preview
@Composable
private fun StatisticsScreenPreview() {
    V2RayEzTheme { StatisticsScreen(onBack = {}, viewModel = StatisticsViewModel()) }
}
