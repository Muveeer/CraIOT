package com.livecontrol.iotmod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livecontrol.iotmod.data.ReadingEntity
import com.livecontrol.iotmod.mqtt.ConnState
import com.livecontrol.iotmod.protocol.ChartPoint
import com.livecontrol.iotmod.protocol.Device
import com.livecontrol.iotmod.protocol.Widget
import com.livecontrol.iotmod.protocol.WidgetKind
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Обзор v3 — low-end first (Redmi Note 8 / SD665 / 393 dp).
 *
 * Бюджет на плитку ≈ бюджет строки: плоский фон (PS App), статическая рамка,
 * НИ ОДНОГО LaunchedEffect / animate* / interactionSource на квадрат; свежесть и возраст —
 * один FreshAge (тикает 1 раз/с локально). Колонки ограничены физической шириной:
 * floor((W − зазоры)/72 dp) → на 393 dp максимум 4; micro-тип (<92 dp) уменьшает шрифты.
 * Батчинг devices ≤5 fps, стабильные TileActions, contentType, remembered-сортировка — сохранены.
 */

enum class TileClass { S, M, L, C, W }

private val tileWidgetOrder = compareBy<Widget>({ it.order ?: Int.MAX_VALUE }, { it.descr.ifBlank { it.id } }, { it.id })

private fun isControlKind(w: Widget): Boolean = w.kind in setOf(
    WidgetKind.TOGGLE, WidgetKind.BTN_SWITCH, WidgetKind.SIMPLE_BTN, WidgetKind.BTN, WidgetKind.POWER_BUTTON,
    WidgetKind.RANGE, WidgetKind.INPUT, WidgetKind.INPUT_NUMBER, WidgetKind.INPUT_DATE, WidgetKind.SELECT,
)
private fun isGaugeKind(w: Widget): Boolean = w.kind in setOf(
    WidgetKind.FILLGAUGE, WidgetKind.GAUGE, WidgetKind.DOUGHNUT, WidgetKind.PROGRESS_LINE, WidgetKind.PROGRESS_ROUND, WidgetKind.STEEL,
)
private fun isArcKind(w: Widget): Boolean = w.kind in setOf(WidgetKind.GAUGE, WidgetKind.DOUGHNUT, WidgetKind.PROGRESS_ROUND, WidgetKind.STEEL)
private fun isInputKind(w: Widget): Boolean = w.kind in setOf(WidgetKind.INPUT, WidgetKind.INPUT_NUMBER, WidgetKind.INPUT_DATE, WidgetKind.SELECT)

fun classifyTile(w: Widget, threshold: Int, override: String?): TileClass {
    override?.let { runCatching { TileClass.valueOf(it) }.getOrNull()?.let { c -> return c } }
    if (isControlKind(w)) return TileClass.C
    if (w.kind == WidgetKind.CHART) return if (w.points.any { it.ys.getOrNull(1) != null }) TileClass.L else TileClass.M
    if (isGaugeKind(w) && w.min != null && w.max != null) return TileClass.M
    val v = w.value.orEmpty()
    if (v.toDoubleOrNull() != null) return if (v.length <= 7) TileClass.S else TileClass.M
    return when { v.length <= 7 -> TileClass.S; v.length <= threshold -> TileClass.M; else -> TileClass.W }
}

private fun tileSpan(cls: TileClass, cols: Int, textMode: String, expanded: Boolean): Int = when {
    expanded -> cols
    cls == TileClass.S -> 1
    cls == TileClass.W -> if (textMode == "clamp") minOf(2, cols) else cols
    else -> minOf(2, cols)
}
private fun tileTall(cls: TileClass, textMode: String, expanded: Boolean): Boolean =
    expanded || (cls == TileClass.W && textMode != "clamp")
private fun tileRank(cls: TileClass, cols: Int, textMode: String, expanded: Boolean): Int = when {
    expanded || (cls == TileClass.W && textMode != "clamp") -> 2
    tileSpan(cls, cols, textMode, false) == 1 -> 0
    else -> 1
}

private fun fmtBound(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

private fun tileWhy(w: Widget, auto: TileClass, threshold: Int): String {
    val v = w.value.orEmpty()
    return when {
        isControlKind(w) -> "Контрол «${w.typeRaw.ifBlank { "control" }}» → C · 2×1. Состояние — словом, цель нажатия ≥ 48 dp."
        w.kind == WidgetKind.CHART && auto == TileClass.L -> "Chart · ${(w.points.firstOrNull()?.ys?.count { it != null } ?: 1)} серии → L. Тап — полный график."
        w.kind == WidgetKind.CHART -> "Chart · история ${w.points.size} точек → M со спарклайном."
        isGaugeKind(w) && w.min != null && w.max != null -> "Шкала ${fmtBound(w.min)}–${fmtBound(w.max)} с цветовыми зонами → M · 2×1."
        v.toDoubleOrNull() != null && auto == TileClass.S -> "Число «$v» · ${v.length} симв. ≤ 7, единица «${w.after ?: "—"}» → квадрат S · 1×1."
        v.toDoubleOrNull() != null -> "Число «$v» · ${v.length} симв. > 7 → M · 2×1."
        auto == TileClass.W -> "Текст ${v.length} симв. > порога $threshold → W: обрезка 2 строки + «ещё», раскрытие по тапу — сетка не ломается."
        else -> "Текст ${v.length} симв. ≤ порога $threshold → ${if (auto == TileClass.S) "S · 1×1" else "M · 2×1"}, помещается целиком."
    }
}

@Immutable
internal data class TileEnv(
    val cols: Int, val density: Float, val textMode: String, val threshold: Int,
    val showAge: Boolean, val showSpark: Boolean, val glow: Boolean, val micro: Boolean,
)

/** Стабильные колбэки: один экземпляр на экран → плитки с неизменным w полностью пропускаются. */
@Immutable
internal class TileActions(
    val pin: (Widget) -> Unit,
    val expand: (Widget) -> Unit,
    val inspect: (Widget) -> Unit,
    val chart: (Widget) -> Unit,
)

@Composable
private fun TileFooter(w: Widget, vm: MainViewModel, env: TileEnv, stale: Boolean, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (env.showAge) FreshAge(w.valueAt, vm, stale = stale)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun GaugeBar(frac: Float, zone: Color, modifier: Modifier = Modifier) {
    val t = LocalCraTokens.current
    val anim by animateFloatAsState(frac.coerceIn(0f, 1f), tween(500), label = "gauge")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(t.tileBorder)) {
        Box(Modifier.fillMaxWidth(anim).fillMaxHeight().clip(CircleShape).background(zone))
    }
}

@Composable
private fun GaugeArc(frac: Float, zone: Color, sizeDp: Dp) {
    val t = LocalCraTokens.current
    val anim by animateFloatAsState(frac.coerceIn(0f, 1f), tween(500), label = "arc")
    Canvas(Modifier.size(sizeDp)) {
        val sw = 5.dp.toPx()
        val tl = Offset(sw / 2, sw / 2)
        val sz = Size(size.width - sw, size.height - sw)
        drawArc(t.tileBorder, 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        drawArc(zone, 135f, 270f * anim, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

@Composable
private fun MiniChart(points: List<ChartPoint>, modifier: Modifier = Modifier) {
    val t = LocalCraTokens.current
    val c1 = SeriesColors[0]; val c2 = SeriesColors[1]
    Canvas(modifier) {
        val s1 = points.mapNotNull { it.ys.getOrNull(0) }
        val s2 = points.mapNotNull { it.ys.getOrNull(1) }
        if (s1.size < 2) return@Canvas
        val all = s1 + s2
        val mn = all.min(); val mx = all.max(); val span = (mx - mn).takeIf { it > 1e-9 } ?: 1.0
        fun y(v: Double) = size.height * (1 - ((v - mn) / span).toFloat()) * .92f + size.height * .04f
        fun path(ys: List<Double>): Path = Path().apply {
            ys.forEachIndexed { i, v ->
                val x = size.width * i / (ys.size - 1)
                if (i == 0) moveTo(x, y(v)) else lineTo(x, y(v))
            }
        }
        drawLine(t.tileBorder, Offset(0f, size.height * .33f), Offset(size.width, size.height * .33f), 1f)
        drawLine(t.tileBorder, Offset(0f, size.height * .66f), Offset(size.width, size.height * .66f), 1f)
        val p1 = path(s1)
        val fill = Path().apply { addPath(p1); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(c1.copy(alpha = .16f), c1.copy(alpha = 0f))))
        drawPath(p1, c1, style = Stroke(1.6.dp.toPx()))
        if (s2.size >= 2) drawPath(path(s2), c2, style = Stroke(1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
    }
}

@Composable
private fun CompactSearch(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalCraTokens.current
    Row(
        modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))
            .background(t.tile).border(1.dp, t.tileBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = t.faint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Поиск: имя, топик, устройство…", fontSize = 12.5.sp, color = t.faint, maxLines = 1)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = t.ink),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.Close, null, tint = t.faint,
                modifier = Modifier.size(16.dp).clip(CircleShape).clickable { onChange("") })
        }
    }
}

@Composable
internal fun WidgetTile(
    w: Widget, vm: MainViewModel, env: TileEnv, cls: TileClass, stale: Boolean,
    pinned: Boolean, expanded: Boolean, actions: TileActions, modifier: Modifier = Modifier,
) {
    val t = LocalCraTokens.current
    val accent = MaterialTheme.colorScheme.primary
    val dd = env.density
    val micro = env.micro && cls == TileClass.S
    val shape = RoundedCornerShape(if (micro) 12.dp else t.radius)
    val pad = if (micro) 8.dp else (10 * dd).dp
    val tap: () -> Unit = if (w.kind == WidgetKind.CHART) { { actions.chart(w) } } else { { actions.inspect(w) } }

    Box(
        modifier.clip(shape).background(t.tile).border(1.dp, t.tileBorder, shape)
            .clickable(interactionSource = null, indication = null, onClick = tap),
    ) {
        Column(Modifier.fillMaxSize().padding(pad).alpha(if (stale) .6f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.descr.ifBlank { w.id }, fontSize = if (micro) 9.5.sp else 11.sp * dd, color = t.dim, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Default.Star, contentDescription = if (pinned) "Открепить" else "Закрепить",
                    tint = if (pinned) MaterialTheme.colorScheme.tertiary else t.faint.copy(alpha = .7f),
                    modifier = Modifier.size(if (micro) 14.dp else 17.dp).clickable { actions.pin(w) })
            }
            Spacer(Modifier.height(((if (micro) 2 else 4) * dd).dp))

            when {
                cls == TileClass.C && (w.kind == WidgetKind.TOGGLE || (w.kind == WidgetKind.BTN_SWITCH && w.mode != "momentary")) -> {
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Switch(checked = w.value == "1", onCheckedChange = { vm.send(w, if (it) "1" else "0") },
                            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = MaterialTheme.colorScheme.onPrimary))
                        Text(if (w.value == "1") "Вкл" else "Выкл", fontSize = 13.sp * dd, fontWeight = FontWeight.Medium,
                            color = if (w.value == "1") t.ink else t.dim)
                        if (w.pending) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = accent)
                    }
                    Spacer(Modifier.weight(1f))
                }
                cls == TileClass.C && w.kind == WidgetKind.BTN_SWITCH && w.mode == "momentary" -> {
                    var held by remember(w.topic) { mutableStateOf(false) }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(t.radius * .6f))
                            .background(accent.copy(alpha = if (held) .32f else .16f))
                            .border(1.dp, accent.copy(alpha = .35f), RoundedCornerShape(t.radius * .6f))
                            .pointerInput(w.topic) { detectTapGestures(onPress = { held = true; vm.send(w, "1"); tryAwaitRelease(); held = false; vm.send(w, "0") }) }
                            .padding(vertical = (10 * dd).dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(w.title ?: "Нажать", color = accent, fontSize = 13.sp * dd, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.weight(1f))
                }
                cls == TileClass.C && w.kind == WidgetKind.RANGE && w.min != null && w.max != null -> {
                    val mn = w.min.toFloat(); val mx = w.max.toFloat()
                    var pos by remember(w.value) { mutableStateOf((w.value?.toFloatOrNull() ?: mn).coerceIn(mn, mx)) }
                    Text(String.format(Locale.US, if ((w.step ?: 1.0) >= 1) "%.0f" else "%.2f", pos) + (w.after?.let { " $it" } ?: ""),
                        style = craNumStyle(18.sp * dd, t.ink))
                    Spacer(Modifier.weight(1f))
                    val steps = if (w.step != null && w.step > 0) (((mx - mn) / w.step).toInt() - 1).coerceAtLeast(0) else 0
                    Slider(value = pos, onValueChange = { pos = it },
                        onValueChangeFinished = { vm.send(w, String.format(Locale.US, if ((w.step ?: 1.0) >= 1) "%.0f" else "%.2f", pos)) },
                        valueRange = mn..mx, steps = steps, modifier = Modifier.height(32.dp),
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
                }
                cls == TileClass.C && isInputKind(w) -> {
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(w.value ?: "—", fontFamily = FontFamily.Monospace, fontSize = 15.sp * dd, color = t.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.Edit, null, tint = accent, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("изменить", fontSize = 10.sp * dd, color = accent)
                    }
                    Spacer(Modifier.weight(1f))
                }
                cls == TileClass.C -> {
                    Spacer(Modifier.weight(1f))
                    val custom = iotColor(w.fill ?: w.color)
                    val bgc = custom ?: accent
                    val fgc = if (custom != null) Color.White else MaterialTheme.colorScheme.onPrimary
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(t.radius * .6f))
                            .background(bgc).clickable { vm.send(w, w.data ?: "1") }.padding(vertical = (10 * dd).dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(w.title ?: w.descr.ifBlank { w.id }, color = fgc, fontSize = 13.sp * dd, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    Spacer(Modifier.weight(1f))
                }
                isGaugeKind(w) && w.min != null && w.max != null && cls != TileClass.S -> {
                    val v = w.value?.toDoubleOrNull()
                    val frac = if (v == null) 0f else ((v - w.min) / (w.max - w.min)).toFloat().coerceIn(0f, 1f)
                    val zone = if (frac < .5f) t.ok else if (frac < .8f) t.warn else t.bad
                    if (isArcKind(w)) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GaugeArc(frac, zone, (46 * dd).dp)
                            Column {
                                Text(w.value ?: "—", style = craNumStyle(21.sp * dd, t.ink))
                                Text(w.after ?: "", fontSize = 10.sp * dd, color = t.dim, maxLines = 1)
                            }
                        }
                    } else {
                        Text(w.value ?: "—", style = craNumStyle(21.sp * dd, t.ink))
                        Text(w.after ?: "", fontSize = 10.sp * dd, color = t.dim, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        GaugeBar(frac, zone)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmtBound(w.min), fontSize = 9.sp * dd, fontFamily = FontFamily.Monospace, color = t.faint)
                        Text(fmtBound(w.max), fontSize = 9.sp * dd, fontFamily = FontFamily.Monospace, color = t.faint)
                    }
                }
                cls == TileClass.L -> {
                    val last1 = w.points.lastOrNull()?.ys?.getOrNull(0)
                    val last2 = w.points.lastOrNull()?.ys?.getOrNull(1)
                    Text(last1?.let { fmtBound(it) } ?: "—", style = craNumStyle(20.sp * dd, t.ink))
                    if (env.showSpark) MiniChart(w.points, Modifier.fillMaxWidth().weight(1f).padding(vertical = 3.dp))
                    else Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(SeriesColors[0]))
                        Text("y1 ${last1?.let { fmtBound(it) } ?: "—"}", fontSize = 10.sp * dd, fontFamily = FontFamily.Monospace, color = t.dim)
                        Box(Modifier.size(6.dp).clip(CircleShape).background(SeriesColors[1]))
                        Text("y2 ${last2?.let { fmtBound(it) } ?: "—"}", fontSize = 10.sp * dd, fontFamily = FontFamily.Monospace, color = t.dim)
                    }
                }
                w.kind == WidgetKind.CHART -> {
                    val last = w.points.lastOrNull()?.ys?.firstOrNull()
                    Text((last?.let { fmtBound(it) } ?: w.value) ?: "—", style = craNumStyle(20.sp * dd, t.ink))
                    Text(w.after ?: "", fontSize = 10.sp * dd, color = t.dim, maxLines = 1)
                    if (env.showSpark && w.points.size >= 2) Sparkline(w.points, Modifier.fillMaxWidth().height((24 * dd).dp).padding(top = 3.dp), SeriesColors[0])
                    Spacer(Modifier.weight(1f))
                }
                cls == TileClass.W || (w.value?.toDoubleOrNull() == null && cls == TileClass.M) -> {
                    val maxLines = when { expanded || env.textMode == "grow" -> Int.MAX_VALUE; env.textMode == "wrap" -> 6; cls == TileClass.W -> 2; else -> 3 }
                    Text(w.value ?: "—", fontSize = 12.5.sp * dd, color = t.ink, lineHeight = 17.sp * dd,
                        maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (cls == TileClass.W || expanded) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (env.showAge) FreshAge(w.valueAt, vm, stale = stale)
                            Spacer(Modifier.weight(1f))
                            Text(if (expanded) "свернуть ↑" else "ещё ↓", fontSize = 11.sp * dd, color = accent, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(CircleShape).background(accent.copy(alpha = .12f))
                                    .clickable { actions.expand(w) }.padding(horizontal = 9.dp, vertical = 3.dp))
                        }
                    }
                }
                else -> {
                    Spacer(Modifier.weight(1f))
                    val numeric = w.value?.toDoubleOrNull() != null
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(w.value ?: "—",
                            style = if (numeric) craNumStyle((if (micro) 17 else if (cls == TileClass.S) 27 else 21).sp * dd, t.ink)
                            else craNumStyle((if (micro) 12 else 15).sp * dd, t.ink, FontWeight.Medium).copy(fontFamily = FontFamily.Monospace),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        val unit = listOfNotNull(w.before, w.after).joinToString(" ")
                        if (unit.isNotBlank() && !micro) {
                            Spacer(Modifier.width(4.dp))
                            Text(unit, fontSize = 11.sp * dd, color = t.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!micro) w.info?.let { Text(it, fontSize = 10.sp * dd, color = iotColor(w.infoColor) ?: t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Spacer(Modifier.height(((if (micro) 2 else 4) * dd).dp))
                }
            }

            if (cls != TileClass.W && !expanded) {
                Spacer(Modifier.height(((if (micro) 2 else 4) * dd).dp))
                TileFooter(w, vm, env, stale) {
                    if (w.pending && cls != TileClass.C) CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = accent)
                }
            }
        }
    }
}

@Composable
private fun DeviceTileHeader(d: Device, alias: String?, vm: MainViewModel, count: Int, collapsed: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val t = LocalCraTokens.current
    val up = d.live || d.online == true
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 7.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            HealthDot(up)
            Spacer(Modifier.width(8.dp))
            Text(alias ?: d.id, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (alias != null) {
                Spacer(Modifier.width(6.dp))
                Text(d.id, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = t.faint, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            if (!up) {
                Row(Modifier.clip(CircleShape).background(t.warn.copy(alpha = .13f)).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("нет связи", fontSize = 10.sp, color = t.warn)
                    d.lastSeen?.let { Spacer(Modifier.width(4.dp)); LiveAge(it, vm, color = t.warn) }
                }
                Spacer(Modifier.width(6.dp))
            }
            Text("$count", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = t.faint)
            TextButton(onClick = onOpen, contentPadding = PaddingValues(4.dp)) { Text("→", fontSize = 13.sp) }
            Icon(if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, tint = t.faint, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(vm: MainViewModel, nav: Nav, onOpenChart: (String, String) -> Unit, onOpenDevice: (String) -> Unit) {
    val t = LocalCraTokens.current
    val devices by vm.devices.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val aliases by vm.aliases.collectAsStateWithLifecycle()
    val dark by vm.dark.collectAsStateWithLifecycle()
    val state by vm.connState.collectAsStateWithLifecycle()
    val columnsPref by vm.tileColumns.collectAsStateWithLifecycle()
    val density by vm.tileDensity.collectAsStateWithLifecycle()
    val textMode by vm.tileTextMode.collectAsStateWithLifecycle()
    val threshold by vm.tileThreshold.collectAsStateWithLifecycle()
    val glow by vm.tileGlow.collectAsStateWithLifecycle()
    val showAge by vm.tileAge.collectAsStateWithLifecycle()
    val showSpark by vm.tileSpark.collectAsStateWithLifecycle()
    val overrides by vm.tileOverrides.collectAsStateWithLifecycle()

    var page by nav.dashPage
    var query by nav.dashQuery
    var collapsed by nav.dashCollapsed
    var expandedTopics by rememberSaveable { mutableStateOf(listOf<String>()) }
    var inspectTopic by rememberSaveable { mutableStateOf<String?>(null) }
    var viewOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var headerCollapsed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(nav.dashList) {
        snapshotFlow { nav.dashList.firstVisibleItemIndex to nav.dashList.firstVisibleItemScrollOffset }
            .collect { (i, off) ->
                headerCollapsed = when {
                    i > 0 || off > 140 -> true
                    i == 0 && off < 40 -> false
                    else -> headerCollapsed
                }
            }
    }

    val all = remember(devices) { devices.values.flatMap { d -> d.widgets.values.filter { !it.hidden }.map { d to it } } }
    val pages = remember(all) {
        all.groupBy { it.second.page.ifBlank { "Без страницы" } }
            .map { (n, ws) -> Triple(n, ws.mapNotNull { it.second.order ?: it.second.pageId }.minOrNull() ?: Int.MAX_VALUE, ws.size) }
            .sortedWith(compareBy({ it.second }, { it.first }))
    }
    val pinnedWidgets = remember(all, pins) { all.filter { it.second.topic in pins } }
    val searchResults = remember(all, query) {
        if (query.isBlank()) emptyList()
        else all.filter { (d, w) -> w.descr.contains(query, true) || w.id.contains(query, true) || w.topic.contains(query, true) || d.id.contains(query, true) }
    }
    val groups = remember(devices, page, query) {
        if (query.isNotBlank()) emptyList()
        else devices.values.sortedWith(compareBy({ !(it.live || it.online == true) }, { it.id })).mapNotNull { d ->
            val ws = d.widgets.values.filter { !it.hidden }
                .filter { page.isEmpty() || it.page.ifBlank { "Без страницы" } == page }
            if (ws.isEmpty()) null else d to ws
        }
    }
    val actions = remember(vm, onOpenChart) {
        TileActions(
            pin = { w -> vm.togglePin(w.topic) },
            expand = { w -> expandedTopics = if (w.topic in expandedTopics) expandedTopics - w.topic else expandedTopics + w.topic },
            inspect = { w -> inspectTopic = w.topic },
            chart = { w -> onOpenChart(w.topic, w.descr.ifBlank { w.id }) },
        )
    }
    val online = devices.values.count { it.live || it.online == true }
    val allCollapsed = groups.isNotEmpty() && groups.all { collapsed.contains(it.first.id) }
    val noConfigs = devices.isNotEmpty() && all.all { it.second.rawConfig.isEmpty() }
    val searchVisible = !headerCollapsed || searchOpen || query.isNotBlank()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (headerCollapsed && page.isNotEmpty()) page else "Обзор",
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (headerCollapsed) "$online/${devices.size} online · ${all.size} виджетов" else "",
                        fontSize = 10.sp, color = t.faint, maxLines = 1)
                }
                if (!searchVisible) {
                    IconButton(onClick = { searchOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Search, null, tint = t.dim, modifier = Modifier.size(18.dp))
                    }
                }
                TextButton(onClick = { collapsed = if (allCollapsed) listOf() else devices.keys.toList() }, contentPadding = PaddingValues(6.dp)) {
                    Text(if (allCollapsed) "⊞" else "⊟", fontSize = 18.sp)
                }
                TextButton(onClick = { vm.hello() }, enabled = state is ConnState.Connected, contentPadding = PaddingValues(6.dp)) { Text("HELLO", fontSize = 12.sp) }
                ConsoleChip("вид", viewOpen) { viewOpen = true }
                TextButton(onClick = { vm.toggleDark() }, contentPadding = PaddingValues(6.dp)) { Text(if (dark) "☀" else "☾") }
            }
            AnimatedVisibility(visible = searchVisible, enter = expandVertically(tween(160)), exit = shrinkVertically(tween(160))) {
                CompactSearch(query, { query = it; if (it.isBlank()) searchOpen = false })
            }
            AnimatedVisibility(visible = !headerCollapsed, enter = expandVertically(tween(160)), exit = shrinkVertically(tween(160))) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConsoleChip("Все", page.isEmpty(), all.size.toLong()) { page = "" }
                    pages.forEach { (n, _, c) -> ConsoleChip(n, page == n, c.toLong()) { page = n } }
                }
            }

            BoxWithConstraints(Modifier.weight(1f)) {
                // Физический кап: квадрат не уже 72 dp. Redmi 393 dp → максимум 4; 6 — планшет/landscape.
                val maxCols = ((maxWidth - t.gap * 5) / 72.dp).toInt().coerceIn(2, 6)
                val autoCols = (maxWidth / 158.dp).toInt().coerceIn(2, maxCols)
                val cols = (if (columnsPref == 0) autoCols else columnsPref).coerceIn(2, maxCols)
                val tileW = (maxWidth - t.gap * (cols - 1)) / cols
                val micro = tileW < 92.dp
                val env = TileEnv(cols, density, textMode, threshold, showAge, showSpark, glow, micro)
                val tileH = (if (micro) 96.dp else 118.dp) * density
                val tallH = tileH * 2 + t.gap
                fun clsOf(w: Widget) = classifyTile(w, threshold, overrides[w.topic])
                fun spanOf(w: Widget) = tileSpan(clsOf(w), cols, textMode, w.topic in expandedTopics)
                val orderedGroups = remember(groups, cols, textMode, threshold, overrides, expandedTopics) {
                    groups.map { (d, ws) ->
                        d to ws.sortedWith(compareBy<Widget> { tileRank(clsOf(it), cols, textMode, it.topic in expandedTopics) }.then(tileWidgetOrder))
                    }
                }
                val orderedPinned = remember(pinnedWidgets, cols, textMode, threshold, overrides, expandedTopics) {
                    pinnedWidgets.sortedWith(compareBy { tileRank(clsOf(it.second), cols, textMode, it.second.topic in expandedTopics) })
                }
                val tile: @Composable (Device, Widget) -> Unit = { d, w ->
                    val cls = clsOf(w)
                    val exp = w.topic in expandedTopics
                    val tall = tileTall(cls, textMode, exp)
                    WidgetTile(w, vm, env, cls, !d.live && d.online != true, w.topic in pins, exp, actions,
                        modifier = Modifier.height(if (tall) tallH else tileH))
                }

                LazyVerticalGrid(
                    state = nav.dashList, columns = GridCells.Fixed(cols), modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(t.gap), verticalArrangement = Arrangement.spacedBy(t.gap),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    when {
                        devices.isEmpty() -> item(key = "empty", span = { GridItemSpan(cols) }) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Нет устройств — подключитесь к брокеру", color = t.dim, fontSize = 13.sp)
                                Button(onClick = { vm.hello() }, enabled = state is ConnState.Connected) { Text("HELLO — получить конфиги") }
                            }
                        }
                        query.isNotBlank() -> {
                            item(key = "s:count", span = { GridItemSpan(cols) }) {
                                Text("${searchResults.size} совпадений", fontSize = 11.sp, color = t.faint)
                            }
                            if (searchResults.isEmpty()) item(key = "s:empty", span = { GridItemSpan(cols) }) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Ничего не нашлось по «$query»", color = t.dim, fontSize = 13.sp)
                                    TextButton(onClick = { query = "" }) { Text("Сбросить поиск") }
                                }
                            }
                            items(searchResults, key = { "s:${it.first.id}/${it.second.id}" }, span = { GridItemSpan(spanOf(it.second)) },
                                contentType = { clsOf(it.second) }) { (d, w) -> tile(d, w) }
                        }
                        else -> {
                            if (noConfigs) item(key = "noconf", span = { GridItemSpan(cols) }) {
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.warn.copy(alpha = .1f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Конфигов нет — запросите HELLO", fontSize = 12.sp, color = t.warn, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { vm.hello() }, enabled = state is ConnState.Connected) { Text("HELLO") }
                                }
                            }
                            if (pinnedWidgets.isNotEmpty()) {
                                item(key = "h:pins", span = { GridItemSpan(cols) }) {
                                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                                        Text("Закреплённое", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
                                        Text("${pinnedWidgets.size}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = t.faint)
                                    }
                                }
                                items(orderedPinned,
                                    key = { "p:${it.first.id}/${it.second.id}" }, span = { GridItemSpan(spanOf(it.second)) },
                                    contentType = { clsOf(it.second) }) { (d, w) -> tile(d, w) }
                            }
                            orderedGroups.forEach { (d, ws) ->
                                item(key = "h:${d.id}", span = { GridItemSpan(cols) }, contentType = "header") {
                                    DeviceTileHeader(d, aliases[d.id], vm, ws.size, collapsed.contains(d.id),
                                        { collapsed = if (collapsed.contains(d.id)) collapsed - d.id else collapsed + d.id },
                                        { onOpenDevice(d.id) })
                                }
                                if (!collapsed.contains(d.id)) {
                                    items(ws, key = { "${d.id}/${it.id}" }, span = { GridItemSpan(spanOf(it)) },
                                        contentType = { clsOf(it) }) { w -> tile(d, w) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    inspectTopic?.let { topic ->
        val pair = remember(devices, topic) {
            devices.values.flatMap { d -> d.widgets.values.map { d to it } }.firstOrNull { it.second.topic == topic }
        }
        if (pair == null) { inspectTopic = null; return@let }
        val (d, w) = pair
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { inspectTopic = null }, sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface, contentColor = t.ink,
            dragHandle = { BottomSheetDefaults.DragHandle(color = t.tileBorder) }) {
            InspectorSheet(d, w, vm, aliases[d.id], threshold, overrides[topic],
                onOpenChart = { inspectTopic = null; onOpenChart(w.topic, w.descr.ifBlank { w.id }) })
        }
    }

    if (viewOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { viewOpen = false }, sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface, contentColor = t.ink,
            dragHandle = { BottomSheetDefaults.DragHandle(color = t.tileBorder) }) {
            ViewSheet(vm, columnsPref, density, textMode, threshold, glow, showAge, showSpark)
        }
    }
}

@Composable
private fun InspectorSheet(
    d: Device, w: Widget, vm: MainViewModel, alias: String?, threshold: Int, override: String?,
    onOpenChart: () -> Unit,
) {
    val t = LocalCraTokens.current
    val accent = MaterialTheme.colorScheme.primary
    val clipboard = LocalClipboardManager.current
    val pins by vm.pins.collectAsStateWithLifecycle()
    val auto = classifyTile(w, threshold, null)
    val cls = classifyTile(w, threshold, override)
    val why = (override?.let { "Оверрайд $it · автоправило: $auto. " } ?: "") + tileWhy(w, auto, threshold)
    var rows by remember { mutableStateOf<List<ReadingEntity>>(emptyList()) }
    LaunchedEffect(w.topic) {
        while (true) {
            rows = vm.readingsLast(w.topic, System.currentTimeMillis() - 6 * 3600_000L, 4000)
            delay(15_000)
        }
    }
    val histPoints = remember(rows) { rows.mapNotNull { r -> r.v1?.let { ChartPoint(r.ts, listOf(it)) } } }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(w.descr.ifBlank { w.id }, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text("${alias ?: d.id} · ${w.topic}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = t.faint, maxLines = 2)
            }
            IconButton(onClick = { vm.togglePin(w.topic) }) {
                Icon(Icons.Default.Star, null, tint = if (w.topic in pins) MaterialTheme.colorScheme.tertiary else t.faint)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(w.value ?: "—", style = craNumStyle(34.sp, t.ink), modifier = Modifier.weight(1f, fill = false))
            w.after?.let { Spacer(Modifier.width(5.dp)); Text(it, fontSize = 14.sp, color = t.dim) }
            Spacer(Modifier.weight(1f))
            FreshAge(w.valueAt, vm)
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = .09f))
            .border(1.dp, accent.copy(alpha = .3f), RoundedCornerShape(12.dp)).padding(11.dp)) {
            Text(why, fontSize = 12.sp, color = t.ink, lineHeight = 17.sp)
        }
        Text("РАЗМЕР ПЛИТКИ", fontSize = 10.sp, color = t.faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConsoleChip("Авто", override == null) { vm.setSizeOverride(w.topic, null) }
            listOf("S" to "S · 1×1", "M" to "M · 2×1", "L" to "L · серии", "W" to "W · текст").forEach { (v, label) ->
                ConsoleChip(label, override == v) { vm.setSizeOverride(w.topic, v) }
            }
        }
        if (isInputKind(w) || w.kind == WidgetKind.RANGE) {
            var text by remember(w.topic, w.value) { mutableStateOf(w.value ?: "") }
            Text("ОТПРАВИТЬ ЗНАЧЕНИЕ", fontSize = 10.sp, color = t.faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = t.tileBorder,
                        focusedContainerColor = t.tile, unfocusedContainerColor = t.tile, cursorColor = accent))
                Button(onClick = { vm.send(w, text) }, enabled = text.isNotBlank()) { Text("Отправить") }
            }
        }
        val sparkPts = if (histPoints.size >= 2) histPoints else w.points
        if (sparkPts.size >= 2) {
            Text("ИСТОРИЯ · 6 Ч", fontSize = 10.sp, color = t.faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.tile).border(1.dp, t.tileBorder, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Sparkline(sparkPts, Modifier.fillMaxWidth().height(70.dp), SeriesColors[0])
            }
            val ys = sparkPts.mapNotNull { it.ys.firstOrNull() }
            if (ys.isNotEmpty()) Text("min ${fmtBound(ys.min())} · avg ${fmtBound(ys.average())} · max ${fmtBound(ys.max())} · ${ys.size} т.",
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = t.dim)
        }
        if (w.kind == WidgetKind.CHART || w.value?.toDoubleOrNull() != null) {
            TextButton(onClick = onOpenChart, contentPadding = PaddingValues(0.dp)) { Text("Открыть график →", fontSize = 13.sp) }
        }
        Text("ТОПИК", fontSize = 10.sp, color = t.faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.tile).border(1.dp, t.tileBorder, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(w.topic, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = t.dim, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(w.topic)) }, contentPadding = PaddingValues(4.dp)) { Text("копировать", fontSize = 10.sp) }
        }
        Text("СЫРОЙ CONFIG · ИНЖЕНЕРНЫЙ СЛОЙ", fontSize = 10.sp, color = t.faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.tile).border(1.dp, t.tileBorder, RoundedCornerShape(12.dp)).padding(10.dp)) {
            Row {
                Text("config", fontSize = 10.sp, color = t.faint, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString("${w.topic}\n${w.rawConfig}\n${w.rawStatus ?: ""}")) },
                    contentPadding = PaddingValues(4.dp)) { Text("копировать", fontSize = 10.sp) }
            }
            Text(w.rawConfig.ifBlank { "—" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = t.ink, lineHeight = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text("status", fontSize = 10.sp, color = t.faint)
            Text(w.rawStatus ?: "—", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = t.ink, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun ViewSheet(vm: MainViewModel, cols: Int, density: Float, textMode: String, threshold: Int, glow: Boolean, showAge: Boolean, showSpark: Boolean) {
    val t = LocalCraTokens.current
    val cfg = LocalConfiguration.current
    val maxCols = ((cfg.screenWidthDp - 24 - 50) / 72).coerceIn(2, 6)
    var dens by remember(density) { mutableStateOf(density) }
    var thr by remember(threshold) { mutableStateOf(threshold.toFloat()) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Вид · раскладка плиток", style = MaterialTheme.typography.titleMedium)
        Text("КОЛОНКИ · на этом экране максимум $maxCols (квадрат ≥ 72 dp)", fontSize = 10.sp, color = t.faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConsoleChip("Авто", cols == 0) { vm.setColumns(0) }
            (2..6).forEach { n -> ConsoleChip("$n", cols == n) { vm.setColumns(n) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Плотность", fontSize = 12.sp, color = t.dim, modifier = Modifier.width(110.dp))
            Slider(value = dens, onValueChange = { dens = it }, onValueChangeFinished = { vm.setDensity(dens) },
                modifier = Modifier.weight(1f), valueRange = .8f..1.3f)
            Text("${(dens * 100).roundToInt()}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = t.ink, modifier = Modifier.width(44.dp))
        }
        Text("ДЛИННЫЙ ТЕКСТ", fontSize = 10.sp, color = t.faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConsoleChip("Обрезать", textMode == "clamp") { vm.setTextMode("clamp") }
            ConsoleChip("Перенос", textMode == "wrap") { vm.setTextMode("wrap") }
            ConsoleChip("Растянуть", textMode == "grow") { vm.setTextMode("grow") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Порог текста", fontSize = 12.sp, color = t.dim, modifier = Modifier.width(110.dp))
            Slider(value = thr, onValueChange = { thr = it }, onValueChangeFinished = { vm.setThreshold(thr.roundToInt()) },
                modifier = Modifier.weight(1f), valueRange = 16f..120f, steps = 25)
            Text("${thr.roundToInt()}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = t.ink, modifier = Modifier.width(44.dp))
        }
        Text("Длину не угадываем — измеряем: payload длиннее порога получает плитку W с раскрытием.", fontSize = 11.sp, color = t.faint, lineHeight = 15.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Возраст значения", fontSize = 12.sp, color = t.dim, modifier = Modifier.weight(1f))
            Switch(showAge, { vm.toggleAge() })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Спарклайны", fontSize = 12.sp, color = t.dim, modifier = Modifier.weight(1f))
            Switch(showSpark, { vm.toggleSpark() })
        }
        TextButton(onClick = { vm.resetView() }) { Text("Сбросить вид к умолчанию") }
    }
}

/** Совместимая строка для DeviceScreen: возраст+свежесть одним FreshAge. */
@Composable
fun WidgetConsoleRow(w: Widget, vm: MainViewModel, compact: Boolean, pinned: Boolean, onPin: () -> Unit) {
    val t = LocalCraTokens.current
    val accent = MaterialTheme.colorScheme.primary
    val clipboard = LocalClipboardManager.current
    var open by remember(w.topic) { mutableStateOf(false) }
    val isControl = w.kind == WidgetKind.TOGGLE || w.kind == WidgetKind.BTN_SWITCH ||
        w.kind == WidgetKind.SIMPLE_BTN || w.kind == WidgetKind.BTN || w.kind == WidgetKind.POWER_BUTTON
    val pad = if (compact) 6.dp else 9.dp
    Column(Modifier.fillMaxWidth().clickable { open = !open }) {
        Row(Modifier.fillMaxWidth().padding(vertical = pad), verticalAlignment = Alignment.CenterVertically) {
            Text(w.descr.ifBlank { w.id }, fontSize = if (compact) 13.sp else 14.sp, color = t.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (w.kind == WidgetKind.CHART && w.points.size >= 2) {
                Spacer(Modifier.width(6.dp))
                Sparkline(w.points, Modifier.width(72.dp).height(26.dp), SeriesColors[0])
            }
            Spacer(Modifier.width(8.dp))
            if (w.pending) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = accent)
            Spacer(Modifier.width(4.dp))
            when {
                w.kind == WidgetKind.TOGGLE || (w.kind == WidgetKind.BTN_SWITCH && w.mode != "momentary") ->
                    Switch(checked = w.value == "1", onCheckedChange = { vm.send(w, if (it) "1" else "0") }, colors = SwitchDefaults.colors(checkedTrackColor = accent))
                w.kind == WidgetKind.BTN_SWITCH && w.mode == "momentary" ->
                    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = .18f))
                        .pointerInput(w.topic) { detectTapGestures(onPress = { vm.send(w, "1"); tryAwaitRelease(); vm.send(w, "0") }) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)) { Text(w.title ?: w.descr.ifBlank { w.id }, color = accent, fontSize = 12.sp) }
                isControl ->
                    Button(onClick = { vm.send(w, w.data ?: "1") }, colors = ButtonDefaults.buttonColors(containerColor = iotColor(w.fill ?: w.color) ?: accent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) { Text(w.title ?: w.descr.ifBlank { w.id }, fontSize = 12.sp) }
                else -> Text("${w.before ?: ""}${w.value ?: "—"}${w.after ?: ""}", fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 14.sp else 15.sp, color = t.ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            }
            Spacer(Modifier.width(6.dp))
            FreshAge(w.valueAt, vm)
            TextButton(onClick = onPin, contentPadding = PaddingValues(4.dp)) {
                Text(if (pinned) "★" else "☆", color = if (pinned) MaterialTheme.colorScheme.tertiary else t.faint, fontSize = 14.sp)
            }
        }
        if (open) {
            Surface(shape = RoundedCornerShape(10.dp), color = t.tile) {
                Column(Modifier.padding(8.dp)) {
                    Row {
                        Text("config", fontSize = 10.sp, color = t.faint, modifier = Modifier.weight(1f))
                        TextButton(onClick = { clipboard.setText(AnnotatedString("${w.topic}\n${w.rawConfig}\n${w.rawStatus ?: ""}")) },
                            contentPadding = PaddingValues(4.dp)) { Text("копировать", fontSize = 10.sp) }
                    }
                    Text(w.rawConfig.ifBlank { "—" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = t.ink)
                    Spacer(Modifier.height(4.dp))
                    Text("status", fontSize = 10.sp, color = t.faint)
                    Text(w.rawStatus ?: "—", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = t.ink)
                }
            }
        }
        HorizontalDivider(color = t.tileBorder)
    }
}
