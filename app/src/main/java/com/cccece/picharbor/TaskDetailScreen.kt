package com.cccece.picharbor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun TaskDetailScreen(vm:HarborViewModel,data:HarborUiData) {
    val item=data.items.firstOrNull{it.id==vm.inspectedItem}
    var remove by remember{mutableStateOf(false)}
    var advanced by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("任务详情",vm)
        if(item!=null)Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val media=data.media.firstOrNull{it.id==item.mediaId}
            var fullName by remember{mutableStateOf(false)}
            TextButton(onClick={fullName=!fullName}){LText(item.name,maxLines=if(fullName)Int.MAX_VALUE else 2,overflow=TextOverflow.Ellipsis)}
            val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
            TextButton(onClick={clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.name))}){LText("复制文件名")}
            LText(when(item.state){"done","exists"->"已备份";"paused"->"已暂停";"failed"->"可重试";"waiting_confirmation"->"等待云端确认";else->phaseLabel(item.state)})
            if(item.error.isNotBlank())LText(Messages.error(item.error))
            if(media!=null)TextButton(onClick={vm.open(media)}){LText("照片详情")}
            Row {
                if(item.state !in setOf("done","exists","cancelled","paused","failed"))TextButton(onClick={vm.itemAction(item.id,"pause")},enabled=!vm.busy){LText("暂停")}
                if(item.state in setOf("paused","failed","waiting_confirmation"))TextButton(onClick={vm.itemAction(item.id,"resume")},enabled=!vm.busy){LText(if(item.state=="failed")"重试" else "继续")}
                if(item.state !in setOf("done","exists","cancelled"))TextButton(onClick={remove=true},enabled=!vm.busy){LText("停止并移除")}
                if(item.state in setOf("done","exists"))TextButton(onClick={vm.archiveItem(item.id,item.archivedAt>0)},enabled=!vm.busy){LText(if(item.archivedAt>0)"取消归档" else "归档")}
            }
            TextButton(onClick={vm.navigate("task-proxy")}){LText("任务连接策略")}
            TextButton(onClick={advanced=!advanced}){LText("高级信息")}
            if(advanced){
                DetailRow("任务标识",item.id);DetailRow("文件大小",formatSize(item.bytes));DetailRow("尝试次数",item.attempt.toString())
                val job=data.jobs.firstOrNull{it.id==item.jobId}
                if(job!=null){DetailRow("来源",if(job.automatic)"自动" else "手动");DetailRow("画质",qualityName(job.quality));DetailRow("动态照片",livePhotoLabel(job.liveStrategy))}
            }
        } else EmptyState("任务已移除","返回活动查看其他任务。")
    }
    if(remove&&item!=null)AlertDialog(onDismissRequest={remove=false},title={LText("移除此任务？")},text={LText("保留本机及云端照片，仍可手动重新备份同一版本。")},confirmButton={TextButton(onClick={vm.itemAction(item.id,"remove");remove=false;vm.back()}){LText("停止并移除")}},dismissButton={TextButton(onClick={remove=false}){LText("取消")}})
}
