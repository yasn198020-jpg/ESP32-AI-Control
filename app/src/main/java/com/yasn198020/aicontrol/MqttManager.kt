package com.yasn198020.aicontrol

import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MqttManager(
    private val onLog: (String) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val onStatus: (String, String, String) -> Unit,
    private val onConfig: (String, String, String, String, String, String, Int, String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var client: MqttAsyncClient? = null
    @Volatile private var connecting = false
    @Volatile private var lastRxAt = 0L
    @Volatile private var rxCallbackCount = 0L
    @Volatile private var lastRxTopic = ""
    @Volatile private var lastRxThread = ""
    private var prefix = ""
    private val connectionStateLogger: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MQTT-ConnectionState").apply { isDaemon = true }
    }
    @Volatile private var connectionStateLoggingStarted = false
    private data class PendingPublish(val topic: String, val payload: String, val eventId: Long?)
    private data class LastStatusEvent(
        val source: String,
        val value: String,
        val timestamp: Long
    )

    private val pendingPublishes = ArrayDeque<PendingPublish>()
    private val publishLock = Any()
    private val maxPendingPublishes = 100

    private val statusEventLock = Any()
    private val lastStatusEvents = mutableMapOf<String, LastStatusEvent>()

    companion object {
        private const val STATUS_EVENT_DEDUP_WINDOW_MS = 750L
        private const val MAX_STATUS_EVENT_KEYS = 512
    }

    fun connect(host: String, port: Int, mqttPrefix: String, username: String, password: String, tls: Boolean) {
        disconnect()

        val normalizedHost = host.trim()
        val normalizedPort = port.coerceIn(1, 65535)
        prefix = mqttPrefix.trim().trim('/')

        if (normalizedHost.isEmpty() || prefix.isEmpty()) {
            emitLog("MQTT: host/prefix is empty")
            return
        }

        val cleanHost = normalizedHost
            .removePrefix("ssl://")
            .removePrefix("tcp://")
            .removePrefix("mqtt://")
            .removePrefix("mqtts://")

        val normalizedUrl = (if (tls) "ssl://" else "tcp://") + cleanHost + ":" + normalizedPort

        emitLog("MQTT target: " + normalizedUrl)

        try {
            val id = "ESP32AI-" + UUID.randomUUID().toString().replace("-", "").take(12)
            val c = MqttAsyncClient(normalizedUrl, id, MemoryPersistence())
            client = c
            lastRxAt = System.currentTimeMillis()
            connecting = true
            startConnectionStateLogger()

            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    connecting = false
                    lastRxAt = System.currentTimeMillis()
                    rxCallbackCount = 0L
                    lastRxTopic = ""
                    lastRxThread = Thread.currentThread().name
                    DiagnosticTrace.system("MQTT Paho connectComplete reconnect=" + reconnect + " serverURI=" + serverURI + " thread=" + Thread.currentThread().name)
                    DiagnosticTrace.system("MQTT Paho state connected=" + (client?.isConnected == true))
                    DiagnosticTrace.system("VBTN90 CONNECTION connected reconnect=" + reconnect)
                    emitLog("MQTT connected: " + serverURI)
                    emitConnected(true)
                    flushPendingPublishes()
                    subscribeDevice()
                }

                override fun connectionLost(cause: Throwable?) {
                    connecting = false
                    DiagnosticTrace.system("MQTT Paho connectionLost thread=" + Thread.currentThread().name + " connected=" + (client?.isConnected == true) + " rxCount=" + rxCallbackCount + " lastRxAt=" + lastRxAt + " lastRxTopic=" + lastRxTopic + " cause=" + mqttExceptionText("cause", cause))
                    DiagnosticTrace.system("VBTN90 CONNECTION lost")
                    emitLog(mqttExceptionText("MQTT connection lost", cause))
                    emitConnected(false)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == null || message == null) return

                    lastRxAt = System.currentTimeMillis()
                    rxCallbackCount++
                    lastRxTopic = topic
                    lastRxThread = Thread.currentThread().name
                    DiagnosticTrace.system("MQTT Paho messageArrived #" + rxCallbackCount + " thread=" + lastRxThread + " topic=" + topic + " bytes=" + message.payload.size + " connected=" + (client?.isConnected == true))
                    val payload = String(message.payload, Charsets.UTF_8)

                    // Record the raw MQTT packet before any topic parsing,
                    // deduplication, scenario processing or history updates.
                    DiagnosticTrace.rawMqttReceived(
                        topic = topic,
                        payload = payload,
                        qos = message.qos,
                        retained = message.isRetained
                    )

                    // Dedicated VBTN90 ingress trace: record every packet related
                    // to vbtn90 immediately after MQTT delivery and before any
                    // parsing, deduplication, scenario processing or callbacks.
                    val isVbtn90Ingress =
                        topic.contains("/vbtn90/", ignoreCase = true) ||
                        topic.endsWith("/vbtn90", ignoreCase = true) ||
                        (topic.endsWith("/config", ignoreCase = true) &&
                            payload.contains("/vbtn90", ignoreCase = true))
                    if (isVbtn90Ingress) {
                        DiagnosticTrace.system(
                            "VBTN90_RAW topic=" + topic +
                                " payload=" + payload +
                                " qos=" + message.qos +
                                " retained=" + message.isRetained +
                                " thread=" + Thread.currentThread().name
                        )
                    }

                    val messageType = when {
                        topic.endsWith("/config") -> "CONFIG"
                        topic.endsWith("/status") -> "STATUS"
                        topic.endsWith("/event") -> "EVENT"
                        else -> "MESSAGE"
                    }

                    val traceId = DiagnosticTrace.beginEvent(topic, payload)

                    try {
                        emitLog(
                        "MQTT RX [" + messageType + "] " +
                            "topic=" + topic +
                            " payload=" + payload +
                            " qos=" + message.qos +
                            " retained=" + message.isRetained
                    )
                    if (topic.contains("vbtn90", ignoreCase = true) || payload.contains("vbtn90", ignoreCase = true)) {
                        DiagnosticTrace.stepForEvent(
                            traceId,
                            "MQTT",
                            "MQTT RX topic=" + topic + " payload=" + payload
                        )
                    }

                    val topicRoots = listOf(
                        "/" + prefix.trim('/'),
                        "/dghjko"
                    ).distinct()
                    val matchingRoot = topicRoots.firstOrNull { topic.startsWith(it + "/") }

                    if (matchingRoot != null && topic.endsWith("/config")) {
                        val parts = topic.removePrefix(matchingRoot).trim('/').split("/")
                        if (parts.size == 2) {
                            try {
                                val json = org.json.JSONObject(payload)
                                DiagnosticTrace.step("MQTT", "CONFIG parsed parts=" + parts.joinToString("/") + " payload valid")
                                val configTopic = json.optString("topic", "").trim()
                                val widgetId = configTopic.trim('/').substringAfterLast('/', "")
                                if (widgetId.isBlank()) {
                                    emitLog("MQTT config ignored: topic is missing: " + topic)
                                    return
                                }
                                val label = json.optString("descr").trim().ifBlank { json.optString("label", json.optString("name", widgetId)) }
                                val widgetType = json.optString("widget", "status")
                                emitLog(
                                    "MQTT CONFIG parsed: device=" + parts[0] +
                                        " widget=" + widgetId +
                                        " type=" + widgetType +
                                        " label=" + label +
                                        " topic=" + configTopic
                                )
                                val page = json.optString("page", "Основная")
                                val order = json.optInt("order", 0)
                                DiagnosticTrace.step("MQTT", "CONFIG widget=" + widgetId + " type=" + widgetType + " page=" + page)
                                emitConfig(parts[0], widgetId, label, widgetType, page, configTopic, order, json.toString())
                            } catch (_: Exception) {
                                DiagnosticTrace.stepForEvent(traceId, "ERROR", "CONFIG parse failed topic=" + topic)
                                emitLog("MQTT config parse failed: " + topic)
                            }
                        }
                    } else if (matchingRoot != null && topic.endsWith("/status")) {
                        val parts = topic.removePrefix(matchingRoot).trim('/').split("/")
                        DiagnosticTrace.stepForEvent(
                            traceId,
                            "MQTT",
                            "STATUS route matched parts=" + parts.joinToString("/")
                        )
                        if (parts.size >= 3) {
                            val widgetId = parts[parts.size - 2]
                            val json = try { org.json.JSONObject(payload) } catch (_: Exception) { null }
                            val value = json?.optString("status")?.takeIf { json.has("status") } ?: payload
                            DiagnosticTrace.stepForEvent(
                                traceId,
                                "MQTT",
                                "STATUS parsed device=" + parts[0] + " widget=" + widgetId + " value=" + value
                            )
                            emitLog(
                                "MQTT STATUS parsed: device=" + parts[0] +
                                    " widget=" + widgetId +
                                    " value=" + value
                            )
                            if (widgetId == "vbtn90") {
                                DiagnosticTrace.stepForEvent(
                                    traceId,
                                    "VBTN90",
                                    "STATUS parsed value=" + value
                                )
                                DiagnosticTrace.stepForEvent(
                                    traceId,
                                    "VBTN90",
                                    "STATUS BEFORE emitStatus value=" + value
                                )
                            }
                            try {
                                if (shouldDropStatusEventDuplicate(
                                        source = "STATUS",
                                        deviceId = parts[0],
                                        widgetId = widgetId,
                                        value = value
                                    )
                                ) {
                                    DiagnosticTrace.stepForEvent(
                                        traceId,
                                        "MQTT",
                                        "STATUS duplicate of EVENT dropped device=" + parts[0] +
                                            " widget=" + widgetId + " value=" + value
                                    )
                                    return
                                }

                                emitStatus(parts[0], widgetId, value)
                                if (widgetId == "vbtn90") {
                                    DiagnosticTrace.stepForEvent(
                                        traceId,
                                        "VBTN90",
                                        "STATUS AFTER emitStatus value=" + value
                                    )
                                }
                            } catch (e: Exception) {
                                DiagnosticTrace.stepForEvent(
                                    traceId,
                                    "ERROR",
                                    "STATUS emitStatus failed device=" + parts[0] +
                                        " widget=" + widgetId +
                                        " value=" + value +
                                        " error=" + (e.message ?: e.javaClass.simpleName)
                                )
                                throw e
                            }
                        } else {
                            DiagnosticTrace.stepForEvent(
                                traceId,
                                "ERROR",
                                "STATUS route invalid parts=" + parts.size + " topic=" + topic
                            )
                        }
                    } else if (matchingRoot != null && topic.endsWith("/event")) {
                        val parts = topic.removePrefix(matchingRoot).trim('/').split("/")
                        if (parts.size >= 3) {
                            try {
                                val json = org.json.JSONObject(payload)
                                val widgetId = json.optString("id", parts[parts.size - 2])
                                val value = json.optString("val", payload)
                                DiagnosticTrace.step("MQTT", "EVENT parsed device=" + parts[0] + " widget=" + widgetId + " value=" + value)
                                emitLog("MQTT EVENT parsed: device=" + parts[0] + " widget=" + widgetId + " value=" + value)
                                if (widgetId == "vbtn90") {
                                    DiagnosticTrace.stepForEvent(
                                        traceId,
                                        "VBTN90",
                                        "EVENT parsed device=" + parts[0] + " widget=" + widgetId + " value=" + value
                                    )
                                }
                                if (shouldDropStatusEventDuplicate(
                                        source = "EVENT",
                                        deviceId = parts[0],
                                        widgetId = widgetId,
                                        value = value
                                    )
                                ) {
                                    DiagnosticTrace.stepForEvent(
                                        traceId,
                                        "MQTT",
                                        "EVENT duplicate of STATUS dropped device=" + parts[0] +
                                            " widget=" + widgetId + " value=" + value
                                    )
                                    return
                                }

                                emitStatus(parts[0], widgetId, value)
                            } catch (e: Exception) {
                                DiagnosticTrace.stepForEvent(traceId, "ERROR", "EVENT parse failed topic=" + topic + " error=" + (e.message ?: e.javaClass.simpleName))
                                emitLog("MQTT event parse failed: " + topic)
                            }
                        }
                    }
                    } catch (e: Exception) {
                        DiagnosticTrace.stepForEvent(traceId, "ERROR", "unexpected RX error: " + (e.message ?: e.javaClass.simpleName))
                        emitLog("MQTT message processing failed: " + (e.message ?: e.javaClass.simpleName))
                    } finally {
                        DiagnosticTrace.stepForEvent(traceId, "MQTT", "RX processing finished")
                        DiagnosticTrace.clearCurrentEvent()
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30

                if (username.isNotBlank()) {
                    userName = username
                    this.password = password.toCharArray()
                }
            }

            emitLog("MQTT connecting: " + normalizedUrl)

            c.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    emitLog("MQTT connection accepted")
                }

                override fun onFailure(
                    asyncActionToken: IMqttToken?,
                    exception: Throwable?
                ) {
                    connecting = false
                    emitLog(mqttExceptionText("MQTT connect failed", exception))
                    emitLog("MQTT credentials supplied: " + username.isNotBlank())
                    emitLog("MQTT TLS: " + tls)
                    emitLog("MQTT URL: " + normalizedUrl)
                    emitConnected(false)
                }
            })
        } catch (e: Exception) {
            connecting = false
            emitLog(mqttExceptionText("MQTT error", e))
            emitConnected(false)
        }
    }

    private fun shouldDropStatusEventDuplicate(
        source: String,
        deviceId: String,
        widgetId: String,
        value: String
    ): Boolean {
        val key = deviceId + "/" + widgetId
        val now = System.currentTimeMillis()

        synchronized(statusEventLock) {
            val previous = lastStatusEvents[key]
            val duplicate = previous != null &&
                previous.source != source &&
                previous.value == value &&
                now - previous.timestamp in 0..STATUS_EVENT_DEDUP_WINDOW_MS

            lastStatusEvents[key] = LastStatusEvent(source, value, now)

            if (lastStatusEvents.size > MAX_STATUS_EVENT_KEYS) {
                val oldestKey = lastStatusEvents.entries
                    .minByOrNull { it.value.timestamp }
                    ?.key
                if (oldestKey != null) lastStatusEvents.remove(oldestKey)
            }

            return duplicate
        }
    }

    private fun mqttExceptionText(prefixText: String, error: Throwable?): String {
        if (error == null) return prefixText + ": unknown error"

        return buildString {
            append(prefixText)
            append(": ")
            append(error.javaClass.simpleName)

            val mqttError = error as? MqttException
            if (mqttError != null) {
                append(" reasonCode=")
                append(mqttError.reasonCode)
            }

            append(" message=")
            append(error.message ?: "<empty>")

            var cause = error.cause
            var level = 1
            while (cause != null && level <= 5) {
                append(" | cause")
                append(level)
                append("=")
                append(cause.javaClass.simpleName)
                append(":")
                append(cause.message ?: "<empty>")
                cause = cause.cause
                level++
            }
        }
    }

    private fun subscribeDevice() {
        val c = client ?: return
        val root = "/" + prefix.trim('/')
        val topics = if (root == "/dghjko") arrayOf("/dghjko/#") else arrayOf(root + "/#", "/dghjko/#")

        try {
            c.subscribe(
                topics,
                IntArray(topics.size) { 1 },
                null,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        emitLog("MQTT subscribed: " + topics.joinToString(", "))
                        DiagnosticTrace.system("VBTN90 SUBSCRIBED via " + topics.joinToString(","))
                        publishHello()
                    }

                    override fun onFailure(
                        asyncActionToken: IMqttToken?,
                        exception: Throwable?
                    ) {
                        emitLog(mqttExceptionText("MQTT subscribe failed", exception))
                    }
                }
            )
        } catch (e: Exception) {
            emitLog(mqttExceptionText("MQTT subscribe error", e))
        }
    }

    fun publishHello() {
        publish("/dghjko", "HELLO")
    }

    fun publishWidget(topic: String, value: String): Boolean {
        if (topic.isBlank()) return false
        return publish(topic, value)
    }

    fun publishControl(
        deviceId: String,
        widgetId: String,
        value: String,
        eventId: Long? = DiagnosticTrace.currentEventId()
    ): Boolean {
        if (prefix.isBlank() || deviceId.isBlank() || widgetId.isBlank()) return false
        val root = "/" + prefix.trim('/')
        return publish(
            root + "/" + deviceId + "/" + widgetId + "/control",
            org.json.JSONObject().put("status", value).toString(),
            eventId
        )
    }

    private fun publish(topic: String, payload: String, eventIdOverride: Long? = null): Boolean {
        val c = client
        val eventId = eventIdOverride ?: DiagnosticTrace.currentEventId()

        if (c == null || !c.isConnected) {
            synchronized(publishLock) {
                if (pendingPublishes.size >= maxPendingPublishes) pendingPublishes.removeFirst()
                pendingPublishes.addLast(PendingPublish(topic, payload, eventId))
            }
            DiagnosticTrace.stepForEvent(eventId, "ACTION", "MQTT queued topic=" + topic + " payload=" + payload)
            emitLog("MQTT publish queued: not connected topic=" + topic)
            return false
        }

        return try {
            val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
                isRetained = false
            }
            c.publish(topic, message)
            if (topic.contains("vbtn90", ignoreCase = true) || payload.contains("vbtn90", ignoreCase = true)) {
                DiagnosticTrace.stepForEvent(eventId, "VBTN90", "TX result=true topic=" + topic + " payload=" + payload)
            }
            DiagnosticTrace.stepForEvent(eventId, "MQTT", "TX result=true topic=" + topic + " payload=" + payload)
            emitLog("MQTT TX topic=" + topic + " payload=" + payload)
            true
        } catch (e: Exception) {
            synchronized(publishLock) {
                if (pendingPublishes.size >= maxPendingPublishes) pendingPublishes.removeFirst()
                pendingPublishes.addLast(PendingPublish(topic, payload, eventId))
            }
            DiagnosticTrace.stepForEvent(eventId, "ERROR", "TX failed topic=" + topic + " error=" + (e.message ?: e.javaClass.simpleName))
            emitLog(mqttExceptionText("MQTT publish failed", e))
            false
        }
    }

    private fun flushPendingPublishes() {
        val c = client ?: return
        if (!c.isConnected) return

        while (true) {
            val pending = synchronized(publishLock) {
                if (pendingPublishes.isEmpty()) null else pendingPublishes.removeFirst()
            } ?: break
            try {
                val message = MqttMessage(pending.payload.toByteArray(Charsets.UTF_8)).apply {
                    qos = 1
                    isRetained = false
                }
                c.publish(pending.topic, message)
                if (pending.topic.contains("vbtn90", ignoreCase = true) || pending.payload.contains("vbtn90", ignoreCase = true)) {
                    DiagnosticTrace.stepForEvent(pending.eventId, "VBTN90", "TX queued result=true topic=" + pending.topic + " payload=" + pending.payload)
                }
                DiagnosticTrace.stepForEvent(pending.eventId, "MQTT", "TX queued result=true topic=" + pending.topic + " payload=" + pending.payload)
                emitLog("MQTT TX queued topic=" + pending.topic + " payload=" + pending.payload)
            } catch (e: Exception) {
                synchronized(publishLock) {
                    if (pendingPublishes.size >= maxPendingPublishes) pendingPublishes.removeFirst()
                    pendingPublishes.addFirst(pending)
                }
                emitLog(mqttExceptionText("MQTT queued publish failed", e))
                break
            }
        }
    }

    private fun startConnectionStateLogger() {
        if (connectionStateLoggingStarted) return
        connectionStateLoggingStarted = true
        // This is based on Paho isConnected(), not on message reception.
        // First log immediately, then every 30 seconds.
        connectionStateLogger.scheduleAtFixedRate({
            runCatching { emitConnectionStateLog() }
        }, 0L, 30L, TimeUnit.SECONDS)
    }

    private fun emitConnectionStateLog() {
        val c = client
        val connected = c?.isConnected == true
        val idleMs = if (lastRxAt == 0L) -1L else System.currentTimeMillis() - lastRxAt
        val state = when {
            connected -> "CONNECTED"
            connecting -> "CONNECTING"
            c == null -> "DISCONNECTED"
            else -> "NOT_CONNECTED"
        }
        DiagnosticTrace.system(
            "MQTT CONNECTION STATE state=" + state +
                " connected=" + connected +
                " connecting=" + connecting +
                " rxCount=" + rxCallbackCount +
                " lastRxIdleMs=" + idleMs +
                " lastRxTopic=" + lastRxTopic
        )
        emitLog(
            "MQTT connection state: " + state +
                " connected=" + connected +
                " connecting=" + connecting +
                " rxCount=" + rxCallbackCount +
                " lastRxIdleMs=" + idleMs
        )
    }

    fun disconnect() {
        val c = client ?: return
        try {
            if (c.isConnected) c.disconnect()
        } catch (e: Exception) {
            emitLog(mqttExceptionText("MQTT disconnect error", e))
        } finally {
            client = null
            connecting = false
            emitConnectionStateLog()
            lastRxAt = 0L
            rxCallbackCount = 0L
            lastRxTopic = ""
            lastRxThread = ""
            emitConnected(false)
        }
    }

    fun isConnected(): Boolean = client?.isConnected == true
    fun isConnecting(): Boolean = connecting

    fun diagnostics(): String {
        val idleMs = if (lastRxAt == 0L) -1L else System.currentTimeMillis() - lastRxAt
        return "connected=" + isConnected() + " connecting=" + isConnecting() + " rxCount=" + rxCallbackCount + " lastRxIdleMs=" + idleMs + " lastRxTopic=" + lastRxTopic + " lastRxThread=" + lastRxThread
    }

    /**
     * Compatibility method retained for older callers.
     * MQTT liveness is determined by Paho connection state, not by absence
     * of telemetry packets.
     */
    fun reconnectIfStale(maxIdleMs: Long = 90_000L): Boolean = false

    private fun emitLog(value: String) {
        main.post { onLog(value) }
    }

    private fun emitConnected(value: Boolean) {
        main.post { onConnected(value) }
    }

    // STATUS/EVENT must never wait for the Activity or main UI looper.
    // AppRuntime owns the background processing path.
    private fun emitStatus(deviceId: String, widgetId: String, value: String) {
        onStatus(deviceId, widgetId, value)
    }

    private fun emitConfig(deviceId: String, widgetId: String, label: String, widgetType: String, page: String, topic: String, order: Int, raw: String) {
        main.post { onConfig(deviceId, widgetId, label, widgetType, page, topic, order, raw) }
    }
}
