package com.livecontrol.craiot

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.livecontrol.craiot.data.BrokerProfile
import com.livecontrol.craiot.data.ConfigCacheRecorder
import com.livecontrol.craiot.data.CraIOTDb
import com.livecontrol.craiot.data.HistoryRecorder
import com.livecontrol.craiot.data.ProfileRepository
import com.livecontrol.craiot.data.SecretBox
import com.livecontrol.craiot.mqtt.AndroidNetworkMonitor
import com.livecontrol.craiot.mqtt.ConnectionManager
import com.livecontrol.craiot.mqtt.PahoTransport
import com.livecontrol.craiot.mqtt.Transport
import com.livecontrol.craiot.protocol.DeviceRegistry
import com.livecontrol.craiot.protocol.WidgetCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class CraIOTApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var stopJob: Job? = null
            override fun onStart(owner: LifecycleOwner) { stopJob?.cancel(); container.scope.launch { container.autoConnect() } }
            override fun onStop(owner: LifecycleOwner) { stopJob = container.scope.launch { delay(30_000); container.backgroundDisconnect() } }
        })
    }
}

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val repo = ProfileRepository(context, SecretBox())
    val ui = UiSettings(context)
    val db = CraIOTDb.build(context)
    private val transport = PahoTransport()
    val manager = ConnectionManager(transport, scope)
    val journal = Journal(scope, manager.incoming)
    val registry = DeviceRegistry(scope, manager.incoming)
    val commands = WidgetCommands(scope, transport, registry, manager.incoming)
    val history = HistoryRecorder(scope, manager.incoming, db.history())
    val telemetry = Telemetry(scope, manager.incoming, manager)
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now
    @Volatile var profileKey: String? = null
    val configCache = ConfigCacheRecorder(scope, manager.incoming, db.configs()) { profileKey }
    @Volatile var userStopped = false
    private var currentPrefix: String? = null
    private val seedMutex = Mutex()

    init {
        AndroidNetworkMonitor(context).start { manager.onNetworkAvailable() }
        manager.onConnected = { currentPrefix?.let { transport.publish(it, "HELLO", qos = 0) } }
        scope.launch { while (isActive) { _now.value = System.currentTimeMillis(); delay(1000) } }
    }

    suspend fun connectProfile(id: String) {
        val p = repo.profile(id) ?: return
        val (u, pw) = repo.credentials(id)
        repo.setActive(id)
        currentPrefix = p.prefix
        profileKey = "$id:${p.prefix}"
        val cached = runCatching { db.configs().load(profileKey!!) }.getOrDefault(emptyList())
        registry.preload(p.prefix, cached.map { it.topic to it.json })
        userStopped = false
        manager.connect(p.toParams(u, pw))
    }

    suspend fun hello() { currentPrefix?.let { transport.publish(it, "HELLO", qos = 0) } }
    fun disconnect() { userStopped = true; manager.disconnect() }
    fun backgroundDisconnect() { manager.disconnect() }

    suspend fun autoConnect() {
        seedMutex.withLock {
            if (repo.profiles.first().isEmpty()) {
                val u = BuildConfig.DEFAULT_USERNAME; val pw = BuildConfig.DEFAULT_PASSWORD
                val live = BrokerProfile(UUID.randomUUID().toString(), "Live · ver407", Transport.WSS, "live-control.com", 18883, "/ws", "/ver407")
                repo.upsert(live, u, pw)
                repo.upsert(live.copy(id = UUID.randomUUID().toString(), name = "Live · vermax", prefix = "/vermax"), u, pw)
                repo.setActive(live.id)
            }
        }
        if (userStopped) return
        val id = repo.activeId.first() ?: return
        connectProfile(id)
    }
}
