@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.cccece.picharbor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun DiagnosticExportDialog(onDismiss:()->Unit,onExport:(LogRange)->Unit) {
    val zone=remember{ZoneId.systemDefault()}
    var custom by remember{mutableStateOf(false)}
    var calendar by remember{mutableStateOf(false)}
    var range by remember{mutableStateOf<LogRange?>(null)}
    var label by remember{mutableStateOf("选择起止日期")}
    val dates=rememberDateRangePickerState()
    if(calendar) {
        DatePickerDialog(onDismissRequest={calendar=false},confirmButton={TextButton(
            enabled=dates.selectedStartDateMillis!=null&&dates.selectedEndDateMillis!=null,
            onClick={
                val start=Instant.ofEpochMilli(dates.selectedStartDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                val end=Instant.ofEpochMilli(dates.selectedEndDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                if(!end.isBefore(start)){range=LogRange.dates(start,end,zone);label="$start 至 $end";calendar=false}
            }){Text("确定")}},dismissButton={TextButton(onClick={calendar=false}){Text("取消")}}) {
            DateRangePicker(dates,modifier=Modifier.heightIn(max=480.dp))
        }
    } else AlertDialog(onDismissRequest=onDismiss,title={Text("导出日志")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Choice("全部",!custom,"当前仍保留的全部日志"){custom=false}
            Choice("自定义日期",custom,"包含起止日期当天"){custom=true}
            if(custom)TextButton(onClick={calendar=true}){Text(label)}
            Text("日期按 ${zone.id} 计算。日志最多保留 14 天或 64 MiB，较早记录可能已自动覆盖。",style=MaterialTheme.typography.bodySmall)
            Text("已脱敏；包含运行信息和文件名，不包含照片或视频本体、账号、密码及登录凭据。",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(enabled=!custom||range!=null,onClick={onExport(if(custom)range!! else LogRange(zone=zone.id))}){Text("导出")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
