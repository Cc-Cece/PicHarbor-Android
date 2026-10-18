package com.cccece.picharbor

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun BackupListScreen(vm:HarborViewModel,data:HarborUiData) {
    val kind=vm.backupListKind
    val title=when(kind){"uploaded"->"已备份";"attention"->"需要处理";else->"待检查"}
    val media=if(kind=="uploaded")data.media.filter{data.backed(it)} else data.pending
    val count=if(kind=="attention")data.attentionItems.size else media.size
    Column(Modifier.fillMaxSize()) {
        PageTitle(title,vm)
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("共 $count 项",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            if(kind=="pending"&&media.isNotEmpty())TextButton(onClick={vm.start(media.map{it.id})},enabled=!vm.busy){Text("备份这些项目")}
            if(kind=="attention")TextButton(onClick={vm.navigate("history")}){Text("查看任务记录")}
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=24.dp)) {
            if(count==0)item{EmptyState(if(kind=="attention")"暂时没有需要处理的项目" else "这里暂时没有照片",if(kind=="pending")"只显示所选备份文件夹中需要检查的照片和视频。" else "此列表仅针对当前备份账号。")}
            if(kind=="attention")items(data.attentionItems,key={it.id}) { task ->
                val local=data.media.find{it.id==task.mediaId}
                BackupListRow(task.name,Messages.error(task.error).let{if(task.state=="album_pending")"照片已备份，相册归类需要重试" else it},local) {
                    if(local!=null)vm.open(local) else vm.showJob(task.jobId)
                }
            } else items(media,key={it.id}) { item ->
                BackupListRow(item.name,"${item.folderName} · ${formatSize(item.size)}",item){vm.open(item)}
            }
        }
    }
}

@Composable
private fun BackupListRow(name:String,description:String,media:MediaEntry?,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        if(media!=null)AsyncImage(Uri.parse(media.id),null,Modifier.size(60.dp),contentScale=ContentScale.Crop)
        else Icon(Icons.Outlined.BrokenImage,null,Modifier.size(60.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(name,style=MaterialTheme.typography.bodyMedium,maxLines=2)
            Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight,null)
    }
}
