package com.yasn198020.aicontrol

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

class HistoryStore(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY = "telemetry_history_v1"
        private const val MAX_POINTS = 5000
        private const val RETENTION_DAYS_KEY = "history_retention_days"
        private const val DEFAULT_RETENTION_DAYS = 30
        private const val DISPLAY_PERIOD_KEY = "history_display_period_ms"
        private const val DEFAULT_DISPLAY_PERIOD_MS = 86_400_000L
        private const val PERSIST_DELAY_MS = 1000L
        private const val MAX_DISPLAY_POINTS = 5000
        private const val DAILY_HISTORY_DIR = "history"
        private const val DAILY_FILE_SUFFIX = ".jsonl"
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
    private val historyDir = File(context.filesDir, DAILY_HISTORY_DIR)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

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
        historyDir.mkdirs()
        migrateLegacyHistory()
        pruneExpiredFiles()
        startSampler()
    }

    fun retentionDays(): Int {
        val saved = prefs.getInt(RETENTION_DAYS_KEY, DEFAULT_RETENTION_DAYS)
        return normalizeRetentionDays(saved)
    }

    fun setRetentionDays(days: Int) {
        val normalized = normalizeRetentionDays(days)
        prefs.edit().putInt(RETENTION_DAYS_KEY, normalized).apply()
        synchronized(lock) {
            if (loaded && normalized > 0) {
                val cutoff = System.currentTimeMillis() -
                    normalized * 24L * 60L * 60L * 1000L
                cache = cache.filter { it.timestamp >= cutoff }.toMutableList()
            }
        }
        pruneExpiredFiles()
    }

    fun displayPeriodMs(): Long {
        val saved = prefs.getLong(DISPLAY_PERIOD_KEY, DEFAULT_DISPLAY_PERIOD_MS)
        return normalizeDisplayPeriod(saved)
    }

    fun setDisplayPeriodMs(periodMs: Long) {
        prefs.edit().putLong(
            DISPLAY_PERIOD_KEY,
            normalizeDisplayPeriod(periodMs)
        ).apply()
    }

    fun displaySinceMillis(now: Long = System.currentTimeMillis()): Long? {
        val period = displayPeriodMs()
        return when {
            period <= 0L -> null
            period == 86_400_000L -> startOfToday(now)
            else -> now - period
        }
    }

    fun loadSince(
        deviceId: String?,
        widgetId: String?,
        since: Long?,
        maxPoints: Int = MAX_DISPLAY_POINTS
    ): List<HistoryPoint> {
        synchronized(lock) {
            ensureLoadedLocked()
            val points = cache.filter { point ->
                (deviceId == null || point.deviceId == deviceId) &&
                    (widgetId == null || point.widgetId == widgetId) &&
                    (since == null || point.timestamp >= since)
            }
            if (points.size <= maxPoints) return points

            val step = points.size.toDouble() / maxPoints.toDouble()
            return buildList(maxPoints) {
                var cursor = 0.0
                repeat(maxPoints) {
                    add(points[cursor.toInt().coerceIn(0, points.lastIndex)])
                    cursor += step
                }
            }
        }
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
                if (loaded) cache.size else -1
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
            count = if (loaded) cache.size else -1
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
            val point = HistoryPoint(
                timestamp = timestamp,
                deviceId = deviceId,
                widgetId = widgetId,
                value = value
            )
            appendPointLocked(point)
            count = cache.size
        }

        appendDailyPoints(
            listOf(
                HistoryPoint(
                    timestamp = timestamp,
                    deviceId = deviceId,
                    widgetId = widgetId,
                    value = value
                )
            )
        )

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
            if (latestCurrent.isEmpty()) return 0

            sampled = latestCurrent.values.map { current ->
                HistoryPoint(
                    timestamp = now,
                    deviceId = current.deviceId,
                    widgetId = current.widgetId,
                    value = current.value
                )
            }

            if (loaded) {
                sampled.forEach { point ->
                    appendPointLocked(point)
                }
            }
        }

        if (sampled.isNotEmpty()) {
            appendDailyPoints(sampled)
            pruneExpiredFiles()
            DiagnosticTrace.system(
                "HISTORY sample period=" + samplePeriodMs() +
                    "ms points=" + sampled.size
            )
        }

        return sampled.size
    }

    private fun normalizeDisplayPeriod(periodMs: Long): Long =
        longArrayOf(
            3_600_000L,
            21_600_000L,
            43_200_000L,
            86_400_000L,
            259_200_000L,
            604_800_000L,
            2_592_000_000L,
            0L
        ).minByOrNull { kotlin.math.abs(it - periodMs) }
            ?: DEFAULT_DISPLAY_PERIOD_MS

    private fun normalizeRetentionDays(days: Int): Int =
        intArrayOf(1, 3, 7, 14, 30, 90, 180, 365, 0)
            .minByOrNull { kotlin.math.abs(it - days) }
            ?: DEFAULT_RETENTION_DAYS

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

        val retention = retentionDays()
        if (retention > 0) {
            val cutoff = point.timestamp - retention * 24L * 60L * 60L * 1000L
            val firstKeptIndex = cache.indexOfFirst { it.timestamp >= cutoff }
            if (firstKeptIndex > 0) {
                cache = cache.drop(firstKeptIndex).toMutableList()
            }
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
            // Use the already loaded cache only. The full daily archive is loaded
            // lazily by loadSince(), so app startup never scans all history files.
            if (!loaded) return emptyMap()
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
            historyDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(DAILY_FILE_SUFFIX)) {
                    file.delete()
                }
            }
            cache.clear()
            latestCurrent.clear()
            persistRequested = false
        }
    }

    private fun appendDailyPoints(points: List<HistoryPoint>) {
        if (points.isEmpty()) return

        synchronized(lock) {
            historyDir.mkdirs()
            val grouped = points.groupBy { point ->
                File(
                    historyDir,
                    dateFormat.format(Date(point.timestamp)) + DAILY_FILE_SUFFIX
                )
            }

            grouped.forEach { (file, dayPoints) ->
                runCatching {
                    FileWriter(file, true).use { writer ->
                        dayPoints.sortedBy { it.timestamp }.forEach { point ->
                            writer.append(
                                JSONObject().apply {
                                    put("t", point.timestamp)
                                    put("d", point.deviceId)
                                    put("w", point.widgetId)
                                    put("v", point.value)
                                }.toString()
                            )
                            writer.append('\n')
                        }
                    }
                    lastPersistAt = System.currentTimeMillis()
                    lastPersistError = ""
                }.onFailure {
                    lastPersistError =
                        it.javaClass.simpleName + ":" + (it.message ?: "<empty>")
                    DiagnosticTrace.system(
                        "HISTORY daily write failed: " + lastPersistError
                    )
                }
            }
        }
    }

    private fun pruneExpiredFiles(now: Long = System.currentTimeMillis()) {
        val retention = retentionDays()
        if (retention <= 0) return

        val cutoff = now - retention * 24L * 60L * 60L * 1000L
        synchronized(lock) {
            historyDir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(DAILY_FILE_SUFFIX)) return@forEach
                val day = runCatching {
                    dateFormat.parse(file.name.removeSuffix(DAILY_FILE_SUFFIX))?.time
                }.getOrNull() ?: return@forEach
                val dayEnd = day + 24L * 60L * 60L * 1000L - 1L
                if (dayEnd < cutoff) file.delete()
            }
        }
    }

    /** Removes points older than the retention window without clearing newer history. */
    fun pruneExpired(now: Long = System.currentTimeMillis()): Int {
        val before = synchronized(lock) {
            historyDir.listFiles()?.count {
                it.isFile && it.name.endsWith(DAILY_FILE_SUFFIX)
            } ?: 0
        }
        pruneExpiredFiles(now)
        val after = synchronized(lock) {
            historyDir.listFiles()?.count {
                it.isFile && it.name.endsWith(DAILY_FILE_SUFFIX)
            } ?: 0
        }
        return before - after
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
            if (loaded) cache.size else -1
        }
        return "points=" + points +
            " retentionDays=" + retentionDays() +
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

    private fun startOfToday(now: Long): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun migrateLegacyHistory() {
        val raw = prefs.getString(KEY, null) ?: return
        runCatching {
            val json = JSONArray(raw)
            val legacy = buildList(json.length()) {
                for (i in 0 until json.length()) {
                    val o = json.getJSONObject(i)
                    val point = HistoryPoint(
                        timestamp = o.optLong("t"),
                        deviceId = o.optString("d"),
                        widgetId = o.optString("w"),
                        value = o.optDouble("v", Double.NaN)
                    )
                    if (point.timestamp > 0L && point.value.isFinite()) add(point)
                }
            }
            if (legacy.isNotEmpty()) {
                appendDailyPoints(legacy)
                DiagnosticTrace.system(
                    "HISTORY legacy migrated points=" + legacy.size
                )
            }
            prefs.edit().remove(KEY).apply()
        }.onFailure {
            DiagnosticTrace.system(
                "HISTORY legacy migration failed: " +
                    (it.message ?: it.javaClass.simpleName)
            )
        }
    }

    private fun readFromPrefs(): List<HistoryPoint> {
        val dailyFiles = historyDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(DAILY_FILE_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (dailyFiles.isNotEmpty()) {
            val daily = ArrayList<HistoryPoint>()
            dailyFiles.forEach { file ->
                runCatching {
                    file.forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        runCatching {
                            val o = JSONObject(line)
                            HistoryPoint(
                                timestamp = o.optLong("t"),
                                deviceId = o.optString("d"),
                                widgetId = o.optString("w"),
                                value = o.optDouble("v", Double.NaN)
                            )
                        }.getOrNull()?.takeIf { it.value.isFinite() }?.let { daily.add(it) }
                    }
                }
            }
            return daily.takeLast(MAX_POINTS)
        }

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
