package com.yasn198020.aicontrol

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compact diagnostic trace used for troubleshooting MQTT -> scenario -> action flow.
 * Only important stages are kept so the trace is easy to copy into chat.
 */
object DiagnosticTrace {
    private const val FILE_NAME = "diagnostic_trace.log"
    private const val MAX_LINES = 250
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val MAX_MESSAGE_LENGTH = 220

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val eventCounter = AtomicLong(System.currentTimeMillis())
    private val currentEvent = ThreadLocal<Long?>()
    private val foreground = AtomicBoolean(false)

    @Volatile private var initialized = false
    private lateinit var file: File
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val importantStages = setOf(
        "MQTT", "CONDITION", "EDGE", "TRIGGER", "ACTION",
        "VERIFY", "NOTIFY", "VBTN90", "ERROR", "APP_STATE"
    )

    fun init(context: Context) {
        BackgroundTrace.init(context)
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
        val state = if (foreground.get()) "FOREGROUND" else "BACKGROUND"
        BackgroundTrace.event("MQTT", "RX state=$state topic=$topic payload=${compact(payload)}", id)
        stepForEvent(id, "MQTT", "RX $topic payload=${compact(payload)}")
        return id
    }

    fun setForeground(active: Boolean) {
        ensureInitialized()
        foreground.set(active)
        BackgroundTrace.setForeground(active)
    }

    fun isForeground(): Boolean = foreground.get()
    fun currentEventId(): Long? = currentEvent.get()
    fun clearCurrentEvent() = currentEvent.remove()

    fun step(stage: String, message: String) {
        ensureInitialized()
        append(currentEvent.get(), stage, message)
    }

    fun stepForEvent(eventId: Long?, stage: String, message: String) {
        ensureInitialized()
        append(eventId, stage, message)
    }

    fun system(message: String) {
        // Intentionally ignored: connection/lifecycle noise is not useful in the compact trace.
    }

    fun error(message: String) {
        ensureInitialized()
        append(currentEvent.get(), "ERROR", message)
    }

    fun read(): List<String> {
        ensureInitialized()
        synchronized(lock) { return lines.toList() }
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
        if (safeStage !in importantStages) return

        synchronized(lock) {
            if (!initialized) return
            val timestamp = formatter.format(Date())
            val idPart = eventId?.let { " #$it" } ?: ""
            val line = "$timestamp [$safeStage$idPart] ${compact(message)}"
            if (!foreground.get() && safeStage in setOf("CONDITION", "EDGE", "TRIGGER", "ACTION", "VERIFY", "NOTIFY", "VBTN90", "ERROR")) {
                BackgroundTrace.event(safeStage, message, eventId)
            }
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()

            runCatching {
                file.appendText(line + "\n", Charsets.UTF_8)
                if (file.length() > MAX_FILE_BYTES) {
                    file.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                }
            }
        }
    }

    private fun compact(value: String): String {
        val oneLine = value.replace("\n", " ").replace("\r", " ").trim()
        return if (oneLine.length <= MAX_MESSAGE_LENGTH) oneLine
        else oneLine.take(MAX_MESSAGE_LENGTH) + "…"
    }

    private fun loadFromDiskLocked() {
        lines.clear()
        if (!file.exists()) return
        runCatching {
            file.readLines(Charsets.UTF_8).takeLast(MAX_LINES).forEach { lines.addLast(it) }
        }
    }

    private fun ensureInitialized() {
        if (!initialized) throw IllegalStateException("DiagnosticTrace.init(context) must be called first")
    }
}
