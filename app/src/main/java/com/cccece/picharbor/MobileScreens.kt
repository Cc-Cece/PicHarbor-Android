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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Presentation filters never change batch identity or the underlying task set. */
object ActivityPresentation {
    fun visible(jobs:List<BackupJob>,source:String,status:String,archived:Boolean):List<BackupJob> = jobs.filter {
        !it.hidden && (it.archivedAt>0)==archived &&
            (source=="all" || it.automatic==(source=="automatic")) &&
            (status=="all" || (it.state=="completed")== (status=="completed"))
    }
}

@Composable
fun ActivityScreen(vm:HarborViewModel,data:HarborUiData,archived:Boolean=false) {
    var source by rememberSaveable { mutableStateOf(if(archived)data.settings.archiveSource else data.settings.activitySource) }
    var status by rememberSaveable { mutableStateOf(if(archived)"completed" else data.settings.activityStatus) }
    var grouping by rememberSaveable { mutableStateOf(data.settings.activityGrouping) }
    LaunchedEffect(source,status,grouping) {
        vm.graph.preferences.string(if(archived)"archiveSource" else "activitySource",source)
        if(!archived)vm.graph.preferences.string("activityStatus",status)
        vm.graph.preferences.string("activityGrouping",grouping)
    }
    var confirmRemoval by remember { mutableStateOf(false) }
    var selecting by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf(emptySet<String>())}
    val byJob=data.items.filter{!it.hidden}.groupBy{it.jobId}
    fun matching(job:BackupJob)=byJob[job.id].orEmpty().filter { item ->
        (item.archivedAt>0)==archived && (status=="all" || (item.state in setOf("done","exists"))==(status=="completed")&&item.state!="cancelled")
    }
    val jobs=data.jobs.filter{!it.hidden&&(source=="all"||it.automatic==(source=="automatic"))&&matching(it).isNotEmpty()}
    val unfinished=jobs.filter { matching(it).any{item->item.state !in setOf("done","exists","cancelled")} }.map { it.id }
    val completed=jobs.filter { matching(it).any{item->item.state in setOf("done","exists")} }.map { it.id }
    val archivable=jobs.flatMap(::matching).filter{it.state in setOf("done","exists")}
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val dateFormat=remember(locale){DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())}
    Column(Modifier.fillMaxSize()) {
        PageTitle(if(archived)"已归档" else "活动",vm,!archived) {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick={menu=true}) { LIcon(Icons.Outlined.MoreVert,"活动选项") }
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                DropdownMenuItem(text={LText("按批次分组")},onClick={grouping="batch";menu=false})
                DropdownMenuItem(text={LText("按来源分组")},onClick={grouping="source";menu=false})
                DropdownMenuItem(text={LText("不分组")},onClick={grouping="none";menu=false})
                if(!archived) {
                    DropdownMenuItem(text={LText("暂停任务")},enabled=unfinished.isNotEmpty(),onClick={vm.pauseBatch(unfinished);menu=false})
                    DropdownMenuItem(text={LText("继续暂停任务")},enabled=jobs.any{it.state=="paused"},onClick={vm.resumeBatch(jobs.filter{it.state=="paused"}.map{it.id});menu=false})
                    DropdownMenuItem(text={LText("重试全部异常任务")},enabled=!vm.busy&&jobs.any{it.state=="needs_attention"},onClick={vm.retryAll(jobs.filter{it.state=="needs_attention"}.map{it.id});menu=false})
                    DropdownMenuItem(text={LText("已归档")},onClick={vm.navigate("archives");menu=false})
                    DropdownMenuItem(text={LText("停止并移除未完成")},enabled=unfinished.isNotEmpty(),onClick={confirmRemoval=true;menu=false})
                }
                DropdownMenuItem(text={LText(if(archived)"取消归档全部" else "归档已完成")},enabled=completed.isNotEmpty(),onClick={vm.archiveBatch(completed,archived);menu=false})
                DropdownMenuItem(text={LText(if(archived)"选择取消归档" else "选择归档")},enabled=archivable.isNotEmpty(),onClick={selected=emptySet();selecting=true;menu=false})
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部来源","manual" to "手动","automatic" to "自动").forEach{(id,label)->FilterChip(selected=source==id,onClick={source=id},label={LText(label)})}
        }
        if(!archived)Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部状态","unfinished" to "未完成","completed" to "已完成").forEach{(id,label)->FilterChip(selected=status==id,onClick={status=id},label={LText(label)})}
        }
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(jobs.isEmpty())item {EmptyState("暂无活动","新的备份任务会显示在这里。",Icons.Outlined.History)}
            val groups=when(grouping){"source"->jobs.groupBy{if(it.automatic)"自动备份" else "手动备份"};else->mapOf("" to jobs)}
            if(grouping=="none") {
                items(jobs.flatMap(::matching),key={it.id}) { item ->
                    Surface(onClick={vm.showItem(item)},shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {LText(item.name,localize=false);LText(phaseLabel(item.state),style=MaterialTheme.typography.bodySmall)}
                    }
                }
            } else groups.forEach { (title,rows) ->
                if(title.isNotEmpty()) item(key=title){SectionLabel(title)}
                items(rows,key={it.id}) { job ->
                    val stats=data.statisticsMap[job.id]
                    val members=matching(job)
                    Surface(onClick={if(members.size==1)vm.showItem(members.first()) else vm.showJob(job.id)},shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            LText(if(grouping=="batch" && (stats?.total?:0)>1)"备份批次 · ${stats?.total} 项" else members.firstOrNull()?.name?:if(job.automatic)"自动备份" else "手动备份",style=MaterialTheme.typography.titleMedium)
                            LText(dateFormat.format(Instant.ofEpochMilli(job.created)),style=MaterialTheme.typography.bodySmall)
                            LText("${localize(jobLabel(job.state))} · 已备份 ${stats?.succeeded?:0} / ${stats?.total?:0}")
                            if(members.size!=byJob[job.id].orEmpty().size)LText("当前筛选 ${members.size} 项",style=MaterialTheme.typography.bodySmall)
                            Row {
                                if(job.state=="paused")TextButton(onClick={vm.resume(job.id)}){LText("继续")}
                                if(job.state=="needs_attention")TextButton(onClick={vm.retry(job.id)}){LText("重试")}
                                if(job.state=="completed")TextButton(onClick={vm.archiveBatch(listOf(job.id),archived)}){LText(if(archived)"取消归档" else "归档")}
                                TextButton(onClick={vm.showJob(job.id)}){LText("查看详情")}
                            }
                        }
                    }
                }
            }
        }
    }
    if(confirmRemoval)AlertDialog(onDismissRequest={confirmRemoval=false},title={LText("移除未完成任务？")},text={LText("仅处理当前筛选结果，保留本机和云端照片。同一版本不再自动加入，仍可手动备份。")},confirmButton={TextButton(onClick={vm.removeBatch(unfinished);confirmRemoval=false}){LText("停止并移除")}},dismissButton={TextButton(onClick={confirmRemoval=false}){LText("取消")}})
    if(selecting)AlertDialog(onDismissRequest={selecting=false},title={LText(if(archived)"选择取消归档" else "选择归档")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        archivable.forEach{item->Row{Checkbox(item.id in selected,onCheckedChange={checked->selected=if(checked)selected+item.id else selected-item.id});LText(item.name,localize=false)}}
    }},confirmButton={TextButton(onClick={vm.archiveSelected(selected.toList(),archived);selecting=false},enabled=selected.isNotEmpty()){LText("完成")}},dismissButton={TextButton(onClick={selecting=false}){LText("取消")}})
}

@Composable
fun UnifiedSettingsScreen(vm:HarborViewModel,data:HarborUiData,onPermissions:()->Unit,onExport:()->Unit) {
    val context=LocalContext.current
    var restore by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("设置",vm,true)
        Column(Modifier.padding(horizontal=20.dp)) {
            SettingGroup { SettingsRow("账号",data.settings.account.ifEmpty{"连接 Google 账号"},Icons.Outlined.AccountCircle,onClick={vm.sheet="account"}) }
            SectionLabel("备份")
            SettingGroup {
                SettingsRow("备份偏好","画质、动态照片、相簿同步",Icons.Outlined.Cloud,onClick={vm.navigate("preferences")})
                SettingsRow("备份范围",if(data.settings.allFolders)"全部可访问照片与视频" else "已选择的相簿",Icons.Outlined.Folder,onClick={vm.navigate("folders")})
                SettingsRow("网络与后台",if(data.settings.wifi)"仅 Wi-Fi" else "Wi-Fi 与蜂窝网络",Icons.Outlined.Wifi,onClick={vm.navigate("network")})
                SettingsRow("应用内代理",if(data.settings.proxyEnabled)"已开启" else "未开启",Icons.Outlined.Public,onClick={vm.navigate("proxy")})
                SettingsRow("活动记录","自动归档与归档记录",Icons.Outlined.Archive,onClick={vm.navigate("archive-settings")})
            }
            SectionLabel("设备")
            SettingGroup {
                SettingsRow("界面","语言与外观",Icons.Outlined.Palette,onClick={vm.navigate("interface")})
                SettingsRow("相册访问","仅显示系统授权内容",Icons.Outlined.PhotoLibrary,onClick=onPermissions)
                SettingsRow("后台运行","通知、电池优化和自启动",Icons.Outlined.BatteryChargingFull,onClick={vm.sheet="background"})
            }
            SectionLabel("帮助")
            SettingGroup {
                SettingsRow("恢复备份默认设置","保留账号、照片与任务历史",Icons.Outlined.Restore,onClick={restore=true})
                SettingsRow("诊断","导出脱敏日志，可选择日期范围",Icons.Outlined.BugReport,onClick=onExport)
                SettingsRow("关于 PicHarbor",BuildConfig.VERSION_NAME,Icons.Outlined.Info,onClick={vm.sheet="about"})
            }
            Helper("这是独立的新应用，不读取旧版 PicHarbor 的账号、设置或任务。")
            TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}){LText("打开系统设置")}
        }
    }
    if(restore)AlertDialog(onDismissRequest={restore=false},title={LText("恢复默认设置？")},text={LText("关闭自动备份并重新确认范围。账号、照片、历史任务、语言及外观会保留，不会重新上传照片。")},confirmButton={TextButton(onClick={restore=false;vm.restoreDefaults()}){LText("恢复默认设置")}},dismissButton={TextButton(onClick={restore=false}){LText("取消")}})
}

@Composable
fun ConfirmationSettingsScreen(vm:HarborViewModel,data:HarborUiData) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("高级备份检查",vm)
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            ToggleRow("等待云端完成动态处理","照片和视频保存后，继续等待云端识别动态内容",data.settings.strictConfirmation,
                enabled=!vm.busy){vm.setBoolean("strictConfirmation",it)}
            Helper("默认关闭。开启后，已保存的内容仍会显示已备份，任务会继续等待动态处理，不会重新上传。")
            Button(onClick=vm::applyConfirmationPolicy,enabled=!vm.busy&&data.items.any{it.state=="waiting_confirmation"}){LText("对等待确认的任务使用此设置")}
            Helper("只影响等待确认的任务，保留手动暂停，不改变已完成任务。")
        }
    }
}

@Composable
fun ArchiveSettingsScreen(vm:HarborViewModel,data:HarborUiData) {
    var days by remember(data.settings.archiveDays){mutableStateOf(data.settings.archiveDays.toString())}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("活动记录",vm)
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            ToggleRow("自动归档","仅归档已完成的任务",data.settings.archiveEnabled,enabled=!vm.busy){vm.setBoolean("archiveEnabled",it)}
            OutlinedTextField(value=days,onValueChange={days=it},label={LText("保留天数")},isError=days.toIntOrNull() !in 1..90,singleLine=true)
            Button(onClick={days.toIntOrNull()?.let{vm.setInteger("archiveDays",it)}},enabled=days.toIntOrNull() in 1..90){LText("保存")}
            SettingsRow("归档记录",onClick={vm.navigate("archives")})
            Helper("归档只改变活动列表，不删除照片或备份记录。")
        }
    }
}

@Composable
fun InterfaceScreen(vm:HarborViewModel,data:HarborUiData) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("界面",vm)
        Column(Modifier.padding(20.dp)) {
            SectionLabel("语言")
            listOf("system" to "跟随系统","zh-Hans" to "简体中文","en" to "English","yue-Hant-HK" to "粵語（香港）").forEach{(id,title)->
                Row { RadioButton(selected=data.settings.language==id,onClick={vm.setString("language",id)});TextButton(onClick={vm.setString("language",id)}){LText(title)} }
            }
            SectionLabel("外观")
            listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach{(id,title)->
                Row { RadioButton(selected=data.settings.theme==id,onClick={vm.setString("theme",id)});TextButton(onClick={vm.setString("theme",id)}){LText(title)} }
            }
        }
    }
}
