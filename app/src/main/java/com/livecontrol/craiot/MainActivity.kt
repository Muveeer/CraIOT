package com.livecontrol.craiot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.livecontrol.craiot.mqtt.ConnState

class Nav(
    val dashList: LazyListState, val dashPage: MutableState<String>, val dashQuery: MutableState<String>, val dashCollapsed: MutableState<List<String>>,
    val chartsList: LazyListState, val chartsPage: MutableState<String>, val chartsType: MutableState<String>, val chartsQuery: MutableState<String>, val chartsCollapsed: MutableState<List<String>>,
    val devList: LazyListState, val devShowUnknown: MutableState<Boolean>,
    val journalList: LazyListState, val journalFilter: MutableState<String>, val journalPaused: MutableState<Boolean>, val journalFollow: MutableState<Boolean>, val journalAnchor: MutableState<Long>,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            val dark by vm.dark.collectAsStateWithLifecycle()
            CraIOTTheme(dark) { AppRoot(vm) }
        }
    }
}

/** Одна slim-строка связи. Полоса потока (msg/s + гистограмма) убрана из шапки. */
@Composable
fun TelemetryBar(vm: MainViewModel) {
    val st by vm.connState.collectAsStateWithLifecycle()
    val tel by vm.telemetry.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val unknown by vm.unknown.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val p = profiles.find { it.id == activeId }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HealthDot(st is ConnState.Connected)
        Text(p?.let { "${it.host} · ${it.protocol.name.lowercase()} · ${it.prefix}" } ?: "нет профиля",
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = dim, modifier = Modifier.weight(1f), maxLines = 1)
        Text(if (tel.sessionStart > 0) fmtDur(now - tel.sessionStart) else "—", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = dim)
        if (tel.reconnects > 0) Text("↻${tel.reconnects}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Amber)
        if (unknown > 0) Text("?$unknown", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Red)
    }
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var screen by rememberSaveable { mutableStateOf("overview") }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var chartTopic by rememberSaveable { mutableStateOf("") }
    var chartTitle by rememberSaveable { mutableStateOf("") }
    var chartBack by rememberSaveable { mutableStateOf("overview") }
    val nav = Nav(
        dashList = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
        dashPage = rememberSaveable { mutableStateOf("") },
        dashQuery = rememberSaveable { mutableStateOf("") },
        dashCollapsed = rememberSaveable { mutableStateOf(listOf()) },
        chartsList = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
        chartsPage = rememberSaveable { mutableStateOf("") },
        chartsType = rememberSaveable { mutableStateOf("all") },
        chartsQuery = rememberSaveable { mutableStateOf("") },
        chartsCollapsed = rememberSaveable { mutableStateOf(listOf()) },
        devList = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
        devShowUnknown = rememberSaveable { mutableStateOf(false) },
        journalList = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
        journalFilter = rememberSaveable { mutableStateOf("") },
        journalPaused = rememberSaveable { mutableStateOf(false) },
        journalFollow = rememberSaveable { mutableStateOf(true) },
        journalAnchor = rememberSaveable { mutableStateOf(-1L) },
    )
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TelemetryBar(vm)
        Box(Modifier.weight(1f)) {
            when (screen) {
                "charts" -> ChartsListScreen(vm, nav, onOpen = { t, title -> chartTopic = t; chartTitle = title; chartBack = "charts"; screen = "chart" })
                "stream" -> JournalScreen(vm, nav)
                "devices" -> DevicesScreen(vm, nav, onOpen = { deviceId = it; screen = "device" }, onBack = { screen = "overview" })
                "device" -> DeviceScreen(vm, deviceId, onBack = { screen = "devices" })
                "chart" -> ChartScreen(vm, chartTopic, chartTitle, onBack = { screen = chartBack })
                "profiles" -> ProfilesScreen(vm, onPick = { vm.connect(it); screen = "overview" }, onEdit = { editId = it; screen = "edit" }, onBack = { screen = "overview" })
                "edit" -> EditScreen(vm, editId, onDone = { screen = "profiles" })
                else -> OverviewScreen(vm, nav,
                    onOpenChart = { t, title -> chartTopic = t; chartTitle = title; chartBack = "overview"; screen = "chart" },
                    onOpenDevice = { deviceId = it; screen = "device" })
            }
        }
        NavigationBar {
            NavigationBarItem(selected = screen == "overview", onClick = { screen = "overview" },
                icon = { Icon(Icons.Default.Home, null) }, label = { Text("Обзор", fontSize = 10.sp, maxLines = 1, softWrap = false) })
            NavigationBarItem(selected = screen == "devices" || screen == "device", onClick = { screen = "devices" },
                icon = { Icon(Icons.Default.Build, null) }, label = { Text("Устройства", fontSize = 10.sp, maxLines = 1, softWrap = false) })
            NavigationBarItem(selected = screen == "stream", onClick = { screen = "stream" },
                icon = { Icon(Icons.Default.List, null) }, label = { Text("Поток", fontSize = 10.sp, maxLines = 1, softWrap = false) })
            NavigationBarItem(selected = screen == "charts" || screen == "chart", onClick = { screen = "charts" },
                icon = { Text("📈", fontSize = 18.sp) }, label = { Text("Графики", fontSize = 10.sp, maxLines = 1, softWrap = false) })
            NavigationBarItem(selected = screen == "profiles" || screen == "edit", onClick = { screen = "profiles" },
                icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Связь", fontSize = 10.sp, maxLines = 1, softWrap = false) })
        }
    }
}
