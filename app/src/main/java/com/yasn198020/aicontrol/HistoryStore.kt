package com.yasn198020.aicontrol

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class HistoryPoint(
    val timestamp: Long,
    val deviceId: String,
    val widgetId: String,
    val value: Double
)

data class HistoryWriteResult(
    val accepted: Boolean,
    val pointCount: Int,
    val reason: String = ""
)

class HistoryStore(private val prefs: SharedPreferences) {
    companion object {
        private const val KEY = "telemetry_history_v1"
        private const val MAX_POINTS = 5000
        private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }

    private val lock = Any()

    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HistoryStore").apply {
            isDaemon = true
        }
    }

    private val pendingPersistCount = AtomicInteger(0)

    @Volatile
    private var loaded = false

    private var cache: MutableList<HistoryPoint> = mutableListOf()

    @Volatile
    private var persistRequested = false

    @Volatile
    private var lastPersistAt = 0L

    @Volatile
    private var lastPersistError = ""

    fun add(
        deviceId: String,
        widgetId: String,
        rawValue: String,
        timestamp: Long = System.currentTimeMillis()
    ): HistoryWriteResult {
        val value = rawValue.replace(',', '.').trim()
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() }

        if (value == null) {
            val count = synchronized(lock) {
                ensureLoadedLocked()
                cache.size
            }
            return HistoryWriteResult(
                accepted = false,
                pointCount = count,
                reason = "non_numeric_value raw=" + rawValue
            )
        }

        val count: Int
        synchronized(lock) {
            ensureLoadedLocked()
            cache.add(HistoryPoint(timestamp, deviceId, widgetId, value))

            val cutoff = timestamp - MAX_AGE_MS
            val firstKeptIndex = cache.indexOfFirst { it.timestamp >= cutoff }
            if (firstKeptIndex > 0) {
                cache = cache.drop(firstKeptIndex).toMutableList()
            }
            if (cache.size > MAX_POINTS) {
                cache = cache.takeLast(MAX_POINTS).toMutableList()
            }

            count = cache.size
            persistRequested = true
        }

        schedulePersist()

        return HistoryWriteResult(
            accepted = true,
            pointCount = count
        )
    }

    fun latestValues(): Map<String, Double> {
        val latest = LinkedHashMap<String, HistoryPoint>()
        synchronized(lock) {
            ensureLoadedLocked()
            cache.forEach { point ->
                latest[point.deviceId + "/" + point.widgetId] = point
            }
        }
        return latest.mapValues { it.value.value }
    }

    fun load(): List<HistoryPoint> = synchronized(lock) {
        ensureLoadedLocked()
        cache.toList()
    }

    fun clear() {
        synchronized(lock) {
            ensureLoadedLocked()
            cache.clear()
            persistRequested = true
        }
        schedulePersist()
    }

    /**
     * Schedules persistence of the latest in-memory snapshot without blocking
     * MQTT or the Android main thread.
     */
    fun flushNow() {
        schedulePersist()
    }

    fun diagnostics(): String {
        val points = synchronized(lock) {
            ensureLoadedLocked()
            cache.size
        }
        return "points=" + points +
            " pending=" + pendingPersistCount.get() +
            " requested=" + persistRequested +
            " lastPersistAt=" + lastPersistAt +
            if (lastPersistError.isBlank()) "" else " lastPersistError=" + lastPersistError
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        cache = readFromPrefs().toMutableList()
        loaded = true
    }

    private fun readFromPrefs(): List<HistoryPoint> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()

        return try {
            val json = JSONArray(raw)
            buildList(json.length()) {
                for (i in 0 until json.length()) {
                    val o = json.getJSONObject(i)
                    add(
                        HistoryPoint(
                            timestamp = o.optLong("t"),
                            deviceId = o.optString("d"),
                            widgetId = o.optString("w"),
                            value = o.optDouble("v", Double.NaN)
                        )
                    )
                }
            }.filter { it.value.isFinite() }.takeLast(MAX_POINTS)
        } catch (e: Exception) {
            lastPersistError = "read:" + (e.message ?: e.javaClass.simpleName)
            emptyList()
        }
    }

    private fun schedulePersist() {
        synchronized(lock) {
            if (pendingPersistCount.get() > 0) return
            pendingPersistCount.incrementAndGet()
        }

        persistExecutor.execute {
            try {
                persistLoop()
            } finally {
                pendingPersistCount.decrementAndGet()
                if (synchronized(lock) { persistRequested }) {
                    schedulePersist()
                }
            }
        }
    }

    private fun persistLoop() {
        while (true) {
            val snapshot: List<HistoryPoint> = synchronized(lock) {
                ensureLoadedLocked()
                if (!persistRequested) return
                persistRequested = false
                cache.toList()
            }

            try {
                if (snapshot.isEmpty()) {
                    prefs.edit().remove(KEY).commit()
                } else {
                    val json = JSONArray()
                    snapshot.forEach { point ->
                        json.put(
                            JSONObject().apply {
                                put("t", point.timestamp)
                                put("d", point.deviceId)
                                put("w", point.widgetId)
                                put("v", point.value)
                            }
                        )
                    }
                    prefs.edit().putString(KEY, json.toString()).commit()
                }

                lastPersistAt = System.currentTimeMillis()
                lastPersistError = ""
            } catch (e: Exception) {
                lastPersistError = e.javaClass.simpleName + ":" + (e.message ?: "<empty>")
                synchronized(lock) {
                    persistRequested = true
                }
                DiagnosticTrace.system("HISTORY persist failed: " + lastPersistError)
                return
            }

            if (!synchronized(lock) { persistRequested }) return
        }
    }
}
