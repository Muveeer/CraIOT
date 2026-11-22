package com.livecontrol.craiot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livecontrol.craiot.data.BrokerProfile
import com.livecontrol.craiot.data.ReadingEntity
import com.livecontrol.craiot.mqtt.Transport
import com.livecontrol.craiot.protocol.Widget
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CraIOTApp).container
    val profiles = c.repo.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeId = c.repo.activeId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val connState = c.manager.state
    val journal = c.journal
    val devices = c.registry.devices
    val unknown = c.registry.unknown
    val unknownList = c.registry.unknownList
    val telemetry = c.telemetry.snap
    val now = c.now
    val dark = c.ui.dark.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val compact = c.ui.compact.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val pins = c.ui.pins.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val aliases = c.ui.aliases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun connect(id: String) { viewModelScope.launch { c.connectProfile(id) } }
    fun disconnect() = c.disconnect()
    fun retry() = c.manager.retryNow()
    fun hello() { viewModelScope.launch { c.hello() } }
    fun send(w: Widget, value: String) = c.commands.send(w, value)
    fun toggleDark() { viewModelScope.launch { c.ui.set(!dark.value) } }
    fun toggleCompact() { viewModelScope.launch { c.ui.setCompact(!compact.value) } }
    fun togglePin(topic: String) { viewModelScope.launch { c.ui.togglePin(topic) } }
    fun setAlias(id: String, name: String) { viewModelScope.launch { c.ui.setAlias(id, name) } }

    suspend fun readings(topic: String, from: Long): List<ReadingEntity> =
        runCatching { c.db.history().range(topic, from) }.getOrDefault(emptyList())
    suspend fun historyCount(): Long = runCatching { c.db.history().count() }.getOrDefault(0L)

    suspend fun exportProfiles(): String {
        val arr = JSONArray()
        c.repo.profiles.first().forEach { p ->
            val (u, pw) = c.repo.credentials(p.id)
            arr.put(JSONObject().apply {
                put("name", p.name); put("protocol", p.protocol.name); put("host", p.host)
                put("port", p.port); put("path", p.path); put("prefix", p.prefix)
                put("username", u); put("password", pw)
            })
        }
        return JSONObject().apply { put("app", "CraIOT"); put("version", 1); put("profiles", arr) }.toString(2)
    }

    suspend fun importProfiles(json: String): Int {
        val arr = JSONObject(json).getJSONArray("profiles"); var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = BrokerProfile(UUID.randomUUID().toString(), o.optString("name", "Imported"),
                Transport.valueOf(o.optString("protocol", "WSS")), o.getString("host"),
                o.getInt("port"), o.optString("path", "/ws"), o.getString("prefix"))
            c.repo.upsert(p, o.optString("username"), o.optString("password")); n++
        }
        return n
    }

    fun save(p: BrokerProfile, user: String, pass: String) {
        viewModelScope.launch {
            c.repo.upsert(p, user, pass)
            val active = c.repo.activeId.first()
            if (active == null) c.connectProfile(p.id)
            else if (active == p.id && !c.userStopped) c.connectProfile(p.id)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            if (c.repo.activeId.first() == id) c.disconnect()
            c.repo.delete(id)
        }
    }

    suspend fun load(id: String): Pair<BrokerProfile?, Pair<String, String>> =
        c.repo.profile(id) to c.repo.credentials(id)
}
