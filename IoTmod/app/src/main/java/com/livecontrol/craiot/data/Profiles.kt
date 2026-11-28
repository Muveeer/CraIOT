package com.livecontrol.craiot.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.livecontrol.craiot.mqtt.ConnectionParams
import com.livecontrol.craiot.mqtt.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.profileStore by preferencesDataStore(name = "craiot_profiles")

data class BrokerProfile(
    val id: String, val name: String, val protocol: Transport,
    val host: String, val port: Int, val path: String, val prefix: String,
) {
    fun toParams(username: String, password: String) =
        ConnectionParams(protocol, host, port, path, prefix, username, password)
}

object ProfileValidator {
    private val PREFIX_RE = Regex("^/[A-Za-z0-9_-]+$")
    fun validate(name: String, host: String, portText: String, path: String, prefix: String): String? = when {
        name.isBlank() -> "Укажите название профиля"
        host.isBlank() -> "Укажите host"
        portText.toIntOrNull() == null -> "Порт — число"
        portText.toInt() !in 1..65535 -> "Порт вне диапазона 1–65535"
        !PREFIX_RE.matches(prefix) -> "Prefix: один сегмент /слово (латиница, цифры, _ -)"
        path.isNotBlank() && !path.startsWith("/") -> "Path должен начинаться с /"
        else -> null
    }
}

class ProfileRepository(private val context: Context, private val box: SecretBox) {
    private data class Stored(val profile: BrokerProfile, val encUser: String, val encPass: String)

    private val profilesKey = stringPreferencesKey("profiles_json")
    private val activeKey = stringPreferencesKey("active_id")

    private fun parse(prefs: Preferences): List<Stored> {
        val raw = prefs[profilesKey] ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Stored(
                    BrokerProfile(
                        o.getString("id"), o.getString("name"), Transport.valueOf(o.getString("protocol")),
                        o.getString("host"), o.getInt("port"), o.getString("path"), o.getString("prefix")
                    ),
                    o.getString("u"), o.getString("p")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun toJson(list: List<Stored>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.profile.id); put("name", s.profile.name)
                put("protocol", s.profile.protocol.name); put("host", s.profile.host)
                put("port", s.profile.port); put("path", s.profile.path); put("prefix", s.profile.prefix)
                put("u", s.encUser); put("p", s.encPass)
            })
        }
        return arr.toString()
    }

    val profiles: Flow<List<BrokerProfile>> = context.profileStore.data.map { p -> parse(p).map { it.profile } }
    val activeId: Flow<String?> = context.profileStore.data.map { it[activeKey] }

    suspend fun profile(id: String): BrokerProfile? = profiles.first().find { it.id == id }

    suspend fun credentials(id: String): Pair<String, String> {
        val s = parse(context.profileStore.data.first()).find { it.profile.id == id } ?: return "" to ""
        return box.decrypt(s.encUser) to box.decrypt(s.encPass)
    }

    suspend fun upsert(p: BrokerProfile, user: String, pass: String) {
        context.profileStore.edit { prefs ->
            val list = parse(prefs).filter { it.profile.id != p.id } + Stored(p, box.encrypt(user), box.encrypt(pass))
            prefs[profilesKey] = toJson(list)
        }
    }

    suspend fun delete(id: String) {
        context.profileStore.edit { prefs ->
            prefs[profilesKey] = toJson(parse(prefs).filter { it.profile.id != id })
            if (prefs[activeKey] == id) prefs.remove(activeKey)
        }
    }

    suspend fun setActive(id: String) {
        context.profileStore.edit { it[activeKey] = id }
    }
}
