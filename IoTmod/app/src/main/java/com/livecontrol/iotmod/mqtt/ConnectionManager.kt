package com.livecontrol.iotmod.mqtt

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.min
import kotlin.random.Random

object Backoff {
    fun delayMs(
        attempt: Int, base: Long = 1_000, cap: Long = 60_000,
        jitter: Double = 0.2, rnd: Random = Random.Default,
    ): Long {
        val exp = base * (1L shl (attempt - 1).coerceIn(0, 16))
        val capped = min(exp, cap)
        val j = (capped * jitter * (rnd.nextDouble() * 2 - 1)).toLong()
        return (capped + j).coerceAtLeast(0)
    }
}

/** M1.3: единственный владелец политики переподключений. */
class ConnectionManager(
    private val transport: MqttTransport,
    private val scope: CoroutineScope,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 60_000,
    private val random: Random = Random(42),
) {
    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()
    val incoming: SharedFlow<RawMessage> get() = transport.incoming

    private var params: ConnectionParams? = null
    private var desired = false
    private var attempt = 0
    private var job: Job? = null
    private var watching = false

    /** Хук M2: после успешного connect+subscribe (сюда встанет HELLO). */
    var onConnected: (suspend () -> Unit)? = null

    fun connect(p: ConnectionParams) {
        params = p; desired = true; attempt = 0
        watch(); schedule(0)
    }

    fun disconnect() {
        desired = false; job?.cancel()
        scope.launch { transport.disconnect() }
        _state.value = ConnState.Disconnected
    }

    fun retryNow() { if (desired) { attempt = 0; schedule(0) } }

    fun onNetworkAvailable() {
        if (desired && _state.value !is ConnState.Connected) retryNow()
    }

    private fun watch() {
        if (watching) return; watching = true
        scope.launch {
            transport.state.collect { s ->
                // обрыв в активной сессии (не во время нашей попытки connect)
                if (s is ConnState.Error && desired && _state.value !is ConnState.Connecting) {
                    fail(s.kind, s.message)
                }
            }
        }
    }

    private fun schedule(delayMs: Long) {
        job?.cancel()
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val p = params ?: return@launch
            _state.value = ConnState.Connecting
            val r = transport.connect(p)
            if (r.isFailure) {
                val e = r.exceptionOrNull() ?: Exception("unknown")
                fail(MqttErrors.classify(e), MqttErrors.describe(e)); return@launch
            }
            val sub = transport.subscribe("${p.prefix}/#")
            if (sub.isFailure) {
                val e = sub.exceptionOrNull() ?: Exception("subscribe")
                fail(MqttErrors.classify(e), MqttErrors.describe(e)); return@launch
            }
            attempt = 0
            _state.value = ConnState.Connected
            onConnected?.invoke()
        }
    }

    private fun fail(kind: ErrorKind, msg: String) {
        if (!desired) return
        if (kind == ErrorKind.AUTH || kind == ErrorKind.TLS) {
            _state.value = ConnState.Error(kind, msg) // терминальные: ждём пользователя
            return
        }
        attempt++
        val d = Backoff.delayMs(attempt, baseDelayMs, maxDelayMs, rnd = random)
        _state.value = ConnState.Reconnecting(attempt, d, msg)
        schedule(d)
    }
}
