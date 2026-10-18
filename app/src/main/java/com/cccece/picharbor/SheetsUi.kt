@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.cccece.picharbor

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun Choice(label:String,checked:Boolean,description:String="",onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
        RadioButton(checked,onClick=onClick)
        Column(Modifier.weight(1f)){Text(label,style=MaterialTheme.typography.bodyLarge);if(description.isNotEmpty())Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
@Composable
fun HarborSheets(vm:HarborViewModel,data:HarborUiData,onLogin:()->Unit,onCleanup:()->Unit,onLocalDelete:()->Unit,onPickTree:()->Unit) {
    val context=LocalContext.current
    val accounts by vm.accounts.collectAsState()
    val mode=vm.sheet
    if(mode.isEmpty())return
    val cleanupNow by rememberCleanupClock()
    var input by remember(mode){mutableStateOf(if(mode=="time")data.settings.time else "")}
    var dateDialog by remember(mode){mutableStateOf(false)}
    val dates=rememberDateRangePickerState()
    if(dateDialog)DatePickerDialog(onDismissRequest={dateDialog=false},confirmButton={TextButton(onClick={
        fun localDate(value:Long)=Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate()
        vm.fromDate=dates.selectedStartDateMillis?.let{localDate(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()}?:0
        vm.toDate=dates.selectedEndDateMillis?.let{localDate(it).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()-1}?:Long.MAX_VALUE
        dateDialog=false;vm.sheet=""
    }){Text("应用")}},dismissButton={TextButton(onClick={dateDialog=false}){Text("取消")}}){DateRangePicker(dates,modifier=Modifier.heightIn(max=480.dp),showModeToggle=true)}
    val title=when {
        mode=="account"||mode=="login"->"备份账号"
        mode.startsWith("remove-account:")->"移除账号？"
        mode=="appearance"->"外观"
        mode=="quality"->"备份画质"
        mode=="album"->"相册归类"
        mode=="custom-album"->"自定义相册"
        mode=="albums"->"选择已有相册"
        mode=="filter"->"筛选照片"
        mode=="time"->"备份时间"
        mode=="sd"->"访问文件夹 / SD 卡"
        mode=="mobile"->"移动流量使用量"
        mode.startsWith("mobile-")->"本次使用移动流量？"
        mode=="background"->"让备份在后台继续"
        mode=="help"->"备份遇到问题？"
        mode=="about"->"关于 PicHarbor"
        mode=="concurrency"->"同时上传数量"
        mode=="timeout"->"连接超时"
        mode=="delete-local"->"从手机删除？"
        mode=="cleanup-confirm"->"可以安心释放空间"
        mode=="cleanup-list"->"核验结果"
        mode=="cleanup-result"->"清理结果"
        mode=="cleanup-speed"->"空间检查速度"
        mode=="cleanup-readers"->"同时读取文件数量"
        mode=="cleanup-retries"->"检查失败重试"
        mode=="cleanup-timing"->"检查耗时详情"
        mode.startsWith("job:")->"任务详情"
        mode.startsWith("end:")->"结束当前任务？"
        else->"PicHarbor"
    }
    ModalBottomSheet(onDismissRequest={vm.sheet=""},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=640.dp).verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Text(title,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f));IconButton(onClick={vm.sheet=""}){Icon(Icons.Outlined.Close,"关闭")}}
            when {
                mode=="account"||mode=="login"->{
                    Text("选择备份使用的账号",style=MaterialTheme.typography.bodyMedium)
                    accounts.forEach{account->
                        SettingsRow(account.email,if(account.email==data.settings.account)"当前备份账号" else if(account.needsLogin)"需要重新登录" else "点按快速切换",Icons.Outlined.AccountCircle,!vm.busy,
                            onClick={if(account.needsLogin){vm.sheet="";onLogin()}else if(account.email!=data.settings.account)vm.selectAccount(account.email)}) {
                            if(account.email==data.settings.account)Icon(Icons.Outlined.CheckCircle,"当前账号",tint=MaterialTheme.colorScheme.primary)
                            IconButton(onClick={vm.sheet="remove-account:${account.email}"},enabled=!vm.busy){Icon(Icons.Outlined.PersonRemove,"从设备移除此账号")}
                        }
                    }
                    Button(onClick={vm.switchAccount(onLogin)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Text("添加 Google 账号")}
                    if(data.settings.account.isNotEmpty())TextButton(onClick=vm::checkAccount,enabled=!vm.busy){Text("检查当前账号连接")}
                    Helper("每个账号分别保存登录凭据、备份范围与记录。自动备份只使用当前账号。",Icons.Outlined.Security)
                }
                mode.startsWith("remove-account:")->{
                    val account=mode.substringAfter(':')
                    Text("从此设备移除 $account？将清除该账号的登录凭据，保留云端照片和本地任务记录。")
                    Button(onClick={vm.removeAccount(account)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("移除账号")}
                    TextButton(onClick={vm.sheet="account"}){Text("保留")}
                }
                mode=="appearance"->{
                    Text("主题",style=MaterialTheme.typography.titleSmall)
                    listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach{(key,label)->Choice(label,data.settings.theme==key){vm.setString("theme",key)}}
                    HorizontalDivider();Text("主题颜色",style=MaterialTheme.typography.titleSmall)
                    listOf("system" to "系统动态配色","lavender" to "薰衣草","sage" to "鼠尾草","peach" to "暖杏").forEach{(key,label)->Choice(label,data.settings.palette==key){vm.setString("palette",key)}}
                }
                mode=="quality"->{
                    Choice("原始画质",data.settings.quality=="original","保留原始清晰度，使用当前免配额上传方式"){vm.setString("quality","original")}
                    Choice("节省空间",data.settings.quality=="saver","可能压缩照片和视频"){vm.setString("quality","saver")}
                    Choice("原始画质（计入配额）",data.settings.quality=="quota","使用 Google 账号存储空间"){vm.setString("quality","quota")}
                    Helper("存储空间是否计入配额，以 Google 相册实际处理结果为准。")
                }
                mode=="album"->{
                    listOf("none" to "不分类","folder" to "按来源文件夹").forEach{(key,label)->Choice(label,data.settings.albumMode==key){vm.album(key)}}
                    SettingsRow("自定义相册","为这份备份起个名字",Icons.Outlined.Edit,onClick={vm.sheet="custom-album"})
                    SettingsRow("加入已有相册","读取当前账号相册",Icons.Outlined.Collections,onClick=vm::albums)
                }
                mode=="custom-album"->{
                    OutlinedTextField(input,{input=it.take(100)},label={Text("相册名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={vm.album("custom",input.trim())},enabled=input.isNotBlank()&&!vm.busy,modifier=Modifier.fillMaxWidth()){Text("保存")}
                }
                mode=="albums"->{
                    if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(vm.albumChoices.isEmpty()&&!vm.busy)Text("暂未发现可用相册，可刷新后重试或使用自定义相册。")
                    vm.albumChoices.forEach{(id,name)->Choice(name,data.settings.albumMode=="existing"&&data.settings.albumValue==id){vm.album("existing",id)}}
                    TextButton(onClick=vm::albums,enabled=!vm.busy){Text("刷新相册")}
                }
                mode=="filter"->{
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("all" to "全部","photo" to "照片","video" to "视频").forEach{(key,label)->FilterChip(vm.type==key,{vm.type=key},{Text(label)})}}
                    Text("拍摄日期",style=MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick={dateDialog=true},modifier=Modifier.fillMaxWidth()){Text("选择日期范围")}
                    TextButton(onClick={vm.type="all";vm.fromDate=0;vm.toDate=Long.MAX_VALUE;vm.sheet=""}){Text("清除筛选")}
                }
                mode=="time"->{
                    Text("每天在这个时间附近检查并备份新增内容。")
                    val initial=runCatching{LocalTime.parse(data.settings.time)}.getOrDefault(LocalTime.of(2,0))
                    val timeState=rememberTimePickerState(initial.hour,initial.minute,true)
                    TimeInput(timeState,modifier=Modifier.align(Alignment.CenterHorizontally))
                    Helper("网络、充电与系统省电设置可能使执行时间延后。")
                    Button(onClick={vm.setString("time","%02d:%02d".format(java.util.Locale.ROOT,timeState.hour,timeState.minute));vm.sheet=""},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("保存")}

                }
                mode=="mobile"->{
                    listOf(Triple("none","不使用移动流量","连接 Wi-Fi 后再备份"),Triple("photos","仅照片","视频等待 Wi-Fi"),Triple("all","照片和视频","可能消耗较多移动流量")).forEach{(key,label,description)->Choice(label,data.settings.mobile==key,description){vm.setMobile(key)}}
                }
                mode.startsWith("mobile-")->{
                    Text("当前不是 Wi-Fi 连接。本次备份可能消耗移动流量，长期网络设置不会改变。")
                    Button(onClick={val ids=vm.pendingManualIds;vm.sheet="";vm.start(ids,true)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("本次使用移动流量")}
                    TextButton(onClick={vm.sheet=""}){Text("等待 Wi-Fi")}
                }
                mode=="sd"->{
                    Text("选择想备份的文件夹，只授权你需要的范围。")
                    listOf("打开系统文件夹选择器","选择手机或 SD 卡上的照片文件夹","点击“使用此文件夹”并允许访问").forEachIndexed{i,text->SettingsRow("${i+1}  $text",onClick={}){}}
                    Button(onClick={onPickTree();vm.sheet=""},modifier=Modifier.fillMaxWidth()){Text("选择文件夹")}
                }
                mode=="background"->{
                    Helper("允许通知和后台运行，有助于备份在锁屏后继续。不同手机的自启动设置名称可能不同。")
                    Button(onClick={runCatching{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}} ,modifier=Modifier.fillMaxWidth()){Text("打开应用设置")}
                    OutlinedButton(onClick={runCatching{context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}},modifier=Modifier.fillMaxWidth()){Text("查看电池优化设置")}
                }
                mode=="concurrency"->{(1..3).forEach{n->Choice(if(n==2)"2 个（默认）" else "$n 个",data.settings.concurrency==n){vm.setInteger("concurrency",n)}}}
                mode=="timeout"->{listOf(30,60,120).forEach{n->Choice("$n 秒",data.settings.timeout==n){vm.setInteger("timeout",n)}}}
                mode=="cleanup-speed"->{
                    listOf(Triple(1,"较省资源","最多同时检查 1 项"),Triple(2,"均衡（默认）","最多同时检查 2 项"),Triple(4,"较快","最多同时检查 4 项")).forEach{(n,label,description)->
                        Choice(label,data.settings.cleanupConcurrency==n,description){vm.setInteger("cleanupConcurrency",n);vm.sheet=""}
                    }
                    var maximum by remember{mutableFloatStateOf(data.settings.cleanupConcurrency.toFloat())}
                    Text("自定义：最多 ${maximum.toInt()} 项")
                    Slider(value=maximum,onValueChange={maximum=it},valueRange=1f..20f,steps=18)
                    Button(onClick={vm.setInteger("cleanupConcurrency",maximum.toInt());vm.sheet=""},modifier=Modifier.fillMaxWidth()){Text("应用自定义数量")}
                    Helper("较高数值可能增加耗电，并不一定更快。不省略核验，对下次检查生效。")
                    SettingsRow("同时读取文件数量","${data.settings.cleanupReaders} 个 · 避免同时读取大量视频",Icons.Outlined.Folder,onClick={vm.sheet="cleanup-readers"})
                    SettingsRow("检查失败重试","最多 ${data.settings.cleanupRetries} 次",Icons.Outlined.Refresh,onClick={vm.sheet="cleanup-retries"})
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("连接不稳定时自动降速",Modifier.weight(1f));Switch(data.settings.cleanupAdaptive,{vm.setBoolean("cleanupAdaptive",it)})}
                    Helper("网络恢复后逐步提高至设置上限；登录失效直接停止。")
                }
                mode=="cleanup-readers"->{(1..4).forEach{n->Choice("$n 个"+(if(n==2)"（默认）" else ""),data.settings.cleanupReaders==n){vm.setInteger("cleanupReaders",n);vm.sheet="cleanup-speed"}}}
                mode=="cleanup-retries"->{(0..3).forEach{n->Choice(if(n==0)"不重试" else "$n 次",data.settings.cleanupRetries==n){vm.setInteger("cleanupRetries",n);vm.sheet="cleanup-speed"}}}
                mode=="cleanup-timing"->{CheckTimingDetails(data.cleanup)}
                mode=="cleanup-confirm"->{
                    val ready=data.cleanups.filter{BackupPolicy.cleanupFresh(it,data.settings.account,cleanupNow)}.take(100)
                    Text("本次最多移除 ${ready.size} 个已核验的手机文件，释放 ${formatSize(ready.sumOf{it.bytes})}。")
                    Helper("Google 相册中的备份会保留。手机原件将被移除，其他本地图库可能不再显示；此清理操作可能无法从手机恢复。",Icons.Outlined.Security)
                    Button(onClick=onCleanup,enabled=ready.isNotEmpty()&&!vm.busy&&!data.cleanup.running,modifier=Modifier.fillMaxWidth()){Text("继续并确认清理")}
                    TextButton(onClick={vm.sheet=""}){Text("暂不清理")}
                }
                mode=="cleanup-list"->{
                    val results=data.cleanups.filter{it.id.startsWith("quick:")==(data.cleanup.mode=="quick")}
                    if(results.isEmpty())Text("先检查可释放空间，再查看核验结果。")
                    if(data.cleanup.mode=="quick")Helper("仅为备份状态查询，不能据此删除手机文件。")
                    results.take(150).forEach{record->val media=data.media.find{it.id==record.mediaId};SettingsRow(media?.name?:"已移除的手机文件",when(record.state){"quick_found"->"云端找到相同内容，清理前仍需核验";"ready"->if(BackupPolicy.cleanupFresh(record,data.settings.account,cleanupNow))"核验通过，等待确认" else "结果已过期，需要重新核验";"deleted"->"已从手机移除";"expired"->"需要重新核验";"cancelled"->"已取消，文件保留";else->Messages.error(record.reason)},Icons.Outlined.Security,onClick={}){}}
                    Helper("不完整的信息不会被当作清理通过的依据。")
                }
                mode=="cleanup-result"->{
                    EmptyState("清理操作已结束",vm.deleteSummary,Icons.Outlined.CloudDone)
                    Button(onClick={vm.sheet=""},modifier=Modifier.fillMaxWidth()){Text("完成")}
                    TextButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://photos.google.com/")))}}){Text("前往 Google 相册")}
                }
                mode=="delete-local"->{
                    val local=data.media.filter{it.id in vm.selected}
                    Text("将从手机处理 ${local.size.coerceAtMost(100)} 个项目，云端备份不受影响。")
                    if(local.any{!data.backed(it)})Text("其中有尚未确认备份的内容，删除后可能无法找回。",color=MaterialTheme.colorScheme.error)
                    Helper("支持的系统媒体会先移入手机回收站；其他文件夹中的文件可能直接移除。")
                    Button(onClick=onLocalDelete,enabled=local.isNotEmpty()&&!vm.busy,modifier=Modifier.fillMaxWidth()){Text("继续从手机删除")}
                    TextButton(onClick={vm.sheet=""}){Text("保留")}
                }
                mode.startsWith("job:")->{
                    val details by vm.jobDetails.collectAsState()
                    val items=details.filter{it.account==data.settings.account&&it.jobId==mode.substringAfter(':')}
                    items.take(vm.detailLimit).forEach{item->SettingsRow(item.name,when(item.state){"done"->"已上传";"exists"->"云端已有相同内容";"filename_skipped"->"仅同名跳过，内容待确认";"album_pending"->"内容已备份，相册归类待重试";"cancelled"->if(item.error=="PROMOTED")"已转入优先手动任务" else "已取消";"failed"->Messages.error(item.error);"queued"->"等待处理";else->phaseLabel(item.state)},onClick={}){}}
                    if(items.size>vm.detailLimit)TextButton(onClick={vm.detailLimit+=200}){Text("加载更多")}
                    if(items.isEmpty())Text("暂无项目明细。")
                }
                mode.startsWith("end:")->{
                    Text("保留已成功备份的内容与历史记录，结束尚未完成的部分。不会删除手机或云端照片。")
                    Button(onClick={vm.end(mode.substringAfter(':'));vm.sheet=""},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Text("结束任务")}
                }
                mode=="help"->{
                    SettingsRow("检查文件访问权限","只会显示已授权的内容",Icons.Outlined.PhotoLibrary,onClick={vm.sheet="background"})
                    SettingsRow("检查网络","确认网络与移动流量设置",Icons.Outlined.Wifi,onClick={vm.navigate("network")})
                    SettingsRow("后台备份中断","检查通知、省电和自启动设置",Icons.Outlined.BatteryChargingFull,onClick={vm.sheet="background"})
                    SettingsRow("账号连接异常","重新确认当前账号",Icons.Outlined.PersonOutline,onClick={vm.sheet="account"})
                }
                mode=="about"->{
                    EmptyState("PicHarbor","为珍贵的日常，留一份安心。",Icons.Outlined.CloudDone)
                    Text("版本 ${BuildConfig.VERSION_NAME} · 作者 cc_cece")
                    Helper("备份到 Google 相册，支持手动与自动备份、状态查看及核验后释放空间。不是 Google 官方客户端。")
                    Helper("本应用无自建业务服务器、无广告或分析上报。照片和必要认证数据直接发送至 Google 服务。")
                    TextButton(onClick={vm.sheet="licenses"}){Text("开源许可")}
                }
                mode=="licenses"->{
                    val notice=remember { context.assets.open("NOTICE.txt").bufferedReader().use{it.readText()} }
                    val license=remember { context.assets.open("LICENSE.txt").bufferedReader().use{it.readText()} }
                    Text(notice,style=MaterialTheme.typography.bodySmall);Text(license,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
