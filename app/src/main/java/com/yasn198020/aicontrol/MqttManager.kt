package com.yasn198020.aicontrol

import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

class MqttManager(
    private val onLog: (String) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val onStatus: (String, String) -> Unit,
    private val onConfig: (String, String, String, String) -> Unit
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
                    emitLog("IN " + topic + " = " + payload)

                    val root = prefix + "/"
                    if (topic.startsWith(root) && topic.endsWith("/config")) {
                        val parts = topic.removePrefix(root).trim('/').split("/")
                        if (parts.size == 2) {
                            try {
                                val json = org.json.JSONObject(payload)
                                val widgetId = parts[1]
                                val label = json.optString("label", json.optString("name", widgetId))
                                val widgetType = json.optString("widget", "status")
                                emitConfig(parts[0], widgetId, label, widgetType)
                            } catch (_: Exception) {
                                emitLog("MQTT config parse failed: " + topic)
                            }
                        }
                    } else if (topic.startsWith(root) && topic.endsWith("/status")) {
                        val parts = topic.removePrefix(root).trim('/').split("/")
                        if (parts.size >= 3) {
                            emitStatus(parts[parts.size - 2], payload)
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
        val root = prefix
        val topics = arrayOf(root + "/#")

        try {
            c.subscribe(
                topics,
                intArrayOf(0),
                null,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        emitLog("MQTT subscribed: " + root)
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
        publish(prefix + "/HELLO", "HELLO")
    }

    fun publishControl(deviceId: String, widgetId: String, value: String): Boolean {
        if (prefix.isBlank() || deviceId.isBlank() || widgetId.isBlank()) {
            return false
        }

        return publish(
            prefix + "/" + deviceId + "/" + widgetId + "/control",
            value
        )
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
            emitLog("OUT " + topic + " = " + payload)
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

    private fun emitStatus(id: String, value: String) {
        main.post { onStatus(id, value) }
    }

    private fun emitConfig(deviceId: String, widgetId: String, label: String, widgetType: String) {
        main.post { onConfig(deviceId, widgetId, label, widgetType) }
    }
}
