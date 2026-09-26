package com.yasn198020.aicontrol

import android.content.SharedPreferences
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
        private const val PERSIST_DELAY_MS = 1000L
        private const val SAMPLE_PERIOD_KEY = "history_sample_period_ms"
        private const val DEFAULT_SAMPLE_PERIOD_MS = 10_000L
        private val SUPPORTED_SAMPLE_PERIODS_MS = longArrayOf(
            1_000L,
            5_000L,
            10_000L,
            30_000L,
            60_000L,
            300_000L,
            600_000L,
            1_800_000L,
            3_600_000L
        )
    }

    private val lock = Any()

    private val persistExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "HistoryStore").apply {
                isDaemon = true
            }
        }

    private var persistFuture: ScheduledFuture<*>? = null

    @Volatile
    private var loaded = false

    private var cache: MutableList<HistoryPoint> = mutableListOf()

    /**
     * Latest numeric MQTT value for each device/widget.
     * MQTT only updates this map. History points are created by the sampler
     * on the configured measurement period.
     */
    private val latestCurrent: MutableMap<String, HistoryPoint> = LinkedHashMap()

    @Volatile
    private var persistRequested = false

    @Volatile
    private var lastPersistAt = 0L

    @Volatile
    private var lastPersistError = ""

    @Volatile
    private var sampleFuture: ScheduledFuture<*>? = null

    init {
        startSampler()
    }

    fun samplePeriodMs(): Long {
        val saved = prefs.getLong(SAMPLE_PERIOD_KEY, DEFAULT_SAMPLE_PERIOD_MS)
        return normalizeSamplePeriod(saved)
    }

    fun setSamplePeriodMs(periodMs: Long) {
        val normalized = normalizeSamplePeriod(periodMs)
        prefs.edit().putLong(SAMPLE_PERIOD_KEY, normalized).apply()

        synchronized(lock) {
            sampleFuture?.cancel(false)
            sampleFuture = scheduleSamplerLocked(normalized)
        }
    }

    fun updateLatest(
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
            latestCurrent[deviceId + "/" + widgetId] =
                HistoryPoint(timestamp, deviceId, widgetId, value)
            ensureLoadedLocked()
            count = cache.size
        }

        return HistoryWriteResult(
            accepted = true,
            pointCount = count,
            reason = "current_updated"
        )
    }

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
            appendPointLocked(
                HistoryPoint(
                    timestamp = timestamp,
                    deviceId = deviceId,
                    widgetId = widgetId,
                    value = value
                )
            )

            count = cache.size
            persistRequested = true
        }

        schedulePersist()

        return HistoryWriteResult(
            accepted = true,
            pointCount = count
        )
    }

    private fun startSampler() {
        synchronized(lock) {
            sampleFuture?.cancel(false)
            sampleFuture = scheduleSamplerLocked(samplePeriodMs())
        }
    }

    private fun scheduleSamplerLocked(periodMs: Long): ScheduledFuture<*> {
        return persistExecutor.scheduleWithFixedDelay({
            runCatching {
                sampleLatest()
            }.onFailure {
                DiagnosticTrace.system(
                    "HISTORY sampler failed: " +
                        (it.message ?: it.javaClass.simpleName)
                )
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS)
    }

    private fun sampleLatest(now: Long = System.currentTimeMillis()): Int {
        val sampled: List<HistoryPoint>
        synchronized(lock) {
            ensureLoadedLocked()
            if (latestCurrent.isEmpty()) return 0

            sampled = latestCurrent.values.map { current ->
                HistoryPoint(
                    timestamp = now,
                    deviceId = current.deviceId,
                    widgetId = current.widgetId,
                    value = current.value
                )
            }

            sampled.forEach { point ->
                appendPointLocked(point)
            }
            if (sampled.isNotEmpty()) {
                persistRequested = true
            }
        }

        if (sampled.isNotEmpty()) {
            schedulePersist()
            DiagnosticTrace.system(
                "HISTORY sample period=" + samplePeriodMs() +
                    "ms points=" + sampled.size
            )
        }

        return sampled.size
    }

    private fun normalizeSamplePeriod(periodMs: Long): Long {
        var best = DEFAULT_SAMPLE_PERIOD_MS
        var bestDistance = Long.MAX_VALUE

        for (candidate in SUPPORTED_SAMPLE_PERIODS_MS) {
            val distance = kotlin.math.abs(candidate - periodMs)
            if (distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    private fun appendPointLocked(point: HistoryPoint) {
        cache.add(point)

        val cutoff = point.timestamp - MAX_AGE_MS
        val firstKeptIndex = cache.indexOfFirst { it.timestamp >= cutoff }
        if (firstKeptIndex > 0) {
            cache = cache.drop(firstKeptIndex).toMutableList()
        }
        if (cache.size > MAX_POINTS) {
            cache = cache.takeLast(MAX_POINTS).toMutableList()
        }
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

    /** Latest value for each global widget variable, regardless of source device. */
    fun latestValuesByWidget(): Map<String, Double> {
        val latest = LinkedHashMap<String, HistoryPoint>()
        synchronized(lock) {
            ensureLoadedLocked()
            cache.forEach { point ->
                val previous = latest[point.widgetId]
                if (previous == null || point.timestamp >= previous.timestamp) {
                    latest[point.widgetId] = point
                }
            }
        }
        return latest.mapValues { it.value.value }
    }


    fun clear() {
        synchronized(lock) {
            ensureLoadedLocked()
            cache.clear()
            persistRequested = true
        }
        schedulePersist()
    }

    /** Removes points older than the retention window without clearing newer history. */
    fun pruneExpired(now: Long = System.currentTimeMillis()): Int {
        val removed: Int
        synchronized(lock) {
            ensureLoadedLocked()
            val cutoff = now - MAX_AGE_MS
            val before = cache.size
            cache = cache.filter { it.timestamp >= cutoff }.toMutableList()
            removed = before - cache.size
            if (removed > 0) persistRequested = true
        }
        if (removed > 0) schedulePersist()
        return removed
    }

    /**
     * Schedules persistence of the latest in-memory snapshot without blocking
     * MQTT or the Android main thread.
     */
    fun flushNow() {
        schedulePersist(0L)
    }

    fun diagnostics(): String {
        val points = synchronized(lock) {
            ensureLoadedLocked()
            cache.size
        }
        return "points=" + points +
            " samplePeriodMs=" + samplePeriodMs() +
            " pending=" + synchronized(lock) {
                if (persistFuture?.isDone == false) 1 else 0
            } +
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

    private fun schedulePersist(delayMs: Long = PERSIST_DELAY_MS) {
        synchronized(lock) {
            if (!persistRequested) return

            val existing = persistFuture
            if (existing?.isDone == false) {
                if (delayMs > 0L) return
                existing.cancel(false)
            }

            persistFuture = persistExecutor.schedule({
                try {
                    persistLoop()
                } finally {
                    synchronized(lock) {
                        persistFuture = null
                    }
                    if (synchronized(lock) { persistRequested }) {
                        schedulePersist()
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
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
