package com.livecontrol.iotmod.mqtt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

enum class Transport { TCP, WS, WSS }
enum class ErrorKind { AUTH, TLS, NETWORK, TIMEOUT, PROTOCOL, UNKNOWN }

data class ConnectionParams(
    val protocol: Transport, val host: String, val port: Int,
    val path: String, val prefix: String,
    val username: String, val password: String,
) {
    fun serverUri(): String = when (protocol) {
        Transport.TCP -> "tcp://$host:$port"
        Transport.WS  -> "ws://$host:$port${path.ifBlank { "/mqtt" }}"
        Transport.WSS -> "wss://$host:$port${path.ifBlank { "/mqtt" }}"
    }
}

sealed interface ConnState {
    data object Disconnected : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Reconnecting(val attempt: Int, val delayMs: Long, val reason: String) : ConnState
    data class Error(val kind: ErrorKind, val message: String) : ConnState // терминальная: нужно действие пользователя
}

data class RawMessage(
    val topic: String, val payload: String,
    val retained: Boolean = false, val qos: Int = 0,
    val at: Long = System.currentTimeMillis(),
)

interface MqttTransport {
    val state: StateFlow<ConnState>
    val incoming: SharedFlow<RawMessage>
    suspend fun connect(params: ConnectionParams): Result<Unit>
    suspend fun subscribe(filter: String, qos: Int = 1): Result<Unit>
    suspend fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false): Result<Unit>
    fun disconnect()
}

object MqttErrors {
    fun classify(e: Throwable): ErrorKind {
        if (e is MqttException) when (e.reasonCode) {
            MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
            MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() -> return ErrorKind.AUTH
            MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() -> return ErrorKind.TIMEOUT
            MqttException.REASON_CODE_CONNECTION_LOST.toInt() -> return ErrorKind.NETWORK
        }
        var c: Throwable? = e
        while (c != null) {
            when (c) {
                is SSLException -> return ErrorKind.TLS
                is UnknownHostException, is ConnectException,
                is SocketTimeoutException, is SocketException -> return ErrorKind.NETWORK
            }
            c = c.cause
        }
        return if (e is MqttException) ErrorKind.PROTOCOL else ErrorKind.UNKNOWN
    }

    fun describe(e: Throwable): String = when (classify(e)) {
        ErrorKind.AUTH -> "Доступ запрещён — проверьте логин/пароль"
        ErrorKind.TLS -> "TLS-ошибка: ${e.message}"
        ErrorKind.NETWORK -> "Сеть недоступна: ${e.message}"
        ErrorKind.TIMEOUT -> "Таймаут подключения"
        else -> e.message ?: e.javaClass.simpleName
    }
}

class PahoTransport : MqttTransport {
    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    override val state = _state.asStateFlow()
    private val _incoming = MutableSharedFlow<RawMessage>(
        extraBufferCapacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming = _incoming.asSharedFlow()
    private var client: MqttAsyncClient? = null

    override suspend fun connect(params: ConnectionParams): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            disconnect()
            _state.value = ConnState.Connecting
            val c = MqttAsyncClient(
                params.serverUri(),
                "iotmod-" + UUID.randomUUID().toString().take(8),
                MemoryPersistence()
            )
            c.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    val e = cause ?: Exception("connection lost")
                    _state.value = ConnState.Error(MqttErrors.classify(e), MqttErrors.describe(e))
                }
                override fun messageArrived(topic: String, message: MqttMessage) {
                    _incoming.tryEmit(
                        RawMessage(topic, String(message.payload, Charsets.UTF_8), message.isRetained, message.qos)
                    )
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val opts = MqttConnectOptions().apply {
                setCleanSession(true)
                setKeepAliveInterval(45)
                setConnectionTimeout(10)
                setAutomaticReconnect(false) // backoff рулит ConnectionManager
                if (params.username.isNotBlank()) setUserName(params.username)
                if (params.password.isNotEmpty()) setPassword(params.password.toCharArray())
            }
            c.connect(opts).waitForCompletion(15_000)
            client = c
            _state.value = ConnState.Connected
        }.onFailure { _state.value = ConnState.Disconnected }
    }

    override suspend fun subscribe(filter: String, qos: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { client?.subscribe(filter, qos)?.waitForCompletion(10_000) ?: error("нет клиента") }
    }

    override suspend fun publish(topic: String, payload: String, qos: Int, retained: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val m = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                    this.qos = qos; this.isRetained = retained
                }
                client?.publish(topic, m)?.waitForCompletion(10_000) ?: error("нет клиента")
            }
        }

    override fun disconnect() {
        runCatching {
            client?.disconnect()?.waitForCompletion(3_000)
            client?.close()
        }
        client = null
        _state.value = ConnState.Disconnected
    }
}
