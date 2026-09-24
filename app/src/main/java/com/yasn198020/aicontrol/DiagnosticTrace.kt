package com.yasn198020.aicontrol

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Background-safe diagnostic trace.
 *
 * It does not depend on Activity, Compose, Handler or UI state.
 * The last 1000 lines are kept in memory and persisted to disk so a trace
 * survives closing/reopening the app or losing the Activity.
 */
object DiagnosticTrace {
    private const val FILE_NAME = "diagnostic_trace.log"
    private const val MAX_LINES = 1000
    private const val MAX_FILE_BYTES = 1024 * 1024L

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val eventCounter = AtomicLong(System.currentTimeMillis())
    private val currentEvent = ThreadLocal<Long?>()

    @Volatile
    private var initialized = false

    private lateinit var file: File
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            file = File(context.applicationContext.filesDir, FILE_NAME)
            loadFromDiskLocked()
            initialized = true
        }
    }

    fun beginEvent(topic: String, payload: String): Long {
        ensureInitialized()
        val id = eventCounter.incrementAndGet()
        currentEvent.set(id)
        stepForEvent(id, "MQTT", "RX topic=$topic payload=$payload")
        return id
    }

    fun currentEventId(): Long? = currentEvent.get()

    fun clearCurrentEvent() {
        currentEvent.remove()
    }

    fun step(stage: String, message: String) {
        ensureInitialized()
        append(currentEvent.get(), stage, message)
    }

    fun stepForEvent(eventId: Long?, stage: String, message: String) {
        ensureInitialized()
        append(eventId, stage, message)
    }

    fun system(message: String) {
        ensureInitialized()
        append(null, "SYSTEM", message)
    }

    fun error(message: String) {
        ensureInitialized()
        append(currentEvent.get(), "ERROR", message)
    }

    fun read(): List<String> {
        ensureInitialized()
        synchronized(lock) {
            return lines.toList()
        }
    }

    fun clear() {
        ensureInitialized()
        synchronized(lock) {
            lines.clear()
            runCatching { file.writeText("") }
        }
    }

    private fun append(eventId: Long?, stage: String, message: String) {
        val safeStage = stage.trim().uppercase(Locale.ROOT).ifBlank { "TRACE" }
        synchronized(lock) {
            if (!initialized) return
            val timestamp = formatter.format(Date())
            val idPart = eventId?.let { "#$it " } ?: ""
            val line = "$timestamp [$idPart$safeStage] ${message.replace('\n', ' ')}"
            lines.addLast(line)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
            runCatching {
                file.appendText(line + "\n", Charsets.UTF_8)
                if (file.length() > MAX_FILE_BYTES) {
                    val kept = lines.joinToString("\n", postfix = "\n")
                    file.writeText(kept, Charsets.UTF_8)
                }
            }
        }
    }

    private fun loadFromDiskLocked() {
        lines.clear()
        if (!file.exists()) return
        runCatching {
            file.readLines(Charsets.UTF_8)
                .takeLast(MAX_LINES)
                .forEach { lines.addLast(it) }
        }
    }

    private fun ensureInitialized() {
        if (!initialized) {
            throw IllegalStateException("DiagnosticTrace.init(context) must be called first")
        }
    }
}
