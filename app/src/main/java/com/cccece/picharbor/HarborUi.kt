@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.cccece.picharbor

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HarborUiData(val settings:HarborSettings,val media:List<MediaEntry>,val backups:List<BackupRecord>,val jobs:List<BackupJob>,
    val items:List<BackupItem>,val cleanups:List<CleanupRecord>,val transfer:TransferProgress,val activeJob:String,val cleanup:CleanupProgress,
    val backupMap:Map<String,BackupRecord> = backups.filter{it.account==settings.account}.associateBy{it.mediaId},
    val itemMap:Map<String,BackupItem> = items.filter{it.account==settings.account}.asReversed().associateBy{it.mediaId},
    val scope:List<MediaEntry> = media.filter{settings.includes(it)},
    val pending:List<MediaEntry> = scope.filterNot{BackupPolicy.matches(it,backupMap[it.id])},
    val doneCount:Int = media.count{BackupPolicy.matches(it,backupMap[it.id])},val statistics:List<JobStatistics> = emptyList()) {
    val statisticsMap=statistics.associateBy{it.jobId}
    val attentionItems=itemMap.values.filter{it.state in setOf("failed","album_pending")}.sortedBy{it.name}
    fun backed(m:MediaEntry)=backupMap[m.id]?.account==settings.account && BackupPolicy.matches(m,backupMap[m.id])
    fun status(m:MediaEntry):String {
        if(settings.account.isEmpty())return "unknown"
        val task=itemMap[m.id]?.takeIf{it.account==settings.account}
        if(task?.id==transfer.item||task?.state in setOf("hashing","checking","uploading","finalizing","finalizing_album"))return "uploading"
        if(backed(m))return "uploaded"
        if(task?.state=="failed"||task?.state=="album_pending")return "error"
        if(task?.state=="queued")return "pending"
        return "unknown"
    }
    val latestJob=jobs.firstOrNull{it.state in setOf("queued","running","paused","pausing","waiting","auth_required","needs_attention")}
}

@Composable
fun HarborRoot(vm:HarborViewModel,onLogin:()->Unit,onPermissions:()->Unit,onPickFiles:()->Unit,onPickTree:()->Unit,onCleanup:()->Unit,onLocalDelete:()->Unit,onExport:()->Unit) {
    val settings by vm.settings.collectAsState()
    val media by vm.media.collectAsState();val records by vm.records.collectAsState();val jobs by vm.jobs.collectAsState()
    val items by vm.items.collectAsState();val cleanups by vm.cleanups.collectAsState()
    val statistics by vm.statistics.collectAsState()
    val transfer by vm.graph.backup.progress.collectAsState();val active by vm.graph.backup.activeJob.collectAsState();val cleanup by vm.graph.cleanup.progress.collectAsState()
    val notice by vm.graph.backup.notice.collectAsState()
    val transfers by vm.graph.backup.inFlight.collectAsState()
    val base=remember(settings,media,records,jobs,items,cleanups,statistics){
        // DataStore and Room emit independently during a switch: hide any old-account frame.
        val accountJobs=jobs.filter{it.account==settings.account}
        val jobIds=accountJobs.map{it.id}.toSet()
        HarborUiData(settings,media,records.filter{it.account==settings.account},accountJobs,
            items.filter{it.account==settings.account},cleanups.filter{it.account==settings.account},
            TransferProgress(),"",CleanupProgress(),statistics=statistics.filter{it.jobId in jobIds})
    }
    val data=base.copy(transfer=transfer,activeJob=active,cleanup=if(cleanup.account==settings.account)cleanup else CleanupProgress())
    val hasStatus=topBackupJob(data)!=null||data.cleanup.running
    val snackbar=remember{SnackbarHostState()}
    val pageState=rememberSaveableStateHolder()
    LaunchedEffect(vm.message){if(vm.message.isNotBlank()){snackbar.showSnackbar(vm.message);vm.clearMessage()}}
    LaunchedEffect(notice){if(notice.isNotBlank())snackbar.showSnackbar(notice)}
    BackHandler(vm.page!="photos"||vm.sheet.isNotEmpty()||vm.selectionMode){vm.back()}
    PicHarborTheme(settings) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
          Column(Modifier.fillMaxSize()) {
            HarborTopStatus(vm,data,transfers)
            AnimatedContent(targetState=vm.page,modifier=Modifier.weight(1f).fillMaxWidth()
                .consumeWindowInsets(if(hasStatus)WindowInsets.statusBars else WindowInsets(0,0,0,0)),transitionSpec={
                val direction=if(HarborMotion.pageOrder(targetState)>=HarborMotion.pageOrder(initialState))1 else -1
                ((fadeIn(tween(HarborMotion.MEDIUM,60))+slideInHorizontally(tween(300,easing=FastOutSlowInEasing)){it/18*direction}) togetherWith
                    fadeOut(tween(HarborMotion.SHORT))).using(null)
            },label="page-transition") { page ->
                pageState.SaveableStateProvider(page) {
                    Scaffold(bottomBar={
                        if(page in setOf("photos","backup","me")) NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
                            listOf(Triple("photos",Icons.Outlined.PhotoLibrary,"照片"),Triple("backup",Icons.Outlined.Cloud,"备份"),Triple("me",Icons.Outlined.PersonOutline,"我的")).forEach{(route,icon,label)->
                                NavigationBarItem(selected=page==route,onClick={vm.tab(route)},icon={Icon(icon,label)},label={Text(label)})
                            }
                        }
                    }) { insets ->
                        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                            when(page) {
                                "photos"->PhotosScreen(vm,data,onPermissions,onPickFiles)
                                "backup"->BackupScreen(vm,data,onLogin)
                                "me"->ProfileScreen(vm,data,onLogin)
                                "settings"->SettingsScreen(vm,data)
                                "folders"->FoldersScreen(vm,data,onPermissions,onPickTree)
                                "network"->NetworkScreen(vm,data)
                                "advanced"->AdvancedScreen(vm,data,onExport)
                                "history"->HistoryScreen(vm,data)
                                "cleanup"->CleanupScreen(vm,data,onCleanup)
                                "detail"->DetailScreen(vm,data)
                                "backup-list"->BackupListScreen(vm,data)
                                else->PhotosScreen(vm,data,onPermissions,onPickFiles)
                            }
                            if(vm.busy&&!hasStatus)LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                        }
                    }
                }
            }
          }
            SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=if(vm.page in setOf("photos","backup","me"))80.dp else 0.dp))
        }
        HarborSheets(vm,data,onLogin,onCleanup,onLocalDelete,onPickTree)
    }
}

@Composable
fun PageTitle(title:String,vm:HarborViewModel,root:Boolean=false,action:@Composable RowScope.()->Unit={}) {
    if(root)Row(Modifier.fillMaxWidth().padding(start=24.dp,end=16.dp,top=18.dp,bottom=16.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,style=MaterialTheme.typography.headlineLarge,modifier=Modifier.weight(1f));action()
    } else TopAppBar(title={Text(title)},navigationIcon={IconButton(onClick=vm::back){Icon(Icons.Outlined.ArrowBack,"返回")}},actions=action)
}
@Composable
fun SettingsRow(title:String,subtitle:String="",icon:ImageVector?=null,enabled:Boolean=true,onClick:()->Unit,trailing:@Composable (()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(enabled=enabled,onClick=onClick)
        .padding(horizontal=18.dp,vertical=15.dp).heightIn(min=40.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        if(icon!=null)Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(23.dp))
        Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.bodyLarge);if(subtitle.isNotEmpty())Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=3.dp))}
        if(trailing!=null)trailing() else Icon(Icons.Outlined.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(19.dp))
    }
}
@Composable
fun ToggleRow(title:String,subtitle:String,checked:Boolean,icon:ImageVector?=null,enabled:Boolean=true,onChange:(Boolean)->Unit) {
    SettingsRow(title,subtitle,icon,enabled,{onChange(!checked)}){Switch(checked,onCheckedChange=onChange,enabled=enabled)}
}
@Composable
fun SettingGroup(content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxWidth().animateContentSize().clip(RoundedCornerShape(24.dp)),verticalArrangement=Arrangement.spacedBy(2.dp),content=content)}
@Composable
fun SectionLabel(label:String){Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(start=5.dp,top=24.dp,bottom=12.dp))}
@Composable
fun Helper(text:String,icon:ImageVector=Icons.Outlined.Info){Row(Modifier.padding(horizontal=4.dp,vertical=14.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)){
    Icon(icon,null,modifier=Modifier.size(17.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
    Text(text,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}}
@Composable
fun EmptyState(title:String,text:String,icon:ImageVector=Icons.Outlined.CloudDone,action:(@Composable ()->Unit)?=null) {
    Column(Modifier.fillMaxWidth().animateContentSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(15.dp)) {
        Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(35.dp),modifier=Modifier.size(106.dp)){Box(contentAlignment=Alignment.Center){
            Crossfade(icon,animationSpec=tween(HarborMotion.MEDIUM),label="status-icon") { current -> Icon(current,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(53.dp)) }
        }}
        AnimatedContent(title,label="status-title") { current -> Text(current,style=MaterialTheme.typography.headlineSmall) }
        Text(text,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}
fun backupHeading(vm:HarborViewModel,data:HarborUiData):Pair<String,String> = when {
    !vm.engineReady->"暂时无法启动备份" to "请检查安装包或重新安装"
    data.settings.account.isEmpty()||vm.loginRequired->"登录后开始安心备份" to "连接你的 Google 账号"
    data.activeJob.isNotEmpty()->(if(data.latestJob?.state=="pausing")"正在暂停备份" else "正在备份你的照片") to data.transfer.name.ifEmpty{"正在准备文件"}
    data.latestJob?.state=="paused"->"备份已暂停" to "已完成的内容会保留，随时可以继续"
    data.latestJob?.state=="waiting"->"等待网络条件满足" to "连接 Wi-Fi 后会继续未完成的任务"
    data.latestJob?.state=="auth_required"->"账号需要重新登录" to "重新连接后继续未完成的备份"
    data.latestJob?.state=="needs_attention"->"有项目需要处理" to "已成功备份的内容不会重复上传"
    data.settings.folders.isEmpty()->"选择备份文件夹" to "只备份你想留住的内容"
    data.pending.isNotEmpty()->"还有 ${data.pending.size} 项待检查或备份" to "原始画质 · 备份到 Google 相册"
    else->"备份已完成" to "已选文件夹中当前可访问的内容均已备份"
}

@Composable
fun PhotosScreen(vm:HarborViewModel,data:HarborUiData,onPermissions:()->Unit,onPickFiles:()->Unit) {
    val status=backupHeading(vm,data)
    val list=remember(data.media,data.backups,data.items,vm.filter,vm.type,vm.fromDate,vm.toDate,data.settings.account){data.media.filter { m ->
        val s=data.status(m)
        (vm.filter=="all"||(vm.filter=="uploaded"&&s=="uploaded")||(vm.filter=="pending"&&s in setOf("pending","unknown"))||(vm.filter=="error"&&s=="error")) &&
            (vm.type=="all"||(vm.type=="video"&&m.video)||(vm.type=="photo"&&!m.video)) && m.captured>=vm.fromDate && m.captured<=vm.toDate
    }}
    val groups=remember(list){list.groupBy{Instant.ofEpochMilli(it.captured).atZone(ZoneId.systemDefault()).toLocalDate()}}
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns=GridCells.Adaptive(112.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalArrangement=Arrangement.spacedBy(3.dp),contentPadding=PaddingValues(bottom=if(vm.selectionMode)100.dp else 20.dp)) {
            item(span={GridItemSpan(maxLineSpan)}){PageTitle(if(vm.selectionMode)"已选择 ${vm.selected.size} 项" else "照片",vm,true){
                if(vm.selectionMode)TextButton(onClick=vm::clearSelection){Text("完成")}
                else {IconButton(onClick=onPickFiles){Icon(Icons.Outlined.AddPhotoAlternate,"手动选择文件")};Avatar(data.settings.account){vm.sheet="account"}}
            }}
            item(span={GridItemSpan(maxLineSpan)}){Surface(onClick={vm.tab("backup")},color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(25.dp),modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp)) {
                Row(Modifier.padding(17.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Icon(if(data.pending.isEmpty())Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload,null,tint=MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)){Text(status.first,style=MaterialTheme.typography.titleSmall);Text(status.second,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    Icon(Icons.Outlined.ChevronRight,null)
                }
            }}
            item(span={GridItemSpan(maxLineSpan)}){Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("all" to "全部","pending" to "待备份","uploaded" to "已备份","error" to "需要处理").forEach{(key,label)->FilterChip(vm.filter==key,{vm.filter=key;vm.clearSelection()},{Text(label)})}
                IconButton(onClick={vm.sheet="filter"}){Icon(Icons.Outlined.Tune,"筛选照片")}
            }}
            if(!vm.graph.media.fullAccess())item(span={GridItemSpan(maxLineSpan)}){SettingsRow(if(vm.graph.media.hasAccess())"仅显示已授权的照片" else "允许访问手机照片", "授权后可以查看和备份照片，也可手动选择文件",Icons.Outlined.PhotoLibrary,onClick=onPermissions)}
            if(list.isEmpty())item(span={GridItemSpan(maxLineSpan)}){EmptyState(if(vm.filter=="pending")"这里暂时没有待处理项目" else "这里还没有照片","你可以检查访问权限、切换筛选条件，或手动选择照片。",Icons.Outlined.PhotoLibrary){OutlinedButton(onClick=onPickFiles){Text("手动选择照片和视频")}}}
            groups.forEach{(date,photos)->
                item(span={GridItemSpan(maxLineSpan)}){Row(Modifier.fillMaxWidth().padding(start=20.dp,end=10.dp,top=14.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically){
                    Text(date.format(DateTimeFormatter.ofPattern("M月d日 · E",Locale.CHINA)),style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f))
                    TextButton(onClick={vm.selectAll(photos.map{it.id})}){Text("选择")}
                }}
                items(photos,key={it.id}) { media ->
                    val selected=media.id in vm.selected;val phase=data.status(media)
                    val inset by animateDpAsState(if(selected)9.dp else 0.dp,spring(dampingRatio=1f,stiffness=500f),label="photo-selection-inset")
                    val corner by animateDpAsState(if(selected)15.dp else 3.dp,tween(HarborMotion.MEDIUM),label="photo-selection-shape")
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.primaryContainer)
                        .combinedClickable(onClick={vm.open(media)},onLongClick={vm.select(media.id)})) {
                        AsyncImage(ImageRequest.Builder(LocalContext.current).data(Uri.parse(media.id)).size(360).memoryCacheKey("${media.id}:${media.modified}:${media.size}").build(),media.name,
                            modifier=Modifier.fillMaxSize().padding(inset.coerceIn(0.dp,9.dp)).clip(RoundedCornerShape(corner)),contentScale=ContentScale.Crop)
                        AnimatedVisibility(selected,modifier=Modifier.padding(7.dp).align(Alignment.TopStart),enter=fadeIn()+scaleIn(initialScale=.7f),exit=fadeOut()+scaleOut(targetScale=.7f)) {
                            Surface(color=MaterialTheme.colorScheme.primary,shape=CircleShape){Icon(Icons.Outlined.Check,"已选择",Modifier.padding(3.dp).size(15.dp))}
                        }
                        Surface(color=if(phase=="uploaded")Color(0xFFEDF7EB) else MaterialTheme.colorScheme.surface.copy(alpha=.92f),shape=CircleShape,modifier=Modifier.padding(7.dp).align(Alignment.BottomEnd)) {
                            Icon(when(phase){"uploaded"->Icons.Outlined.CloudDone;"error"->Icons.Outlined.ErrorOutline;"uploading"->Icons.Outlined.Sync;else->Icons.Outlined.CloudUpload},
                                when(phase){"uploaded"->"已备份";"error"->"需要处理";"uploading"->"备份中";"pending"->"待备份";else->"待确认"},modifier=Modifier.padding(4.dp).size(15.dp),tint=if(phase=="uploaded")Color(0xFF38664C) else MaterialTheme.colorScheme.primary)
                        }
                        if(media.video)Icon(Icons.Outlined.PlayArrow,"视频",Modifier.align(Alignment.BottomStart).padding(7.dp).size(20.dp),tint=Color.White)
                    }
                }
            }
        }
        AnimatedVisibility(vm.selectionMode,modifier=Modifier.align(Alignment.BottomCenter).padding(12.dp),
            enter=fadeIn()+slideInVertically{it/2},exit=fadeOut()+slideOutVertically{it/2}) {
          Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=RoundedCornerShape(30.dp),shadowElevation=6.dp){
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Button(onClick={vm.start(vm.selected.toList())},enabled=vm.selected.isNotEmpty()&&!vm.busy){Icon(Icons.Outlined.CloudUpload,null);Spacer(Modifier.width(7.dp));Text("备份所选")}
                TextButton(onClick={vm.sheet="delete-local"},enabled=vm.selected.isNotEmpty()&&!vm.busy){Icon(Icons.Outlined.DeleteOutline,null);Spacer(Modifier.width(5.dp));Text("从手机删除")}
            }
          }
        }
    }
}

@Composable
fun Avatar(account:String,onClick:()->Unit){Surface(onClick=onClick,color=MaterialTheme.colorScheme.tertiaryContainer,shape=CircleShape,modifier=Modifier.size(40.dp)){
    Box(contentAlignment=Alignment.Center){Text(if(account.isEmpty())"?" else account.first().uppercase(),fontWeight=FontWeight.SemiBold)}
}}

@Composable
fun BackupScreen(vm:HarborViewModel,data:HarborUiData,onLogin:()->Unit) {
    val status=backupHeading(vm,data);val job=data.latestJob;val done=data.doneCount
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("备份",vm,true){IconButton(onClick={vm.navigate("settings")}){Icon(Icons.Outlined.Settings,"备份设置")}}
        EmptyState(status.first,status.second,when{data.activeJob.isNotEmpty()->Icons.Outlined.CloudUpload;data.pending.isEmpty()->Icons.Outlined.CloudDone;else->Icons.Outlined.CloudUpload}) {
            when {
                data.settings.account.isEmpty()||vm.loginRequired||job?.state=="auth_required"->Button(onClick={vm.sheet="account"},enabled=!vm.busy){Text("选择或添加账号")}
                data.activeJob.isNotEmpty()->FilledTonalButton(onClick=vm::pause,enabled=job?.state!="pausing"&&!vm.busy){Icon(Icons.Outlined.Pause,null);Text("暂停备份")}
                job?.state=="paused"->Button(onClick={vm.resume(job.id)},enabled=!vm.busy){Icon(Icons.Outlined.PlayArrow,null);Text("继续备份")}
                job?.state=="needs_attention"->Button(onClick={vm.retry(job.id)},enabled=!vm.busy){Text("重试未完成项目")}
                data.settings.folders.isEmpty()->Button(onClick={vm.navigate("folders")}){Text("选择备份文件夹")}
                else->Button(onClick={vm.start()},enabled=!vm.busy&&vm.engineReady){Icon(Icons.Outlined.CloudUpload,null);Spacer(Modifier.width(8.dp));Text(if(data.pending.isEmpty())"检查并备份" else "立即备份")}
            }
        }
        Column(Modifier.padding(horizontal=20.dp)) {
            if(data.activeJob.isNotEmpty()) {
                key(data.transfer.item) { HarborProgress(if(data.transfer.total>0)data.transfer.bytes.toFloat()/data.transfer.total else 0f,modifier=Modifier.fillMaxWidth().clip(CircleShape)) }
                Text("${phaseLabel(data.transfer.phase)} · ${formatSize(data.transfer.bytes)} / ${formatSize(data.transfer.total)} · ${formatSize(data.transfer.speed)}/s",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=10.dp))
            }
            val currentStats=data.statisticsMap[job?.id]
            if(currentStats!=null) {
                Text("本次共 ${currentStats.total} 项 · ${formatSize(currentStats.bytes)}",style=MaterialTheme.typography.bodyMedium)
                Text("成功 ${currentStats.succeeded} · 跳过 ${currentStats.skipped} · 失败 ${currentStats.failed}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=14.dp))
            }
            Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                    listOf(Triple(done,"已备份","uploaded"),Triple(data.pending.size,"待检查","pending"),Triple(data.attentionItems.size,"需要处理","attention")).forEach{(n,label,kind)->
                        Column(Modifier.weight(1f).clickable{vm.showBackupList(kind)}.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("$n",style=MaterialTheme.typography.headlineSmall);Text(label,style=MaterialTheme.typography.bodySmall)}
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            SettingGroup {
                ToggleRow("自动备份",if(data.settings.auto)"有新增内容时自动检查" else "已关闭，仍可手动备份",data.settings.auto,Icons.Outlined.CloudUpload,!vm.busy){vm.setBoolean("auto",it)}
                SettingsRow("备份文件夹","选择自动备份的范围",Icons.Outlined.Folder,onClick={vm.navigate("folders")})
                SettingsRow("任务记录","查看已完成和未完成的备份",Icons.Outlined.History,onClick={vm.navigate("history")})
            }
            Helper("已备份状态只针对当前账号。新文件先核对云端内容，再决定是否上传。",Icons.Outlined.Security)
            if(job!=null&&job.state in setOf("paused","waiting","needs_attention","auth_required"))TextButton(onClick={vm.sheet="end:${job.id}"}){Text("结束当前任务")}
        }
    }
}
fun phaseLabel(phase:String)=when(phase){"hashing"->"检查本地文件";"checking","cloud_check"->"核对云端内容";"uploading"->"上传中";"finalizing"->"完成上传";"retrying"->"稍后重试";else->"正在准备"}

@Composable
fun ProfileScreen(vm:HarborViewModel,data:HarborUiData,onLogin:()->Unit) {
    val backed=data.media.filter{data.backed(it)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("我的",vm,true){IconButton(onClick={vm.sheet="appearance"}){Icon(Icons.Outlined.Palette,"外观")}}
        Column(Modifier.padding(horizontal=20.dp)) {
            Surface(onClick={vm.sheet="account"},shape=RoundedCornerShape(26.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){
                Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                    Avatar(data.settings.account){vm.sheet="account"}
                    Column(Modifier.weight(1f)){Text(data.settings.account.substringBefore('@').ifEmpty{"尚未登录"},style=MaterialTheme.typography.titleLarge);Text(data.settings.account.ifEmpty{"登录 Google，开始备份"},style=MaterialTheme.typography.bodySmall)}
                    Icon(Icons.Outlined.ChevronRight,null)
                }
            }
            Spacer(Modifier.height(18.dp))
            Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(28.dp)){
                Column(Modifier.fillMaxWidth().padding(24.dp)){
                    Text("给手机腾点空间",style=MaterialTheme.typography.labelLarge)
                    Text(formatSize(backed.sumOf{it.size}),style=MaterialTheme.typography.displaySmall,modifier=Modifier.padding(vertical=8.dp))
                    Text("${backed.size} 个已备份项目可进一步核验",style=MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(15.dp));Button(onClick={vm.navigate("cleanup")}){Icon(Icons.Outlined.AutoAwesome,null);Spacer(Modifier.width(8.dp));Text("释放手机空间")}
                }
            }
            SectionLabel("偏好设置")
            SettingGroup {
                SettingsRow("备份设置","自动备份、画质和相册",Icons.Outlined.Cloud,onClick={vm.navigate("settings")})
                SettingsRow("网络与移动流量",if(data.settings.wifi)"仅使用 Wi-Fi" else "允许移动流量",Icons.Outlined.Wifi,onClick={vm.navigate("network")})
                SettingsRow("外观","跟随系统或使用喜欢的配色",Icons.Outlined.Palette,onClick={vm.sheet="appearance"})
                SettingsRow("高级设置","后台运行、重试和更多选项",Icons.Outlined.Settings,onClick={vm.navigate("advanced")})
            }
            SectionLabel("帮助")
            SettingGroup{SettingsRow("备份遇到问题？","检查权限、网络与后台运行",Icons.Outlined.HelpOutline,onClick={vm.sheet="help"});SettingsRow("关于 PicHarbor","cc_cece · ${BuildConfig.VERSION_NAME}",Icons.Outlined.Info,onClick={vm.sheet="about"})}
            Helper("为珍贵的日常，留一份安心。",Icons.Outlined.CloudDone)
        }
    }
}
