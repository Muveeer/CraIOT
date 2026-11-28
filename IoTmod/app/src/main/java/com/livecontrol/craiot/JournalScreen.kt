package com.livecontrol.craiot

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
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

/**
 * Поток v3: каждый Text с явным цветом из токенов (ничего не сливается с фоном на MIUI),
 * строка ленты дешёвая (~6 composable, contentType для переиспользования слотов),
 * счётчики отдельной строкой — не схлопываются weight'ами на 393 dp.
 */
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
                Text("Поток", style = MaterialTheme.typography.titleMedium, color = t.ink, modifier = Modifier.weight(1f))
                Text("${fmtRate(tel.rate)} msg/s", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Cyan)
                Spacer(Modifier.width(8.dp))
                RateSpark(tel.spark, Modifier.width(90.dp).height(16.dp))
            }
            StatusBanner(state, null, onConnect = { vm.activeId.value?.let { vm.connect(it) } }, onDisconnect = vm::disconnect, onRetry = vm::retry)
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(t.tile).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = t.faint, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (filter.isEmpty()) Text("Фильтр: топик или payload…", fontSize = 12.5.sp, color = t.faint, maxLines = 1)
                    BasicTextField(filter, { filter = it }, singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = t.ink),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth())
                }
                if (filter.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Close, null, tint = t.faint,
                        modifier = Modifier.size(16.dp).clip(CircleShape).clickable { filter = "" })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConsoleChip("все", type == "all", tel.total) { type = "all" }
                listOf("status", "config", "event", "control", "state", "other").forEach { tp ->
                    ConsoleChip(tp, type == tp, tel.byType[tp]) { type = tp }
                }
            }
            Text("${shown.size} / ${entries.size}" + (if (paused) " · ПАУЗА" else ""), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, color = if (paused) Amber else t.faint, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ConsoleChip("авто", follow) { follow = !follow }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { paused = !paused }, contentPadding = PaddingValues(6.dp)) { Text(if (paused) "Пуск" else "Пауза", fontSize = 12.sp) }
                TextButton(onClick = { export() }, contentPadding = PaddingValues(6.dp)) { Text("Экспорт", fontSize = 12.sp) }
                TextButton(onClick = { vm.journal.clear() }, contentPadding = PaddingValues(6.dp)) { Text("Очистить", fontSize = 12.sp) }
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(t.tile)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    items(shown, key = { it.seq }, contentType = { "row" }) { e ->
                        var open by remember(e.seq) { mutableStateOf(false) }
                        val tp = Telemetry.typeOf(e.msg.topic)
                        Column(Modifier.fillMaxWidth().clickable { clipboard.setText(AnnotatedString("${e.msg.topic}\n${e.msg.payload}")) }.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(timeFmt.format(Date(e.msg.at)) + " q${e.msg.qos}", fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, color = t.faint, maxLines = 1)
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
                        Icon(Icons.Default.KeyboardArrowUp, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (newCount > 0) "$newCount новых" else "К ленте", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text("тап — копировать · + — полный payload · R — retained", fontSize = 10.sp, color = t.faint)
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
