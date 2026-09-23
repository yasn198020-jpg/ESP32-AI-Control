package com.yasn198020.aicontrol.core

/** Runtime representation of an MQTT device and its widgets. */
data class Device(
    val id: String,
    val name: String,
    val online: Boolean,
    val widgets: List<WidgetState>
)

data class WidgetState(
    val id: String,
    val title: String,
    val type: Type,
    val value: String,
    val page: String = "Основная",
    val topic: String = "",
    val order: Int = 0,
    val unit: String = ""
) {
    enum class Type { TOGGLE, BUTTON, INPUT, VALUE, STATUS }
}
