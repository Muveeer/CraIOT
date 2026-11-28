package com.livecontrol.iotmod

import com.livecontrol.iotmod.mqtt.RawMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** M1.8: кольцевой буфер 5000, батчинг UI ~150 мс, пауза замораживает ленту, но не сбор. */
class Journal(scope: CoroutineScope, incoming: SharedFlow<RawMessage>) {
    data class Entry(val seq: Long, val msg: RawMessage)

    private val buf = ArrayDeque<Entry>()
    private val _items = MutableStateFlow<List<Entry>>(emptyList())
    val items: StateFlow<List<Entry>> = _items.asStateFlow()
    private var seq = 0L
    @Volatile private var dirty = false
    @Volatile private var paused = false

    init {
        scope.launch {
            incoming.collect { m ->
                synchronized(buf) {
                    buf.addFirst(Entry(seq++, m))
                    while (buf.size > CAP) buf.removeLast()
                }
                dirty = true
            }
        }
        scope.launch {
            while (isActive) {
                delay(150)
                if (dirty && !paused) {
                    dirty = false
                    _items.value = synchronized(buf) { buf.toList() }
                }
            }
        }
    }

    fun setPaused(p: Boolean) { paused = p; if (!p) dirty = true }
    fun clear() { synchronized(buf) { buf.clear() }; _items.value = emptyList() }

    private companion object { const val CAP = 2000 }
}
