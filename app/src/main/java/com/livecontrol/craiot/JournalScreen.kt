package com.livecontrol.craiot

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livecontrol.craiot.mqtt.ConnState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JournalScreen(vm: MainViewModel, nav: Nav) {
    val state by vm.connState.collectAsStateWithLifecycle()
    val entries by vm.journal.items.collectAsStateWithLifecycle()
    val tel by vm.telemetry.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
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
                if (scrolling) { wasScrolling = true; if (idx > 0 || off > 0) follow = false }
                else if (wasScrolling) { wasScrolling = false; if (idx == 0 && off == 0) follow = true }
            }
    }
    LaunchedEffect(shown, follow) { if (follow && shown.isNotEmpty() && !listState.isScrollInProgress) listState.scrollToItem(0) }
    LaunchedEffect(follow) { if (!follow) anchorSeq = entries.firstOrNull()?.seq ?: -1L }
    LaunchedEffect(filter, type) { listState.scrollToItem(0) }
    val newCount by remember { derivedStateOf { if (follow) 0 else shown.count { it.seq > anchorSeq } } }

    fun export() {
        val sb = StringBuilder()
        shown.take(800).asReversed().forEach { e ->
            sb.append(timeFmt.format(Date(e.msg.at))).append(if (e.msg.retained) " R " else "   ").append(e.msg.topic).append('\n').append(e.msg.payload).append("\n\n")
        }
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sb.toString()) }, "Экспорт потока"))
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Поток", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${fmtRate(tel.rate)} msg/s", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Signal)
                Spacer(Modifier.width(8.dp))
                RateSpark(tel.spark, Modifier.width(90.dp).height(16.dp))
            }
            StatusBanner(state, null, onConnect = { vm.activeId.value?.let { vm.connect(it) } }, onDisconnect = vm::disconnect, onRetry = vm::retry)
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Фильтр: топик или payload…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton({ filter = "" }) { Icon(Icons.Default.Close, null) } })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConsoleChip("все", type == "all", tel.total) { type = "all" }
                listOf("status", "config", "event", "control", "state", "other").forEach { t ->
                    ConsoleChip(t, type == t, tel.byType[t]) { type = t }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${shown.size} / ${entries.size}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (paused) Text("ПАУЗА", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                ConsoleChip("авто", follow) { follow = !follow }
                TextButton(onClick = { paused = !paused }) { Text(if (paused) "Пуск" else "Пауза") }
                TextButton(onClick = { export() }) { Text("Экспорт") }
                TextButton(onClick = { vm.journal.clear() }) { Text("Очистить") }
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(shown, key = { it.seq }) { e ->
                        var open by remember(e.seq) { mutableStateOf(false) }
                        val t = Telemetry.typeOf(e.msg.topic)
                        Column(Modifier.fillMaxWidth().clickable { clipboard.setText(AnnotatedString("${e.msg.topic}\n${e.msg.payload}")) }.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(timeFmt.format(Date(e.msg.at)), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TypeBadge(t)
                                if (e.msg.retained) Text("R", fontSize = 9.sp, color = Amber, fontWeight = FontWeight.Bold)
                                Text("q${e.msg.qos}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(e.msg.topic, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = typeColor(t),
                                    maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(2.dp)) { Text(if (open) "−" else "+", fontSize = 12.sp) }
                            }
                            Text(e.msg.payload, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface,
                                maxLines = if (open) 20 else 2, overflow = TextOverflow.Ellipsis)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                        }
                    }
                }
                if (shown.isEmpty()) Text(if (filter.isBlank() && type == "all") "Ждём сообщения…" else "Ничего не найдено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
                if (!follow) Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).clip(CircleShape).background(Signal)
                    .clickable { follow = true }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.KeyboardArrowUp, null, tint = Color(0xFF081018), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (newCount > 0) "$newCount новых" else "К ленте", color = Color(0xFF081018), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text("тап — копировать · + — полный payload · R — retained", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatusBanner(state: ConnState, name: String?, onConnect: () -> Unit, onDisconnect: () -> Unit, onRetry: () -> Unit) {
    val text: String; val color: Color
    when (state) {
        is ConnState.Connected -> { text = "Подключено" + (name?.let { " · $it" } ?: ""); color = OnlineGreen }
        is ConnState.Connecting -> { text = "Подключение…"; color = MaterialTheme.colorScheme.onSurfaceVariant }
        is ConnState.Reconnecting -> { text = "Переподключение · попытка ${state.attempt} · ${state.delayMs / 1000} с"; color = Amber }
        is ConnState.Error -> { text = state.message; color = MaterialTheme.colorScheme.error }
        else -> { text = "Отключено"; color = MaterialTheme.colorScheme.onSurfaceVariant }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f))
        when (state) {
            is ConnState.Connected, is ConnState.Connecting, is ConnState.Reconnecting -> Button(onClick = onDisconnect) { Text("Отключить") }
            is ConnState.Error -> Button(onClick = onRetry) { Text("Повторить") }
            else -> Button(onClick = onConnect) { Text("Подключить") }
        }
    }
}
