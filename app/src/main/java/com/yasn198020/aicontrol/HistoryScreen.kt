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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yasn198020.aicontrol.core.Device
import com.yasn198020.aicontrol.core.WidgetState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun HistoryScreen(modifier: Modifier, devices: List<Device>, store: HistoryStore) {
    var points by remember { mutableStateOf(store.load()) }; var selectedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { points=store.load(); kotlinx.coroutines.delay(5000) } }
    val numeric=devices.flatMap { d -> d.widgets.filter { it.type==WidgetState.Type.VALUE || it.type==WidgetState.Type.STATUS }.map { d to it } }
    val selected=selectedKey?.split("/", limit=2); val selectedPoints=if(selected!=null&&selected.size==2) points.filter { it.deviceId==selected[0]&&it.widgetId==selected[1] }.takeLast(120) else emptyList()
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) { Text("История",fontSize=24.sp,modifier=Modifier.weight(1f)); OutlinedButton(onClick={store.clear();points=emptyList()}){Text("Очистить")} }
        Text("Последние 7 дней, максимум 5000 измерений.",style=MaterialTheme.typography.bodySmall)
        if(numeric.isEmpty()){Text("Нет числовых виджетов. Подключитесь к MQTT и дождитесь CONFIG/STATE.")} else {
            LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.weight(1f)){ items(numeric,key={"${it.first.id}/${it.second.id}"}){(device,widget)->
                val key="${device.id}/${widget.id}"; val open=selectedKey==key
                OutlinedButton(onClick={selectedKey=if(open)null else key},modifier=Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.Start){Text(widget.title,fontSize=17.sp);Text("${device.name.ifBlank{device.id}} • ${widget.value}${widget.unit}",style=MaterialTheme.typography.bodySmall)}}
                if(open){HistoryChart(selectedPoints,widget.unit); Text(if(selectedPoints.isEmpty())"Пока нет сохранённых измерений." else "Измерений: ${selectedPoints.size}",style=MaterialTheme.typography.bodySmall)}
            }}
        }
    }
}
@Composable private fun HistoryChart(points: List<HistoryPoint>, unit:String){
    if(points.size<2){Text("Нужно минимум два измерения для графика.",modifier=Modifier.padding(8.dp));return}

    val min=points.minOf{it.value}
    val max=points.maxOf{it.value}
    val span=(max-min).takeIf{it>0.0}?:1.0
    val timeFormat=remember{SimpleDateFormat("HH:mm:ss",Locale.getDefault())}

    Column(Modifier.fillMaxWidth()){
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement=Arrangement.SpaceBetween
        ){
            Text(String.format(Locale.US,"%.2f %s",max,unit))
            Text(String.format(Locale.US,"%.2f %s",min,unit))
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(vertical=8.dp)
        ){
            val w=size.width
            val h=size.height
            val graphHeight=h-28.dp.toPx()

            points.forEachIndexed{index,p->
                val x=index.toFloat()/(points.size-1)*w
                val y=graphHeight-((p.value-min)/span*graphHeight).toFloat()

                if(index>0){
                    val prev=points[index-1]
                    val x1=(index-1).toFloat()/(points.size-1)*w
                    val y1=graphHeight-((prev.value-min)/span*graphHeight).toFloat()
                    drawLine(
                        color=androidx.compose.ui.graphics.Color.Gray,
                        start=Offset(x1,y1),
                        end=Offset(x,y),
                        strokeWidth=4f
                    )
                }

                drawCircle(
                    color=androidx.compose.ui.graphics.Color.Gray,
                    radius=4f,
                    center=Offset(x,y)
                )
            }

            // Показываем время изменения под графиком.
            // Чтобы подписи не накладывались друг на друга, выводим до 6 меток.
            val labelCount=minOf(6,points.size)
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{
                textSize=10.dp.toPx()
                color=android.graphics.Color.DKGRAY
                textAlign=android.graphics.Paint.Align.CENTER
            }

            if(labelCount>1){
                for(labelIndex in 0 until labelCount){
                    val pointIndex=labelIndex*(points.size-1)/(labelCount-1)
                    val x=pointIndex.toFloat()/(points.size-1)*w
                    val time=timeFormat.format(Date(points[pointIndex].timestamp))
                    drawContext.canvas.drawText(
                        time,
                        x,
                        h-2.dp.toPx(),
                        paint
                    )
                }
            }
        }
    }
}
