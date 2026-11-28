package com.livecontrol.iotmod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livecontrol.iotmod.protocol.Widget
import com.livecontrol.iotmod.protocol.WidgetKind

/** Графики v4: шапка сворачивается, группы свёрнуты по умолчанию, закреплённые сверху. */
@Composable
fun ChartsListScreen(vm: MainViewModel, nav: Nav, onOpen: (String, String) -> Unit) {
    val t = LocalCraTokens.current
    val devices by vm.devices.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val tel by vm.telemetry.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    var query by nav.chartsQuery
    var type by nav.chartsType
    var page by nav.chartsPage
    var expanded by nav.chartsCollapsed

    val chrome = rememberChrome(nav.barsHidden,
        { nav.chartsList.firstVisibleItemIndex }, { nav.chartsList.firstVisibleItemScrollOffset })
    val headerCollapsed = chrome.headerCollapsed.value

    val all = remember(devices) {
        devices.values.flatMap { d -> d.widgets.values.filter { !it.hidden && isChartable(it.kind, it.value) }.map { d to it } }
    }
    val pages = remember(all) {
        all.groupBy { it.second.page.ifBlank { "Без страницы" } }
            .map { (n, ws) -> Triple(n, ws.mapNotNull { it.second.order ?: it.second.pageId }.minOrNull() ?: Int.MAX_VALUE, ws.size) }
            .sortedWith(compareBy({ it.second }, { it.first }))
    }
    val pinned = remember(all, pins) { all.filter { it.second.topic in pins } }
    val groups = remember(devices, query, type, page) {
        devices.values.sortedBy { it.id }.mapNotNull { d ->
            val ws = d.widgets.values
                .filter { !it.hidden && isChartable(it.kind, it.value) }
                .filter { type == "all" || (type == "chart") == (it.kind == WidgetKind.CHART) }
                .filter { page.isEmpty() || it.page.ifBlank { "Без страницы" } == page }
                .filter { query.isBlank() || it.descr.contains(query, true) || it.id.contains(query, true) || it.topic.contains(query, true) }
                .sortedWith(consoleWidgetOrder)
            if (ws.isEmpty()) null else d to ws
        }
    }
    val total = groups.sumOf { it.second.size }
    val searchVisible = !headerCollapsed || query.isNotBlank()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Графики · $total", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(if (headerCollapsed) "тап — история показаний" else "сенсоры и chart-виджеты · тап — история показаний",
                        style = CraType.rowSub, color = t.faint, maxLines = 1)
                }
                Text("${fmtRate(tel.rate)} msg/s", style = CraType.meta, color = Signal)
            }
            AnimatedVisibility(visible = searchVisible, enter = expandVertically(tween(160)), exit = shrinkVertically(tween(160))) {
                CraSearchField(query, { query = it }, "Поиск: имя, топик, страница…")
            }
            AnimatedVisibility(visible = !headerCollapsed, enter = expandVertically(tween(160)), exit = shrinkVertically(tween(160))) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConsoleChip("Все типы", type == "all", all.size.toLong()) { type = "all" }
                        ConsoleChip("Сенсоры", type == "sensor", all.count { it.second.kind != WidgetKind.CHART }.toLong()) { type = "sensor" }
                        ConsoleChip("Chart", type == "chart", all.count { it.second.kind == WidgetKind.CHART }.toLong()) { type = "chart" }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConsoleChip("Все страницы", page.isEmpty()) { page = "" }
                        pages.forEach { (n, _, c) -> ConsoleChip(n, page == n, c.toLong()) { page = n } }
                    }
                }
            }
            if (groups.isEmpty() && pinned.isEmpty()) {
                EmptyState("Нет виджетов с числовыми данными.\nПодключитесь к брокеру и нажмите HELLO.", Modifier.weight(1f))
            } else {
                LazyColumn(state = nav.chartsList, modifier = Modifier.weight(1f).nestedScroll(chrome.connection)) {
                    if (pinned.isNotEmpty()) {
                        item(key = "h:pins") {
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                                Text("Закреплённое", style = CraType.groupTitle, color = MaterialTheme.colorScheme.tertiary)
                                Text("${pinned.size}", style = CraType.meta, color = t.faint)
                            }
                        }
                        items(pinned, key = { "p:${it.first.id}/${it.second.id}" }) { (d, w) ->
                            ChartRow(w, now, true,
                                { onOpen(w.topic, w.descr.ifBlank { w.id }) }, { vm.togglePin(w.topic) })
                        }
                    }
                    groups.forEach { (d, ws) ->
                        val isOpen = expanded.contains(d.id)
                        item(key = "h:${d.id}") {
                            Row(Modifier.fillMaxWidth().clickable {
                                expanded = if (isOpen) expanded - d.id else expanded + d.id
                            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                HealthDot(d.live || d.online == true)
                                Spacer(Modifier.width(8.dp))
                                Text(d.id, style = CraType.groupTitle, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${fmtRate(tel.perDevice[d.id] ?: 0.0)}/s", style = CraType.meta, color = Signal)
                                Spacer(Modifier.width(6.dp))
                                Text("${ws.size}", style = CraType.meta, color = t.faint)
                                Spacer(Modifier.width(6.dp))
                                Icon(if (isOpen) androidx.compose.material.icons.Icons.Default.KeyboardArrowUp
                                    else androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                                    null, tint = t.faint, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (isOpen) {
                            items(ws, key = { "${d.id}/${it.id}" }) { w ->
                                ChartRow(w, now, w.topic in pins,
                                    { onOpen(w.topic, w.descr.ifBlank { w.id }) }, { vm.togglePin(w.topic) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartRow(w: Widget, now: Long, pinned: Boolean, onOpen: () -> Unit, onPin: () -> Unit) {
    val t = LocalCraTokens.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(w.descr.ifBlank { w.id }, style = CraType.rowTitle, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(w.topic, style = CraType.meta, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${w.page.ifBlank { "без страницы" }} · ${w.typeRaw.ifBlank { "сенсор" }}",
                    style = CraType.rowSub, color = t.dim, maxLines = 1)
            }
            if (w.points.size >= 2) {
                Sparkline(w.points, Modifier.width(72.dp).height(26.dp))
                Spacer(Modifier.width(8.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${w.before ?: ""}${w.value ?: "—"}${w.after ?: ""}", style = CraType.numSmall, color = t.ink,
                    textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
                AgeText(w.valueAt, now)
            }
            Box(Modifier.size(28.dp).clickable(onClick = onPin), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Star, contentDescription = if (pinned) "Открепить" else "Закрепить",
                    tint = if (pinned) MaterialTheme.colorScheme.tertiary else t.faint.copy(alpha = .7f), modifier = Modifier.size(15.dp))
            }
        }
        HorizontalDivider(color = t.tileBorder)
    }
}
