package com.yasn198020.aicontrol.mqtt

import android.content.SharedPreferences

/** Persistent MQTT connection settings. Keeps storage details out of the Compose screen. */
class MqttSettingsStore(private val prefs: SharedPreferences) {
    val host: String get() = prefs.getString("mqtt_host", "m4.wqtt.ru") ?: "m4.wqtt.ru"
    val port: String get() = prefs.getString("mqtt_port", "1883") ?: "1883"
    val tls: Boolean get() = prefs.getBoolean("mqtt_tls", false)
    val prefix: String get() = prefs.getString("mqtt_prefix", "IoTManager") ?: "IoTManager"
    val username: String get() = prefs.getString("mqtt_user", "") ?: ""
    val password: String get() = prefs.getString("mqtt_pass", "") ?: ""

    fun save(host: String, port: String, tls: Boolean, prefix: String, username: String, password: String) {
        prefs.edit()
            .putString("mqtt_host", host)
            .putString("mqtt_port", port)
            .putBoolean("mqtt_tls", tls)
            .putString("mqtt_prefix", prefix)
            .putString("mqtt_user", username)
            .putString("mqtt_pass", password)
            .apply()
    }
}

// Keep this store intentionally small: MQTT protocol behavior remains in MqttManager.
