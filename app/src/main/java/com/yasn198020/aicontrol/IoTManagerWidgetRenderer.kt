package com.yasn198020.aicontrol

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yasn198020.aicontrol.core.WidgetState
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native Android renderer for all widget definitions from IoTManager data_svelte/widgets.json.
 *
 * Supported definitions:
 * anydata*, button, toggle, chart1..chart6, fillgauge,
 * inputDate/inputDgt/inputTxt/inputTm, progressLine, progressRound,
 * range/rangeServo, select and nil.
 *
 * The original CONFIG JSON is kept in WidgetState so widget-specific settings
 * such as min/max/k/options/series/dateFormat/colors survive MQTT parsing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IoTManagerWidgetRow(
    deviceId: String,
    widget: WidgetState,
    onSend: (String) -> Unit,
    onTrain: () -> Unit
) {
    val definition = widget.definitionName.ifBlank {
        when (widget.type) {
            WidgetState.Type.INPUT -> "input"
            WidgetState.Type.BUTTON -> "button"
            WidgetState.Type.TOGGLE -> "toggle"
            WidgetState.Type.VALUE -> "anydata"
            WidgetState.Type.STATUS -> "status"
        }
    }
    val def = definition.lowercase(Locale.ROOT)
    if (def == "nil") return

    val modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(onClick = {}, onLongClick = onTrain)

    when {
        def == "toggle" ->
            ToggleWidgetRow(widget, modifier, onSend)

        def == "button" || def == "vbtn" || def == "btn" ->
            ButtonWidgetRow(widget, modifier, onSend)

        def == "inputdate" ->
            DateWidgetRow(widget, modifier, onSend)

        def == "inputtm" ->
            TimeWidgetRow(widget, modifier, onSend)

        def == "inputdgt" ->
            TextInputWidgetRow(widget, modifier, onSend, true)

        def == "inputtxt" || def == "input" ->
            TextInputWidgetRow(widget, modifier, onSend, false)

        def == "range" ->
            RangeWidgetRow(widget, modifier, onSend, false)

        def == "rangeservo" ->
            RangeWidgetRow(widget, modifier, onSend, true)

        def == "select" ->
            SelectWidgetRow(widget, modifier, onSend)

        def == "progressline" ->
            ProgressLineWidgetRow(widget, modifier)

        def == "progressround" ->
            ProgressRoundWidgetRow(widget, modifier)

        def == "fillgauge" ->
            FillGaugeWidgetRow(widget, modifier)

        def.startsWith("chart") || widget.configObject().optString("widget").equals("chart", true) ->
            ChartWidgetRow(deviceId, widget, def, modifier)

        def.startsWith("anydata") ||
            widget.type == WidgetState.Type.VALUE ||
            widget.type == WidgetState.Type.STATUS ->
            AnyDataWidgetRow(widget, modifier)

        else ->
            FallbackWidgetRow(widget, modifier)
    }
}

private fun WidgetState.configObject(): JSONObject =
    runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())

private fun formatNumber(value: Double): String {
    return if (abs(value - value.roundToInt()) < 0.0005) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", value)
            .trimEnd('0')
            .trimEnd('.')
    }
}

/* ---------------- basic controls ---------------- */

@Composable
private fun ToggleWidgetRow(
    widget: WidgetState,
    modifier: Modifier,
    onSend: (String) -> Unit
) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("◉", color = Color(0xFF8065E8), fontSize = 22.sp, modifier = Modifier.width(34.dp))
        Text(widget.title, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = widget.value == "1" || widget.value.equals("true", true),
            onCheckedChange = { onSend(if (it) "1" else "0") }
        )
    }
}

@Composable
private fun ButtonWidgetRow(
    widget: WidgetState,
    modifier: Modifier,
    onSend: (String) -> Unit
) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("◉", color = Color(0xFF8065E8), fontSize = 22.sp, modifier = Modifier.width(34.dp))
        Text(widget.title, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Button(onClick = { onSend("1") }) {
            Text("Нажать")
        }
    }
}

@Composable
private fun TextInputWidgetRow(
    widget: WidgetState,
    modifier: Modifier,
    onSend: (String) -> Unit,
    numeric: Boolean
) {
    var value by remember(widget.id, widget.value) { mutableStateOf(widget.value) }

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(widget.title) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text
            ),
            modifier = Modifier.weight(1f)
        )
        Button(onClick = { onSend(value) }) {
            Text("Отправить")
        }
    }
}

@Composable
private fun DateWidgetRow(
    widget: WidgetState,
    modifier: Modifier,
    onSend: (String) -> Unit
) {
    val context = LocalContext.current
    var value by remember(widget.id, widget.value) { mutableStateOf(widget.value) }

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(widget.title) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = {
            val calendar = Calendar.getInstance()
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    val selected = Calendar.getInstance().apply {
                        set(year, month, day)
                    }
                    val formatted = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(selected.time)
                    value = formatted
                    onSend(formatted)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }) {
            Text("📅")
        }
        Button(onClick = { onSend(value) }) {
            Text("Отправить")
        }
    }
}

@Composable
private fun TimeWidgetRow(
    widget: WidgetState,
    modifier: Modifier,
    onSend: (String) -> Unit
) {
    val context = LocalContext.current
    var value by remember(widget.id, widget.value) { mutableStateOf(widget.value) }

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(widget.title) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = {
            val calendar = Calendar.getInstance()
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val formatted = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                    value = formatted
                    onSend(formatted)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        }) {
            Text("🕒")
        }
        Button(onClick = { onSend(value) }) {
            Text("Отправить")
        }
    }
}

@Composable
private fun RangeWidgetRow(
    widget: WidgetState,
    modifier: Modifier,
    onSend: (String) -> Unit,
    servo: Boolean
) {
    val json = remember(widget.configJson) { widget.configObject() }
    val minValue = json.optDouble("min", if (servo) 0.0 else 0.0).toFloat()
    val maxValue = json.optDouble("max", if (servo) 180.0 else 100.0).toFloat()
    val k = json.optDouble("k", 1.0).toFloat().takeIf { it != 0f } ?: 1f
    val rawValue = widget.value.replace(',', '.').toFloatOrNull()
    val initialDisplay = ((rawValue ?: minValue) * k).coerceIn(minValue, maxValue)

    var value by remember(widget.id, widget.value, minValue, maxValue, k) {
        mutableFloatStateOf(initialDisplay)
    }

    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(widget.title, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(formatNumber(value.toDouble()) + widget.unit, fontWeight = FontWeight.SemiBold)
        }

        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = minValue..maxValue,
            onValueChangeFinished = {
                val rawOut = value / k
                val output = if (abs(rawOut - rawOut.roundToInt()) < 0.0005f) {
                    rawOut.roundToInt().toString()
                } else {
                    formatNumber(rawOut.toDouble())
                }
                onSend(output)
            }
        )

        Text(
            "Диапазон " + formatNumber(minValue.toDouble()) +
                " … " + formatNumber(maxValue.toDouble()) +
                if (k != 1f) "  •  коэффициент " + k else "",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SelectWidgetRow(
    widget: WidgetState,
    modifier: Modifier,
    onSend: (String) -> Unit
) {
    val json = remember(widget.configJson) { widget.configObject() }
    val options = remember(widget.configJson) {
        val array = json.optJSONArray("options") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                add(array.optString(i))
            }
        }.ifEmpty {
            listOf("Выключен", "Включен")
        }
    }

    var expanded by remember(widget.id) { mutableStateOf(false) }
    val initial = (widget.value.toIntOrNull() ?: json.optInt("status", 0))
        .coerceIn(0, max(0, options.lastIndex))
    var selectedIndex by remember(widget.id, widget.value, initial) {
        mutableIntStateOf(initial)
    }

    Column(modifier.padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(widget.title, fontSize = 17.sp)
        Spacer(Modifier.height(4.dp))

        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(options.getOrElse(selectedIndex) { "Выбрать" })
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            selectedIndex = index
                            expanded = false
                            onSend(index.toString())
                        }
                    )
                }
            }
        }
    }
}

/* ---------------- telemetry widgets ---------------- */

@Composable
private fun AnyDataWidgetRow(
    widget: WidgetState,
    modifier: Modifier
) {
    val json = remember(widget.configJson) { widget.configObject() }
    val numeric = widget.value.replace(',', '.').trim().toDoubleOrNull()
    val suffix = widget.unit.ifBlank { json.optString("after").trim() }
    val iconName = json.optString("icon").trim().lowercase(Locale.ROOT)

    val icon = when (iconName) {
        "thermometer" -> "🌡"
        "water" -> "💧"
        "walk" -> "🚶"
        "body" -> "◉"
        "speedometer" -> "◌"
        "sunny" -> "☀"
        else -> "•"
    }

    val color = resolveThresholdColor(json.opt("color"), numeric)

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 21.sp, modifier = Modifier.width(34.dp))
        Text(widget.title, fontSize = 17.sp, modifier = Modifier.weight(1f))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = color.copy(alpha = 0.18f)
        ) {
            Text(
                if (suffix.isNotBlank()) widget.value.ifBlank { "—" } + " " + suffix
                else widget.value.ifBlank { "—" },
                color = color,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ProgressLineWidgetRow(
    widget: WidgetState,
    modifier: Modifier
) {
    val json = remember(widget.configJson) { widget.configObject() }
    val maxValue = json.optDouble("max", 100.0).coerceAtLeast(0.000001)
    val value = widget.value.replace(',', '.').toDoubleOrNull() ?: 0.0
    val fraction = (value / maxValue).coerceIn(0.0, 1.0).toFloat()
    val stroke = json.optDouble("stroke", 10.0).toFloat().coerceAtLeast(2f)
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(widget.title, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(formatNumber(value) + " / " + formatNumber(maxValue))
        }

        Spacer(Modifier.height(5.dp))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height((stroke + 8f).dp)
        ) {
            val y = size.height / 2f
            val half = stroke / 2f
            drawLine(
                color = Color(0xFF555555),
                start = androidx.compose.ui.geometry.Offset(half, y),
                end = androidx.compose.ui.geometry.Offset(size.width - half, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = primaryColor,
                start = androidx.compose.ui.geometry.Offset(half, y),
                end = androidx.compose.ui.geometry.Offset(
                    half + (size.width - stroke) * fraction,
                    y
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun ProgressRoundWidgetRow(
    widget: WidgetState,
    modifier: Modifier
) {
    val json = remember(widget.configJson) { widget.configObject() }
    val maxValue = json.optDouble("max", 100.0).coerceAtLeast(0.000001)
    val value = widget.value.replace(',', '.').toDoubleOrNull() ?: 0.0
    val fraction = (value / maxValue).coerceIn(0.0, 1.0).toFloat()
    val stroke = json.optDouble("stroke", 20.0).toFloat().coerceAtLeast(4f)
    val semicircle = json.optString("semicircle") == "1"
    val primaryColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(82.dp)) {
            val inset = stroke / 2f
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            val drawSize = androidx.compose.ui.geometry.Size(
                size.width - stroke,
                size.height - stroke
            )

            if (semicircle) {
                drawArc(
                    color = Color(0xFF555555),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = drawSize,
                    style = Stroke(stroke)
                )
                drawArc(
                    color = parseColor(json.optString("color"), primaryColor),
                    startAngle = 180f,
                    sweepAngle = 180f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = drawSize,
                    style = Stroke(stroke)
                )
            } else {
                drawArc(
                    color = Color(0xFF555555),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = drawSize,
                    style = Stroke(stroke)
                )
                drawArc(
                    color = parseColor(json.optString("color"), primaryColor),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = drawSize,
                    style = Stroke(stroke)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(widget.title, fontSize = 17.sp)
            Text(formatNumber(value) + " / " + formatNumber(maxValue))
        }
    }
}

@Composable
private fun FillGaugeWidgetRow(
    widget: WidgetState,
    modifier: Modifier
) {
    val json = remember(widget.configJson) { widget.configObject() }
    val value = widget.value.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0
    val fraction = (value / 100.0).toFloat()
    val circleColor = parseColor(json.optString("circleColor"), Color.Cyan)
    val waveColor = parseColor(json.optString("waveColor"), Color(0xFF00BCD4))

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(86.dp)) {
                drawCircle(color = Color(0xFF252525), style = Fill)
                drawArc(
                    color = circleColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(7f)
                )
                val waterTop = size.height * (1f - fraction)
                val waterHeight = (size.height - waterTop - 7f).coerceAtLeast(0f)
                if (waterHeight > 0f) {
                    drawRect(
                        color = waveColor.copy(alpha = 0.60f),
                        topLeft = androidx.compose.ui.geometry.Offset(7f, waterTop),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - 14f,
                            waterHeight
                        ),
                        style = Fill
                    )
                }
            }
            Text(formatNumber(value) + "%", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(widget.title, fontSize = 17.sp)
            Text("0 … 100 %", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/* ---------------- charts ---------------- */

private data class ChartSeriesPoint(
    val timestamp: Long,
    val values: List<Double>
)

private fun parseChartPayload(value: String): List<ChartSeriesPoint> {
    if (!value.trimStart().startsWith("{")) return emptyList()
    val root = runCatching { JSONObject(value) }.getOrNull() ?: return emptyList()
    val status = root.optJSONArray("status") ?: return emptyList()

    return buildList {
        for (i in 0 until status.length()) {
            val point = status.optJSONObject(i) ?: continue
            val timestamp = point.optLong("x", 0L)
            val values = buildList {
                if (point.has("y1")) add(point.optDouble("y1", Double.NaN))
                if (point.has("y2")) add(point.optDouble("y2", Double.NaN))
                if (point.has("y3")) add(point.optDouble("y3", Double.NaN))
            }.filter { it.isFinite() }

            if (values.isNotEmpty()) {
                add(ChartSeriesPoint(timestamp, values))
            }
        }
    }
}

@Composable
private fun ChartWidgetRow(
    deviceId: String,
    widget: WidgetState,
    definition: String,
    modifier: Modifier
) {
    val context = LocalContext.current
    val json = remember(widget.configJson) { widget.configObject() }
    var points by remember(widget.id) { mutableStateOf(emptyList<ChartSeriesPoint>()) }

    LaunchedEffect(widget.id, widget.value, widget.configJson) {
        val mqttPoints = parseChartPayload(widget.value)
        if (mqttPoints.isNotEmpty()) {
            points = mqttPoints.takeLast(json.optInt("maxCount", 240).coerceIn(20, 600))
        } else {
            val maxCount = json.optInt("maxCount", 240).coerceIn(20, 600)
            points = AppRuntime.get(context)
                .historyStore
                .load()
                .asSequence()
                .filter { it.deviceId == deviceId && it.widgetId == widget.id }
                .takeLast(maxCount)
                .map { ChartSeriesPoint(it.timestamp, listOf(it.value)) }
                .toList()
        }
    }

    val isBar = json.optString("type").equals("bar", true) ||
        definition == "chart3" ||
        definition == "chart4"

    val showPoints = json.optInt("pointRadius", 2) > 0 && !isBar

    val seriesNames = buildList {
        val series = json.optJSONArray("series")
        if (series != null) {
            for (i in 0 until series.length()) {
                add(series.optString(i))
            }
        }
    }

    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(widget.title, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text("Точек: " + points.size, style = MaterialTheme.typography.bodySmall)
        }

        if (seriesNames.isNotEmpty()) {
            Text(seriesNames.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(6.dp))

        MiniChart(points, isBar, showPoints)

        Text(
            when (definition) {
                "chart1" -> "График без точек"
                "chart2" -> "График с точками"
                "chart3" -> "Дневной"
                "chart4" -> "Часовой"
                "chart5" -> "Двойной"
                "chart6" -> "Тройной"
                else -> "График"
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MiniChart(
    points: List<ChartSeriesPoint>,
    isBar: Boolean,
    showPoints: Boolean
) {
    if (points.isEmpty()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF252525)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("Нет данных графика", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    val seriesCount = points.maxOf { it.values.size }.coerceAtMost(3)
    val series = (0 until seriesCount).map { index ->
        points.mapNotNull { it.values.getOrNull(index) }
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
    ) {
        val left = 10f
        val right = size.width - 10f
        val top = 10f
        val bottom = size.height - 12f

        drawRoundRect(
            color = Color(0xFF202020),
            style = Fill,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f)
        )

        series.forEachIndexed { index, values ->
            if (values.isEmpty()) return@forEachIndexed

            val color = when (index) {
                0 -> Color(0xFF42A5F5)
                1 -> Color(0xFF66BB6A)
                else -> Color(0xFFFFB300)
            }

            if (isBar) {
                val visible = values.takeLast(48)
                val minValue = visible.minOrNull() ?: 0.0
                val maxValue = visible.maxOrNull() ?: 1.0
                val range = (maxValue - minValue).takeIf { it > 0.000001 } ?: 1.0
                val barWidth = (right - left) / visible.size.coerceAtLeast(1).toFloat()

                visible.forEachIndexed { i, v ->
                    val normalized = ((v - minValue) / range).toFloat()
                    val h = (bottom - top) * normalized
                    drawRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            left + i * barWidth + 1f,
                            bottom - h
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            max(1f, barWidth - 2f),
                            max(1f, h)
                        ),
                        style = Fill
                    )
                }
            } else {
                val minValue = values.minOrNull() ?: 0.0
                val maxValue = values.maxOrNull() ?: 1.0
                val range = (maxValue - minValue).takeIf { it > 0.000001 } ?: 1.0
                val path = Path()

                values.forEachIndexed { i, v ->
                    val x = if (values.size == 1) {
                        (left + right) / 2f
                    } else {
                        left + (right - left) * i / (values.size - 1).toFloat()
                    }
                    val normalized = ((v - minValue) / range).toFloat()
                    val y = bottom - (bottom - top) * normalized

                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)

                    if (showPoints) {
                        drawCircle(color = color, radius = 3f, center = androidx.compose.ui.geometry.Offset(x, y))
                    }
                }

                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = 4f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

/* ---------------- fallback ---------------- */

@Composable
private fun FallbackWidgetRow(
    widget: WidgetState,
    modifier: Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("•", fontSize = 21.sp, modifier = Modifier.width(34.dp))
        Text(widget.title, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(
            widget.value.ifBlank { "—" } +
                if (widget.unit.isNotBlank()) " " + widget.unit else "",
            fontSize = 18.sp
        )
    }
}

/* ---------------- colors ---------------- */

private fun resolveThresholdColor(raw: Any?, value: Double?): Color {
    if (value == null) return Color(0xFF90CAF9)

    if (raw is JSONArray) {
        var selected: String? = null
        var selectedLevel = Double.NEGATIVE_INFINITY

        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val level = item.optDouble("level", Double.NaN)
            val colorName = item.optString("value").trim()

            if (level.isFinite() &&
                colorName.isNotBlank() &&
                level <= value &&
                level >= selectedLevel
            ) {
                selectedLevel = level
                selected = colorName
            }
        }

        if (!selected.isNullOrBlank()) {
            return parseColor(selected, Color(0xFF90CAF9))
        }
    }

    if (raw is String && raw.isNotBlank()) {
        return parseColor(raw, Color(0xFF90CAF9))
    }

    return Color(0xFF90CAF9)
}

private fun parseColor(value: String, fallback: Color): Color {
    if (value.isBlank()) return fallback
    return runCatching {
        Color(AndroidColor.parseColor(value))
    }.getOrElse { fallback }
}
