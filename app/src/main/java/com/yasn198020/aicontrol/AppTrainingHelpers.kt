package com.yasn198020.aicontrol

import com.yasn198020.aicontrol.core.WidgetState

import com.yasn198020.aicontrol.voice.TRAINED_READ_VALUE

/** Returns whether a widget can be attached to a trained voice command. */
fun isTrainableWidget(widget: WidgetState): Boolean =
    widget.type == WidgetState.Type.TOGGLE ||
        widget.type == WidgetState.Type.BUTTON ||
        widget.type == WidgetState.Type.VALUE ||
        widget.type == WidgetState.Type.STATUS

/** Returns the value used when training a widget action or read command. */
fun trainingValueFor(widget: WidgetState): String =
    if (widget.type == WidgetState.Type.VALUE || widget.type == WidgetState.Type.STATUS) {
        TRAINED_READ_VALUE
    } else {
        "1"
    }
