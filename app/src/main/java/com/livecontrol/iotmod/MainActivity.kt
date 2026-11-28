package com.livecontrol.iotmod

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class Nav(
    val dashList: LazyGridState, val dashPage: MutableState<String>, val dashQuery: MutableState<String>,
    val dashCollapsed: MutableState<List<String>>,
    val dashExpanded: MutableState<List<String>>,
    val chartsList: LazyListState, val chartsPage: MutableState<String>, val chartsType: MutableState<String>, val chartsQuery: MutableState<String>, val chartsCollapsed: MutableState<List<String>>,
    val devList: LazyListState, val devShowUnknown: MutableState<Boolean>,
    val journalList: LazyListState, val journalFilter: MutableState<String>, val journalPaused: MutableState<Boolean>, val journalFollow: MutableState<Boolean>, val journalAnchor: MutableState<Long>,
    val barsHidden: MutableState<Boolean>,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val themeId by vm.themeId.collectAsStateWithLifecycle()
            IoTmodTheme(themeId) {
                val dark = LocalCraTokens.current.dark
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as Activity).window
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                }
                AppRoot(vm)
            }
        }
    }
}

@Composable
private fun ChartGlyph(active: Boolean) {
    val c = if (active) MaterialTheme.colorScheme.primary else LocalCraTokens.current.dim
    Canvas(Modifier.size(22.dp)) {
        val bw = size.width / 5.4f
        listOf(.45f, .72f, 1f).forEachIndexed { i, h ->
            drawRoundRect(
                c.copy(alpha = .55f + .45f * h),
                topLeft = Offset(bw * (i * 1.7f + .3f), size.height * (1 - h)),
                size = Size(bw, size.height * h),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel) {
    val t = LocalCraTokens.current
    var screen by rememberSaveable { mutableStateOf("overview") }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var chartTopic by rememberSaveable { mutableStateOf("") }
    var chartTitle by rememberSaveable { mutableStateOf("") }
    var chartBack by rememberSaveable { mutableStateOf("overview") }
    val nav = Nav(
        dashList = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() },
        dashPage = rememberSaveable { mutableStateOf("") },
        dashQuery = rememberSaveable { mutableStateOf("") },
        dashCollapsed = rememberSaveable { mutableStateOf(listOf()) },
        dashExpanded = rememberSaveable { mutableStateOf(listOf()) },
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
        barsHidden = rememberSaveable { mutableStateOf(false) },
    )
    LaunchedEffect(screen) { nav.barsHidden.value = false }
    BackHandler(enabled = screen != "overview") {
        screen = when (screen) {
            "chart" -> chartBack
            "device" -> "devices"
            "edit" -> "profiles"
            else -> "overview"
        }
    }
    val navSlide by animateFloatAsState(if (nav.barsHidden.value) 1f else 0f, tween(200), label = "navSlide")
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .statusBarsPadding()
    ) {
        Box(Modifier.weight(1f)) {
            when (screen) {
                "charts" -> ChartsListScreen(vm, nav, onOpen = { topic, title -> chartTopic = topic; chartTitle = title; chartBack = "charts"; screen = "chart" })
                "stream" -> JournalScreen(vm, nav)
                "devices" -> DevicesScreen(vm, nav, onOpen = { deviceId = it; screen = "device" }, onBack = { screen = "overview" })
                "device" -> DeviceScreen(vm, nav, deviceId, onBack = { screen = "devices" })
                "chart" -> ChartScreen(vm, chartTopic, chartTitle, onBack = { screen = chartBack })
                "profiles" -> ProfilesScreen(vm, nav, onPick = { vm.connect(it); screen = "overview" }, onEdit = { editId = it; screen = "edit" }, onBack = { screen = "overview" })
                "edit" -> EditScreen(vm, editId, onDone = { screen = "profiles" })
                else -> OverviewScreen(vm, nav,
                    onOpenChart = { topic, title -> chartTopic = topic; chartTitle = title; chartBack = "overview"; screen = "chart" },
                    onOpenDevice = { deviceId = it; screen = "device" })
            }
        }
        Box(Modifier.graphicsLayer { translationY = size.height * navSlide; alpha = 1f - navSlide }) {
            val itemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .16f),
                unselectedIconColor = t.dim, unselectedTextColor = t.dim,
            )
            NavigationBar(containerColor = t.nav, tonalElevation = 0.dp) {
                NavigationBarItem(selected = screen == "overview", onClick = { screen = "overview" }, colors = itemColors,
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text("Обзор", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                NavigationBarItem(selected = screen == "devices" || screen == "device", onClick = { screen = "devices" }, colors = itemColors,
                    icon = { Icon(Icons.Default.Build, null) }, label = { Text("Устройства", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                NavigationBarItem(selected = screen == "stream", onClick = { screen = "stream" }, colors = itemColors,
                    icon = { Icon(Icons.Default.List, null) }, label = { Text("Поток", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                NavigationBarItem(selected = screen == "charts" || screen == "chart", onClick = { screen = "charts" }, colors = itemColors,
                    icon = { ChartGlyph(screen == "charts" || screen == "chart") }, label = { Text("Графики", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                NavigationBarItem(selected = screen == "profiles" || screen == "edit", onClick = { screen = "profiles" }, colors = itemColors,
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Связь", fontSize = 10.sp, maxLines = 1, softWrap = false) })
            }
        }
    }
}
