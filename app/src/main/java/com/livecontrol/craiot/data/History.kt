package com.livecontrol.craiot.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.livecontrol.craiot.mqtt.RawMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "readings", indices = [Index(value = ["topic", "ts"], unique = true)])
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val deviceId: String,
    val ts: Long,
    val v1: Double?,
    val v2: Double?,
    val v3: Double?,
    val v4: Double?,
)

@Entity(tableName = "config_cache", primaryKeys = ["profileKey", "topic"])
data class ConfigCacheEntity(
    val profileKey: String,
    val topic: String,
    val deviceId: String,
    val json: String,
    val updatedAt: Long,
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<ReadingEntity>)

    @Query("SELECT * FROM readings WHERE topic = :topic AND ts >= :from ORDER BY ts")
    suspend fun range(topic: String, from: Long): List<ReadingEntity>

    @Query("SELECT * FROM readings WHERE topic = :topic AND ts >= :from ORDER BY ts DESC LIMIT :limit")
    suspend fun rangeLast(topic: String, from: Long, limit: Int): List<ReadingEntity>

    @Query("DELETE FROM readings WHERE ts < :before")
    suspend fun prune(before: Long)

    @Query("SELECT COUNT(*) FROM readings")
    suspend fun count(): Long
}

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<ConfigCacheEntity>)

    @Query("SELECT * FROM config_cache WHERE profileKey = :key")
    suspend fun load(key: String): List<ConfigCacheEntity>

    @Query("DELETE FROM config_cache WHERE profileKey = :key")
    suspend fun clear(key: String)
}

@Database(
    entities = [ReadingEntity::class, ConfigCacheEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CraIOTDb : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun configs(): ConfigDao

    companion object {
        fun build(context: Context): CraIOTDb =
            Room.databaseBuilder(context.applicationContext, CraIOTDb::class.java, "craiot.db").build()
    }
}

private fun JSONObject.dbl(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() } else null

/** M4: numeric statuses and chart points are batched into Room once per second. */
class HistoryRecorder(
    scope: CoroutineScope,
    incoming: SharedFlow<RawMessage>,
    private val dao: HistoryDao,
) {
    private val buffer = ArrayList<ReadingEntity>()

    init {
        scope.launch {
            incoming.collect { message ->
                rows(message)?.let { parsed -> synchronized(buffer) { buffer.addAll(parsed) } }
            }
        }
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1_000)
                val batch = synchronized(buffer) {
                    if (buffer.isEmpty()) null else ArrayList(buffer).also { buffer.clear() }
                }
                if (batch != null) runCatching { dao.insertAll(batch) }
                if (++ticks % 60 == 0) {
                    runCatching { dao.prune(System.currentTimeMillis() - HISTORY_MS) }
                }
            }
        }
    }

    private fun rows(message: RawMessage): List<ReadingEntity>? {
        if (!message.topic.endsWith("/status")) return null

        val topic = message.topic.removeSuffix("/status")
        val segments = message.topic.split('/').filter { it.isNotBlank() }
        val deviceId = segments.getOrNull(1).orEmpty()
        val parsed = runCatching { JSONObject(message.payload) }.getOrNull()

        if (parsed == null) {
            val numeric = message.payload.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            return listOf(
                ReadingEntity(
                    topic = topic,
                    deviceId = deviceId,
                    ts = message.at,
                    v1 = numeric,
                    v2 = null,
                    v3 = null,
                    v4 = null,
                ),
            )
        }

        val status = parsed.opt("status") ?: return null
        return when (status) {
            is JSONArray -> {
                val output = ArrayList<ReadingEntity>()
                for (index in 0 until status.length()) {
                    val point = status.optJSONObject(index) ?: continue
                    val y1 = point.dbl("y1")
                    val y2 = point.dbl("y2")
                    val y3 = point.dbl("y3")
                    val y4 = point.dbl("y4")
                    if (y1 == null && y2 == null && y3 == null && y4 == null) continue
                    output += ReadingEntity(
                        topic = topic,
                        deviceId = deviceId,
                        ts = point.optLong("x", message.at / 1_000L) * 1_000L,
                        v1 = y1,
                        v2 = y2,
                        v3 = y3,
                        v4 = y4,
                    )
                }
                if (output.isEmpty()) null else output
            }
            else -> {
                val numeric = status.toString().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                listOf(
                    ReadingEntity(
                        topic = topic,
                        deviceId = deviceId,
                        ts = message.at,
                        v1 = numeric,
                        v2 = null,
                        v3 = null,
                        v4 = null,
                    ),
                )
            }
        }
    }

    private companion object {
        const val HISTORY_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}

/** M4: raw configs are cached in Room so the dashboard can preload before HELLO/network. */
class ConfigCacheRecorder(
    scope: CoroutineScope,
    incoming: SharedFlow<RawMessage>,
    private val dao: ConfigDao,
    private val key: () -> String?,
) {
    private val buffer = HashMap<String, ConfigCacheEntity>()

    init {
        scope.launch {
            incoming.collect { message ->
                val profileKey = key() ?: return@collect
                if (!message.topic.endsWith("/config")) return@collect
                val segments = message.topic.split('/').filter { it.isNotBlank() }
                val deviceId = segments.getOrNull(1).orEmpty()
                synchronized(buffer) {
                    buffer["$profileKey|${message.topic}"] = ConfigCacheEntity(
                        profileKey = profileKey,
                        topic = message.topic,
                        deviceId = deviceId,
                        json = message.payload,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                val batch = synchronized(buffer) {
                    if (buffer.isEmpty()) null else ArrayList(buffer.values).also { buffer.clear() }
                }
                if (batch != null) runCatching { dao.putAll(batch) }
            }
        }
    }
}
