package com.cccece.picharbor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class MediaResultRow(val id:String,val name:String,val description:String,val media:MediaEntry?,val category:String)

@Composable
private fun ResultList(vm:HarborViewModel,title:String,scopeKey:String,rows:List<MediaResultRow>,categories:List<Pair<String,String>>,intro:@Composable ()->Unit={}) {
    var filter by rememberSaveable(scopeKey){mutableStateOf("all")}
    val lists=rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        PageTitle(title,vm)
        intro()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            (listOf("all" to "全部")+categories).forEach{(id,label)->
                val count=if(id=="all")rows.size else rows.count{it.category==id}
                FilterChip(selected=filter==id,onClick={filter=id},label={Text("$label $count")})
            }
        }
        val visible=if(filter=="all")rows else rows.filter{it.category==filter}
        lists.SaveableStateProvider(filter) {
            LazyColumn(state=rememberLazyListState(),modifier=Modifier.weight(1f),contentPadding=PaddingValues(vertical=8.dp)) {
                if(visible.isEmpty())item {EmptyState("这里暂时没有项目","可以切换分类查看其他结果。")}
                items(visible,key={it.id}) { row ->
                    MediaStatusRow(row.name,row.description,row.media) {
                        row.media?.let{vm.open(it)}?:vm.say(row.description+"。手机原件不可用，无法预览。")
                    }
                }
            }
        }
    }
}

@Composable
fun JobDetailsScreen(vm:HarborViewModel,data:HarborUiData) {
    val id by vm.inspectedJob.collectAsState()
    val details by vm.jobDetails.collectAsState()
    val media=remember(data.media){data.media.associateBy{it.id}}
    val rows=details.filter{it.account==data.settings.account&&it.jobId==id}.map{item->
        val category=when(item.state){"done","exists"->"done";"failed","album_pending"->"attention";"filename_skipped","cancelled"->"skipped";else->"pending"}
        val status=when(item.state){"done"->"已上传";"exists"->"云端已有相同内容";"filename_skipped"->"仅同名跳过，内容待确认";"album_pending"->"内容已备份，相册归类待重试";"cancelled"->if(item.error=="PROMOTED")"已转入优先手动任务" else "已取消";"failed"->Messages.error(item.error);"queued"->"等待处理";else->phaseLabel(item.state)}
        MediaResultRow(item.id,item.name,"${formatSize(item.bytes)} · $status",media[item.mediaId],category)
    }
    ResultList(vm,"任务详情",id,rows,listOf("done" to "已备份","pending" to "待处理","attention" to "需要处理","skipped" to "已跳过或取消"))
}
