package com.livecontrol.craiot

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

val OnlineGreen = Color(0xFF5BD69A)
val OfflineRed = Color(0xFFF26D6D)
val Signal = Color(0xFF6EA8FF)
val Cyan = Color(0xFF53D6E8)
val Mint = Color(0xFF5BD69A)
val Amber = Color(0xFFF2B441)
val Red = Color(0xFFF26D6D)
val Violet = Color(0xFFB48EFA)

private val DarkColors = darkColorScheme(
    primary = Signal, onPrimary = Color(0xFF081018), secondary = Mint, tertiary = Amber,
    background = Color(0xFF0B0E12), onBackground = Color(0xFFE6EBF2),
    surface = Color(0xFF12161C), onSurface = Color(0xFFE6EBF2),
    surfaceVariant = Color(0xFF1A2029), onSurfaceVariant = Color(0xFF97A3B4),
    outline = Color(0xFF232B36), outlineVariant = Color(0xFF1E2630), error = Red,
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FDE), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF1E8E5A), tertiary = Color(0xFF9A6B00),
    background = Color(0xFFF4F6F9), onBackground = Color(0xFF141A22),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF141A22),
    surfaceVariant = Color(0xFFE8EDF4), onSurfaceVariant = Color(0xFF51607A),
    outline = Color(0xFFC9D3E0), outlineVariant = Color(0xFFDDE5EE), error = Color(0xFFC62828),
)

@Composable
fun CraIOTTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

fun iotColor(s: String?): Color? {
    if (s.isNullOrBlank()) return null
    val t = s.trim()
    if (t.startsWith("#")) return runCatching { Color(android.graphics.Color.parseColor(t)) }.getOrNull()
    return when (t.lowercase()) {
        "blue", "calm", "positive" -> Signal
        "green", "balanced", "secondary" -> Mint
        "red", "assertive", "danger" -> Red
        "orange", "energized", "warning" -> Amber
        "yellow" -> Color(0xFFE0AF68)
        "purple", "royal" -> Violet
        "white", "light" -> Color(0xFFD6DEEB)
        "black", "dark", "stable" -> Color(0xFF565F89)
        else -> null
    }
}

fun typeColor(t: String): Color = when (t) {
    "status" -> Mint; "config" -> Signal; "event" -> Violet; "control" -> Amber; "state" -> Cyan; else -> Color(0xFF5C6878)
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

@Composable
fun StatTile(label: String, value: String, sub: String? = null, accent: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 19.sp, color = accent, fontFamily = FontFamily.Monospace, maxLines = 1)
        if (sub != null) Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun ConsoleChip(label: String, selected: Boolean, count: Long? = null, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.clip(CircleShape).background(if (selected) accent.copy(alpha = .2f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, fontSize = 12.sp, color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant)
        if (count != null) Text("$count", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = if (selected) accent.copy(alpha = .8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
    }
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(128.dp))
        Text(v, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

private val Context.uiStore by preferencesDataStore("craiot_ui")

class UiSettings(private val context: Context) {
    private val darkKey = booleanPreferencesKey("dark")
    private val compactKey = booleanPreferencesKey("compact")
    private val pinsKey = stringPreferencesKey("pins")
    private val aliasKey = stringPreferencesKey("aliases")
    val dark: Flow<Boolean> = context.uiStore.data.map { it[darkKey] ?: true }
    val compact: Flow<Boolean> = context.uiStore.data.map { it[compactKey] ?: false }
    val pins: Flow<Set<String>> = context.uiStore.data.map { p ->
        runCatching { val a = JSONArray(p[pinsKey] ?: "[]"); (0 until a.length()).map { a.getString(it) }.toSet() }.getOrDefault(emptySet())
    }
    val aliases: Flow<Map<String, String>> = context.uiStore.data.map { p ->
        runCatching { val o = JSONObject(p[aliasKey] ?: "{}"); o.keys().asSequence().associateWith { o.getString(it) } }.getOrDefault(emptyMap())
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
}
