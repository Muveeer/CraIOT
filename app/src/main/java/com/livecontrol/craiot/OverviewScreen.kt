package com.livecontrol.craiot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livecontrol.craiot.mqtt.ConnState
import com.livecontrol.craiot.protocol.Device
import com.livecontrol.craiot.protocol.Widget
import com.livecontrol.craiot.protocol.WidgetKind
import kotlinx.coroutines.delay

/** Строка виджета: только имя слева; справа спарклайн/контрол/значение + возраст + ★. Никаких серых подстрок. */
@Composable
fun WidgetConsoleRow(w: Widget, vm: MainViewModel, now: Long, compact: Boolean, pinned: Boolean, onPin: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val clipboard = LocalClipboardManager.current
    var open by remember(w.topic) { mutableStateOf(false) }
    var flash by remember(w.topic) { mutableStateOf(false) }
    LaunchedEffect(w.value) { flash = true; delay(800); flash = false }
    val isControl = w.kind == WidgetKind.TOGGLE || w.kind == WidgetKind.BTN_SWITCH ||
        w.kind == WidgetKind.SIMPLE_BTN || w.kind == WidgetKind.BTN || w.kind == WidgetKind.POWER_BUTTON
    val pad = if (compact) 6.dp else 9.dp
    Column(Modifier.fillMaxWidth().clickable { open = !open }) {
        Row(Modifier.fillMaxWidth().padding(vertical = pad), verticalAlignment = Alignment.CenterVertically) {
            Text(w.descr.ifBlank { w.id }, fontSize = if (compact) 13.sp else 14.sp,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (w.kind == WidgetKind.CHART && w.points.size >= 2) {
                Spacer(Modifier.width(6.dp))
                Sparkline(w.points, Modifier.width(72.dp).height(26.dp))
            }
            Spacer(Modifier.width(8.dp))
            if (w.pending) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = accent)
            Spacer(Modifier.width(4.dp))
            when {
                w.kind == WidgetKind.TOGGLE || (w.kind == WidgetKind.BTN_SWITCH && w.mode != "momentary") ->
                    Switch(checked = w.value == "1", onCheckedChange = { vm.send(w, if (it) "1" else "0") }, colors = SwitchDefaults.colors(checkedTrackColor = accent))
                w.kind == WidgetKind.BTN_SWITCH && w.mode == "momentary" ->
                    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = .18f))
                        .pointerInput(w.topic) { detectTapGestures(onPress = { vm.send(w, "1"); tryAwaitRelease(); vm.send(w, "0") }) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)) { Text(w.title ?: w.descr.ifBlank { w.id }, color = accent, fontSize = 12.sp) }
                isControl ->
                    Button(onClick = { vm.send(w, w.data ?: "1") }, colors = ButtonDefaults.buttonColors(containerColor = iotColor(w.fill ?: w.color) ?: accent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) { Text(w.title ?: w.descr.ifBlank { w.id }, fontSize = 12.sp) }
                else -> Text("${w.before ?: ""}${w.value ?: "—"}${w.after ?: ""}", fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 14.sp else 15.sp, color = if (flash) Mint else accent, textAlign = TextAlign.End, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            }
            Spacer(Modifier.width(6.dp))
            AgeText(w.valueAt, now)
            TextButton(onClick = onPin, contentPadding = PaddingValues(4.dp)) {
                Text(if (pinned) "★" else "☆", color = if (pinned) Amber else dim, fontSize = 14.sp)
            }
        }
        if (open) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(8.dp)) {
                    Row {
                        Text("config", fontSize = 10.sp, color = dim, modifier = Modifier.weight(1f))
                        TextButton(onClick = { clipboard.setText(AnnotatedString("${w.topic}\n${w.rawConfig}\n${w.rawStatus ?: ""}")) },
                            contentPadding = PaddingValues(4.dp)) { Text("копировать", fontSize = 10.sp) }
                    }
                    Text(w.rawConfig.ifBlank { "—" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text("status", fontSize = 10.sp, color = dim)
                    Text(w.rawStatus ?: "—", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
}

@Composable
private fun DeviceHeader(d: Device, alias: String?, now: Long, collapsed: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HealthDot(d.live || d.online == true)
        Spacer(Modifier.width(8.dp))
        Text(alias ?: d.id, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        AgeText(d.lastSeen, now)
        Spacer(Modifier.width(8.dp))
        Text("${d.widgets.size}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = dim)
        TextButton(onClick = onOpen, contentPadding = PaddingValues(4.dp)) { Text("→", fontSize = 13.sp) }
        Icon(if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, tint = dim, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun OverviewScreen(vm: MainViewModel, nav: Nav, onOpenChart: (String, String) -> Unit, onOpenDevice: (String) -> Unit) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val tel by vm.telemetry.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val compact by vm.compact.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val aliases by vm.aliases.collectAsStateWithLifecycle()
    val dark by vm.dark.collectAsStateWithLifecycle()
    val state by vm.connState.collectAsStateWithLifecycle()
    var page by nav.dashPage
    var query by nav.dashQuery
    var collapsed by nav.dashCollapsed
    var hist by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { hist = vm.historyCount(); delay(5000) } }

    val all = remember(devices) { devices.values.flatMap { d -> d.widgets.values.filter { !it.hidden }.map { d to it } } }
    val pages = remember(all) {
        all.groupBy { it.second.page.ifBlank { "Без страницы" } }
            .map { (n, ws) -> Triple(n, ws.mapNotNull { it.second.order ?: it.second.pageId }.minOrNull() ?: Int.MAX_VALUE, ws.size) }
            .sortedWith(compareBy({ it.second }, { it.first }))
    }
    val pinnedWidgets = remember(all, pins) { all.filter { it.second.topic in pins } }
    val searchResults = remember(all, query) {
        if (query.isBlank()) emptyList()
        else all.filter { (d, w) -> w.descr.contains(query, true) || w.id.contains(query, true) || w.topic.contains(query, true) || d.id.contains(query, true) }
    }
    val groups = remember(devices, page, query) {
        if (query.isNotBlank()) emptyList()
        else devices.values.sortedBy { it.id }.mapNotNull { d ->
            val ws = d.widgets.values.filter { !it.hidden }
                .filter { page.isEmpty() || it.page.ifBlank { "Без страницы" } == page }
                .sortedWith(consoleWidgetOrder)
            if (ws.isEmpty()) null else d to ws
        }
    }
    val online = devices.values.count { it.live || it.online == true }
    val allCollapsed = groups.isNotEmpty() && groups.all { collapsed.contains(it.first.id) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Обзор", style = MaterialTheme.typography.titleMedium)
                    Text("by Serghei Crasnicov", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { collapsed = if (allCollapsed) listOf() else devices.keys.toList() }, contentPadding = PaddingValues(6.dp)) {
                    Text(if (allCollapsed) "⊞" else "⊟", fontSize = 18.sp)
                }
                TextButton(onClick = { vm.hello() }, enabled = state is ConnState.Connected, contentPadding = PaddingValues(6.dp)) { Text("HELLO", fontSize = 12.sp) }
                ConsoleChip("compact", compact) { vm.toggleCompact() }
                TextButton(onClick = { vm.toggleDark() }, contentPadding = PaddingValues(6.dp)) { Text(if (dark) "☀" else "☾") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("устройства", "$online/${devices.size}", "online", OnlineGreen, Modifier.width(104.dp))
                StatTile("виджеты", "${all.size}", "видимых", Signal, Modifier.width(96.dp))
                StatTile("поток", fmtRate(tel.rate), "msg/s", Cyan, Modifier.width(96.dp))
                StatTile("история", "$hist", "точек", Violet, Modifier.width(104.dp))
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Поиск: имя, топик, устройство…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, null) } })
            if (query.isBlank()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConsoleChip("Все", page.isEmpty(), all.size.toLong()) { page = "" }
                    pages.forEach { (n, _, c) -> ConsoleChip(n, page == n, c.toLong()) { page = n } }
                }
            }
            LazyColumn(state = nav.dashList, modifier = Modifier.weight(1f)) {
                if (query.isNotBlank()) {
                    item { Text("${searchResults.size} совпадений", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(searchResults, key = { "s:${it.first.id}/${it.second.id}" }) { (d, w) ->
                        WidgetConsoleRow(w, vm, now, compact, w.topic in pins) { vm.togglePin(w.topic) }
                    }
                } else {
                    if (pinnedWidgets.isNotEmpty()) {
                        item(key = "h:pins") { Text("Закреплённое", fontSize = 12.sp, color = Amber, modifier = Modifier.padding(vertical = 4.dp)) }
                        items(pinnedWidgets, key = { "p:${it.first.id}/${it.second.id}" }) { (d, w) ->
                            WidgetConsoleRow(w, vm, now, true, true) { vm.togglePin(w.topic) }
                        }
                    }
                    groups.forEach { (d, ws) ->
                        item(key = "h:${d.id}") {
                            DeviceHeader(d, aliases[d.id], now, collapsed.contains(d.id),
                                { collapsed = if (collapsed.contains(d.id)) collapsed - d.id else collapsed + d.id },
                                { onOpenDevice(d.id) })
                        }
                        if (!collapsed.contains(d.id)) {
                            items(ws, key = { "${d.id}/${it.id}" }) { w ->
                                WidgetConsoleRow(w, vm, now, compact, w.topic in pins) { vm.togglePin(w.topic) }
                            }
                        }
                    }
                }
            }
        }
    }
}
