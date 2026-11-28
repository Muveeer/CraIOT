package com.livecontrol.craiot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livecontrol.craiot.data.BrokerProfile
import com.livecontrol.craiot.data.ProfileValidator
import com.livecontrol.craiot.mqtt.ConnState
import com.livecontrol.craiot.mqtt.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ProfilesScreen(vm: MainViewModel, onPick: (String) -> Unit, onEdit: (String?) -> Unit, onBack: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val state by vm.connState.collectAsStateWithLifecycle()
    val tel by vm.telemetry.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val unknown by vm.unknown.collectAsStateWithLifecycle()
    val dark by vm.dark.collectAsStateWithLifecycle()
    val compact by vm.compact.collectAsStateWithLifecycle()
    val glow by vm.tileGlow.collectAsStateWithLifecycle()
    val active = profiles.find { it.id == activeId }
    var hist by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { hist = vm.historyCount(); delay(10_000) } }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val json = vm.exportProfiles()
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            note = "Экспортировано. Файл содержит креды — храни как секрет."
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            note = if (text != null) "Импортировано профилей: ${vm.importProfiles(text)}" else "Не удалось прочитать файл"
        }
    }

    Scaffold(floatingActionButton = { FloatingActionButton(onClick = { onEdit(null) },
        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Text("+", fontSize = 24.sp) } }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Связь", style = MaterialTheme.typography.titleMedium)
                    Text("подключения, диагностика, оформление", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onBack) { Text("Назад") }
            }
            StatusBanner(state, active?.name,
                onConnect = { activeId?.let { vm.connect(it) } },
                onDisconnect = vm::disconnect, onRetry = vm::retry)
            LazyColumn(Modifier.weight(1f)) {
                item(key = "diag") {
                    SectionHeader("Диагностика", "сводка парка · живые counters сессии и потока")
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatTile("устройства", "${devices.values.count { it.live || it.online == true }}/${devices.size}", "online", OnlineGreen, Modifier.width(104.dp))
                        StatTile("виджеты", "${devices.values.sumOf { d -> d.widgets.values.count { !it.hidden } }}", "видимых", Signal, Modifier.width(96.dp))
                        StatTile("поток", fmtRate(tel.rate), "msg/s", Cyan, Modifier.width(96.dp))
                        StatTile("история", "$hist", "точек", Violet, Modifier.width(104.dp))
                    }
                    KV("брокер", active?.let { "${it.host}:${it.port}" } ?: "—")
                    KV("транспорт", active?.let { "${it.protocol.name.lowercase()}${it.path}" } ?: "—")
                    KV("префикс", active?.prefix ?: "—")
                    KV("сессия", if (tel.sessionStart > 0) fmtDur(now - tel.sessionStart) else "—")
                    KV("переподключений", "${tel.reconnects}")
                    KV("поток", "${fmtRate(tel.rate)} msg/s · всего ${tel.total}")
                    KV("последний пакет", if (tel.lastMsgAt > 0) fmtAge(now - tel.lastMsgAt) + " назад" else "—")
                    KV("устройства", "${devices.values.count { it.live || it.online == true }} online / ${devices.size}")
                    KV("нераспознано", "$unknown")
                    KV("история", "$hist точек · Room 7 дней")
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConsoleChip("HELLO", false) { vm.hello() }
                        ConsoleChip("Переподключить", false) { vm.retry() }
                    }
                }
                item(key = "settings") {
                    SectionHeader("Оформление", "графит / бумага · раскладка плиток — чип «вид» на Обзоре")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConsoleChip(if (dark) "Тёмная" else "Светлая", dark) { vm.toggleDark() }
                        ConsoleChip("Compact", compact) { vm.toggleCompact() }
                        ConsoleChip("Свечение", glow) { vm.toggleGlow() }
                        ConsoleChip("Сброс вида", false) { vm.resetView() }
                    }
                }
                item(key = "profiles-head") {
                    SectionHeader("Подключения", "тап — подключить и сделать активным")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConsoleChip("Экспорт", false) { exportLauncher.launch("craiot-profiles.json") }
                        ConsoleChip("Импорт", false) { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                    }
                    note?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                }
                items(profiles, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        HealthDot(p.id == activeId && state is ConnState.Connected)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name + if (p.id == activeId) " · активный" else "",
                                color = if (p.id == activeId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text("${p.protocol.name.lowercase()}://${p.host}:${p.port}${p.path} · ${p.prefix}",
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onEdit(p.id) }) { Text("Изм.") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
                item(key = "about") {
                    SectionHeader("О приложении")
                    KV("приложение", "CraIOT ${BuildConfig.VERSION_NAME}")
                    KV("автор", "by Serghei Crasnicov")
                    KV("пакет", BuildConfig.APPLICATION_ID)
                    KV("протокол", "IoT Manager ver4 · MQTT 3.1.1")
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun EditScreen(vm: MainViewModel, id: String?, onDone: () -> Unit) {
    var loaded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var protocol by rememberSaveable { mutableStateOf("wss") }
    var host by rememberSaveable { mutableStateOf("live-control.com") }
    var port by rememberSaveable { mutableStateOf("18883") }
    var path by rememberSaveable { mutableStateOf("/ws") }
    var prefix by rememberSaveable { mutableStateOf("/ver407") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPass by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        if (!loaded) {
            if (id != null) {
                val (p, creds) = vm.load(id)
                p?.let {
                    name = it.name; protocol = it.protocol.name.lowercase()
                    host = it.host; port = it.port.toString(); path = it.path; prefix = it.prefix
                }
                username = creds.first; password = creds.second
            }
            loaded = true
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (id == null) "Новый профиль" else "Профиль", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("tcp", "ws", "wss").forEach { p -> FilterChip(selected = protocol == p, onClick = { protocol = p }, label = { Text(p) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, Modifier.weight(2f), label = { Text("Host") }, singleLine = true)
                OutlinedTextField(port, { port = it }, Modifier.weight(1f), label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(path, { path = it }, Modifier.weight(1f), label = { Text("Path") }, singleLine = true)
                OutlinedTextField(prefix, { prefix = it }, Modifier.weight(1f), label = { Text("Prefix") }, singleLine = true)
            }
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Скрыть" else "Показать") } })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val err = ProfileValidator.validate(name, host, port, path.trim(), prefix.trim())
                    if (err != null) { error = err; return@Button }
                    val p = BrokerProfile(id ?: UUID.randomUUID().toString(), name.trim(),
                        Transport.valueOf(protocol.uppercase()), host.trim(), port.toInt(), path.trim(), prefix.trim())
                    vm.save(p, username.trim(), password)
                    onDone()
                }) { Text("Сохранить") }
                TextButton(onClick = onDone) { Text("Отмена") }
                if (id != null) TextButton(onClick = { vm.delete(id); onDone() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
