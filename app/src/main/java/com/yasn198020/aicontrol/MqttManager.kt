package com.yasn198020.aicontrol

import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

class MqttManager(
    private val onLog: (String) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val onStatus: (String, String, String) -> Unit,
    private val onConfig: (String, String, String, String, String, String, Int, String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var client: MqttAsyncClient? = null
    @Volatile private var connecting = false
    private var prefix = ""
    private data class PendingPublish(val topic: String, val payload: String, val eventId: Long?)
    private val pendingPublishes = ArrayDeque<PendingPublish>()
    private val publishLock = Any()
    private val maxPendingPublishes = 100

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
            connecting = true

            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    connecting = false
                    DiagnosticTrace.system("MQTT connected reconnect=" + reconnect + " serverURI=" + serverURI)
                    DiagnosticTrace.system("VBTN90 CONNECTION connected reconnect=" + reconnect)
                    emitLog("MQTT connected: " + serverURI)
                    emitConnected(true)
                    flushPendingPublishes()
                    subscribeDevice()
                }

                override fun connectionLost(cause: Throwable?) {
                    connecting = false
                    DiagnosticTrace.system("MQTT connection lost: " + (cause?.message ?: cause?.javaClass?.simpleName ?: "unknown"))
                    DiagnosticTrace.system("VBTN90 CONNECTION lost")
                    emitLog(mqttExceptionText("MQTT connection lost", cause))
                    emitConnected(false)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == null || message == null) return

                    val payload = String(message.payload, Charsets.UTF_8)
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
                    if (topic.contains("/vbtn90/")) {
                        DiagnosticTrace.stepForEvent(
                            traceId,
                            "MQTT",
                            "VBTN90 RX topic=" + topic + " payload=" + payload
                        )
                    }

                    val root = "/dghjko/"
                    if (topic.startsWith(root) && topic.endsWith("/config")) {
                        val parts = topic.removePrefix(root).trim('/').split("/")
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
                    } else if (topic.startsWith(root) && topic.endsWith("/status")) {
                        val parts = topic.removePrefix(root).trim('/').split("/")
                        if (parts.size >= 3) {
                            val json = try { org.json.JSONObject(payload) } catch (_: Exception) { null }
                            val value = json?.optString("status")?.takeIf { json.has("status") } ?: payload
                            DiagnosticTrace.step("MQTT", "STATUS parsed device=" + parts[0] + " widget=" + parts[parts.size - 2] + " value=" + value)
                            emitLog(
                                "MQTT STATUS parsed: device=" + parts[0] +
                                    " widget=" + parts[parts.size - 2] +
                                    " value=" + value
                            )
                            if (parts[parts.size - 2] == "vbtn90") {
                                DiagnosticTrace.stepForEvent(
                                    traceId,
                                    "MQTT",
                                    "VBTN90 STATUS parsed value=" + value
                                )
                            }
                            emitStatus(parts[0], parts[parts.size - 2], value)
                        }
                    } else if (topic.startsWith(root) && topic.endsWith("/event")) {
                        val parts = topic.removePrefix(root).trim('/').split("/")
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
                                        "MQTT",
                                        "VBTN90 EVENT parsed value=" + value
                                    )
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
                IntArray(topics.size) { 0 },
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

    fun publishControl(deviceId: String, widgetId: String, value: String): Boolean {
        if (prefix.isBlank() || deviceId.isBlank() || widgetId.isBlank()) return false
        return publish(
            "/dghjko/" + deviceId + "/" + widgetId + "/control",
            org.json.JSONObject().put("status", value).toString()
        )
    }

    private fun publish(topic: String, payload: String): Boolean {
        val c = client
        val eventId = DiagnosticTrace.currentEventId()

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
                qos = 0
                isRetained = false
            }
            c.publish(topic, message)
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

    fun disconnect() {
        val c = client ?: return
        try {
            if (c.isConnected) c.disconnect()
        } catch (e: Exception) {
            emitLog(mqttExceptionText("MQTT disconnect error", e))
        } finally {
            client = null
            connecting = false
            emitConnected(false)
        }
    }

    fun isConnected(): Boolean = client?.isConnected == true
    fun isConnecting(): Boolean = connecting

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
