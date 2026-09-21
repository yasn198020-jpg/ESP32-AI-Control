package com.yasn198020.aicontrol

import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.*
import java.util.UUID

class MqttManager(
    private val onLog: (String) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val onStatus: (String, String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var client: MqttAsyncClient? = null
    private var prefix = ""
    private var deviceId = ""

    fun connect(url: String, mqttPrefix: String, device: String, username: String, password: String) {
        disconnect()
        val normalizedUrl = url.trim().let { value ->
            when {
                value.startsWith("tcp://") || value.startsWith("ssl://") -> value
                value.startsWith("mqtt://") -> "tcp://" + value.removePrefix("mqtt://")
                value.startsWith("mqtts://") -> "ssl://" + value.removePrefix("mqtts://")
                else -> "tcp://" + value
            }
        }
        prefix = mqttPrefix.trim().trim('/')
        deviceId = device.trim()
        if (prefix.isEmpty() || deviceId.isEmpty()) { emitLog("MQTT: prefix/device is empty"); return }
        try {
            val id = "ESP32AI-" + UUID.randomUUID().toString().replace("-", "").take(12)
            val c = MqttAsyncClient(normalizedUrl, id)
            client = c
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    emitLog("MQTT connected: " + serverURI)
                    emitConnected(true)
                    subscribeDevice()
                }
                override fun connectionLost(cause: Throwable?) {
                    emitLog("MQTT connection lost: " + (cause?.message ?: "unknown"))
                    emitConnected(false)
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == null || message == null) return
                    val payload = String(message.payload, Charsets.UTF_8)
                    emitLog("IN " + topic + " = " + payload)
                    val root = prefix + "/" + deviceId + "/"
                    if (topic.startsWith(root) && topic.endsWith("/status")) {
                        val id = topic.removePrefix(root).removeSuffix("/status").trim('/')
                        emitStatus(id, payload)
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                if (username.isNotBlank()) { userName = username; this.password = password.toCharArray() }
            }
            emitLog("MQTT connecting: " + normalizedUrl)
            c.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) { emitLog("MQTT connection accepted") }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    emitLog("MQTT connect failed: " + (exception?.message ?: "unknown"))
                    emitConnected(false)
                }
            })
        } catch (e: Exception) {
            emitLog("MQTT error: " + (e.message ?: e.javaClass.simpleName))
            emitConnected(false)
        }
    }

    private fun subscribeDevice() {
        val c = client ?: return
        val root = prefix + "/" + deviceId
        val topics = arrayOf(root + "/+/status", root + "/config", root + "/+/event")
        try {
            c.subscribe(topics, intArrayOf(0, 0, 0), null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) { emitLog("MQTT subscribed: " + root) }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    emitLog("MQTT subscribe failed: " + (exception?.message ?: "unknown"))
                }
            })
        } catch (e: Exception) { emitLog("MQTT subscribe error: " + (e.message ?: "unknown")) }
    }

    fun publishHello() { publish(prefix, "HELLO") }

    fun publishControl(widgetId: String, value: String): Boolean {
        if (prefix.isBlank() || deviceId.isBlank() || widgetId.isBlank()) return false
        return publish(prefix + "/" + deviceId + "/" + widgetId + "/control", value)
    }

    private fun publish(topic: String, payload: String): Boolean {
        val c = client
        if (c == null || !c.isConnected) { emitLog("MQTT publish skipped: not connected"); return false }
        return try {
            val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply { qos = 0; isRetained = false }
            c.publish(topic, message)
            emitLog("OUT " + topic + " = " + payload)
            true
        } catch (e: Exception) {
            emitLog("MQTT publish failed: " + (e.message ?: "unknown"))
            false
        }
    }

    fun disconnect() {
        val c = client ?: return
        try { if (c.isConnected) c.disconnect() }
        catch (e: Exception) { emitLog("MQTT disconnect error: " + (e.message ?: "unknown")) }
        finally { client = null; emitConnected(false) }
    }

    fun isConnected(): Boolean = client?.isConnected == true
    private fun emitLog(value: String) = main.post { onLog(value) }
    private fun emitConnected(value: Boolean) = main.post { onConnected(value) }
    private fun emitStatus(id: String, value: String) = main.post { onStatus(id, value) }
}