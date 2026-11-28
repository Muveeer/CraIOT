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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livecontrol.iotmod.protocol.Device
import com.livecontrol.iotmod.protocol.WidgetKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FMT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun ts(at: Long?): String = at?.let { FMT.format(Date(it)) } ?: "—"
private fun isUp(d: Device): Boolean = d.live || d.online == true
private val SYS_RE = Regex("^(ip|up|cr|rssi|heap|fv|bn|bt|bver|wver|upt|time).*", RegexOption.IGNORE_CASE)

@Composable
private fun OnlineBadge(up: Boolean, known: Boolean) {
    val (text, color) = when {
        up -> "online" to OnlineGreen
        known -> "offline" to OfflineRed
        else -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = .12f)) {
        Text(text, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun DevicesScreen(vm: MainViewModel, nav: Nav, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val t = LocalCraTokens.current
    val devices by vm.devices.collectAsStateWithLifecycle()
    val unknown by vm.unknown.collectAsStateWithLifecycle()
    val unknownList by vm.unknownList.collectAsStateWithLifecycle()
    val tel by vm.telemetry.collectAsStateWithLifecycle()
    val aliases by vm.aliases.collectAsStateWithLifecycle()
    val state by vm.connState.collectAsStateWithLifecycle()
    var showUnknown by nav.devShowUnknown
    val list = remember(devices) { devices.values.sortedBy { it.id } }
    val chrome = rememberChrome(nav.barsHidden,
        { nav.devList.firstVisibleItemIndex }, { nav.devList.firstVisibleItemScrollOffset })
    val headerCollapsed = chrome.headerCollapsed.value

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Устройства · ${list.size}", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(if (headerCollapsed) "online ${list.count { isUp(it) }}/${list.size}" else "",
                        style = CraType.meta, color = t.faint, maxLines = 1)
                }
                TextButton(onClick = { vm.hello() }, enabled = state is com.livecontrol.iotmod.mqtt.ConnState.Connected,
                    contentPadding = PaddingValues(6.dp)) { Text("HELLO", fontSize = 12.sp) }
            }
            AnimatedVisibility(visible = !headerCollapsed, enter = expandVertically(tween(160)), exit = shrinkVertically(tween(160))) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("online ${list.count { isUp(it) }} · offline ${list.count { !isUp(it) && it.online == false }} · ? ${list.count { it.online == null && !it.live }} · нераспознано $unknown",
                        style = CraType.rowSub, color = t.dim)
                    if (unknown > 0) TextButton(onClick = { showUnknown = !showUnknown }, contentPadding = PaddingValues(6.dp)) {
                        Text(if (showUnknown) "Скрыть нераспознанные" else "Показать нераспознанные", fontSize = 12.sp)
                    }
                }
            }
            LazyColumn(state = nav.devList, modifier = Modifier.weight(1f).nestedScroll(chrome.connection)) {
                if (showUnknown) items(unknownList, key = { "u:${it.at}:${it.topic}" }) { u ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(u.topic, style = CraType.meta, color = Signal)
                        Text(u.reason, style = CraType.meta, color = MaterialTheme.colorScheme.error)
                        Text(u.payload, style = CraType.meta, color = t.dim, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        HorizontalDivider(Modifier.padding(top = 4.dp))
                    }
                }
                items(list, key = { it.id }) { d ->
                    val ip = d.widgets.values.firstOrNull { it.topic.substringAfterLast('/').startsWith("ip", true) }?.value
                    val up = d.widgets.values.firstOrNull { it.topic.substringAfterLast('/').startsWith("up", true) }?.value
                    Row(Modifier.fillMaxWidth().clickable { onOpen(d.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        HealthDot(isUp(d))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(aliases[d.id] ?: d.id, style = CraType.rowTitle.copy(fontFamily = FontFamily.Monospace), color = t.ink, maxLines = 1)
                            Text("${d.widgets.size} виджетов · ${fmtRate(tel.perDevice[d.id] ?: 0.0)}/s · ${ts(d.lastSeen)}" +
                                (ip?.let { " · $it" } ?: "") + (up?.let { " · $it" } ?: ""),
                                style = CraType.meta, color = t.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OnlineBadge(isUp(d), d.online != null)
                    }
                    HorizontalDivider(color = t.tileBorder)
                }
            }
        }
    }
}

@Composable
fun DeviceScreen(vm: MainViewModel, nav: Nav, id: String, onBack: () -> Unit) {
    val t = LocalCraTokens.current
    val devices by vm.devices.collectAsStateWithLifecycle()
    val aliases by vm.aliases.collectAsStateWithLifecycle()
    val tel by vm.telemetry.collectAsStateWithLifecycle()
    val compact by vm.compact.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val unknownList by vm.unknownList.collectAsStateWithLifecycle()
    val d: Device? = devices[id]
    var tab by rememberSaveable { mutableStateOf("widgets") }
    var alias by rememberSaveable(id) { mutableStateOf(aliases[id] ?: "") }
    var expandedPages by rememberSaveable(id) { mutableStateOf(listOf<String>()) }
    val listState = rememberLazyListState()
    val chrome = rememberChrome(nav.barsHidden,
        { listState.firstVisibleItemIndex }, { listState.firstVisibleItemScrollOffset })
    val headerCollapsed = chrome.headerCollapsed.value

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(aliases[id] ?: id, style = CraType.groupTitle.copy(fontFamily = FontFamily.Monospace), color = t.ink, maxLines = 1)
                    if (aliases[id] != null) Text(id, style = CraType.meta, color = t.faint)
                }
                if (d != null) OnlineBadge(isUp(d), d.online != null)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onBack, contentPadding = PaddingValues(6.dp)) { Text("Назад", fontSize = 12.sp) }
            }
            if (d == null) {
                Text("Устройство не найдено", color = t.dim)
                return@Column
            }
            AnimatedVisibility(visible = !headerCollapsed, enter = expandVertically(tween(160)), exit = shrinkVertically(tween(160))) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConsoleChip("Виджеты", tab == "widgets", d.widgets.size.toLong()) { tab = "widgets" }
                    ConsoleChip("Паспорт", tab == "passport") { tab = "passport" }
                }
            }
            if (tab == "widgets") {
                val groups = remember(d.widgets) {
                    d.widgets.values.sortedWith(consoleWidgetOrder).groupBy { it.page.ifBlank { "Без страницы" } }
                }
                val pinnedWidgets = remember(d.widgets, pins) { d.widgets.values.filter { it.topic in pins }.sortedWith(consoleWidgetOrder) }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).nestedScroll(chrome.connection)) {
                    if (pinnedWidgets.isNotEmpty()) {
                        item(key = "h:pins") {
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(androidx.compose.material.icons.Icons.Default.Star, null,
                                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                                Text("Закреплённое", style = CraType.groupTitle, color = MaterialTheme.colorScheme.tertiary)
                                Text("${pinnedWidgets.size}", style = CraType.meta, color = t.faint)
                            }
                        }
                        items(pinnedWidgets, key = { "p:${it.id}" }) { w ->
                            WidgetConsoleRow(w, vm, compact, true) { vm.togglePin(w.topic) }
                        }
                    }
                    groups.forEach { (page, ws) ->
                        val isOpen = expandedPages.contains(page)
                        item(key = "h:$page") {
                            CollapseHeader(page, ws.size, isOpen,
                                { expandedPages = if (isOpen) expandedPages - page else expandedPages + page })
                        }
                        if (isOpen) items(ws, key = { it.id }) { w ->
                            WidgetConsoleRow(w, vm, compact, w.topic in pins) { vm.togglePin(w.topic) }
                        }
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).nestedScroll(chrome.connection)) {
                    item {
                        val now by vm.now.collectAsStateWithLifecycle()
                        OutlinedTextField(alias, { alias = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Имя устройства (алиас)") })
                        TextButton(onClick = { vm.setAlias(id, alias) }) { Text("Сохранить имя") }
                        KV("device id", d.id)
                        KV("state", if (d.online == true) "online" else if (d.online == false) "offline" else "—")
                        KV("state at", ts(d.stateAt))
                        KV("last seen", ts(d.lastSeen) + " · " + fmtAge(now - (d.lastSeen ?: now)))
                        KV("rate", "${fmtRate(tel.perDevice[d.id] ?: 0.0)} msg/s")
                        KV("widgets", "${d.widgets.size} · скрыто ${d.widgets.values.count { it.hidden }}")
                        KV("announced", if (d.announced) "да" else "нет")
                        Spacer(Modifier.height(8.dp))
                        Text("Системные виджеты", style = CraType.groupTitle, color = MaterialTheme.colorScheme.primary)
                    }
                    val sys = d.widgets.values.filter {
                        SYS_RE.matches(it.topic.substringAfterLast('/')) ||
                            (it.kind == WidgetKind.UNKNOWN && it.value != null && it.value!!.toDoubleOrNull() == null)
                    }
                    items(sys, key = { "s:${it.id}" }) { w ->
                        KV(w.topic.substringAfterLast('/'), "${w.value ?: "—"}${w.after ?: ""}")
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("Нераспознанные по устройству", style = CraType.groupTitle, color = MaterialTheme.colorScheme.primary)
                    }
                    val un = unknownList.filter { it.topic.contains("/$id/") }
                    if (un.isEmpty()) item { Text("нет", style = CraType.rowSub, color = t.dim) }
                    items(un, key = { "u:${it.at}:${it.topic}" }) { u ->
                        Column(Modifier.padding(vertical = 3.dp)) {
                            Text(u.topic, style = CraType.meta, color = Signal)
                            Text(u.reason + " · " + u.payload, style = CraType.meta, color = t.dim,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
