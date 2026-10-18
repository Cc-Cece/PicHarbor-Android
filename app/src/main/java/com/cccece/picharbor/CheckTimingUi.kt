package com.cccece.picharbor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

private fun checkDuration(ms:Long)=String.format(Locale.getDefault(),"%.2f 秒",ms/1000.0)

@Composable
fun CheckTimingDetails(progress:CleanupProgress) {
    val metrics=runCatching{JSONObject(progress.metrics)}.getOrDefault(JSONObject())
    Text(if(progress.mode=="quick")"快速检查备份状态" else "释放空间核验",style=MaterialTheme.typography.titleMedium)
    Text("已检查 ${progress.checked} / ${progress.total} 项")
    Text("实际执行时间：${checkDuration(progress.elapsedMs)}")
    Helper("不包含排队及应用停止期间的时间。运行中在每项或阶段结束后更新；恢复后累计保留。")
    val stages=listOf("cloud_sync" to "云端完整同步","sync_requests" to "其中：云端分页请求","read_wait" to "等待读取文件",
        "file_read" to "文件读取与哈希","content_query" to "云端内容查询","original_url" to "获取原件地址",
        "original_head" to "原件 HEAD 检查","original_range" to "原件少量读取检查","retry_wait" to "失败后等待重试","persist" to "保存结果")
    stages.forEach{(key,label)->
        val stage=metrics.optJSONObject(key)
        val count=stage?.optLong("count")?:0
        Row(Modifier.fillMaxWidth().padding(vertical=7.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            Text(if(count==0L)"未执行" else "${checkDuration(stage!!.optLong("ms"))} · $count 次",style=MaterialTheme.typography.bodySmall)
        }
    }
    Text("累计读取：${formatSize(metrics.optJSONObject("read_bytes")?.optLong("count")?:0)}",modifier=Modifier.padding(vertical=8.dp))
    Helper("各阶段为所有文件的累计耗时，包含重试。并行任务时间会重叠，不能相加当作总等待时间；分页请求已包含在云端同步中。快速检查不执行完整同步和原件检查。")
}
