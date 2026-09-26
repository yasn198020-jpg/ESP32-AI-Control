package com.yasn198020.aicontrol

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

@Composable
fun HistoryScreen(
    modifier: Modifier,
    devices: List<Device>,
    store: HistoryStore
) {
    var points by remember { mutableStateOf(store.load()) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var periodMenuOpen by remember { mutableStateOf(false) }
    var samplePeriod by remember { mutableLongStateOf(store.samplePeriodMs()) }

    LaunchedEffect(Unit) {
        while (true) {
            points = store.load()
            kotlinx.coroutines.delay(5000)
        }
    }

    val numeric = devices.flatMap { d ->
        d.widgets
            .filter {
                it.type == WidgetState.Type.VALUE ||
                    it.type == WidgetState.Type.STATUS
            }
            .map { d to it }
    }

    val selected = selectedKey?.split("/", limit = 2)
    val selectedPoints =
        if (selected != null && selected.size == 2) {
            points.filter {
                it.deviceId == selected[0] && it.widgetId == selected[1]
            }
        } else {
            emptyList()
        }

    val samplePeriods = listOf(
        1_000L to "1 сек",
        5_000L to "5 сек",
        10_000L to "10 сек",
        30_000L to "30 сек",
        60_000L to "1 мин",
        300_000L to "5 мин",
        600_000L to "10 мин",
        1_800_000L to "30 мин",
        3_600_000L to "1 час"
    )

    val samplePeriodLabel = samplePeriods.firstOrNull { it.first == samplePeriod }?.second
        ?: (samplePeriod / 1000).toString() + " сек"

    Column(
        modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "История",
                fontSize = 24.sp,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    store.clear()
                    points = emptyList()
                }
            ) {
                Text("Очистить")
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Период измерения:",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )

            Box {
                OutlinedButton(onClick = { periodMenuOpen = true }) {
                    Text(samplePeriodLabel)
                }

                DropdownMenu(
                    expanded = periodMenuOpen,
                    onDismissRequest = { periodMenuOpen = false }
                ) {
                    samplePeriods.forEach { (periodMs, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                store.setSamplePeriodMs(periodMs)
                                samplePeriod = periodMs
                                periodMenuOpen = false
                            }
                        )
                    }
                }
            }
        }

        Text(
            "Точка графика записывается таймером, независимо от частоты MQTT. " +
                "По умолчанию — каждые 10 секунд.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Последние 7 дней, максимум 5000 измерений.",
            style = MaterialTheme.typography.bodySmall
        )

        if (numeric.isEmpty()) {
            Text("Нет числовых виджетов. Подключитесь к MQTT и дождитесь CONFIG/STATE.")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(
                    numeric,
                    key = { it.first.id + "/" + it.second.id }
                ) { (device, widget) ->
                    val key = device.id + "/" + widget.id
                    val open = selectedKey == key

                    OutlinedButton(
                        onClick = {
                            selectedKey = if (open) null else key
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(widget.title, fontSize = 17.sp)
                            Text(
                                device.name.ifBlank { device.id } + " • " + widget.value + widget.unit,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (open) {
                        HistoryChart(selectedPoints, widget.unit)
                        Text(
                            if (selectedPoints.isEmpty()) {
                                "Пока нет сохранённых измерений."
                            } else {
                                "Измерений: " + selectedPoints.size
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
@Composable private fun HistoryChart(points: List<HistoryPoint>, unit:String){
    if(points.size<2){
        Text("Нужно минимум два измерения для графика.",modifier=Modifier.padding(8.dp))
        return
    }

    val minValue=points.minOf{it.value}
    val maxValue=points.maxOf{it.value}
    val span=(maxValue-minValue).takeIf{it>0.0}?:1.0
    val primaryColor=MaterialTheme.colorScheme.primary
    val timeFormat=remember{SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.getDefault())}

    var zoom by remember(points.firstOrNull()?.timestamp,points.size){mutableFloatStateOf(1f)}
    var offsetX by remember(points.firstOrNull()?.timestamp,points.size){mutableFloatStateOf(0f)}
    var selectedIndex by remember(points.firstOrNull()?.timestamp,points.size){mutableIntStateOf(points.lastIndex)}

    Column(Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            Column{
                Text(String.format(Locale.US,"%.2f %s",maxValue,unit),style=MaterialTheme.typography.bodySmall)
                Text(String.format(Locale.US,"%.2f %s",minValue,unit),style=MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={
                    zoom=(zoom/1.5f).coerceAtLeast(1f)
                    if(zoom==1f) offsetX=0f
                }){Text("−")}
                Text("×${String.format(Locale.US,"%.1f",zoom)}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={zoom=(zoom*1.5f).coerceAtMost(20f)}){Text("+")}
                TextButton(onClick={zoom=1f;offsetX=0f}){Text("Сброс")}
            }
        }

        Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,tonalElevation=2.dp){
            Column(Modifier.fillMaxWidth().padding(8.dp)){
                val selected=points[selectedIndex.coerceIn(0,points.lastIndex)]
                Text(
                    "Выбрано: ${timeFormat.format(Date(selected.timestamp))}  •  "+
                        String.format(Locale.US,"%.3f %s",selected.value,unit),
                    style=MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                ZoomableHistoryCanvas(
                    points=points,
                    minValue=minValue,
                    span=span,
                    zoom=zoom,
                    offsetX=offsetX,
                    selectedIndex=selectedIndex,
                    primaryColor=primaryColor,
                    onZoomOffsetChanged={newZoom,newOffset->{zoom=newZoom;offsetX=newOffset}},
                    onSelectIndex={selectedIndex=it.coerceIn(0,points.lastIndex)}
                )
                Text(
                    "Щипок — увеличить/уменьшить • перетаскивание — прокрутка • нажмите на график — выбрать момент",
                    style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.padding(top=4.dp)
                )
            }
        }
    }
}

@Composable private fun ZoomableHistoryCanvas(
    points:List<HistoryPoint>,
    minValue:Double,
    span:Double,
    zoom:Float,
    offsetX:Float,
    selectedIndex:Int,
    primaryColor:androidx.compose.ui.graphics.Color,
    onZoomOffsetChanged:(Float,Float)->Unit,
    onSelectIndex:(Int)->Unit
){
    var canvasWidth by remember{mutableFloatStateOf(0f)}

    val latestPoints by rememberUpdatedState(points)
    val latestZoom by rememberUpdatedState(zoom)
    val latestOffsetX by rememberUpdatedState(offsetX)
    val latestCanvasWidth by rememberUpdatedState(canvasWidth)
    val latestOnZoomOffsetChanged by rememberUpdatedState(onZoomOffsetChanged)
    val latestOnSelectIndex by rememberUpdatedState(onSelectIndex)

    fun clampOffset(scale:Float,rawOffset:Float,width:Float):Float{
        val contentWidth=width*scale
        return if(contentWidth<=width||width<=0f) 0f else rawOffset.coerceIn(0f,contentWidth-width)
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .onSizeChanged{canvasWidth=it.width.toFloat()}
            .pointerInput(Unit){
                awaitEachGesture{
                    val down=awaitFirstDown(requireUnconsumed=false)
                    var workingOffset=latestOffsetX
                    var moved=false
                    var horizontalDrag=false

                    val slopChange=awaitTouchSlopOrCancellation(down.id){change,overSlop->
                        moved=true

                        if(
                            latestZoom>1f &&
                            latestCanvasWidth>0f &&
                            abs(overSlop.x)>=abs(overSlop.y)
                        ){
                            change.consume()
                            horizontalDrag=true
                            workingOffset=clampOffset(
                                latestZoom,
                                workingOffset-overSlop.x,
                                latestCanvasWidth
                            )
                            latestOnZoomOffsetChanged(latestZoom,workingOffset)
                        }
                    }

                    if(slopChange!=null&&horizontalDrag&&latestZoom>1f&&latestCanvasWidth>0f){
                        drag(down.id){change->
                            val dx=change.positionChange().x
                            if(dx!=0f){
                                change.consume()
                                workingOffset=clampOffset(
                                    latestZoom,
                                    workingOffset-dx,
                                    latestCanvasWidth
                                )
                                latestOnZoomOffsetChanged(latestZoom,workingOffset)
                            }
                        }
                    }

                    if(
                        !moved &&
                        latestCanvasWidth>0f
                    ){
                        latestOnSelectIndex(
                            nearestPointIndex(
                                latestPoints,
                                down.position.x,
                                latestCanvasWidth,
                                latestZoom,
                                workingOffset
                            )
                        )
                    }
                }
            }
    ){
        val contentWidth=size.width*zoom
        val h=size.height

        points.forEachIndexed{index,point->
            val normalizedX=index.toFloat()/(points.size-1).toFloat()
            val x=normalizedX*contentWidth-offsetX
            val y=h-((point.value-minValue)/span*h).toFloat()

            if(index>0){
                val previous=points[index-1]
                val previousNormalizedX=(index-1).toFloat()/(points.size-1).toFloat()
                val x1=previousNormalizedX*contentWidth-offsetX
                val y1=h-((previous.value-minValue)/span*h).toFloat()
                if((x1>=-8f&&x1<=size.width+8f)||(x>=-8f&&x<=size.width+8f)){
                    drawLine(color=androidx.compose.ui.graphics.Color.Gray,start=Offset(x1,y1),end=Offset(x,y),strokeWidth=4f)
                }
            }

            if(x in -8f..size.width+8f){
                drawCircle(
                    color=if(index==selectedIndex)primaryColor else androidx.compose.ui.graphics.Color.Gray,
                    radius=if(index==selectedIndex)7f else 3f,
                    center=Offset(x,y)
                )
            }
        }

        val selected=points.getOrNull(selectedIndex)
        if(selected!=null){
            val normalizedX=selectedIndex.toFloat()/(points.size-1).toFloat()
            val selectedX=normalizedX*contentWidth-offsetX
            if(selectedX in 0f..size.width){
                drawLine(
                    color=primaryColor.copy(alpha=.65f),
                    start=Offset(selectedX,0f),
                    end=Offset(selectedX,h),
                    strokeWidth=2f
                )
            }
        }
    }
}
private fun nearestPointIndex(
    points:List<HistoryPoint>,
    tapX:Float,
    width:Float,
    zoom:Float,
    offsetX:Float
):Int{
    if(points.isEmpty()||width<=0f)return 0
    val contentWidth=width*zoom
    val ratio=((tapX+offsetX)/contentWidth).coerceIn(0f,1f)
    val scaledPosition=ratio*(points.size-1)
    val lower=scaledPosition.toInt().coerceIn(0,points.lastIndex)
    val upper=min(lower+1,points.lastIndex)
    val lowerX=lower.toFloat()/(points.size-1).toFloat()*contentWidth-offsetX
    val upperX=upper.toFloat()/(points.size-1).toFloat()*contentWidth-offsetX
    return if(abs(lowerX-tapX)<=abs(upperX-tapX))lower else upper
}

