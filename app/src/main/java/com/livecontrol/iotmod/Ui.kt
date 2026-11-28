package com.livecontrol.iotmod

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
 * «Тихая консоль» v5 — M3: роли-токены цвета, тональные поверхности, единая шкала типов,
 * роли-токены вместо hex; тональные поверхности; семантика едина; значения всегда чернилами (tnum).
 * Shape-морф включается отдельно в настройках вида.
 */

val OnlineGreen = Color(0xFF5BD69A)
val OfflineRed = Color(0xFFF26D6D)
val Signal = Color(0xFF7C92AC)
val Cyan = Color(0xFF53D6E8)
val Mint = Color(0xFF5BD69A)
val Amber = Color(0xFFE3B264)
val Orange = Color(0xFFE08A3C)
val Red = Color(0xFFF26D6D)
val Violet = Color(0xFFB48EFA)

/** Единая шкала типов M3: готовые TextStyle без аллокаций при каждой рекомпозиции. */
object CraType {
    private const val TNUM = "tnum"
    val numDisplay = TextStyle(fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM, letterSpacing = (-0.5).sp)
    val numHeadline = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM, letterSpacing = (-0.5).sp)
    val numTitle = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM, letterSpacing = (-0.5).sp)
    val numInspector = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM, letterSpacing = (-0.5).sp)
    val numMicro = TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM, letterSpacing = (-0.5).sp)
    val numSmall = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, fontFeatureSettings = TNUM)
    val tileLabel = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    val tileLabelMicro = TextStyle(fontSize = 9.5.sp, lineHeight = 12.sp)
    val meta = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace)
    val metaMicro = TextStyle(fontSize = 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace)
    val body = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp)
    val button = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val chip = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val state = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val unit = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    val groupTitle = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    val rowTitle = TextStyle(fontSize = 14.sp, lineHeight = 18.sp)
    val rowSub = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    val section = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

data class CraTokens(
    val ink: Color, val dim: Color, val faint: Color,
    val tile: Color, val tileHigh: Color, val tileBorder: Color,
    val ok: Color, val warn: Color, val bad: Color,
    val nav: Color, val dark: Boolean, val radius: Dp, val gap: Dp,
)

class CraThemeSpec(val id: String, val title: String, val colors: ColorScheme, val tokens: CraTokens)

private val NightTokens = CraTokens(
    ink = Color(0xFFE6EDF7), dim = Color(0xFF93A4BD), faint = Color(0xFF5C6F8A),
    tile = Color(0xFF141D2E), tileHigh = Color(0xFF1A2436), tileBorder = Color(0xFF24304A),
    ok = OnlineGreen, warn = Orange, bad = Red,
    nav = Color(0xFF0E1522), dark = true, radius = 18.dp, gap = 10.dp,
)
private val StormTokens = CraTokens(
    ink = Color(0xFFEAF0F8), dim = Color(0xFFA5B4CB), faint = Color(0xFF6E8098),
    tile = Color(0xFF243144), tileHigh = Color(0xFF2B3A50), tileBorder = Color(0xFF35465F),
    ok = OnlineGreen, warn = Orange, bad = Red,
    nav = Color(0xFF1E2938), dark = true, radius = 18.dp, gap = 10.dp,
)
private val MidnightTokens = CraTokens(
    ink = Color(0xFFF2F6FC), dim = Color(0xFFA9B7CC), faint = Color(0xFF64788F),
    tile = Color(0xFF0D1220), tileHigh = Color(0xFF131A2A), tileBorder = Color(0xFF22304A),
    ok = Color(0xFF63E0A5), warn = Color(0xFFF0954A), bad = Color(0xFFFF7B7B),
    nav = Color(0xFF070A10), dark = true, radius = 18.dp, gap = 10.dp,
)
private val AuroraTokens = CraTokens(
    ink = Color(0xFFECEFF4), dim = Color(0xFFD8DEE9), faint = Color(0xFF9AA5B4),
    tile = Color(0xFF353C4A), tileHigh = Color(0xFF3B4252), tileBorder = Color(0xFF4C566A),
    ok = Color(0xFFA3BE8C), warn = Color(0xFFEBCB8B), bad = Color(0xFFBF616A),
    nav = Color(0xFF2B313C), dark = true, radius = 18.dp, gap = 10.dp,
)
private val PaperTokens = CraTokens(
    ink = Color(0xFF141A22), dim = Color(0xFF51607A), faint = Color(0xFF8B96A8),
    tile = Color(0xFFFFFFFF), tileHigh = Color(0xFFF7F9FC), tileBorder = Color(0xFFE2E7EF),
    ok = Color(0xFF1E8E5A), warn = Color(0xFF9A6B00), bad = Color(0xFFC62828),
    nav = Color(0xFFFFFFFF), dark = false, radius = 18.dp, gap = 10.dp,
)

private val NightColors = darkColorScheme(
    primary = Color(0xFF7AA2F7), onPrimary = Color(0xFF0B111C),
    primaryContainer = Color(0xFF22304C), onPrimaryContainer = Color(0xFFC9D8FF),
    secondary = Color(0xFF8FA6C4), onSecondary = Color(0xFF0B111C),
    tertiary = Amber, onTertiary = Color(0xFF241A08),
    background = Color(0xFF0B111C), onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF101826), onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF1A2436), onSurfaceVariant = Color(0xFF93A4BD),
    outline = Color(0xFF2A3852), outlineVariant = Color(0xFF1E2A40),
    error = Red, onError = Color(0xFF2A0B0B), surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF0A0F18), surfaceContainerLow = Color(0xFF0E1522),
    surfaceContainer = Color(0xFF121B2B), surfaceContainerHigh = Color(0xFF141D2E),
    surfaceContainerHighest = Color(0xFF1A2436),
)
private val StormColors = darkColorScheme(
    primary = Color(0xFF89B4FA), onPrimary = Color(0xFF101826),
    primaryContainer = Color(0xFF2A3A55), onPrimaryContainer = Color(0xFFD3E2FF),
    secondary = Color(0xFF93A9C8), onSecondary = Color(0xFF101826),
    tertiary = Amber, onTertiary = Color(0xFF241A08),
    background = Color(0xFF1A2332), onBackground = Color(0xFFEAF0F8),
    surface = Color(0xFF202C3D), onSurface = Color(0xFFEAF0F8),
    surfaceVariant = Color(0xFF2B3A50), onSurfaceVariant = Color(0xFFA5B4CB),
    outline = Color(0xFF35465F), outlineVariant = Color(0xFF2A3850),
    error = Red, onError = Color(0xFF2A0B0B), surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF17202D), surfaceContainerLow = Color(0xFF1E2938),
    surfaceContainer = Color(0xFF223042), surfaceContainerHigh = Color(0xFF243144),
    surfaceContainerHighest = Color(0xFF2B3A50),
)
private val MidnightColors = darkColorScheme(
    primary = Color(0xFF8AB4FF), onPrimary = Color(0xFF04060B),
    primaryContainer = Color(0xFF1B2A47), onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF9FB2CC), onSecondary = Color(0xFF04060B),
    tertiary = Color(0xFFE8BC72), onTertiary = Color(0xFF241A08),
    background = Color(0xFF05070C), onBackground = Color(0xFFF2F6FC),
    surface = Color(0xFF0A0E16), onSurface = Color(0xFFF2F6FC),
    surfaceVariant = Color(0xFF131A2A), onSurfaceVariant = Color(0xFFA9B7CC),
    outline = Color(0xFF22304A), outlineVariant = Color(0xFF182338),
    error = Color(0xFFFF7B7B), onError = Color(0xFF2A0B0B), surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF030509), surfaceContainerLow = Color(0xFF070A10),
    surfaceContainer = Color(0xFF0B101B), surfaceContainerHigh = Color(0xFF0D1220),
    surfaceContainerHighest = Color(0xFF131A2A),
)
private val AuroraColors = darkColorScheme(
    primary = Color(0xFF88C0D0), onPrimary = Color(0xFF2E3440),
    primaryContainer = Color(0xFF434C5E), onPrimaryContainer = Color(0xFFECEFF4),
    secondary = Color(0xFF81A1C1), onSecondary = Color(0xFF2E3440),
    tertiary = Color(0xFFEBCB8B), onTertiary = Color(0xFF2E3440),
    background = Color(0xFF2E3440), onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF333A47), onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF3B4252), onSurfaceVariant = Color(0xFFD8DEE9),
    outline = Color(0xFF4C566A), outlineVariant = Color(0xFF434C5E),
    error = Color(0xFFBF616A), onError = Color(0xFF2E3440), surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF292F3A), surfaceContainerLow = Color(0xFF2B313C),
    surfaceContainer = Color(0xFF313845), surfaceContainerHigh = Color(0xFF353C4A),
    surfaceContainerHighest = Color(0xFF3B4252),
)
private val PaperColors = lightColorScheme(
    primary = Color(0xFF2F6FDE), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7FB), onPrimaryContainer = Color(0xFF123A7A),
    secondary = Color(0xFF1E8E5A), onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF9A6B00), onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F6F9), onBackground = Color(0xFF141A22),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF141A22),
    surfaceVariant = Color(0xFFE8EDF4), onSurfaceVariant = Color(0xFF51607A),
    outline = Color(0xFFC9D3E0), outlineVariant = Color(0xFFDDE5EE),
    error = Color(0xFFC62828), onError = Color(0xFFFFFFFF), surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFEFF3F8), surfaceContainerHigh = Color(0xFFE8EDF4),
    surfaceContainerHighest = Color(0xFFE2E7EF),
)

object CraThemes {
    val night = CraThemeSpec("night", "Ночь", NightColors, NightTokens)
    val storm = CraThemeSpec("storm", "Шторм", StormColors, StormTokens)
    val midnight = CraThemeSpec("midnight", "Полночь", MidnightColors, MidnightTokens)
    val aurora = CraThemeSpec("aurora", "Аврора", AuroraColors, AuroraTokens)
    val paper = CraThemeSpec("paper", "Бумага", PaperColors, PaperTokens)
    val all = listOf(night, storm, midnight, aurora, paper)
    fun byId(id: String): CraThemeSpec = all.firstOrNull { it.id == id } ?: night
}

/** Токены для dynamic-схемы: выводим из ролей M3, семантику фиксируем сами. */
private fun tokensFromScheme(cs: ColorScheme, dark: Boolean): CraTokens = CraTokens(
    ink = cs.onSurface, dim = cs.onSurfaceVariant, faint = cs.onSurfaceVariant.copy(alpha = .66f),
    tile = cs.surfaceContainerHigh, tileHigh = cs.surfaceContainerHighest, tileBorder = cs.outlineVariant,
    ok = if (dark) OnlineGreen else Color(0xFF1E8E5A),
    warn = if (dark) Orange else Color(0xFF9A6B00),
    bad = if (dark) Red else Color(0xFFC62828),
    nav = cs.surfaceContainerLow, dark = dark, radius = 18.dp, gap = 10.dp,
)

val LocalCraTokens = staticCompositionLocalOf { NightTokens }

@Composable
fun IoTmodTheme(themeId: String, content: @Composable () -> Unit) {
    val spec = if (themeId == "system") {
        val sysDark = isSystemInDarkTheme()
        val cs = if (Build.VERSION.SDK_INT >= 31) {
            if (sysDark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        } else if (sysDark) CraThemes.night.colors else CraThemes.paper.colors
        CraThemeSpec("system", "Системная", cs, tokensFromScheme(cs, sysDark))
    } else CraThemes.byId(themeId)
    CompositionLocalProvider(LocalCraTokens provides spec.tokens) {
        MaterialTheme(colorScheme = spec.colors, content = content)
    }
}

/** Пикер тем для листа «Вид». */
@Composable
fun ThemePicker(vm: MainViewModel) {
    val t = LocalCraTokens.current
    val current by vm.themeId.collectAsStateWithLifecycle()
    Text("ТЕМА", style = CraType.section, color = t.faint)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CraThemes.all.forEach { s -> ConsoleChip(s.title, current == s.id) { vm.setTheme(s.id) } }
        ConsoleChip("Системная", current == "system") { vm.setTheme("system") }
    }
    Text("«Системная» берёт цвета обоев (Android 12+), иначе запасная тема. Семантика ok/warn/bad едина во всех темах.",
        style = CraType.body, color = t.faint)
}

fun craNumStyle(size: TextUnit, color: Color, weight: FontWeight = FontWeight.SemiBold): TextStyle =
    TextStyle(fontSize = size, fontWeight = weight, color = color, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)

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
        Text(t, color = c, style = CraType.metaMicro)
    }
}

@Composable
fun AgeText(at: Long?, now: Long, modifier: Modifier = Modifier) {
    Text(at?.let { fmtAge(now - it) } ?: "—", modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = CraType.meta)
}

@Composable
fun LiveAge(at: Long?, vm: MainViewModel, modifier: Modifier = Modifier, color: Color = LocalCraTokens.current.faint) {
    val now by vm.now.collectAsStateWithLifecycle()
    Text(at?.let { fmtAge(now - it) } ?: "—", modifier, color = color, style = CraType.meta, maxLines = 1)
}

@Composable
fun FreshAge(at: Long?, vm: MainViewModel, modifier: Modifier = Modifier, stale: Boolean = false) {
    val t = LocalCraTokens.current
    val now by vm.now.collectAsStateWithLifecycle()
    val fresh = at != null && (now - at) in 0..2000L
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (fresh) t.ok else t.faint))
        Spacer(Modifier.width(5.dp))
        Text(at?.let { fmtAge(now - it) } ?: "—", color = if (stale) t.warn else t.faint, style = CraType.meta, maxLines = 1)
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String? = null, accent: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    val t = LocalCraTokens.current
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(t.tile).border(1.dp, t.tileBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, style = CraType.meta, color = t.dim, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, style = CraType.numHeadline, color = accent, maxLines = 1)
        if (sub != null) Text(sub, style = CraType.meta, color = t.faint, maxLines = 1)
    }
}

/** Выделенный чип — тональная роль primaryContainer (M3), не просто альфа акцента. */
@Composable
fun ConsoleChip(label: String, selected: Boolean, count: Long? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = LocalCraTokens.current
    Row(
        Modifier.clip(CircleShape)
            .background(if (selected) cs.primaryContainer else t.tile)
            .border(1.dp, if (selected) cs.primary.copy(alpha = .45f) else t.tileBorder, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, style = CraType.chip, color = if (selected) cs.onPrimaryContainer else t.dim)
        if (count != null) Text("$count", style = CraType.meta,
            color = if (selected) cs.onPrimaryContainer.copy(alpha = .7f) else t.faint)
    }
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = CraType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(128.dp))
        Text(v, style = CraType.numSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

private val Context.uiStore by preferencesDataStore("iotmod_ui")

class UiSettings(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_id")
    private val compactKey = booleanPreferencesKey("compact")
    private val pinsKey = stringPreferencesKey("pins")
    private val aliasKey = stringPreferencesKey("aliases")
    private val columnsKey = intPreferencesKey("tile_columns")
    private val densityKey = floatPreferencesKey("tile_density")
    private val textModeKey = stringPreferencesKey("tile_text_mode")
    private val thresholdKey = intPreferencesKey("tile_text_threshold")
    private val overridesKey = stringPreferencesKey("tile_size_overrides")
    private val glowKey = booleanPreferencesKey("tile_glow")
    private val ageKey = booleanPreferencesKey("tile_age")
    private val sparkKey = booleanPreferencesKey("tile_spark")
    private val morphKey = booleanPreferencesKey("tile_morph")

    val themeId: Flow<String> = context.uiStore.data.map { it[themeKey] ?: "night" }
    val dark: Flow<Boolean> = themeId.map { CraThemes.byId(it).tokens.dark }
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
    val morph: Flow<Boolean> = context.uiStore.data.map { it[morphKey] ?: true }
    val overrides: Flow<Map<String, String>> = context.uiStore.data.map { p ->
        runCatching { val o = JSONObject(p[overridesKey] ?: "{}"); o.keys().asSequence().associateWith { o.getString(it) } }.getOrDefault(emptyMap())
    }

    suspend fun setTheme(id: String) { context.uiStore.edit { it[themeKey] = id } }
    suspend fun set(v: Boolean) = setTheme(if (v) "night" else "paper")
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
    suspend fun setMorph(v: Boolean) { context.uiStore.edit { it[morphKey] = v } }
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
