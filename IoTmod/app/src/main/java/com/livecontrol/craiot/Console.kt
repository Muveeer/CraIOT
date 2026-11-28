package com.livecontrol.craiot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livecontrol.craiot.protocol.Widget
import com.livecontrol.craiot.protocol.WidgetKind

internal val consoleWidgetOrder = compareBy<Widget>({ it.order ?: Int.MAX_VALUE }, { it.descr.ifBlank { it.id } }, { it.id })

internal fun isChartable(kind: WidgetKind, value: String?): Boolean =
    kind == WidgetKind.CHART || (value?.toDoubleOrNull() != null &&
        kind != WidgetKind.TOGGLE && kind != WidgetKind.BTN_SWITCH &&
        kind != WidgetKind.SIMPLE_BTN && kind != WidgetKind.BTN && kind != WidgetKind.POWER_BUTTON)

@Composable
fun SectionHeader(title: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        if (hint != null) Text(hint, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
