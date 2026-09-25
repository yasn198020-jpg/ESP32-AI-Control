package com.yasn198020.aicontrol

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Separate compact trace for proving what happens while the app screen is
 * in foreground/background. It intentionally contains only state transitions
 * and important MQTT/scenario events observed while backgrounded.
 */
object BackgroundTrace {
    private const val FILE_NAME = "background_trace.log"
    private const val MAX_LINES = 300
    private const val MAX_FILE_BYTES = 128 * 1024L
    private const val MAX_MESSAGE_LENGTH = 220

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var initialized = false
    private var foreground = false
    private lateinit var file: File
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BackgroundTraceWriter").apply {
            isDaemon = true
        }
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            file = File(context.applicationContext.filesDir, FILE_NAME)
            loadLocked()
            initialized = true
        }
    }

    fun setForeground(active: Boolean) {
        ensureInitialized()
        synchronized(lock) {
            if (foreground == active) return
            foreground = active
            appendLocked(if (active) "FOREGROUND" else "BACKGROUND", null)
        }
    }

    fun event(stage: String, message: String, eventId: Long? = null) {
        ensureInitialized()
        synchronized(lock) {
            if (foreground) return
            val safeStage = stage.trim().uppercase(Locale.ROOT)
            appendLocked(safeStage, eventId, message)
        }
    }

    fun read(): List<String> {
        ensureInitialized()
        synchronized(lock) { return lines.toList() }
    }

    fun clear() {
        ensureInitialized()
        synchronized(lock) {
            lines.clear()
        }
        writer.execute {
            runCatching { file.writeText("") }
        }
    }
    private fun appendLocked(stage: String, eventId: Long?, message: String? = null) {
        val time = formatter.format(Date())
        val id = eventId?.let { " #" + it } ?: ""
        val suffix = message?.let { " " + compact(it) } ?: ""
        val line = time + " [" + stage + id + "]" + suffix
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()

        writer.execute {
            runCatching {
                file.appendText(line + "\n", Charsets.UTF_8)
                if (file.length() > MAX_FILE_BYTES) {
                    val snapshot = synchronized(lock) {
                        lines.joinToString("\n", postfix = "\n")
                    }
                    file.writeText(snapshot, Charsets.UTF_8)
                }
            }
        }
    }
    private fun compact(value: String): String {
        val oneLine = value.replace("\n", " ").replace("\r", " ").trim()
        return if (oneLine.length <= MAX_MESSAGE_LENGTH) oneLine
        else oneLine.take(MAX_MESSAGE_LENGTH) + "…"
    }

    private fun loadLocked() {
        lines.clear()
        if (!file.exists()) return
        runCatching {
            file.readLines(Charsets.UTF_8).takeLast(MAX_LINES).forEach { lines.addLast(it) }
        }
        // The file may survive a process restart while the current process starts
        // in the foreground; the next lifecycle callback establishes the real state.
    }

    private fun ensureInitialized() {
        if (!initialized) throw IllegalStateException("BackgroundTrace.init(context) must be called first")
    }
}
