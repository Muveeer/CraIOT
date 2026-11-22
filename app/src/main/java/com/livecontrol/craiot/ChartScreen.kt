package com.livecontrol.craiot

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livecontrol.craiot.data.ReadingEntity
import com.livecontrol.craiot.protocol.ChartPoint
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

val SeriesColors = listOf(Color(0xFF82AAFF), Color(0xFF9ECE6A), Color(0xFFFFCB6B), Color(0xFFF7768E))
private val Caption = Color(0xFF97A3B4)
private const val BAR_SLOTS = 48
private data class Pt(val t: Long, val y: Double)
private data class Series(val idx: Int, val name: String, val pts: List<Pt>)
private data class Selection(val label: String, val vals: List<Double?>, val xFrac: Float)

@Composable
fun Sparkline(points: List<ChartPoint>, modifier: Modifier = Modifier, color: Color = SeriesColors[0]) {
    Canvas(modifier) {
        val ys = points.mapNotNull { it.ys.firstOrNull() }
        if (ys.size < 2) return@Canvas
        val mn = ys.min(); val mx = ys.max(); val span = (mx - mn).takeIf { it > 1e-9 } ?: 1.0
        val path = Path()
        ys.forEachIndexed { i, y ->
            val x = size.width * i / (ys.size - 1)
            val yy = size.height * (1 - ((y - mn) / span).toFloat())
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
    }
}

private fun downsample(pts: List<Pt>, buckets: Int, envelope: Boolean): List<Pt> {
    if (pts.size <= buckets) return pts
    val out = ArrayList<Pt>(buckets * 2)
    val t0 = pts.first().t; val span = (pts.last().t - t0).coerceAtLeast(1)
    var i = 0
    for (b in 0 until buckets) {
        val be = t0 + span * (b + 1) / buckets
        var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE; var tmn = 0L; var tmx = 0L
        var sum = 0.0; var n = 0; var lastT = t0
        while (i < pts.size && pts[i].t < be) {
            val p = pts[i]
            if (p.y < mn) { mn = p.y; tmn = p.t }; if (p.y > mx) { mx = p.y; tmx = p.t }
            sum += p.y; n++; lastT = p.t; i++
        }
        if (n == 0) continue
        if (envelope && mn != mx) {
            if (tmn <= tmx) { out += Pt(tmn, mn); out += Pt(tmx, mx) } else { out += Pt(tmx, mx); out += Pt(tmn, mn) }
        } else out += Pt(lastT, sum / n)
    }
    return out
}

private fun niceStep(range: Double): Double {
    val raw = (range / 4).coerceAtLeast(1e-9)
    val mag = 10.0.pow(floor(log10(raw))); val n = raw / mag
    return mag * when { n < 1.5 -> 1.0; n < 3 -> 2.0; n < 7 -> 5.0; else -> 10.0 }
}

private fun fmtVal(v: Double): String =
    if (v == v.toLong().toDouble() && abs(v) < 1e9) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

private fun bucketStart(t: Long, bucket: String): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = t
    c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    if (bucket == "day") c.set(Calendar.HOUR_OF_DAY, 0)
    return c.timeInMillis
}

@Composable
private fun ControlRow(caption: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(caption, fontSize = 11.sp, color = Caption, modifier = Modifier.width(88.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun ChartScreen(vm: MainViewModel, topic: String, title: String, onBack: () -> Unit) {
    var rangeMs by rememberSaveable { mutableStateOf(6 * 3600_000L) }
    var bucket by rememberSaveable { mutableStateOf("raw") }
    var mode by rememberSaveable { mutableStateOf("line") }
    var agg by rememberSaveable { mutableStateOf("avg") }
    var rows by remember { mutableStateOf<List<ReadingEntity>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var hidden by remember { mutableStateOf(setOf<Int>()) }
    var sel by remember { mutableStateOf<Selection?>(null) }
    var initialized by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val devices by vm.devices.collectAsStateWithLifecycle()
    val live = remember(devices, topic) {
        devices.values.flatMap { it.widgets.values }.firstOrNull { it.topic == topic }?.points ?: emptyList()
    }
    val drawMode = if (bucket == "raw") mode else "bar"
    val aggLabel = when (agg) { "max" -> "максимум"; "min" -> "минимум"; "last" -> "последнее значение"; else -> "среднее" }

    LaunchedEffect(topic, rangeMs) {
        while (true) {
            rows = vm.readings(topic, if (rangeMs == 0L) 0L else System.currentTimeMillis() - rangeMs)
            total = vm.historyCount()
            delay(5_000)
        }
    }

    val series = remember(rows, live) {
        if (rows.isNotEmpty()) {
            (1..4).mapNotNull { s ->
                val pts = rows.mapNotNull { r ->
                    val v = when (s) { 1 -> r.v1; 2 -> r.v2; 3 -> r.v3; else -> r.v4 }
                    v?.let { Pt(r.ts, it) }
                }
                if (pts.isEmpty()) null else Series(s, if (s == 1 && rows.all { it.v2 == null }) "значение" else "y$s", pts)
            }
        } else {
            (1..4).mapNotNull { s ->
                val pts = live.mapNotNull { p -> p.ys.getOrNull(s - 1)?.let { Pt(p.x, it) } }
                if (pts.isEmpty()) null else Series(s, if (s == 1) "значение" else "y$s", pts)
            }
        }
    }

    // Дневной ряд (точки раз в сутки) → сразу столбики по дням
    LaunchedEffect(series) {
        if (!initialized && series.isNotEmpty()) {
            val pts = series.first().pts
            if (pts.size >= 2) {
                val gaps = pts.zipWithNext { a, b -> b.t - a.t }.sorted()
                if (gaps[gaps.size / 2] >= 6 * 3600_000L) bucket = "day"
            }
            initialized = true
        }
    }

    val visible = series.filter { it.idx !in hidden }
    val bucketed = remember(visible, bucket, agg) {
        if (bucket == "raw" || visible.isEmpty()) null else {
            val keys = visible.flatMap { s -> s.pts.map { bucketStart(it.t, bucket) } }.distinct().sorted()
            val matrix = keys.map { k ->
                visible.map { s ->
                    val inB = s.pts.filter { bucketStart(it.t, bucket) == k }
                    val ys = inB.map { it.y }
                    when {
                        ys.isEmpty() -> null
                        agg == "max" -> ys.max()
                        agg == "min" -> ys.min()
                        agg == "last" -> inB.last().y
                        else -> ys.average()
                    }
                }
            }
            keys to matrix
        }
    }
    val t0 = visible.minOfOrNull { it.pts.first().t } ?: 0L
    val t1 = visible.maxOfOrNull { it.pts.last().t } ?: 0L
    val down = remember(visible, drawMode) { visible.map { it.idx to downsample(it.pts, 160, drawMode != "bar") } }
    // Столбцы: равномерные временные слоты, среднее в слоте, пустой слот = пауза данных
    val barBuckets = remember(visible, t0, t1) {
        val span = (t1 - t0).coerceAtLeast(1L)
        visible.map { s ->
            val sum = DoubleArray(BAR_SLOTS); val cnt = IntArray(BAR_SLOTS)
            s.pts.forEach { p ->
                val i = (((p.t - t0).toDouble() / span) * BAR_SLOTS).toInt().coerceIn(0, BAR_SLOTS - 1)
                sum[i] += p.y; cnt[i]++
            }
            Array(BAR_SLOTS) { i -> if (cnt[i] > 0) sum[i] / cnt[i] else null }
        }
    }
    val tm = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = Caption, fontFamily = FontFamily.Monospace)
    val xFmt = remember(bucket, rangeMs) {
        SimpleDateFormat(
            when {
                bucket == "day" -> "dd.MM"
                bucket == "hour" -> "HH:mm"
                rangeMs == 0L || rangeMs > 2 * 24 * 3600_000L -> "dd.MM HH:mm"
                else -> "HH:mm"
            }, Locale.getDefault()
        )
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(topic, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = {
                    val sb = StringBuilder("ts;").append(series.joinToString(";") { it.name }).append('\n')
                    rows.forEach { r ->
                        sb.append(r.ts).append(';')
                            .append(listOf(r.v1, r.v2, r.v3, r.v4).joinToString(";") { it?.let { v -> fmtVal(v) } ?: "" }).append('\n')
                    }
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sb.toString()) }, "CSV"))
                }) { Text("CSV") }
                TextButton(onClick = onBack) { Text("Назад") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ControlRow("Период") {
                    listOf(3600_000L to "1 ч", 6 * 3600_000L to "6 ч", 24 * 3600_000L to "24 ч", 7 * 24 * 3600_000L to "7 д", 0L to "Всё").forEach { (ms, label) ->
                        Chip(label, rangeMs == ms) { rangeMs = ms; sel = null }
                    }
                }
                ControlRow("Группировка") {
                    Chip("Точки", bucket == "raw") { bucket = "raw"; sel = null }
                    Chip("Часы", bucket == "hour") { bucket = "hour"; sel = null }
                    Chip("Дни", bucket == "day") { bucket = "day"; sel = null }
                }
                if (bucket == "raw") {
                    ControlRow("Вид") {
                        Chip("Линия", mode == "line") { mode = "line" }
                        Chip("Область", mode == "area") { mode = "area" }
                        Chip("Столбцы", mode == "bar") { mode = "bar" }
                    }
                } else {
                    ControlRow("Агрегация") {
                        Chip("Среднее", agg == "avg") { agg = "avg" }
                        Chip("Макс", agg == "max") { agg = "max" }
                        Chip("Мин", agg == "min") { agg = "min" }
                        Chip("Последнее", agg == "last") { agg = "last" }
                    }
                    Text("Точки объединяются за ${if (bucket == "hour") "час" else "день"}: $aggLabel", fontSize = 10.sp, color = Caption)
                }
                ControlRow("Серии") {
                    series.forEach { sr ->
                        val on = sr.idx !in hidden
                        Row(
                            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { hidden = if (on) hidden + sr.idx else hidden - sr.idx }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) SeriesColors[(sr.idx - 1) % 4] else Color.Gray))
                            Text(sr.name, fontSize = 12.sp, color = if (on) MaterialTheme.colorScheme.onSurface else Color.Gray)
                        }
                    }
                }
            }

            if (visible.isEmpty() || visible.all { it.pts.isEmpty() }) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (total == 0L) "История копится с момента установки M4.\nЧисловые статусы и точки chart пишутся в Room."
                            else "В этом диапазоне нет точек.\nВсего в истории: $total.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center,
                        )
                        if (total > 0L) Button(onClick = { rangeMs = 0L }) { Text("Показать всё") }
                    }
                }
            } else {
                val domainVals = if (bucketed != null) bucketed.second.flatten().filterNotNull() + 0.0
                else if (drawMode == "bar") barBuckets.flatMap { it.asList() }.filterNotNull() + 0.0
                else visible.flatMap { it.pts.map { p -> p.y } }
                var yMin = domainVals.min(); var yMax = domainVals.max()
                if (yMax - yMin < 1e-9) { yMin -= 1.0; yMax += 1.0 }
                if (drawMode == "bar") { yMin = minOf(yMin, 0.0); yMax *= 1.08 }
                else { val pad = (yMax - yMin) * 0.08; yMin -= pad; yMax += pad }
                val step = niceStep(yMax - yMin)

                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0B0E12))) {
                    Canvas(
                        Modifier.fillMaxSize().padding(start = 44.dp, end = 10.dp, top = 10.dp, bottom = 24.dp)
                            .pointerInput(bucketed, visible, t0, t1, drawMode) {
                                detectTapGestures { off ->
                                    val frac = (off.x / size.width).coerceIn(0f, 1f)
                                    if (bucketed != null) {
                                        val (keys, matrix) = bucketed
                                        if (keys.isNotEmpty()) {
                                            val i = (frac * keys.size).toInt().coerceIn(0, keys.size - 1)
                                            sel = Selection(xFmt.format(Date(keys[i])), matrix[i], (i + 0.5f) / keys.size)
                                        }
                                    } else if (drawMode == "bar") {
                                        val i = (frac * BAR_SLOTS).toInt().coerceIn(0, BAR_SLOTS - 1)
                                        val span = (t1 - t0).coerceAtLeast(1L)
                                        val t = t0 + span * i / BAR_SLOTS
                                        sel = Selection(xFmt.format(Date(t)), barBuckets.map { it[i] }, (i + 0.5f) / BAR_SLOTS)
                                    } else {
                                        val t = t0 + ((t1 - t0) * frac).toLong()
                                        sel = Selection(xFmt.format(Date(t)), visible.map { s -> s.pts.minByOrNull { abs(it.t - t) }?.y }, frac)
                                    }
                                }
                            }
                    ) {
                        val w = size.width; val h = size.height
                        fun Y(v: Double) = h * (1 - ((v - yMin) / (yMax - yMin)).toFloat())
                        var gv = ceil(yMin / step) * step
                        while (gv <= yMax + 1e-9) {
                            val y = Y(gv)
                            drawLine(Color(0xFF232B36), Offset(0f, y), Offset(w, y), 1f)
                            val tl = tm.measure(fmtVal(gv), axisStyle)
                            drawText(tl, topLeft = Offset(-40.dp.toPx(), y - tl.size.height / 2f))
                            gv += step
                        }
                        sel?.let {
                            drawLine(color = Caption, start = Offset(it.xFrac * w, 0f), end = Offset(it.xFrac * w, h),
                                strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                        }

                        if (bucketed != null) {
                            val (keys, matrix) = bucketed
                            val n = keys.size.coerceAtLeast(1)
                            val slot = w / n
                            val labelStep = ceil(n / 6f).toInt().coerceAtLeast(1)
                            keys.forEachIndexed { i, k ->
                                if (i % labelStep == 0) {
                                    val tl = tm.measure(xFmt.format(Date(k)), axisStyle)
                                    val cx = slot * i + slot / 2
                                    drawText(tl, topLeft = Offset((cx - tl.size.width / 2f).coerceIn(0f, w - tl.size.width), h + 6.dp.toPx()))
                                }
                            }
                            val groupW = slot * 0.72f
                            val bw = groupW / visible.size.coerceAtLeast(1)
                            matrix.forEachIndexed { i, row ->
                                row.forEachIndexed { si, v ->
                                    if (v == null) return@forEachIndexed
                                    val color = SeriesColors[(visible[si].idx - 1) % 4]
                                    val x = slot * i + (slot - groupW) / 2f + bw * si
                                    val y = Y(v); val y0 = Y(0.0).coerceIn(0f, h)
                                    drawRoundRect(
                                        color.copy(alpha = .9f), Offset(x, minOf(y, y0)),
                                        Size((bw * 0.86f).coerceAtLeast(1f), abs(y0 - y).coerceAtLeast(1f)),
                                        CornerRadius(2.dp.toPx()),
                                    )
                                }
                            }
                        } else if (drawMode == "bar") {
                            val slot = w / BAR_SLOTS
                            val bw = (slot * 0.62f).coerceAtLeast(2f)
                            val y0 = Y(0.0).coerceIn(0f, h)
                            for (i in 0..3) {
                                val t = t0 + (t1 - t0) * i / 3
                                val tl = tm.measure(xFmt.format(Date(t)), axisStyle)
                                drawText(tl, topLeft = Offset((slot * BAR_SLOTS * i / 3f - tl.size.width / 2f).coerceIn(0f, w - tl.size.width), h + 6.dp.toPx()))
                            }
                            barBuckets.forEachIndexed { si, arr ->
                                val color = SeriesColors[(visible[si].idx - 1) % 4]
                                val offset = (si - (visible.size - 1) / 2f) * (bw + 1f)
                                arr.forEachIndexed { i, v ->
                                    if (v == null) return@forEachIndexed
                                    val cx = slot * i + slot / 2 + offset
                                    val y = Y(v)
                                    drawRoundRect(color.copy(alpha = .9f), Offset(cx - bw / 2, minOf(y, y0)),
                                        Size(bw, abs(y0 - y).coerceAtLeast(2f)), CornerRadius(2.dp.toPx()))
                                }
                            }
                        } else {
                            fun X(t: Long) = if (t1 == t0) w / 2f else w * ((t - t0).toFloat() / (t1 - t0))
                            for (i in 0..3) {
                                val t = t0 + (t1 - t0) * i / 3
                                val tl = tm.measure(xFmt.format(Date(t)), axisStyle)
                                drawText(tl, topLeft = Offset((X(t) - tl.size.width / 2f).coerceIn(0f, w - tl.size.width), h + 6.dp.toPx()))
                            }
                            down.forEach { (idx, pts) ->
                                val color = SeriesColors[(idx - 1) % 4]
                                if (pts.size == 1) {
                                    val y = Y(pts[0].y)
                                    drawLine(color, Offset(X(pts[0].t) - 7.dp.toPx(), y), Offset(X(pts[0].t) + 7.dp.toPx(), y), 2.dp.toPx())
                                } else {
                                    val path = Path()
                                    pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(X(p.t), Y(p.y)) else path.lineTo(X(p.t), Y(p.y)) }
                                    if (drawMode == "area") {
                                        val fill = Path().apply { addPath(path); lineTo(X(pts.last().t), h); lineTo(X(pts.first().t), h); close() }
                                        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .35f), color.copy(alpha = 0f))))
                                    }
                                    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                                }
                            }
                        }
                    }
                }
                sel?.let { s ->
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(s.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            visible.forEachIndexed { i, sr ->
                                Text("${sr.name}: ${s.vals.getOrNull(i)?.let { fmtVal(it) } ?: "—"}", fontSize = 12.sp,
                                    color = SeriesColors[(sr.idx - 1) % 4], fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    visible.forEach { s ->
                        val ys = s.pts.map { it.y }
                        Text("${s.name}: min ${fmtVal(ys.min())} · avg ${fmtVal(ys.average())} · max ${fmtVal(ys.max())} · ${ys.size} т.",
                            fontSize = 11.sp, color = SeriesColors[(s.idx - 1) % 4], fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.clip(CircleShape)
            .background(if (selected) accent.copy(alpha = .22f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, fontSize = 12.sp, color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant) }
}
