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
    private val onConfig: (String, String, String, String, String, String, Int, String) -> Unit,
    private val onReconnectRequested: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var connecting = false
    @Volatile private var reconnecting = false
    @Volatile private var lastConnectAttemptAt = 0L
    @Volatile private var lastRxAt = 0L
    @Volatile private var rxCallbackCount = 0L
    @Volatile private var lastRxTopic = ""
    @Volatile private var lastRxThread = ""
    private var prefix = ""
    @Volatile private var connectionConfigKey = ""

    private val connectionStateLogger: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MQTT-ConnectionState").apply { isDaemon = true }
    }

    // Used only for failures of the very first connection attempt. Once a
    // connection has succeeded, Paho owns reconnection of that same client.
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MQTT-ReconnectSupervisor").apply { isDaemon = true }
    }

    @Volatile private var connectionStateLoggingStarted = false
    @Volatile private var initialReconnectAttempt = 0

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

    @Synchronized
    fun connect(host: String, port: Int, mqttPrefix: String, username: String, password: String, tls: Boolean) {
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
        val newConfigKey = normalizedUrl + "|" + username + "|" + password + "|" + prefix

        // Do not tear down a healthy Paho client just because the Activity or
        // service asked for "connect" again. Replacing a live client creates
        // late callbacks from the old client and can produce stale-client
        // races during foreground/background transitions.
        val current = client
        if (connectionConfigKey == newConfigKey &&
            current != null &&
            (current.isConnected || connecting || reconnecting)
        ) {
            DiagnosticTrace.system(
                "MQTT connect ignored: existing client owns same configuration " +
                    "connected=" + current.isConnected +
                    " connecting=" + connecting +
                    " reconnecting=" + reconnecting +
                    " thread=" + Thread.currentThread().name
            )
            return
        }

        if (current != null || connecting || reconnecting) {
            DiagnosticTrace.system(
                "MQTT client replacement requested oldConfigSame=" +
                    (connectionConfigKey == newConfigKey) +
                    " oldConnected=" + (current?.isConnected == true) +
                    " oldConnecting=" + connecting +
                    " oldReconnecting=" + reconnecting +
                    " thread=" + Thread.currentThread().name
            )
            disconnect()
        }

        connectionConfigKey = newConfigKey

        emitLog("MQTT target: " + normalizedUrl)

        try {
            val id = "ESP32AI-" + UUID.randomUUID().toString().replace("-", "").take(12)
            val c = MqttAsyncClient(normalizedUrl, id, MemoryPersistence())

            client = c
            connecting = true
            reconnecting = false
            lastConnectAttemptAt = System.currentTimeMillis()
            lastRxAt = 0L
            rxCallbackCount = 0L
            lastRxTopic = ""
            lastRxThread = ""
            startConnectionStateLogger()

            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    if (client !== c) {
                        DiagnosticTrace.system("MQTT Paho connectComplete ignored: stale client")
                        return
                    }

                    connecting = false
                    reconnecting = false
                    lastConnectAttemptAt = 0L
                    initialReconnectAttempt = 0
                    lastRxAt = System.currentTimeMillis()
                    rxCallbackCount = 0L
                    lastRxTopic = ""
                    lastRxThread = Thread.currentThread().name

                    DiagnosticTrace.system(
                        "MQTT Paho connectComplete reconnect=" + reconnect +
                            " serverURI=" + serverURI +
                            " thread=" + Thread.currentThread().name
                    )
                    DiagnosticTrace.system(
                        "MQTT Paho state connected=" + (client?.isConnected == true) +
                            " automaticReconnect=true"
                    )
                    DiagnosticTrace.system("VBTN90 CONNECTION connected reconnect=" + reconnect)

                    emitLog("MQTT connected: " + serverURI)
                    emitConnected(true)
                    flushPendingPublishes()
                    subscribeDevice()
                }

                override fun connectionLost(cause: Throwable?) {
                    if (client !== c) {
                        DiagnosticTrace.system("MQTT Paho connectionLost ignored: stale client")
                        return
                    }

                    connecting = false
                    reconnecting = true
                    lastConnectAttemptAt = 0L

                    DiagnosticTrace.system(
                        "MQTT Paho connectionLost thread=" + Thread.currentThread().name +
                            " connected=" + (client?.isConnected == true) +
                            " rxCount=" + rxCallbackCount +
                            " lastRxAt=" + lastRxAt +
                            " lastRxTopic=" + lastRxTopic +
                            " cause=" + mqttExceptionText("cause", cause)
                    )
                    DiagnosticTrace.system("MQTT Paho automatic reconnect ACTIVE")
                    DiagnosticTrace.system("VBTN90 CONNECTION lost")

                    emitLog(mqttExceptionText("MQTT connection lost; Paho automatic reconnect active", cause))
                    emitConnected(false)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == null || message == null) return
                    if (client !== c) {
                        DiagnosticTrace.system("MQTT Paho messageArrived ignored: stale client topic=" + topic)
                        return
                    }

                    lastRxAt = System.currentTimeMillis()
                    rxCallbackCount++
                    lastRxTopic = topic
                    lastRxThread = Thread.currentThread().name
                    DiagnosticTrace.system("MQTT Paho messageArrived #" + rxCallbackCount + " thread=" + lastRxThread + " topic=" + topic + " bytes=" + message.payload.size + " connected=" + (client?.isConnected == true))
                    val payload = String(message.payload, Charsets.UTF_8)

                    DiagnosticTrace.rawMqttReceived(
                        topic = topic,
                        payload = payload,
                        qos = message.qos,
                        retained = message.isRetained
                    )

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
                                    val label = json.optString("descr").trim().ifBlank {
                                        json.optString("label", json.optString("name", widgetId))
                                    }
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
                                    DiagnosticTrace.stepForEvent(traceId, "VBTN90", "STATUS parsed value=" + value)
                                    DiagnosticTrace.stepForEvent(traceId, "VBTN90", "STATUS BEFORE emitStatus value=" + value)
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
                                        DiagnosticTrace.stepForEvent(traceId, "VBTN90", "STATUS AFTER emitStatus value=" + value)
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
                                    DiagnosticTrace.stepForEvent(
                                        traceId,
                                        "ERROR",
                                        "EVENT parse failed topic=" + topic +
                                            " error=" + (e.message ?: e.javaClass.simpleName)
                                    )
                                    emitLog("MQTT event parse failed: " + topic)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        DiagnosticTrace.stepForEvent(
                            traceId,
                            "ERROR",
                            "unexpected RX error: " + (e.message ?: e.javaClass.simpleName)
                        )
                        emitLog("MQTT message processing failed: " + (e.message ?: e.javaClass.simpleName))
                    } finally {
                        DiagnosticTrace.stepForEvent(traceId, "MQTT", "RX processing finished")
                        DiagnosticTrace.clearCurrentEvent()
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })

            val options = MqttConnectOptions().apply {
                // One MQTT client owns both the initial connection and all
                // reconnects after a successful session.
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
                    if (client !== c) return

                    client = null
                    connecting = false
                    reconnecting = false
                    lastConnectAttemptAt = 0L

                    try { c.close() } catch (_: Exception) {}

                    emitLog(mqttExceptionText("MQTT connect failed", exception))
                    emitLog("MQTT credentials supplied: " + username.isNotBlank())
                    emitLog("MQTT TLS: " + tls)
                    emitLog("MQTT URL: " + normalizedUrl)
                    emitConnected(false)

                    scheduleInitialReconnect("connectFailure")
                }
            })
        } catch (e: Exception) {
            client = null
            connecting = false
            reconnecting = false
            lastConnectAttemptAt = 0L
            emitLog(mqttExceptionText("MQTT error", e))
            emitConnected(false)
            scheduleInitialReconnect("connectException")
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

    @Synchronized
    fun disconnect() {
        initialReconnectAttempt = 0

        // Remove the client reference before calling into Paho so a late
        // callback from the old client is always classified as stale.
        val oldClient = client
        client = null
        connecting = false
        reconnecting = false
        lastConnectAttemptAt = 0L
        connectionConfigKey = ""

        try {
            oldClient?.disconnect()
        } catch (e: Exception) {
            emitLog(mqttExceptionText("MQTT disconnect error", e))
        }

        try {
            oldClient?.close()
        } catch (e: Exception) {
            emitLog(mqttExceptionText("MQTT close error", e))
        }

        emitConnectionStateLog()
        lastRxAt = 0L
        rxCallbackCount = 0L
        lastRxTopic = ""
        lastRxThread = ""
        emitConnected(false)
    }

    fun isConnected(): Boolean = client?.isConnected == true

    // CONNECTING includes Paho's automatic reconnect phase. This prevents
    // AppRuntime and the background supervisor from creating a second client.
    fun isConnecting(): Boolean = connecting || reconnecting

    fun diagnostics(): String {
        val idleMs = if (lastRxAt == 0L) -1L else System.currentTimeMillis() - lastRxAt
        val c = client
        val state = when {
            c?.isConnected == true -> "CONNECTED"
            connecting -> "CONNECTING"
            reconnecting -> "RECONNECTING"
            c == null -> "DISCONNECTED"
            else -> "NOT_CONNECTED"
        }
        return "state=" + state +
            " connected=" + (c?.isConnected == true) +
            " connecting=" + connecting +
            " reconnecting=" + reconnecting +
            " rxCount=" + rxCallbackCount +
            " lastRxIdleMs=" + idleMs +
            " lastRxTopic=" + lastRxTopic +
            " lastRxThread=" + lastRxThread
    }

    /**
     * Initial-connect safety net only.
     *
     * This never interrupts Paho automatic reconnect. It is used when the very
     * first connection attempt never completes within the expected timeout.
     */
    @Synchronized
    fun reconnectIfConnectingTooLong(maxConnectingMs: Long = 25_000L): Boolean {
        if (!connecting || reconnecting) return false

        val startedAt = lastConnectAttemptAt
        if (startedAt <= 0L) return false

        val elapsedMs = System.currentTimeMillis() - startedAt
        if (elapsedMs < maxConnectingMs) return false

        DiagnosticTrace.system(
            "MQTT WATCHDOG CONNECTING_STUCK elapsedMs=" + elapsedMs +
                " thresholdMs=" + maxConnectingMs
        )
        emitLog(
            "MQTT watchdog: initial connecting stuck elapsedMs=" + elapsedMs +
                " thresholdMs=" + maxConnectingMs + "; replacing initial client"
        )

        val oldClient = client
        client = null
        connecting = false
        reconnecting = false
        lastConnectAttemptAt = 0L

        try { oldClient?.disconnect() } catch (_: Exception) {}
        try { oldClient?.close() } catch (_: Exception) {}

        lastRxAt = 0L
        rxCallbackCount = 0L
        lastRxTopic = ""
        lastRxThread = ""
        emitConnected(false)
        return true
    }

    private fun scheduleInitialReconnect(reason: String, delayMs: Long? = null) {
        if (client != null || connecting || reconnecting) return

        val attempt = (initialReconnectAttempt + 1).coerceAtMost(6)
        initialReconnectAttempt = attempt
        val backoffMs = delayMs ?: minOf(30_000L, 1_000L shl (attempt - 1))

        DiagnosticTrace.system(
            "MQTT INITIAL RECONNECT scheduled reason=" + reason +
                " attempt=" + attempt +
                " delayMs=" + backoffMs +
                " thread=" + Thread.currentThread().name
        )

        try {
            reconnectExecutor.schedule({
                if (client != null || connecting || reconnecting) return@schedule

                DiagnosticTrace.system(
                    "MQTT INITIAL RECONNECT execute reason=" + reason +
                        " attempt=" + attempt +
                        " thread=" + Thread.currentThread().name
                )

                runCatching {
                    onReconnectRequested()
                }.onFailure {
                    DiagnosticTrace.system(
                        "MQTT INITIAL RECONNECT failed reason=" + reason +
                            " error=" + (it.message ?: it.javaClass.simpleName)
                    )
                    emitLog(
                        "MQTT initial reconnect failed: " +
                            (it.message ?: it.javaClass.simpleName)
                    )
                }
            }, backoffMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            DiagnosticTrace.system(
                "MQTT INITIAL RECONNECT schedule failed reason=" + reason +
                    " error=" + (e.message ?: e.javaClass.simpleName)
            )
        }
    }

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
