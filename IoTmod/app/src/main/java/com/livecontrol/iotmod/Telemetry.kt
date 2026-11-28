package com.livecontrol.iotmod

import com.livecontrol.iotmod.mqtt.ConnState
import com.livecontrol.iotmod.mqtt.ConnectionManager
import com.livecontrol.iotmod.mqtt.RawMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Живая телеметрия потока: rate, гистограмма, счётчики типов, per-device rate, сессия, reconnects. */
class Telemetry(scope: CoroutineScope, incoming: SharedFlow<RawMessage>, manager: ConnectionManager) {
    data class Snap(
        val rate: Double = 0.0, val spark: List<Float> = emptyList(), val total: Long = 0,
        val byType: Map<String, Long> = emptyMap(), val perDevice: Map<String, Double> = emptyMap(),
        val sessionStart: Long = 0, val reconnects: Int = 0, val lastMsgAt: Long = 0,
    )
    private val lock = Any()
    private val sec = ArrayDeque<Long>()
    private var curSec = 0L; private var curCount = 0L
    private val devAt = HashMap<String, ArrayDeque<Long>>()
    private val types = HashMap<String, Long>()
    private var total = 0L; private var lastAt = 0L
    private var sessionStart = 0L; private var reconnects = 0; private var wasConnected = false
    private val _snap = MutableStateFlow(Snap())
    val snap: StateFlow<Snap> = _snap.asStateFlow()

    init {
        scope.launch {
            incoming.collect { m ->
                synchronized(lock) {
                    total++; lastAt = m.at
                    val t = typeOf(m.topic); types[t] = (types[t] ?: 0) + 1
                    val s = m.at / 1000
                    if (s != curSec) { sec.addLast(curCount); while (sec.size > 60) sec.removeFirst(); curSec = s; curCount = 0 }
                    curCount++
                    val dev = m.topic.split("/").getOrElse(2) { "" }
                    val d = devAt.getOrPut(dev) { ArrayDeque() }
                    d.addLast(m.at); while (d.size > 400) d.removeFirst()
                }
            }
        }
        scope.launch { while (isActive) { delay(1000); publish() } }
        scope.launch {
            manager.state.collect { st ->
                when (st) {
                    is ConnState.Connected -> if (!wasConnected) { sessionStart = System.currentTimeMillis(); wasConnected = true }
                    is ConnState.Reconnecting -> if (wasConnected) { reconnects++; wasConnected = false }
                    is ConnState.Disconnected, is ConnState.Error -> wasConnected = false
                    else -> Unit
                }
            }
        }
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val (rate, spark, perDev, typesCopy, tot, last) = synchronized(lock) {
            val buckets = sec.toList() + curCount
            val recent = buckets.takeLast(5)
            val r = if (recent.isEmpty()) 0.0 else recent.sum().toDouble() / recent.size
            val mx = (buckets.maxOrNull() ?: 1L).coerceAtLeast(1)
            val sp = buckets.takeLast(60).map { it.toFloat() / mx }
            val pd = devAt.mapValues { (_, dq) -> dq.count { now - it < 5000 } / 5.0 }
            Sextuple(r, sp, pd, HashMap(types), total, lastAt)
        }
        _snap.value = Snap(rate, spark, tot, typesCopy, perDev, sessionStart, reconnects, last)
    }

    private data class Sextuple(val a: Double, val b: List<Float>, val c: Map<String, Double>, val d: Map<String, Long>, val e: Long, val f: Long)

    companion object {
        fun typeOf(topic: String): String = when {
            topic.endsWith("/status") -> "status"
            topic.endsWith("/config") -> "config"
            topic.endsWith("/event") -> "event"
            topic.endsWith("/control") -> "control"
            topic.endsWith("/state") -> "state"
            else -> "other"
        }
    }
}
