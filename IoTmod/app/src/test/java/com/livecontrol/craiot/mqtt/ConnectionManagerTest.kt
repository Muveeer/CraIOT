package com.livecontrol.craiot.mqtt

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.eclipse.paho.client.mqttv3.MqttException
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException

class FakeTransport : MqttTransport {
    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    override val state = _state.asStateFlow()
    override val incoming = MutableSharedFlow<RawMessage>(extraBufferCapacity = 64)
    val results = ArrayDeque<Result<Unit>>()
    var connectCalls = 0
    val subscribed = mutableListOf<String>()
    override suspend fun connect(params: ConnectionParams): Result<Unit> {
        connectCalls++
        _state.value = ConnState.Connecting
        val r = if (results.isNotEmpty()) results.removeFirst() else Result.success(Unit)
        _state.value = if (r.isSuccess) ConnState.Connected else ConnState.Disconnected
        return r
    }
    override suspend fun subscribe(filter: String, qos: Int): Result<Unit> {
        subscribed += filter; return Result.success(Unit)
    }
    override suspend fun publish(topic: String, payload: String, qos: Int, retained: Boolean) = Result.success(Unit)
    override fun disconnect() { _state.value = ConnState.Disconnected }
    fun loseConnection() { _state.value = ConnState.Error(ErrorKind.NETWORK, "обрыв") }
}

class ConnectionManagerTest {
    private fun params() = ConnectionParams(Transport.WSS, "h", 18883, "/ws", "/ver407", "u", "p")

    @Test fun success_subscribes_and_fires_hook() = runTest {
        val t = FakeTransport(); var hooked = false
        val m = ConnectionManager(t, backgroundScope)
        m.onConnected = { hooked = true }
        m.connect(params()); advanceUntilIdle()
        assertEquals(ConnState.Connected, m.state.value)
        assertEquals(listOf("/ver407/#"), t.subscribed)
        assertTrue(hooked)
    }

    @Test fun auth_error_is_terminal() = runTest {
        val t = FakeTransport()
        t.results += Result.failure(MqttException(MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()))
        val m = ConnectionManager(t, backgroundScope)
        m.connect(params()); advanceUntilIdle()
        assertEquals(ErrorKind.AUTH, (m.state.value as ConnState.Error).kind)
        advanceTimeBy(120_000)
        assertEquals(1, t.connectCalls) // никакого шторма
    }

    @Test fun network_errors_backoff_then_success() = runTest {
        val t = FakeTransport()
        t.results += Result.failure(ConnectException("refused"))
        t.results += Result.failure(ConnectException("refused"))
        val m = ConnectionManager(t, backgroundScope)
        m.connect(params()); advanceUntilIdle()
        assertEquals(1, (m.state.value as ConnState.Reconnecting).attempt)
        advanceTimeBy(1_300)
        assertEquals(2, (m.state.value as ConnState.Reconnecting).attempt)
        advanceTimeBy(2_600); advanceUntilIdle()
        assertEquals(ConnState.Connected, m.state.value)
        assertEquals(3, t.connectCalls)
    }

    @Test fun disconnect_cancels_pending_retry() = runTest {
        val t = FakeTransport()
        t.results += Result.failure(ConnectException("x"))
        val m = ConnectionManager(t, backgroundScope)
        m.connect(params()); advanceUntilIdle()
        m.disconnect(); advanceTimeBy(120_000)
        assertEquals(1, t.connectCalls)
        assertEquals(ConnState.Disconnected, m.state.value)
    }

    @Test fun mid_session_loss_triggers_reconnect() = runTest {
        val t = FakeTransport()
        val m = ConnectionManager(t, backgroundScope)
        m.connect(params()); advanceUntilIdle()
        t.loseConnection(); advanceTimeBy(10)
        assertEquals(1, (m.state.value as ConnState.Reconnecting).attempt)
        advanceTimeBy(1_300); advanceUntilIdle()
        assertEquals(ConnState.Connected, m.state.value)
    }

    @Test fun retry_now_resets_attempt() = runTest {
        val t = FakeTransport()
        t.results += Result.failure(ConnectException("x"))
        val m = ConnectionManager(t, backgroundScope)
        m.connect(params()); advanceUntilIdle()
        m.retryNow(); advanceUntilIdle()
        assertEquals(ConnState.Connected, m.state.value)
        t.loseConnection(); advanceTimeBy(10)
        assertEquals(1, (m.state.value as ConnState.Reconnecting).attempt)
    }

    @Test fun network_available_reconnects_immediately() = runTest {
        val t = FakeTransport()
        t.results += Result.failure(ConnectException("x"))
        val m = ConnectionManager(t, backgroundScope)
        m.connect(params()); advanceUntilIdle()
        assertTrue(m.state.value is ConnState.Reconnecting)
        m.onNetworkAvailable(); advanceUntilIdle()
        assertEquals(ConnState.Connected, m.state.value)
        assertEquals(2, t.connectCalls)
    }
}
