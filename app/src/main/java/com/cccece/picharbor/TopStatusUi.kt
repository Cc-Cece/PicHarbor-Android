package com.cccece.picharbor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

object BackupProgressPolicy {
    fun fraction(finished:Int,total:Int,transfers:Collection<TransferProgress>):Float {
        if(total<=0)return 0f
        val partial=transfers.sumOf { current -> when(current.phase){
            "uploading"->if(current.total>0)(current.bytes.toDouble()/current.total).coerceIn(0.0,.99) else 0.0
            "finalizing","finalizing_album"->.99
            else->0.0
        } }
        return ((finished+partial)/total).toFloat().coerceIn(0f,1f)
    }
}

fun topBackupJob(data:HarborUiData)=data.jobs.firstOrNull{it.id==data.activeJob}
    ?:data.jobs.firstOrNull{it.state in setOf("queued","running","waiting","paused","pausing")||
        (it.state in setOf("auth_required","needs_attention")&&data.attentionItems.any{item->item.jobId==it.id})}

@Composable
fun HarborTopStatus(vm:HarborViewModel,data:HarborUiData,transfers:Map<String,TransferProgress>) {
    val job=topBackupJob(data)
    if(job==null&&!data.cleanup.running)return
    val statistics=job?.let{data.statisticsMap[it.id]}
    val terminalIds=data.items.filter{it.state in BackupPolicy.terminalItems||it.state=="album_pending"}.map{it.id}.toSet()
    val running=transfers.values.filter{it.job==job?.id&&it.item !in terminalIds}
    val total=statistics?.total?:0
    val finished=statistics?.finished?:0
    val fraction=if(job!=null)BackupProgressPolicy.fraction(finished,total,running)
        else if(data.cleanup.total>0)data.cleanup.checked.toFloat()/data.cleanup.total else 0f
    val title=if(job==null)(if(data.cleanup.mode=="quick")"快速检查备份状态" else "检查可释放空间") else when(job.state){
        "paused"->"备份已暂停";"pausing"->"正在暂停备份";"waiting"->"备份等待网络"
        "auth_required"->"备份需要登录";"needs_attention"->"备份有项目需要处理";else->"正在备份"
    }
    val speed=running.filter{it.phase=="uploading"}.sumOf{it.speed}
    val caption=if(job==null)data.cleanup.message else if(total==0)"正在准备备份队列"
        else "已处理 $finished / $total 项"+(if(speed>0)" · ${formatSize(speed)}/s" else "")
    Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,tonalElevation=1.dp) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().clickable {if(job==null){if(vm.page!="cleanup")vm.navigate("cleanup")}else vm.tab("backup")}.padding(horizontal=16.dp,vertical=8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(if(job==null)Icons.Outlined.AutoAwesome else Icons.Outlined.CloudUpload,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(title,style=MaterialTheme.typography.labelLarge)
                    Text(caption,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                }
                Text("${(fraction*100).toInt()}%",style=MaterialTheme.typography.labelLarge)
                if(job?.id==data.activeJob&&job?.state=="running")IconButton(onClick=vm::pause,modifier=Modifier.size(36.dp)){Icon(Icons.Outlined.Pause,"暂停备份")}
                else if(job?.state=="paused")IconButton(onClick={vm.resume(job.id)},modifier=Modifier.size(36.dp)){Icon(Icons.Outlined.PlayArrow,"继续备份")}
            }
            HarborProgress(fraction,Modifier.fillMaxWidth().padding(top=6.dp).height(4.dp))
        }
    }
}
