@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.cccece.picharbor

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(vm:HarborViewModel,data:HarborUiData) {
    val s=data.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("备份设置",vm)
        Column(Modifier.padding(horizontal=20.dp)) {
            Text("让备份，\n成为一件小事。",style=MaterialTheme.typography.headlineLarge,modifier=Modifier.padding(vertical=18.dp))
            SettingGroup {
                ToggleRow("自动备份","备份选定文件夹中的新增内容",s.auto,Icons.Outlined.CloudUpload,!vm.busy){vm.setBoolean("auto",it)}
                SettingsRow("备份账号",s.account.ifEmpty{"尚未登录"},Icons.Outlined.PersonOutline,onClick={vm.sheet="account"})
                SettingsRow("备份画质",qualityName(s.quality),Icons.Outlined.Image,onClick={vm.sheet="quality"})
            }
            SectionLabel("备份内容")
            SettingGroup {
                SettingsRow("备份文件夹",if(s.allFolders)"所有文件夹 · 自动包含新文件夹" else "选择想自动备份的内容",Icons.Outlined.Folder,onClick={vm.navigate("folders")})
                ToggleRow("备份视频","照片之外，也保存精彩片段",s.videos,Icons.Outlined.VideoLibrary,!vm.busy){vm.setBoolean("videos",it)}
                SettingsRow("相册归类",albumLabel(s),Icons.Outlined.Collections,onClick={vm.sheet="album"})
            }
            SectionLabel("备份时机")
            SettingGroup {
                SettingsRow("网络与移动流量",if(s.wifi)"仅使用 Wi-Fi" else "允许移动流量",Icons.Outlined.Wifi,onClick={vm.navigate("network")})
                ToggleRow("仅充电时自动备份","手动备份不受此条件限制",s.charging,Icons.Outlined.BatteryChargingFull,!vm.busy){vm.setBoolean("charging",it)}
                ToggleRow("定时备份",if(s.scheduled)"每天 ${s.time} 附近检查" else "新增内容与定期检查",s.scheduled,Icons.Outlined.Schedule,!vm.busy){vm.setBoolean("scheduled",it)}
                if(s.scheduled)SettingsRow("备份时间","每天 ${s.time} 附近",Icons.Outlined.Schedule,onClick={vm.sheet="time"})
            }
            Helper("系统省电、网络和充电条件可能使任务延后。后台检查不会要求手机一直亮屏。")
        }
    }
}
fun qualityName(value:String)=when(value){"unknown"->"待确认";"saver"->"节省空间";"quota"->"原始画质（计入 Google 存储空间）";else->"原始画质"}
fun albumLabel(s:HarborSettings)=when(s.albumMode){"none"->"不分类";"custom"->"自定义：${s.albumValue}";"existing"->"加入已选择的云端相册";else->"按来源文件夹"}

@Composable
fun FoldersScreen(vm:HarborViewModel,data:HarborUiData,onPermissions:()->Unit,onPickTree:()->Unit) {
    val folders=remember(data.media){data.media.groupBy{it.folderKey}.entries.sortedWith(compareByDescending<Map.Entry<String,List<MediaEntry>>>{it.value.first().folderName.equals("Camera",true)}.thenBy{it.value.first().folderName})}
    LazyColumn(contentPadding=PaddingValues(bottom=24.dp)) {
        item{PageTitle("备份文件夹",vm)}
        item{Column(Modifier.padding(horizontal=20.dp)){Text("只备份你想留住的。",style=MaterialTheme.typography.headlineLarge,modifier=Modifier.padding(vertical=16.dp));Helper("开启的文件夹会自动检查新增内容。关闭后，云端已有备份仍会保留。")}}
        item{Column(Modifier.padding(horizontal=20.dp)){
            ToggleRow("备份所有文件夹","自动包含新发现的文件夹",data.settings.allFolders,Icons.Outlined.FolderCopy,!vm.busy,vm::setAllFolders)
            Helper("仅包含已授权访问的照片和视频。其他文件夹或 SD 卡仍需先授权。关闭此选项后恢复原来的文件夹选择。")
        }}
        if(!vm.graph.media.hasAccess())item{Box(Modifier.padding(horizontal=20.dp)){SettingsRow("允许访问手机照片","授权后可发现相机、截图等文件夹",Icons.Outlined.PhotoLibrary,onClick=onPermissions)}}
        if(folders.none{it.value.first().folderName.equals("Camera",true)})item{Box(Modifier.padding(horizontal=20.dp)){ToggleRow("相机","拍摄的新照片将从这里开始备份",data.settings.allFolders||"@camera" in data.settings.folders,Icons.Outlined.CameraAlt,!vm.busy&&!data.settings.allFolders){vm.folder("@camera",it)}}}
        items(folders,key={it.key}) { entry ->
            val media=entry.value.first();val chosen=data.settings.includesFolder(media)
            Box(Modifier.padding(horizontal=20.dp,vertical=2.dp)) {ToggleRow(media.folderName,"${entry.value.size} 项 · ${formatSize(entry.value.sumOf{it.size})}",chosen,if(media.source=="media")Icons.Outlined.Folder else Icons.Outlined.SdStorage,!vm.busy&&!data.settings.allFolders){vm.folder(entry.key,it)}}
        }
        val lostTrees=data.settings.trees.filter{tree->folders.none{it.key==tree}}
        items(lostTrees){tree->Box(Modifier.padding(horizontal=20.dp)){SettingsRow("已授权文件夹暂不可用","可能为空、存储卡已拔出或权限失效",Icons.Outlined.SdStorage,onClick={vm.sheet="sd"})}}
        item{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedButton(onClick={vm.sheet="sd"},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.CreateNewFolder,null);Spacer(Modifier.width(8.dp));Text("添加文件夹 / SD 卡")}
            if(!data.settings.onboarded)Button(onClick=vm::finishSetup,enabled=!vm.busy&&data.settings.account.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("开启自动备份")}
            else FilledTonalButton(onClick={vm.start()},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text(if(data.settings.allFolders)"备份所有文件夹" else "备份已选文件夹")}
            Helper("默认只选择相机，其他内容由你决定。授权失效时会停止读取，不会自动删除文件。",Icons.Outlined.Security)
        }}
    }
}

@Composable
fun NetworkScreen(vm:HarborViewModel,data:HarborUiData) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("网络与移动流量",vm)
        Column(Modifier.padding(20.dp)) {
            Text("按你的网络习惯备份。",style=MaterialTheme.typography.headlineLarge,modifier=Modifier.padding(bottom=24.dp))
            SettingGroup {
                ToggleRow("仅使用 Wi-Fi","切换到移动网络后等待",data.settings.wifi,Icons.Outlined.Wifi,!vm.busy){vm.setMobile(if(it)"none" else "photos")}
                SettingsRow("移动流量使用量",when(data.settings.mobile){"photos"->"仅照片，视频等待 Wi-Fi";"all"->"照片和视频";else->"不使用移动流量"},Icons.Outlined.SignalCellularAlt,onClick={vm.sheet="mobile"})
            }
            SectionLabel("当前网络")
            SettingsRow(if(vm.graph.backup.onWifi())"已连接 Wi-Fi" else "当前不是 Wi-Fi 连接","上传仍需满足互联网可用条件",Icons.Outlined.Wifi,onClick={vm.say(if(vm.graph.backup.onWifi())"已连接 Wi-Fi" else "请检查手机网络设置")})
            Helper("手动备份可以单独确认本次使用移动流量，不改变长期设置。")
        }
    }
}

@Composable
fun AdvancedScreen(vm:HarborViewModel,data:HarborUiData,onExport:()->Unit) {
    val context=LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("高级设置",vm)
        Column(Modifier.padding(horizontal=20.dp)) {
            Helper("大多数情况下，保持默认设置就好。")
            SectionLabel("后台运行")
            SettingGroup {
                ToggleRow("重启后恢复备份","恢复自动检查与未完成任务",data.settings.restart,Icons.Outlined.RestartAlt,!vm.busy){vm.setBoolean("restart",it)}
                SettingsRow("后台运行设置","检查通知、电池优化和自启动",Icons.Outlined.BatteryChargingFull,onClick={vm.sheet="background"})
            }
            SectionLabel("上传设置")
            SettingGroup {
                SettingsRow("同时上传数量","${data.settings.concurrency} 个",Icons.Outlined.CloudUpload,onClick={vm.sheet="concurrency"})
                SettingsRow("连接超时","${data.settings.timeout} 秒",Icons.Outlined.Schedule,onClick={vm.sheet="timeout"})
                ToggleRow("失败后自动重试","网络失败最多重试 3 次",data.settings.retries>0,Icons.Outlined.Refresh,!vm.busy){vm.setInteger("retries",if(it)3 else 0)}
                ToggleRow("自动备份跳过云端同名文件","手动选中仍会核对内容；同名跳过不计入清理资格",data.settings.sameName,Icons.Outlined.ContentCopy,!vm.busy){vm.setBoolean("sameName",it)}
                SettingsRow("空间检查速度","同时检查 ${data.settings.cleanupConcurrency} 项 · 下次检查生效",Icons.Outlined.Speed,onClick={vm.sheet="cleanup-speed"})
            }
            SectionLabel("诊断")
            SettingGroup {SettingsRow("空间检查流程",data.settings.cleanupPolicy.label,Icons.Outlined.Security,onClick={vm.navigate("cleanup-policy")})}
            if(!data.settings.cleanupPolicy.complete)Helper("已降低核验强度。可能在云端原件不可用或状态未确认时，仍允许移除手机文件。",Icons.Outlined.WarningAmber)
            SettingGroup {SettingsRow("检查账号连接","确认可以继续备份",Icons.Outlined.Security,onClick=vm::checkAccount);SettingsRow("导出诊断报告","不包含密码或登录凭据",Icons.Outlined.FileDownload,onClick=onExport)}
            Helper("设备厂商可能限制后台运行。应用会保存进度，并在允许执行时继续。")
        }
    }
}

@Composable
fun HistoryScreen(vm:HarborViewModel,data:HarborUiData) {
    LazyColumn(contentPadding=PaddingValues(bottom=24.dp)) {
        item{PageTitle("任务记录",vm)}
        item{Text("每一份备份，都有记录。",style=MaterialTheme.typography.headlineLarge,modifier=Modifier.padding(20.dp))}
        if(data.jobs.isEmpty())item{EmptyState("还没有备份任务","完成一次备份后，可以在这里查看记录。",Icons.Outlined.History)}
        items(data.jobs,key={it.id}) { job ->
            val stats=data.statisticsMap[job.id]
            val completed=stats?.succeeded?:0;val errors=stats?.failed?:0
            Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.large,modifier=Modifier.padding(horizontal=20.dp,vertical=5.dp)){
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically){Icon(if(errors>0)Icons.Outlined.ErrorOutline else Icons.Outlined.CloudDone,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Text(if(job.automatic)"自动备份" else "手动备份",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Text(jobLabel(job.state),style=MaterialTheme.typography.labelMedium)}
                    Text(Instant.ofEpochMilli(job.created).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("成功 $completed · 失败 $errors · 共 ${stats?.total?:0} 项",style=MaterialTheme.typography.bodyMedium)
                    if((stats?.skipped?:0)>0)Text("同名跳过 ${stats?.skipped} 项，内容尚未确认",style=MaterialTheme.typography.bodySmall)
                    Row {
                        if(job.state=="paused")TextButton(onClick={vm.resume(job.id)},enabled=!vm.busy){Text("继续")}
                        if(errors>0)TextButton(onClick={vm.retry(job.id)},enabled=!vm.busy){Text("重试未完成项")}
                        TextButton(onClick={vm.showJob(job.id)}){Text("查看详情")}
                        if(job.state in setOf("paused","waiting","needs_attention","auth_required"))TextButton(onClick={vm.sheet="end:${job.id}"}){Text("结束任务")}
                    }
                }
            }
        }
    }
}
fun jobLabel(state:String)=when(state){"completed"->"已完成";"running"->"进行中";"queued"->"等待开始";"paused"->"已暂停";"pausing"->"正在暂停";"waiting"->"等待网络";"auth_required"->"需要登录";"ended"->"已结束";else->"需要处理"}

@Composable
fun CleanupScreen(vm:HarborViewModel,data:HarborUiData,onCleanup:()->Unit) {
    val now by rememberCleanupClock()
    val context=LocalContext.current
    val candidates=data.media.filter{data.backed(it)}
    val ready=data.cleanups.filter{BackupPolicy.cleanupFresh(it,data.settings.account,now,data.settings.cleanupPolicy.id)}
    val blocked=data.cleanups.filter{it.state=="blocked"&&it.scanId==data.cleanup.id&&data.cleanup.mode=="full"}.distinctBy{it.mediaId}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("释放手机空间",vm){IconButton(onClick={vm.sheet="cleanup-speed"}){Icon(Icons.Outlined.Speed,"空间检查速度")}}
        Column(Modifier.padding(horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            EmptyState("给手机，留点新空间。","只移除手机原件，保留 Google 相册备份。",Icons.Outlined.AutoAwesome)
            Text(formatSize(ready.sumOf{it.bytes}),style=MaterialTheme.typography.displayMedium,color=MaterialTheme.colorScheme.primary)
            Text(if(data.cleanup.running)"已检查 ${data.cleanup.checked} / ${data.cleanup.total} 项，检查完成后可确认清理" else if(ready.isNotEmpty())if(data.settings.cleanupPolicy.complete)"${ready.size} 项已核验，可由你确认清理" else "${ready.size} 项基础匹配通过，请留意跳过的检查" else "${candidates.size} 个已备份项目（${formatSize(candidates.sumOf{it.size})}）待核验",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=12.dp))
            if(data.cleanup.running) {
                HarborProgress(if(data.cleanup.total>0)data.cleanup.checked.toFloat()/data.cleanup.total else 0f,modifier=Modifier.fillMaxWidth())
                Text("${data.cleanup.checked} / ${data.cleanup.total} · ${data.cleanup.message}",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=12.dp))
                TextButton(onClick={vm.graph.cleanup.cancel()}){Text("取消检查")}
                Helper("检查会在后台继续。你可以离开此页，完成后会收到通知。")
            } else if(data.cleanup.message.isNotEmpty())Text(data.cleanup.message,style=MaterialTheme.typography.bodySmall)
            if(data.cleanup.mode=="quick")Helper("快速检查只确认云端是否找到相同内容，不代表已满足清理条件。释放空间前仍需完整核验。")
            if(data.cleanup.running)Text("最多同时检查 ${data.cleanup.effectiveParallel} 项",style=MaterialTheme.typography.bodySmall)
            Helper(if(data.settings.cleanupPolicy.complete)"先核对内容、云端状态与原件可访问性。无法确认的项目会保留，不会完整回下载文件。" else "按你选择的流程检查；已开启的检查失败时仍会保留文件，不会自动跳过。",Icons.Outlined.Security)
            if(!data.settings.cleanupPolicy.complete)CleanupPolicyNotice(data.settings.cleanupPolicy)
            Helper("清理后仍可在 Google 相册查看；其他本地图库可能不再显示这些内容。",Icons.Outlined.PhotoLibrary)
            Helper("清理只在你主动确认后进行，不会在备份后自动删除。",Icons.Outlined.Info)
            if(ready.isNotEmpty()&&!data.cleanup.running)Button(onClick={vm.sheet="cleanup-confirm"},enabled=!vm.busy&&data.activeJob.isEmpty(),modifier=Modifier.fillMaxWidth()){Text("释放 ${formatSize(ready.take(100).sumOf{it.bytes})} 空间")}
            FilledTonalButton(onClick={vm.verifyCleanup()},enabled=!vm.busy&&!data.cleanup.running&&data.settings.account.isNotEmpty()&&data.activeJob.isEmpty(),modifier=Modifier.fillMaxWidth().padding(top=10.dp)){Text(if(data.cleanup.running)"正在后台检查" else if(ready.isEmpty())"检查可释放空间" else "重新核验")}
            OutlinedButton(onClick={vm.verifyCleanup(quick=true)},enabled=!vm.busy&&!data.cleanup.running&&data.settings.account.isNotEmpty()&&data.activeJob.isEmpty(),modifier=Modifier.fillMaxWidth().padding(top=8.dp)){Text("快速检查备份状态")}
            Text("快速检查所选文件夹和已有备份记录中的本地文件，不删除照片。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
            if(!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}){Text("允许进度与完成通知")}
            TextButton(onClick={vm.navigate("cleanup-results")}){Text("查看项目与未清理原因")}
            if(data.cleanup.id.isNotEmpty())TextButton(onClick={vm.sheet="cleanup-timing"}){Text("查看检查耗时")}
            if(blocked.isNotEmpty())Text("${blocked.size} 项暂未满足清理条件，手机原件已保留。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }
    }
}
