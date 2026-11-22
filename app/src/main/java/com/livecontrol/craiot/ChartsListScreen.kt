package com.livecontrol.craiot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ChartsListScreen(vm: MainViewModel, nav: Nav, onOpen: (String, String) -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val tel by vm.telemetry.collectAsStateWithLifecycle()
    var query by nav.chartsQuery
    var type by nav.chartsType
    var page by nav.chartsPage
    var collapsed by nav.chartsCollapsed

    val all = remember(devices) {
        devices.values.flatMap { d -> d.widgets.values.filter { !it.hidden && isChartable(it.kind, it.value) }.map { d to it } }
    }
    val pages = remember(all) {
        all.groupBy { it.second.page.ifBlank { "Без страницы" } }
            .map { (n, ws) -> Triple(n, ws.mapNotNull { it.second.order ?: it.second.pageId }.minOrNull() ?: Int.MAX_VALUE, ws.size) }
            .sortedWith(compareBy({ it.second }, { it.first }))
    }
    val groups = remember(devices, query, type, page) {
        devices.values.sortedBy { it.id }.mapNotNull { d ->
            val ws = d.widgets.values
                .filter { !it.hidden && isChartable(it.kind, it.value) }
                .filter { type == "all" || (type == "chart") == (it.kind == com.livecontrol.craiot.protocol.WidgetKind.CHART) }
                .filter { page.isEmpty() || it.page.ifBlank { "Без страницы" } == page }
                .filter { query.isBlank() || it.descr.contains(query, true) || it.id.contains(query, true) || it.topic.contains(query, true) }
                .sortedWith(consoleWidgetOrder)
            if (ws.isEmpty()) null else d to ws
        }
    }
    val total = groups.sumOf { it.second.size }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Графики · $total", style = MaterialTheme.typography.titleMedium)
                    Text("сенсоры и chart-виджеты · тап — история показаний", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${fmtRate(tel.rate)} msg/s", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Signal)
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Поиск: имя, топик, страница…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, null) } },
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConsoleChip("Все типы", type == "all", all.size.toLong()) { type = "all" }
                ConsoleChip("Сенсоры", type == "sensor", all.count { it.second.kind != com.livecontrol.craiot.protocol.WidgetKind.CHART }.toLong()) { type = "sensor" }
                ConsoleChip("Chart", type == "chart", all.count { it.second.kind == com.livecontrol.craiot.protocol.WidgetKind.CHART }.toLong()) { type = "chart" }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConsoleChip("Все страницы", page.isEmpty()) { page = "" }
                pages.forEach { (n, _, c) -> ConsoleChip(n, page == n, c.toLong()) { page = n } }
            }
            if (groups.isEmpty()) {
                EmptyState("Нет виджетов с числовыми данными.\nПодключитесь к брокеру и нажмите HELLO.", Modifier.weight(1f))
            } else {
                LazyColumn(state = nav.chartsList, modifier = Modifier.weight(1f)) {
                    groups.forEach { (d, ws) ->
                        item(key = "h:${d.id}") {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    collapsed = if (collapsed.contains(d.id)) collapsed - d.id else collapsed + d.id
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                HealthDot(d.live || d.online == true)
                                Spacer(Modifier.width(8.dp))
                                Text(d.id, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text("${fmtRate(tel.perDevice[d.id] ?: 0.0)}/s", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Signal)
                                Spacer(Modifier.width(6.dp))
                                Text("${ws.size}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(if (collapsed.contains(d.id)) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (!collapsed.contains(d.id)) {
                            items(ws, key = { "${d.id}/${it.id}" }) { w ->
                                Column(Modifier.fillMaxWidth().clickable { onOpen(w.topic, w.descr.ifBlank { w.id }) }) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(w.descr.ifBlank { w.id }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(w.topic, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${w.page.ifBlank { "без страницы" }} · ${w.typeRaw.ifBlank { "сенсор" }}",
                                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        }
                                        if (w.points.size >= 2) {
                                            Sparkline(w.points, Modifier.width(72.dp).height(26.dp))
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("${w.before ?: ""}${w.value ?: "—"}${w.after ?: ""}", fontFamily = FontFamily.Monospace,
                                                fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
                                            AgeText(w.valueAt, now)
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
