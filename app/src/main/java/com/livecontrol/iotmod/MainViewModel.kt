@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.livecontrol.iotmod

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livecontrol.iotmod.data.BrokerProfile
import com.livecontrol.iotmod.data.ReadingEntity
import com.livecontrol.iotmod.mqtt.Transport
import com.livecontrol.iotmod.protocol.Widget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Обёртка с равенством по ссылке: без глубокого equals по дереву устройств на каждый тик. */
class IdentityRef<T>(val value: T)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as IoTmodApp).container
    val profiles = c.repo.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeId = c.repo.activeId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val connState = c.manager.state
    val journal = c.journal
    val journalFeed = c.journal.items.sample(250).flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), c.journal.items.value)
    val devices = c.registry.devices.sample(200).flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), c.registry.devices.value)
    val devicesRef = c.registry.devices.sample(200).flowOn(Dispatchers.Default).map { IdentityRef(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IdentityRef(c.registry.devices.value))
    val unknown = c.registry.unknown
    val unknownList = c.registry.unknownList
    val telemetry = c.telemetry.snap.sample(500).flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), c.telemetry.snap.value)
    val now = c.now
    val themeId = c.ui.themeId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "night")
    val dark = c.ui.dark.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val compact = c.ui.compact.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val pins = c.ui.pins.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val aliases = c.ui.aliases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val tileColumns = c.ui.columns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val tileDensity = c.ui.density.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)
    val tileTextMode = c.ui.textMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "clamp")
    val tileThreshold = c.ui.threshold.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 48)
    val tileGlow = c.ui.glow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val tileAge = c.ui.showAge.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val tileSpark = c.ui.showSpark.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val tileMorph = c.ui.morph.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val tileOverrides = c.ui.overrides.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun connect(id: String) { viewModelScope.launch { c.connectProfile(id) } }
    fun disconnect() = c.disconnect()
    fun retry() = c.manager.retryNow()
    fun hello() { viewModelScope.launch { c.hello() } }
    fun send(w: Widget, value: String) = c.commands.send(w, value)
    fun setTheme(id: String) { viewModelScope.launch { c.ui.setTheme(id) } }
    fun toggleDark() { viewModelScope.launch { c.ui.setTheme(if (dark.value) "paper" else "night") } }
    fun toggleCompact() { viewModelScope.launch { c.ui.setCompact(!compact.value) } }
    fun togglePin(topic: String) { viewModelScope.launch { c.ui.togglePin(topic) } }
    fun setAlias(id: String, name: String) { viewModelScope.launch { c.ui.setAlias(id, name) } }

    fun setColumns(v: Int) { viewModelScope.launch { c.ui.setColumns(v) } }
    fun setDensity(v: Float) { viewModelScope.launch { c.ui.setDensity(v) } }
    fun setTextMode(v: String) { viewModelScope.launch { c.ui.setTextMode(v) } }
    fun setThreshold(v: Int) { viewModelScope.launch { c.ui.setThreshold(v) } }
    fun toggleGlow() { viewModelScope.launch { c.ui.setGlow(!tileGlow.value) } }
    fun toggleAge() { viewModelScope.launch { c.ui.setShowAge(!tileAge.value) } }
    fun toggleSpark() { viewModelScope.launch { c.ui.setShowSpark(!tileSpark.value) } }
    fun toggleMorph() { viewModelScope.launch { c.ui.setMorph(!tileMorph.value) } }
    fun setSizeOverride(topic: String, size: String?) { viewModelScope.launch { c.ui.setSizeOverride(topic, size) } }
    fun resetView() { viewModelScope.launch { c.ui.resetView() } }

    suspend fun readings(topic: String, from: Long): List<ReadingEntity> =
        runCatching { c.db.history().range(topic, from) }.getOrDefault(emptyList())
    suspend fun readingsLast(topic: String, from: Long, limit: Int): List<ReadingEntity> =
        runCatching { c.db.history().rangeLast(topic, from, limit).asReversed() }.getOrDefault(emptyList())
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
        return JSONObject().apply { put("app", "IoTmod"); put("version", 1); put("profiles", arr) }.toString(2)
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
