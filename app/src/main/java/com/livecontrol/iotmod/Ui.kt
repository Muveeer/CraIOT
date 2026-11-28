package com.livecontrol.iotmod

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Дизайн-язык «Тихая консоль» v2.
 *
 * Тёмная тема — НЕЙТРАЛЬНЫЙ графит (#0F0F12…#202027), без синего подтона и без OLED-чёрного.
 * Светлая тема «Бумага» — сохранена 1:1 (она принята).
 * Акцент тёмной темы — тёплый янтарь (приборный, Teenage Engineering / Braun);
 * семантика warn ушла в оранжевый, чтобы не спорить с акцентом.
 *
 * Референсы (что откуда взято):
 *  - Apple HIG · Widgets — системные размеры S/M/L → таксономия плиток S/M/L/C/W;
 *  - PlayStation 5 · Control Center — матовые слои, фокус-подсветка边框 вместо теней «парения»;
 *  - Home Assistant · Mushroom (github.com/piitaya/lovelace-mushroom) — автоклассификация + ручной оверрайд размера;
 *  - Grafana · Stat panel — большое табличное число + спарклайн + зоны цветом;
 *  - Material 3 · Window size classes — колонки 2…6 от ширины окна;
 *  - Linear / Things 3 — один акцент, движение 200–250 мс, значения всегда чернилами.
 *
 * Правила: значение НИКОГДА не красится в семантический цвет; «свежесть» — только кольцо/точка ≤ 2 с.
 */

// ── Именованные цвета (совместимы со всеми экранами; Signal успокоен до стали) ──
val OnlineGreen = Color(0xFF5BD69A)
val OfflineRed = Color(0xFFF26D6D)
val Signal = Color(0xFF7C92AC)   // «Сталь»: legacy-значения на старых экранах больше не кричат синим
val Cyan = Color(0xFF53D6E8)
val Mint = Color(0xFF5BD69A)
val Amber = Color(0xFFE3B264)    // акцент тёмной темы
val Orange = Color(0xFFE08A3C)   // семантический warn (янтарь отдан акценту)
val Red = Color(0xFFF26D6D)
val Violet = Color(0xFFB48EFA)

/** Токены поверхностей/состояний, зависящие от темы. Числа данных — всегда ink. */
data class CraTokens(
    val ink: Color, val dim: Color, val faint: Color,
    val tile: Color, val tileHigh: Color, val tileBorder: Color,
    val ok: Color, val warn: Color, val bad: Color,
    val nav: Color, val radius: Dp, val gap: Dp,
)

private val GraphiteTokens = CraTokens(
    ink = Color(0xFFF4F5F7), dim = Color(0xFF9BA1AB), faint = Color(0xFF6E747E),
    tile = Color(0xFF17181D), tileHigh = Color(0xFF1D1F25), tileBorder = Color(0xFF2A2D34),
    ok = OnlineGreen, warn = Orange, bad = Red,
    nav = Color(0xFF101115), radius = 18.dp, gap = 10.dp,
)
private val PaperTokens = CraTokens(
    ink = Color(0xFF141A22), dim = Color(0xFF51607A), faint = Color(0xFF8B96A8),
    tile = Color(0xFFFFFFFF), tileHigh = Color(0xFFF7F9FC), tileBorder = Color(0xFFE2E7EF),
    ok = Color(0xFF1E8E5A), warn = Color(0xFF9A6B00), bad = Color(0xFFC62828),
    nav = Color(0xFFFFFFFF), radius = 18.dp, gap = 10.dp,
)

val LocalCraTokens = staticCompositionLocalOf { GraphiteTokens }

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4F5F7), onPrimary = Color(0xFF15161A),
    secondary = Color(0xFFC7CBD4), onSecondary = Color(0xFF17181C), tertiary = Amber,
    background = Color(0xFF0C0D10), onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF121317), onSurface = Color(0xFFF4F5F7),
    surfaceVariant = Color(0xFF1B1D23), onSurfaceVariant = Color(0xFF9BA1AB),
    outline = Color(0xFF2A2D34), outlineVariant = Color(0xFF22242B),
    error = Red, surfaceTint = Color.Transparent,
)

// «Бумага» — без изменений, принята пользователем.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FDE), onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF1E8E5A), tertiary = Color(0xFF9A6B00),
    background = Color(0xFFF4F6F9), onBackground = Color(0xFF141A22),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF141A22),
    surfaceVariant = Color(0xFFE8EDF4), onSurfaceVariant = Color(0xFF51607A),
    outline = Color(0xFFC9D3E0), outlineVariant = Color(0xFFDDE5EE),
    error = Color(0xFFC62828), surfaceTint = Color.Transparent,
)

@Composable
fun IoTmodTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCraTokens provides if (dark) GraphiteTokens else PaperTokens) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}

/** Табличные цифры для показаний (Grafana stat): tnum + лёгкий трекинг. */
fun craNumStyle(size: TextUnit, color: Color, weight: FontWeight = FontWeight.SemiBold): TextStyle =
    TextStyle(fontSize = size, fontWeight = weight, color = color, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)

/** Маппинг цветов прошивки: это ДАННЫЕ устройства, не хром интерфейса. */
fun iotColor(s: String?): Color? {
    if (s.isNullOrBlank()) return null
    val t = s.trim()
    if (t.startsWith("#")) return runCatching { Color(android.graphics.Color.parseColor(t)) }.getOrNull()
    return when (t.lowercase()) {
        "blue", "calm", "positive" -> Color(0xFF82AAFF)
        "green", "balanced", "secondary" -> Mint
        "red", "assertive", "danger" -> Red
        "orange", "energized", "warning" -> Orange
        "yellow" -> Color(0xFFE0AF68)
        "purple", "royal" -> Violet
        "white", "light" -> Color(0xFFD6DEEB)
        "black", "dark", "stable" -> Color(0xFF565F89)
        else -> null
    }
}

fun typeColor(t: String): Color = when (t) {
    "status" -> Mint; "config" -> Signal; "event" -> Violet; "control" -> Amber; "state" -> Cyan; else -> Color(0xFF6B7280)
}
fun fmtAge(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when { s < 60 -> "$s с"; s < 3600 -> "${s / 60} мин"; s < 86400 -> "${s / 3600} ч"; else -> "${s / 86400} д" }
}
fun fmtRate(d: Double): String = if (d >= 10) String.format(Locale.US, "%.0f", d) else String.format(Locale.US, "%.1f", d)
fun fmtDur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "${h}ч ${m}м" else if (m > 0) "${m}м ${ss}с" else "${ss}с"
}

@Composable
fun HealthDot(up: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(if (up) OnlineGreen else OfflineRed))
}

@Composable
fun RateSpark(values: List<Float>, modifier: Modifier = Modifier, color: Color = Signal) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val bw = size.width / 60f
        values.forEachIndexed { i, v ->
            val h = (v.coerceIn(0f, 1f) * size.height).coerceAtLeast(1f)
            val x = size.width - bw * (values.size - i)
            drawRect(color.copy(alpha = .3f + .7f * v), topLeft = Offset(x, size.height - h), size = Size(bw * .72f, h))
        }
    }
}

@Composable
fun TypeBadge(t: String) {
    val c = typeColor(t)
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = .16f)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(t, color = c, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun AgeText(at: Long?, now: Long, modifier: Modifier = Modifier) {
    Text(at?.let { fmtAge(now - it) } ?: "—", modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
}

/** Секундный тик читается ЛОКАЛЬНО: recompose'ится только этот Text, а не плитка/экран. */
@Composable
fun LiveAge(at: Long?, vm: MainViewModel, modifier: Modifier = Modifier, color: Color = LocalCraTokens.current.faint) {
    val now by vm.now.collectAsStateWithLifecycle()
    Text(at?.let { fmtAge(now - it) } ?: "—", modifier, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
}

/** Возраст + точка свежести (≤2 с) одним дешёвым composable: без эффектов и анимаций на плитку. */
@Composable
fun FreshAge(at: Long?, vm: MainViewModel, modifier: Modifier = Modifier, stale: Boolean = false) {
    val t = LocalCraTokens.current
    val now by vm.now.collectAsStateWithLifecycle()
    val fresh = at != null && (now - at) in 0..2000L
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (fresh) t.ok else t.faint))
        Spacer(Modifier.width(5.dp))
        Text(at?.let { fmtAge(now - it) } ?: "—", color = if (stale) t.warn else t.faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String? = null, accent: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    val t = LocalCraTokens.current
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(t.tile).border(1.dp, t.tileBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 10.sp, color = t.dim, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 19.sp, color = accent, fontFamily = FontFamily.Monospace, maxLines = 1)
        if (sub != null) Text(sub, fontSize = 10.sp, color = t.faint, maxLines = 1)
    }
}

@Composable
fun ConsoleChip(label: String, selected: Boolean, count: Long? = null, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val t = LocalCraTokens.current
    Row(
        Modifier.clip(CircleShape)
            .background(if (selected) accent.copy(alpha = .16f) else t.tile)
            .border(1.dp, if (selected) accent.copy(alpha = .35f) else t.tileBorder, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, fontSize = 12.sp, color = if (selected) accent else t.dim)
        if (count != null) Text("$count", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = if (selected) accent.copy(alpha = .8f) else t.faint)
    }
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(128.dp))
        Text(v, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

private val Context.uiStore by preferencesDataStore("iotmod_ui")

class UiSettings(private val context: Context) {
    private val darkKey = booleanPreferencesKey("dark")
    private val compactKey = booleanPreferencesKey("compact")
    private val pinsKey = stringPreferencesKey("pins")
    private val aliasKey = stringPreferencesKey("aliases")
    // v2 «Тихая консоль»: параметры раскладки плиток
    private val columnsKey = intPreferencesKey("tile_columns")        // 0 = авто по ширине
    private val densityKey = floatPreferencesKey("tile_density")      // 0.8…1.3
    private val textModeKey = stringPreferencesKey("tile_text_mode")  // clamp | wrap | grow
    private val thresholdKey = intPreferencesKey("tile_text_threshold")
    private val overridesKey = stringPreferencesKey("tile_size_overrides") // topic → S|M|L|W
    private val glowKey = booleanPreferencesKey("tile_glow")
    private val ageKey = booleanPreferencesKey("tile_age")
    private val sparkKey = booleanPreferencesKey("tile_spark")

    val dark: Flow<Boolean> = context.uiStore.data.map { it[darkKey] ?: true }
    val compact: Flow<Boolean> = context.uiStore.data.map { it[compactKey] ?: false }
    val pins: Flow<Set<String>> = context.uiStore.data.map { p ->
        runCatching { val a = JSONArray(p[pinsKey] ?: "[]"); (0 until a.length()).map { a.getString(it) }.toSet() }.getOrDefault(emptySet())
    }
    val aliases: Flow<Map<String, String>> = context.uiStore.data.map { p ->
        runCatching { val o = JSONObject(p[aliasKey] ?: "{}"); o.keys().asSequence().associateWith { o.getString(it) } }.getOrDefault(emptyMap())
    }
    val columns: Flow<Int> = context.uiStore.data.map { it[columnsKey] ?: 0 }
    val density: Flow<Float> = context.uiStore.data.map { it[densityKey] ?: 1f }
    val textMode: Flow<String> = context.uiStore.data.map { it[textModeKey] ?: "clamp" }
    val threshold: Flow<Int> = context.uiStore.data.map { it[thresholdKey] ?: 48 }
    val glow: Flow<Boolean> = context.uiStore.data.map { it[glowKey] ?: true }
    val showAge: Flow<Boolean> = context.uiStore.data.map { it[ageKey] ?: true }
    val showSpark: Flow<Boolean> = context.uiStore.data.map { it[sparkKey] ?: true }
    val overrides: Flow<Map<String, String>> = context.uiStore.data.map { p ->
        runCatching { val o = JSONObject(p[overridesKey] ?: "{}"); o.keys().asSequence().associateWith { o.getString(it) } }.getOrDefault(emptyMap())
    }

    suspend fun set(v: Boolean) { context.uiStore.edit { it[darkKey] = v } }
    suspend fun setCompact(v: Boolean) { context.uiStore.edit { it[compactKey] = v } }
    suspend fun togglePin(topic: String) {
        val cur = pins.first().toMutableSet()
        if (!cur.add(topic)) cur.remove(topic)
        context.uiStore.edit { it[pinsKey] = JSONArray(cur.toList()).toString() }
    }
    suspend fun setAlias(id: String, name: String) {
        val cur = JSONObject(aliases.first().let { m -> JSONObject().apply { m.forEach { (k, v) -> put(k, v) } } }.toString())
        if (name.isBlank()) cur.remove(id) else cur.put(id, name.trim())
        context.uiStore.edit { it[aliasKey] = cur.toString() }
    }
    suspend fun setColumns(v: Int) { context.uiStore.edit { it[columnsKey] = v.coerceIn(0, 6) } }
    suspend fun setDensity(v: Float) { context.uiStore.edit { it[densityKey] = v.coerceIn(.8f, 1.3f) } }
    suspend fun setTextMode(v: String) { context.uiStore.edit { it[textModeKey] = v } }
    suspend fun setThreshold(v: Int) { context.uiStore.edit { it[thresholdKey] = v.coerceIn(16, 120) } }
    suspend fun setGlow(v: Boolean) { context.uiStore.edit { it[glowKey] = v } }
    suspend fun setShowAge(v: Boolean) { context.uiStore.edit { it[ageKey] = v } }
    suspend fun setShowSpark(v: Boolean) { context.uiStore.edit { it[sparkKey] = v } }
    suspend fun setSizeOverride(topic: String, size: String?) {
        val cur = JSONObject(overrides.first().let { m -> JSONObject().apply { m.forEach { (k, v) -> put(k, v) } } }.toString())
        if (size == null) cur.remove(topic) else cur.put(topic, size)
        context.uiStore.edit { it[overridesKey] = cur.toString() }
    }
    suspend fun resetView() {
        context.uiStore.edit {
            it[columnsKey] = 0; it[densityKey] = 1f; it[textModeKey] = "clamp"; it[thresholdKey] = 48
            it[glowKey] = true; it[ageKey] = true; it[sparkKey] = true; it[overridesKey] = "{}"
        }
    }
}
