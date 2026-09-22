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
    private var prefix = ""

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

            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    emitLog("MQTT connected: " + serverURI)
                    emitConnected(true)
                    subscribeDevice()
                }

                override fun connectionLost(cause: Throwable?) {
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

                    emitLog(
                        "MQTT RX [" + messageType + "] " +
                            "topic=" + topic +
                            " payload=" + payload +
                            " qos=" + message.qos +
                            " retained=" + message.isRetained
                    )

                    val root = "/dghjko/"
                    if (topic.startsWith(root) && topic.endsWith("/config")) {
                        val parts = topic.removePrefix(root).trim('/').split("/")
                        if (parts.size == 2) {
                            try {
                                val json = org.json.JSONObject(payload)
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
                                emitConfig(parts[0], widgetId, label, widgetType, page, configTopic, order, json.toString())
                            } catch (_: Exception) {
                                emitLog("MQTT config parse failed: " + topic)
                            }
                        }
                    } else if (topic.startsWith(root) && topic.endsWith("/status")) {
                        val parts = topic.removePrefix(root).trim('/').split("/")
                        if (parts.size >= 3) {
                            val json = try { org.json.JSONObject(payload) } catch (_: Exception) { null }
                            val value = json?.optString("status")?.takeIf { json.has("status") } ?: payload
                            emitLog(
                                "MQTT STATUS parsed: device=" + parts[0] +
                                    " widget=" + parts[parts.size - 2] +
                                    " value=" + value
                            )
                            emitStatus(parts[0], parts[parts.size - 2], value)
                        }
                    } else if (topic.startsWith(root) && topic.endsWith("/event")) {
                        val parts = topic.removePrefix(root).trim('/').split("/")
                        if (parts.size >= 3) {
                            try {
                                val json = org.json.JSONObject(payload)
                                val widgetId = json.optString("id", parts[parts.size - 2])
                                val value = json.optString("val", payload)
                                emitLog("MQTT EVENT parsed: device=" + parts[0] + " widget=" + widgetId + " value=" + value)
                                emitStatus(parts[0], widgetId, value)
                            } catch (_: Exception) {
                                emitLog("MQTT event parse failed: " + topic)
                            }
                        }
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
                    emitLog(mqttExceptionText("MQTT connect failed", exception))
                    emitLog("MQTT credentials supplied: " + username.isNotBlank())
                    emitLog("MQTT TLS: " + tls)
                    emitLog("MQTT URL: " + normalizedUrl)
                    emitConnected(false)
                }
            })
        } catch (e: Exception) {
            emitLog(mqttExceptionText("MQTT error", e))
            emitConnected(false)
        }
    }

    private fun mqttExceptionText(prefixText: String, error: Throwable?): String {
        if (error == null) {
            return prefixText + ": unknown error"
        }

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
        return publish("/" + prefix.trim('/') + "/" + deviceId + "/" + widgetId + "/control", org.json.JSONObject().put("status", value).toString())
    }

    private fun publish(topic: String, payload: String): Boolean {
        val c = client

        if (c == null || !c.isConnected) {
            emitLog("MQTT publish skipped: not connected")
            return false
        }

        return try {
            val message = MqttMessage(
                payload.toByteArray(Charsets.UTF_8)
            ).apply {
                qos = 0
                isRetained = false
            }

            c.publish(topic, message)
            emitLog("MQTT TX topic=" + topic + " payload=" + payload)
            true
        } catch (e: Exception) {
            emitLog(mqttExceptionText("MQTT publish failed", e))
            false
        }
    }

    fun disconnect() {
        val c = client ?: return

        try {
            if (c.isConnected) {
                c.disconnect()
            }
        } catch (e: Exception) {
            emitLog(mqttExceptionText("MQTT disconnect error", e))
        } finally {
            client = null
            emitConnected(false)
        }
    }

    fun isConnected(): Boolean = client?.isConnected == true

    private fun emitLog(value: String) {
        main.post { onLog(value) }
    }

    private fun emitConnected(value: Boolean) {
        main.post { onConnected(value) }
    }

    private fun emitStatus(deviceId: String, widgetId: String, value: String) {
        main.post { onStatus(deviceId, widgetId, value) }
    }

    private fun emitConfig(deviceId: String, widgetId: String, label: String, widgetType: String, page: String, topic: String, order: Int, raw: String) {
        main.post { onConfig(deviceId, widgetId, label, widgetType, page, topic, order, raw) }
    }
}
