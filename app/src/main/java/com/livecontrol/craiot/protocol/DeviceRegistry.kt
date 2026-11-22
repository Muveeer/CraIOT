package com.livecontrol.craiot.protocol

import com.livecontrol.craiot.mqtt.MqttTransport
import com.livecontrol.craiot.mqtt.RawMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

 data class UnknownEntry(val topic: String, val payload: String, val reason: String, val at: Long)

class DeviceRegistry(scope: CoroutineScope, incoming: SharedFlow<RawMessage>) {
    private val lock = Any()
    private var map = LinkedHashMap<String, Device>()
    private var prefix = ""
    private var unknownCount = 0L
    private val unknownBuffer = ArrayDeque<UnknownEntry>()
    @Volatile private var dirty = false

    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()
    private val _unknown = MutableStateFlow(0L)
    val unknown: StateFlow<Long> = _unknown.asStateFlow()
    private val _unknownList = MutableStateFlow<List<UnknownEntry>>(emptyList())
    val unknownList: StateFlow<List<UnknownEntry>> = _unknownList.asStateFlow()

    init {
        scope.launch { incoming.collect { message -> synchronized(lock) { handle(message) } } }
        scope.launch {
            while (isActive) {
                delay(150)
                if (dirty) {
                    dirty = false
                    val now = System.currentTimeMillis()
                    val (snapshot, unknown, unknownEntries) = synchronized(lock) {
                        val current = LinkedHashMap(map)
                        for ((id, device) in current) {
                            current[id] = device.copy(
                                live = device.lastSeen != null && now - device.lastSeen < LIVE_MS,
                            )
                        }
                        Triple(current, unknownCount, unknownBuffer.toList())
                    }
                    _devices.value = snapshot
                    _unknown.value = unknown
                    _unknownList.value = unknownEntries
                }
            }
        }
    }

    fun bind(prefix: String) {
        synchronized(lock) {
            this.prefix = prefix
            map = LinkedHashMap()
            unknownCount = 0
            unknownBuffer.clear()
        }
        dirty = false
        _devices.value = emptyMap()
        _unknown.value = 0
        _unknownList.value = emptyList()
    }

    /** M4: build the dashboard from cached configs before HELLO or a network connection. */
    fun preload(prefix: String, entries: List<Pair<String, String>>) {
        bind(prefix)
        synchronized(lock) {
            entries.forEach { (topic, json) ->
                when (val parsed = ProtocolParser.parse(prefix, RawMessage(topic, json))) {
                    is Parsed.Config -> update(parsed.deviceId) {
                        applyConfig(it, parsed.widget, parsed.hideOnly, 0L)
                    }
                    is Parsed.ConfigBatch -> update(parsed.deviceId) { device ->
                        parsed.widgets.fold(device) { current, widget ->
                            applyConfig(current, widget, false, 0L)
                        }
                    }
                    else -> Unit
                }
            }
        }
        dirty = true
    }

    fun optimistic(topic: String, value: String) {
        synchronized(lock) { mutate(topic) { it.copy(value = value, pending = true) } }
        dirty = true
    }

    fun rollback(topic: String, previous: String?) {
        synchronized(lock) { mutate(topic) { it.copy(value = previous, pending = false) } }
        dirty = true
    }

    private fun mutate(topic: String, transform: (Widget) -> Widget) {
        for ((deviceId, device) in map) {
            val entry = device.widgets.entries.find { it.value.topic == topic } ?: continue
            map[deviceId] = device.copy(widgets = device.widgets + (entry.key to transform(entry.value)))
            return
        }
    }

    private fun handle(message: RawMessage) {
        if (prefix.isBlank()) return
        when (val parsed = ProtocolParser.parse(prefix, message)) {
            is Parsed.Hello, is Parsed.Control -> Unit
            is Parsed.Announce -> update(parsed.deviceId) {
                it.copy(announced = true, lastSeen = message.at)
            }
            is Parsed.State -> update(parsed.deviceId) {
                it.copy(
                    online = parsed.online,
                    stateAt = message.at,
                    lastSeen = message.at,
                )
            }
            is Parsed.Config -> update(parsed.deviceId) {
                applyConfig(it, parsed.widget, parsed.hideOnly, message.at)
            }
            is Parsed.ConfigBatch -> update(parsed.deviceId) { device ->
                parsed.widgets.fold(device) { current, widget ->
                    applyConfig(current, widget, false, message.at)
                }
            }
            is Parsed.StatusValue -> widget(parsed.deviceId, parsed.widgetTopic, message.at) {
                it.copy(
                    value = parsed.value,
                    valueAt = message.at,
                    rawStatus = message.payload,
                    pending = false,
                )
            }
            is Parsed.StatusSeries -> widget(parsed.deviceId, parsed.widgetTopic, message.at) {
                it.copy(
                    points = parsed.points.takeLast(MAX_WIDGET_POINTS),
                    value = parsed.points.lastOrNull()?.ys?.firstOrNull()?.toString(),
                    valueAt = message.at,
                    rawStatus = message.payload,
                    pending = false,
                )
            }
            is Parsed.StatusInfo -> widget(parsed.deviceId, parsed.widgetTopic, message.at) {
                it.copy(info = parsed.info, infoColor = parsed.color, rawStatus = message.payload)
            }
            is Parsed.Event -> widget(parsed.deviceId, parsed.widgetTopic, message.at) {
                it.copy(rawStatus = message.payload)
            }
            is Parsed.Unknown -> {
                unknownCount++
                unknownBuffer.addFirst(
                    UnknownEntry(message.topic, message.payload, parsed.reason, message.at),
                )
                while (unknownBuffer.size > MAX_UNKNOWN) unknownBuffer.removeLast()
            }
        }
        dirty = true
    }

    private fun applyConfig(device: Device, widget: Widget, hideOnly: Boolean, at: Long): Device {
        val byTopic = device.widgets.entries.find { it.value.topic == widget.topic }
        val old = byTopic?.value ?: device.widgets[widget.id]
        var widgets = device.widgets
        if (byTopic != null && byTopic.key != widget.id) widgets = widgets - byTopic.key

        val merged = if (hideOnly) {
            (old ?: widget).copy(hidden = true)
        } else {
            widget.copy(
                value = old?.value,
                info = old?.info,
                infoColor = old?.infoColor,
                valueAt = old?.valueAt,
                points = old?.points ?: emptyList(),
                rawStatus = old?.rawStatus,
                pending = old?.pending ?: false,
                hidden = false,
            )
        }
        return device.copy(
            widgets = widgets + (widget.id to merged),
            lastSeen = if (at > 0L) at else device.lastSeen,
        )
    }

    private fun update(deviceId: String, transform: (Device) -> Device) {
        map[deviceId] = transform(map[deviceId] ?: Device(id = deviceId))
    }

    private fun widget(deviceId: String, topic: String, at: Long, transform: (Widget) -> Widget) {
        update(deviceId) { device ->
            val entry = device.widgets.entries.find { it.value.topic == topic }
            val key = entry?.key ?: topic.substringAfterLast('/')
            val current = entry?.value ?: Widget(
                id = key,
                deviceId = deviceId,
                topic = topic,
                kind = WidgetKind.UNKNOWN,
                typeRaw = "",
                descr = key,
                page = "",
                pageId = null,
                mode = null,
                color = null,
                before = null,
                after = null,
                min = null,
                max = null,
                step = null,
            )
            device.copy(
                widgets = device.widgets + (key to transform(current)),
                lastSeen = at,
            )
        }
    }

    private companion object {
        const val LIVE_MS = 90_000L
        const val MAX_UNKNOWN = 100
        const val MAX_WIDGET_POINTS = 240
    }
}

class WidgetCommands(
    private val scope: CoroutineScope,
    private val transport: MqttTransport,
    private val registry: DeviceRegistry,
    incoming: SharedFlow<RawMessage>,
) {
    private data class Pending(val previous: String?, val job: Job)

    private val lock = Any()
    private val pending = HashMap<String, Pending>()

    init {
        scope.launch {
            incoming.collect { message ->
                if (message.topic.endsWith("/status")) confirm(message.topic.removeSuffix("/status"))
            }
        }
    }

    fun send(widget: Widget, value: String): Job {
        val base = widget.topic
        val previous = widget.value
        return scope.launch {
            registry.optimistic(base, value)
            val result = transport.publish("$base/control", value, qos = 1)
            if (result.isFailure) {
                registry.rollback(base, previous)
                return@launch
            }
            val timeout = scope.launch {
                delay(5_000)
                synchronized(lock) { pending.remove(base) }
                registry.rollback(base, previous)
            }
            synchronized(lock) { pending.put(base, Pending(previous, timeout))?.job?.cancel() }
        }
    }

    private fun confirm(base: String) {
        synchronized(lock) { pending.remove(base)?.job?.cancel() }
    }
}
