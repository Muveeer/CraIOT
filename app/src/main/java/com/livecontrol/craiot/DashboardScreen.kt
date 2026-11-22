package com.livecontrol.craiot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livecontrol.craiot.mqtt.ConnState
import com.livecontrol.craiot.protocol.Widget
import com.livecontrol.craiot.protocol.WidgetKind

private data class PageInfo(val name: String, val order: Int, val count: Int)

internal val widgetOrder = compareBy<Widget>({ it.order ?: Int.MAX_VALUE }, { it.descr.ifBlank { it.id } }, { it.id })

@Composable
fun DashboardScreen(vm: MainViewModel, nav: Nav, onOpenChart: (String, String) -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val state by vm.connState.collectAsStateWithLifecycle()
    val dark by vm.dark.collectAsStateWithLifecycle()
    var page by nav.dashPage
    var query by nav.dashQuery
    var collapsed by nav.dashCollapsed

    val allWidgets = remember(devices) { devices.values.flatMap { it.widgets.values }.filter { !it.hidden } }
    val pages = remember(allWidgets) {
        allWidgets.groupBy { it.page.ifBlank { "Без страницы" } }
            .map { (name, ws) -> PageInfo(name, ws.mapNotNull { it.order ?: it.pageId }.minOrNull() ?: Int.MAX_VALUE, ws.size) }
            .sortedWith(compareBy({ it.order }, { it.name }))
    }
    val groups = remember(devices, page, query) {
        devices.values.sortedBy { it.id }.mapNotNull { d ->
            val ws = d.widgets.values.filter { !it.hidden }
                .filter { query.isNotBlank() || page.isEmpty() || it.page.ifBlank { "Без страницы" } == page }
                .filter { query.isBlank() || it.descr.contains(query, true) || it.id.contains(query, true) || it.topic.contains(query, true) }
                .sortedWith(widgetOrder)
            if (ws.isEmpty()) null else d to ws
        }
    }
    val totalShown = groups.sumOf { it.second.size }
    val noConfigs = devices.isNotEmpty() && allWidgets.all { it.rawConfig.isEmpty() }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Дашборд", style = MaterialTheme.typography.titleMedium)
                    Text("by Serghei Crasnicov", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.toggleDark() }) { Text(if (dark) "☀" else "☾") }
            }
            StatusBanner(state, null,
                onConnect = { vm.activeId.value?.let { vm.connect(it) } },
                onDisconnect = vm::disconnect, onRetry = vm::retry)
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Поиск виджета…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, null) } },
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PageChip("Все", allWidgets.size, page.isEmpty()) { page = "" }
                pages.forEach { p -> PageChip(p.name, p.count, page == p.name) { page = p.name } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${groups.size} устройств · $totalShown виджетов", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    collapsed = if (collapsed.isEmpty()) devices.keys.toList() else listOf()
                }) { Text(if (collapsed.isEmpty()) "Свернуть все" else "Развернуть все") }
            }
            if (groups.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (devices.isEmpty()) "Нет устройств — подключитесь к брокеру" else "Ничего не найдено",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (noConfigs) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.hello() }, enabled = state is ConnState.Connected) { Text("HELLO — получить конфиги") }
                    }
                }
            } else {
                LazyColumn(state = nav.dashList, modifier = Modifier.weight(1f)) {
                    groups.forEach { (d, ws) ->
                        item(key = "h:${d.id}") {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    collapsed = if (collapsed.contains(d.id)) collapsed - d.id else collapsed + d.id
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (d.live || d.online == true) OnlineGreen else OfflineRed))
                                Spacer(Modifier.width(8.dp))
                                Text(d.id, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text("${ws.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(if (collapsed.contains(d.id)) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (!collapsed.contains(d.id)) {
                            items(ws, key = { "${d.id}/${it.id}" }) { w -> WidgetControlRow(w, vm, onOpenChart) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.clip(CircleShape)
            .background(if (selected) accent.copy(alpha = .20f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text("$count", color = if (selected) accent.copy(alpha = .7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun WidgetControlRow(w: Widget, vm: MainViewModel, onOpenChart: (String, String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val isControl = w.kind == WidgetKind.TOGGLE || w.kind == WidgetKind.BTN_SWITCH ||
        w.kind == WidgetKind.SIMPLE_BTN || w.kind == WidgetKind.BTN || w.kind == WidgetKind.POWER_BUTTON
    val canOpen = w.kind == WidgetKind.CHART || (!isControl && w.value?.toDoubleOrNull() != null)
    Column(Modifier.fillMaxWidth().then(if (canOpen) Modifier.clickable { onOpenChart(w.topic, w.descr.ifBlank { w.id }) } else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(w.descr.ifBlank { w.id }, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(w.info, w.typeRaw.ifBlank { null }).joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (w.kind == WidgetKind.CHART && w.points.size >= 2) {
                Sparkline(w.points, Modifier.width(72.dp).height(26.dp))
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.width(8.dp))
            if (w.pending) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = accent)
            Spacer(Modifier.width(6.dp))
            when {
                w.kind == WidgetKind.TOGGLE || (w.kind == WidgetKind.BTN_SWITCH && w.mode != "momentary") ->
                    Switch(checked = w.value == "1", onCheckedChange = { vm.send(w, if (it) "1" else "0") },
                        colors = SwitchDefaults.colors(checkedTrackColor = accent))
                w.kind == WidgetKind.BTN_SWITCH && w.mode == "momentary" ->
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = .18f))
                            .pointerInput(w.topic) { detectTapGestures(onPress = { vm.send(w, "1"); tryAwaitRelease(); vm.send(w, "0") }) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(w.title ?: w.descr.ifBlank { w.id }, color = accent, fontSize = 13.sp) }
                isControl ->
                    Button(onClick = { vm.send(w, w.data ?: "1") },
                        colors = ButtonDefaults.buttonColors(containerColor = iotColor(w.fill ?: w.color) ?: accent),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Text(w.title ?: w.descr.ifBlank { w.id }, fontSize = 13.sp)
                    }
                else -> Text(
                    "${w.before ?: ""}${w.value ?: "—"}${w.after ?: ""}",
                    fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = accent,
                    textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
}
