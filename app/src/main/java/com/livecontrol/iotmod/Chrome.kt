package com.livecontrol.iotmod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.dp

private const val HIDE_PX = 56f
private const val SHOW_PX = 56f

/**
 * Скрытие хрома по РЕАЛЬНОМУ скроллу (nested-scroll), а не по позиции списка:
 * ресайз вьюпорта (скрытие бара/шапки) не порождает ложных срабатываний → нет дёрганий.
 * Гистерезис: накопленный дельта-скролл ≥ 56 px вниз — скрыть, ≥ 56 px вверх — показать.
 * Короткий список, который не скроллится, не трогает бар (он остаётся).
 */
class Chrome internal constructor(
    val barsHidden: MutableState<Boolean>,
    val headerCollapsed: MutableState<Boolean>,
) {
    internal var acc = 0f
    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy == 0f) return Offset.Zero
            if (dy < 0f && acc > 0f) acc = 0f
            if (dy > 0f && acc < 0f) acc = 0f
            acc += dy
            if (acc <= -HIDE_PX) { barsHidden.value = true; headerCollapsed.value = true }
            else if (acc >= SHOW_PX) { barsHidden.value = false; headerCollapsed.value = false }
            return Offset.Zero
        }
    }
    internal fun resetAtTop() { acc = 0f; barsHidden.value = false; headerCollapsed.value = false }
}

@Composable
fun rememberChrome(barsHidden: MutableState<Boolean>, index: () -> Int, offset: () -> Int): Chrome {
    val headerCollapsed = rememberSaveable { mutableStateOf(false) }
    val chrome = remember(barsHidden, headerCollapsed) { Chrome(barsHidden, headerCollapsed) }
    LaunchedEffect(chrome) {
        snapshotFlow { index() to offset() }.collect { (i, off) ->
            if (i == 0 && off == 0) chrome.resetAtTop()
        }
    }
    return chrome
}

@Composable
fun CraSearchField(query: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
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
            if (query.isEmpty()) Text(placeholder, style = CraType.body, color = t.faint, maxLines = 1)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = CraType.body.copy(color = t.ink),
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
fun CollapseHeader(title: String, count: Int?, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalCraTokens.current
    Row(modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = CraType.groupTitle, color = t.ink, maxLines = 1, modifier = Modifier.weight(1f))
        if (count != null) Text("$count", style = CraType.meta, color = t.faint)
        Spacer(Modifier.width(6.dp))
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = t.faint, modifier = Modifier.size(18.dp))
    }
}
