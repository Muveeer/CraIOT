package com.livecontrol.iotmod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livecontrol.iotmod.mqtt.ConnState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Поток v4: шапка сворачивается как в Обзоре, нижний бар прячется при скролле. */
@Composable
fun JournalScreen(vm: MainViewModel, nav: Nav) {
    val t = LocalCraTokens.current
    val state by vm.connState.collectAsStateWithLifecycle()
    val entries by vm.journalFeed.collectAsStateWithLifecycle()
    val tel by vm.telemetry.collectAsStateWithLifecycle()
    var filter by nav.journalFilter
    var paused by nav.journalPaused
    var follow by nav.journalFollow
    var anchorSeq by nav.journalAnchor
    var type by rememberSaveable { mutableStateOf("all") }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val listState = nav.journalList
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    LaunchedEffect(paused) { vm.journal.setPaused(paused) }

    val chrome = rememberChrome(nav.barsHidden,
        { listState.firstVisibleItemIndex }, { listState.firstVisibleItemScrollOffset })
    val headerCollapsed = chrome.headerCollapsed.value

    val shown = remember(entries, filter, type) {
        entries.filter { e ->
            (type == "all" || Telemetry.typeOf(e.msg.topic) == type) &&
                (filter.isBlank() || e.msg.topic.contains(filter, true) || e.msg.payload.contains(filter, true))
        }
    }
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (scrolling, idx, off) ->
                if (scrolling) {
                    wasScrolling = true
                    if (idx > 0 || off > 0) follow = false
                } else if (wasScrolling) {
                    wasScrolling = false
                    if (idx == 0 && off == 0) follow = true
                }
            }
    }
    LaunchedEffect(shown, follow) { if (follow && shown.isNotEmpty() && !listState.isScrollInProgress) listState.scrollToItem(0) }
    LaunchedEffect(follow) { if (!follow) anchorSeq = entries.firstOrNull()?.seq ?: -1L }
    LaunchedEffect(filter, type) { listState.scrollToItem(0) }
    val newCount by remember { derivedStateOf { if (follow) 0 else shown.count { it.seq > anchorSeq } } }

    fun export() {
        val sb = StringBuilder()
        shown.take(800).asReversed().forEach { e ->
            sb.append(timeFmt.format(Date(e.msg.at))).append(if (e.msg.retained) " R " else "   ").append(e.msg.topic)
                .append('\n').append(e.msg.payload).append("\n\n")
        }
        context.startActivity(android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, sb.toString())
            }, "Экспорт потока"))
    }

    val searchVisible = !headerCollapsed || filter.isNotBlank()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Поток", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(if (headerCollapsed) "${shown.size}/${entries.size} · ${fmtRate(tel.rate)} msg/s" else "",
                        style = CraType.rowSub, color = t.faint, maxLines = 1)
                }
                Text("${fmtRate(tel.rate)} msg/s", style = CraType.meta, color = Cyan)
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { paused = !paused }, contentPadding = PaddingValues(6.dp)) { Text(if (paused) "Пуск" else "Пауза", fontSize = 12.sp) }
                TextButton(onClick = { export() }, contentPadding = PaddingValues(6.dp)) { Text("Экспорт", fontSize = 12.sp) }
                TextButton(onClick = { vm.journal.clear() }, contentPadding = PaddingValues(6.dp)) { Text("Очистить", fontSize = 12.sp) }
            }
            AnimatedVisibility(visible = searchVisible, enter = expandVertically(tween(160)), exit = shrinkVertically(tween(160))) {
                CraSearchField(filter, { filter = it }, "Фильтр: топик или payload…")
            }
            AnimatedVisibility(visible = !headerCollapsed, enter = expandVertically(tween(160)), exit = shrinkVertically(tween(160))) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBanner(state, null,
                        onConnect = { vm.activeId.value?.let { vm.connect(it) } },
                        onDisconnect = vm::disconnect, onRetry = vm::retry)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConsoleChip("все", type == "all", tel.total) { type = "all" }
                        listOf("status", "config", "event", "control", "state", "other").forEach { tp ->
                            ConsoleChip(tp, type == tp, tel.byType[tp]) { type = tp }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConsoleChip("авто", follow) { follow = !follow }
                        Spacer(Modifier.weight(1f))
                        Text("${shown.size} / ${entries.size}" + (if (paused) " · ПАУЗА" else ""), style = CraType.meta,
                            color = if (paused) Amber else t.faint, maxLines = 1)
                    }
                    Text("тап — копировать · + — полный payload · R — retained", style = CraType.meta, color = t.faint)
                }
            }
            Box(Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(t.tile)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp).nestedScroll(chrome.connection)) {
                    items(shown, key = { it.seq }, contentType = { "row" }) { e ->
                        var open by remember(e.seq) { mutableStateOf(false) }
                        val tp = Telemetry.typeOf(e.msg.topic)
                        Column(Modifier.fillMaxWidth().clickable {
                            clipboard.setText(AnnotatedString("${e.msg.topic}\n${e.msg.payload}"))
                        }.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(timeFmt.format(Date(e.msg.at)) + " q${e.msg.qos}", fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace, color = t.faint, maxLines = 1)
                                TypeBadge(tp)
                                if (e.msg.retained) Text("R", fontSize = 9.sp, color = Amber, fontWeight = FontWeight.Bold)
                                Text(e.msg.topic, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = typeColor(tp),
                                    maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(if (open) "−" else "+", fontSize = 13.sp, color = t.dim,
                                    modifier = Modifier.clip(CircleShape).clickable { open = !open }.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                            Text(e.msg.payload, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = t.ink,
                                lineHeight = 15.sp, maxLines = if (open) 20 else 2, overflow = TextOverflow.Ellipsis)
                            HorizontalDivider(color = t.tileBorder)
                        }
                    }
                }
                if (shown.isEmpty()) Text(if (filter.isBlank() && type == "all") "Ждём сообщения…" else "Ничего не найдено",
                    color = t.dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                if (!follow) Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { follow = true }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowUp, null,
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (newCount > 0) "$newCount новых" else "К ленте", color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBanner(state: ConnState, name: String?, onConnect: () -> Unit, onDisconnect: () -> Unit, onRetry: () -> Unit) {
    val text: String
    val color: androidx.compose.ui.graphics.Color
    when (state) {
        is ConnState.Connected -> { text = "Подключено" + (name?.let { " · $it" } ?: ""); color = OnlineGreen }
        is ConnState.Connecting -> { text = "Подключение…"; color = MaterialTheme.colorScheme.onSurfaceVariant }
        is ConnState.Reconnecting -> { text = "Переподключение · попытка ${state.attempt} · ${state.delayMs / 1000} с"; color = Amber }
        is ConnState.Error -> { text = state.message; color = MaterialTheme.colorScheme.error }
        else -> { text = "Отключено"; color = MaterialTheme.colorScheme.onSurfaceVariant }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f))
        when (state) {
            is ConnState.Connected, is ConnState.Connecting, is ConnState.Reconnecting -> Button(onClick = onDisconnect) { Text("Отключить") }
            is ConnState.Error -> Button(onClick = onRetry) { Text("Повторить") }
            else -> Button(onClick = onConnect) { Text("Подключить") }
        }
    }
}
