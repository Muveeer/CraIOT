package com.livecontrol.craiot.protocol

import com.livecontrol.craiot.mqtt.RawMessage
import org.json.JSONArray
import org.json.JSONObject

enum class WidgetKind {
    TOGGLE, BTN_SWITCH, SIMPLE_BTN, BTN, POWER_BUTTON, ANYDATA, DISPLAY_VALUE, SMALL_BADGE,
    RANGE, INPUT, INPUT_NUMBER, INPUT_DATE, SELECT, PROGRESS_LINE, PROGRESS_ROUND,
    FILLGAUGE, GAUGE, DOUGHNUT, CHART, STEEL, MARKDOWN, UNKNOWN;

    companion object {
        fun from(s: String?): WidgetKind = when (s?.lowercase()) {
            "toggle" -> TOGGLE
            "btn-switch" -> BTN_SWITCH
            "simple-btn" -> SIMPLE_BTN
            "btn" -> BTN
            "power-button" -> POWER_BUTTON
            "anydata" -> ANYDATA
            "display-value" -> DISPLAY_VALUE
            "small-badge" -> SMALL_BADGE
            "range" -> RANGE
            "input" -> INPUT
            "input-number" -> INPUT_NUMBER
            "input-date" -> INPUT_DATE
            "select" -> SELECT
            "progress-line" -> PROGRESS_LINE
            "progress-round" -> PROGRESS_ROUND
            "fillgauge" -> FILLGAUGE
            "gauge" -> GAUGE
            "doughnut" -> DOUGHNUT
            "chart" -> CHART
            "steel" -> STEEL
            "markdown" -> MARKDOWN
            else -> UNKNOWN
        }
    }
}

/** Chart coordinates are unix milliseconds; null entries preserve y1…y4 positions. */
data class ChartPoint(val x: Long, val ys: List<Double?>)

data class Widget(
    val id: String,
    val deviceId: String,
    val topic: String,
    val kind: WidgetKind,
    val typeRaw: String,
    val descr: String,
    val page: String,
    val pageId: Int?,
    val order: Int? = null,
    val mode: String?,
    val color: String?,
    val before: String?,
    val after: String?,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val title: String? = null,
    val data: String? = null,
    val fill: String? = null,
    val hidden: Boolean = false,
    val pending: Boolean = false,
    val value: String? = null,
    val info: String? = null,
    val infoColor: String? = null,
    val valueAt: Long? = null,
    val points: List<ChartPoint> = emptyList(),
    val rawConfig: String = "",
    val rawStatus: String? = null,
)

data class Device(
    val id: String,
    val online: Boolean? = null,
    val live: Boolean = false,
    val stateAt: Long? = null,
    val lastSeen: Long? = null,
    val announced: Boolean = false,
    val widgets: Map<String, Widget> = emptyMap(),
)

sealed interface Parsed {
    data class Hello(val raw: RawMessage) : Parsed
    data class Announce(val deviceId: String, val raw: RawMessage) : Parsed
    data class State(val deviceId: String, val online: Boolean, val raw: RawMessage) : Parsed
    data class Config(val deviceId: String, val widget: Widget, val hideOnly: Boolean, val raw: RawMessage) : Parsed
    data class ConfigBatch(val deviceId: String, val widgets: List<Widget>, val raw: RawMessage) : Parsed
    data class StatusValue(val deviceId: String, val widgetTopic: String, val value: String, val raw: RawMessage) : Parsed
    data class StatusSeries(val deviceId: String, val widgetTopic: String, val points: List<ChartPoint>, val raw: RawMessage) : Parsed
    data class StatusInfo(
        val deviceId: String,
        val widgetTopic: String,
        val info: String,
        val color: String?,
        val raw: RawMessage,
    ) : Parsed
    data class Event(val deviceId: String, val widgetTopic: String, val value: String?, val raw: RawMessage) : Parsed
    data class Control(val deviceId: String, val widgetTopic: String, val value: String, val raw: RawMessage) : Parsed
    data class Unknown(val raw: RawMessage, val reason: String) : Parsed
}

object ProtocolParser {
    fun parse(prefix: String, message: RawMessage): Parsed {
        val topic = message.topic
        if (topic == prefix) {
            return if (message.payload.trim() == "HELLO") {
                Parsed.Hello(message)
            } else {
                Parsed.Announce(message.payload.trim(), message)
            }
        }
        if (!topic.startsWith("$prefix/")) return Parsed.Unknown(message, "вне префикса")

        val parts = topic.removePrefix("$prefix/").split("/")
        val deviceId = parts[0]
        if (parts.size == 2 && parts[1] == "state") {
            val obj = obj(message.payload) ?: return Parsed.Unknown(message, "state не JSON")
            return Parsed.State(deviceId, obj.optString("status").equals("online", true), message)
        }

        if (parts.size >= 2 && parts.last() == "config") {
            val widgetTopic = if (parts.size == 2) {
                ""
            } else {
                "$prefix/" + parts.subList(1, parts.size - 1).joinToString("/")
            }
            val array = runCatching { JSONArray(message.payload) }.getOrNull()
            if (array != null) {
                val widgets = (0 until array.length())
                    .mapNotNull { index -> runCatching { array.getJSONObject(index) }.getOrNull() }
                    .mapNotNull { widgetFrom(it, deviceId, widgetTopic, prefix, message.payload) }
                return if (widgets.isEmpty()) {
                    Parsed.Unknown(message, "config batch пуст")
                } else {
                    Parsed.ConfigBatch(deviceId, widgets, message)
                }
            }
            val obj = obj(message.payload) ?: return Parsed.Unknown(message, "config не JSON")
            val hideOnly = obj.length() == 1 && obj.has("id")
            val widget = widgetFrom(obj, deviceId, widgetTopic, prefix, message.payload)
                ?: return Parsed.Unknown(message, "config без id и topic")
            return Parsed.Config(deviceId, widget, hideOnly, message)
        }

        if (parts.size >= 3) {
            val widgetTopic = "$prefix/" + parts.dropLast(1).joinToString("/")
            return when (parts.last()) {
                "status" -> {
                    val obj = obj(message.payload)
                    val status = obj?.opt("status")
                    when {
                        obj == null -> Parsed.StatusValue(deviceId, widgetTopic, message.payload.trim(), message)
                        status is JSONArray -> {
                            val points = ArrayList<ChartPoint>()
                            for (index in 0 until status.length()) {
                                val point = status.optJSONObject(index) ?: continue
                                val yValues = listOf(
                                    point.dbl("y1"),
                                    point.dbl("y2"),
                                    point.dbl("y3"),
                                    point.dbl("y4"),
                                )
                                if (yValues.any { it != null }) {
                                    points += ChartPoint(
                                        point.optLong("x", message.at / 1_000L) * 1_000L,
                                        yValues,
                                    )
                                }
                            }
                            Parsed.StatusSeries(deviceId, widgetTopic, points, message)
                        }
                        obj.has("status") -> Parsed.StatusValue(deviceId, widgetTopic, str(status), message)
                        obj.has("info") -> Parsed.StatusInfo(
                            deviceId,
                            widgetTopic,
                            obj.optString("info"),
                            obj.optString("color", null),
                            message,
                        )
                        else -> Parsed.Unknown(message, "status без status/info")
                    }
                }
                "event" -> Parsed.Event(
                    deviceId,
                    widgetTopic,
                    obj(message.payload)?.opt("val")?.let { str(it) },
                    message,
                )
                "control" -> Parsed.Control(deviceId, widgetTopic, message.payload.trim(), message)
                else -> Parsed.Unknown(message, "лист ${parts.last()}")
            }
        }
        return Parsed.Unknown(message, "короткий топик")
    }

    private fun widgetFrom(
        obj: JSONObject,
        deviceId: String,
        widgetTopic: String,
        prefix: String,
        raw: String,
    ): Widget? {
        val topic = obj.optString("topic", "").ifBlank { widgetTopic }
        val id = idOf(obj)
            ?: topic.substringAfterLast('/').ifBlank { null }
            ?: widgetTopic.substringAfterLast('/').ifBlank { null }
            ?: return null
        val widgetConfig = obj.optJSONObject("widgetConfig")
        val typeRaw = obj.optString("widget", "").ifBlank { obj.optString("type", "") }
        return Widget(
            id = id,
            deviceId = deviceId,
            topic = topic.ifBlank { "$prefix/$deviceId/$id" },
            kind = WidgetKind.from(typeRaw),
            typeRaw = typeRaw,
            descr = obj.optString("descr", "").ifBlank { obj.optString("desc", "") },
            page = obj.optString("page", ""),
            pageId = if (obj.has("pageId")) obj.optInt("pageId") else null,
            order = if (obj.has("order")) obj.optInt("order") else if (obj.has("num")) obj.optInt("num") else null,
            mode = obj.optString("mode", null),
            color = obj.optString("color", null),
            before = obj.optString("before", null),
            after = obj.optString("after", "").ifBlank { obj.optString("units", "") }.ifBlank { null },
            min = num(obj, "min"),
            max = num(obj, "max"),
            step = num(obj, "step"),
            title = widgetConfig?.optString("title", null),
            data = widgetConfig?.optString("data", null),
            fill = widgetConfig?.optString("fill", null),
            rawConfig = raw,
        )
    }

    private fun JSONObject.dbl(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() } else null

    private fun obj(value: String): JSONObject? = runCatching { JSONObject(value) }.getOrNull()
    private fun idOf(obj: JSONObject): String? = if (obj.has("id")) str(obj.get("id")).ifBlank { null } else null
    private fun num(obj: JSONObject, key: String): Double? = if (!obj.has(key)) null else runCatching { obj.getDouble(key) }.getOrNull()
    private fun str(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        else -> value.toString()
    }
}
