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
        Column(Modifier.weight(1f)){LText(label,style=MaterialTheme.typography.bodyLarge);if(description.isNotEmpty())LText(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
@Composable
fun HarborSheets(vm:HarborViewModel,data:HarborUiData,onLogin:()->Unit,onPickTree:()->Unit) {
    val context=LocalContext.current
    val accounts by vm.accounts.collectAsState()
    val mode=vm.sheet
    if(mode.isEmpty())return
    var input by remember(mode){mutableStateOf(if(mode=="time")data.settings.time else "")}
    var proxyEnabled by remember(mode){mutableStateOf(data.settings.proxyEnabled)}
    var proxyScheme by remember(mode){mutableStateOf(data.settings.proxyScheme)}
    var proxyHost by remember(mode){mutableStateOf(data.settings.proxyHost)}
    var proxyPort by remember(mode){mutableStateOf(if(data.settings.proxyPort>0)data.settings.proxyPort.toString() else "")}
    var proxyUser by remember(mode){mutableStateOf(data.settings.proxyUser)}
    var proxyPassword by remember(mode){mutableStateOf("")}
    var dateDialog by remember(mode){mutableStateOf(false)}
    val dates=rememberDateRangePickerState()
    if(dateDialog)DatePickerDialog(onDismissRequest={dateDialog=false},confirmButton={TextButton(onClick={
        fun localDate(value:Long)=Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate()
        vm.fromDate=dates.selectedStartDateMillis?.let{localDate(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()}?:0
        vm.toDate=dates.selectedEndDateMillis?.let{localDate(it).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()-1}?:Long.MAX_VALUE
        dateDialog=false;vm.sheet=""
    }){LText("应用")}},dismissButton={TextButton(onClick={dateDialog=false}){LText("取消")}}){DateRangePicker(dates,modifier=Modifier.heightIn(max=480.dp),showModeToggle=true)}
    val title=when {
        mode=="account"||mode=="login"->"备份账号"
        mode.startsWith("remove-account:")->"移除账号？"
        mode=="appearance"->"外观"
        mode=="quality"->"备份画质"
        mode=="live-photo"->"实况照片配对"
        mode=="proxy"->"代理"
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
        mode.startsWith("job:")->"任务详情"
        mode.startsWith("end:")->"结束当前任务？"
        else->"PicHarbor"
    }
    ModalBottomSheet(onDismissRequest={vm.sheet=""},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=640.dp).verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){LText(title,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f));IconButton(onClick={vm.sheet=""}){Icon(Icons.Outlined.Close,"关闭")}}
            when {
                mode=="account"||mode=="login"->{
                    LText("选择备份使用的账号",style=MaterialTheme.typography.bodyMedium)
                    accounts.forEach{account->
                        val choose:()->Unit={if(account.needsLogin){vm.sheet="";onLogin()}else if(account.email!=data.settings.account)vm.selectAccount(account.email)}
                        Surface(onClick=choose,enabled=!vm.busy,color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Avatar(account.email,choose)
                                Column(Modifier.weight(1f)) {
                                    LText(account.displayName.ifBlank{account.email.substringBefore('@')},style=MaterialTheme.typography.bodyLarge,maxLines=1)
                                    LText(account.email,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    LText(if(account.email==data.settings.account)"当前备份账号" else if(account.needsLogin)"需要重新登录" else "点按快速切换",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if(account.email==data.settings.account)Icon(Icons.Outlined.CheckCircle,"当前账号",tint=MaterialTheme.colorScheme.primary)
                                IconButton(onClick={vm.sheet="remove-account:${account.email}"},enabled=!vm.busy){Icon(Icons.Outlined.PersonRemove,"从设备移除此账号")}
                            }
                        }
                    }
                    Button(onClick={vm.switchAccount(onLogin)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);LText("添加 Google 账号")}
                    if(data.settings.account.isNotEmpty())TextButton(onClick=vm::checkAccount,enabled=!vm.busy){LText("检查当前账号连接")}
                    Helper("每个账号分别保存登录凭据、备份范围与记录。自动备份只使用当前账号。",Icons.Outlined.Security)
                }
                mode.startsWith("remove-account:")->{
                    val account=mode.substringAfter(':')
                    LText("从此设备移除 $account？将清除该账号的登录凭据，保留云端照片和本地任务记录。")
                    Button(onClick={vm.removeAccount(account)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){LText("移除账号")}
                    TextButton(onClick={vm.sheet="account"}){LText("保留")}
                }
                mode=="appearance"->{
                    LText("主题",style=MaterialTheme.typography.titleSmall)
                    listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach{(key,label)->Choice(label,data.settings.theme==key){vm.setString("theme",key)}}
                    HorizontalDivider();LText("主题颜色",style=MaterialTheme.typography.titleSmall)
                    listOf("system" to "系统动态配色","lavender" to "薰衣草","sage" to "鼠尾草","peach" to "暖杏").forEach{(key,label)->Choice(label,data.settings.palette==key){vm.setString("palette",key)}}
                }
                mode=="quality"->{
                    Choice("原始画质",data.settings.quality=="original","保留原始清晰度，使用当前免配额上传方式"){vm.setString("quality","original")}
                    Choice("节省空间",data.settings.quality=="saver","可能压缩照片和视频"){vm.setString("quality","saver")}
                    Choice("原始画质（计入配额）",data.settings.quality=="quota","使用 Google 账号存储空间"){vm.setString("quality","quota")}
                    Helper("存储空间是否计入配额，以 Google 相册实际处理结果为准。")
                }
                mode=="live-photo"->{
                    Choice("原始照片配对（分步上传）",data.settings.livePhotoPairing=="stepwise","先保存照片，再关联动态视频"){vm.setString("livePhotoPairing","stepwise")}
                    Choice("原始照片配对（联合提交）",data.settings.livePhotoPairing=="original","照片与视频配对，按一个媒体项目备份并一次确认"){vm.setString("livePhotoPairing","original")}
                    Choice("Motion Photo（单文件）",data.settings.livePhotoPairing=="motion","单个文件内嵌动态视频，按单文件备份"){vm.setString("livePhotoPairing","motion")}
                    Helper("已有设置不会被升级覆盖；未确认的实况照片只备份、不清理本机原件。")
                }
                mode=="proxy"->{
                    Row(verticalAlignment=Alignment.CenterVertically){LText("启用代理",modifier=Modifier.weight(1f));Switch(proxyEnabled,{proxyEnabled=it},enabled=!vm.busy)}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("http" to "HTTP","https" to "HTTPS").forEach{(key,label)->FilterChip(proxyScheme==key,{proxyScheme=key},{LText(label)},enabled=proxyEnabled&&!vm.busy)}}
                    OutlinedTextField(proxyHost,{proxyHost=it.take(253)},label={LText("代理地址")},singleLine=true,enabled=proxyEnabled&&!vm.busy,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(proxyPort,{proxyPort=it.filter{c->c.isDigit()}.take(5)},label={LText("端口")},singleLine=true,enabled=proxyEnabled&&!vm.busy,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(proxyUser,{proxyUser=it.take(128)},label={LText("账号（可选）")},singleLine=true,enabled=proxyEnabled&&!vm.busy,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(proxyPassword,{proxyPassword=it.take(256)},label={LText("密码（可选，留空不改）")},singleLine=true,enabled=proxyEnabled&&!vm.busy,modifier=Modifier.fillMaxWidth(),visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Helper("默认关闭。密码只保存在本机安全存储，不会进入共享核心或日志；TLS 校验始终保留。")
                    Button(onClick={vm.setProxy(proxyEnabled,proxyScheme,proxyHost,proxyPort.toIntOrNull()?:0,proxyUser,proxyPassword)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){LText("保存")}
                }
                mode=="album"->{
                    listOf("none" to "不分类","folder" to "按来源文件夹").forEach{(key,label)->Choice(label,data.settings.albumMode==key){vm.album(key)}}
                    SettingsRow("自定义相册","为这份备份起个名字",Icons.Outlined.Edit,onClick={vm.sheet="custom-album"})
                    SettingsRow("加入已有相册","读取当前账号相册",Icons.Outlined.Collections,onClick=vm::albums)
                }
                mode=="custom-album"->{
                    OutlinedTextField(input,{input=it.take(100)},label={LText("相册名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={vm.album("custom",input.trim())},enabled=input.isNotBlank()&&!vm.busy,modifier=Modifier.fillMaxWidth()){LText("保存")}
                }
                mode=="albums"->{
                    if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(vm.albumChoices.isEmpty()&&!vm.busy)LText("暂未发现可用相册，可刷新后重试或使用自定义相册。")
                    vm.albumChoices.forEach{(id,name)->Choice(name,data.settings.albumMode=="existing"&&data.settings.albumValue==id){vm.album("existing",id)}}
                    TextButton(onClick=vm::albums,enabled=!vm.busy){LText("刷新相册")}
                }
                mode=="filter"->{
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("all" to "全部","photo" to "照片","video" to "视频","live" to "实况").forEach{(key,label)->FilterChip(vm.type==key,{vm.type=key},{LText(label)})}}
                    LText("拍摄日期",style=MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick={dateDialog=true},modifier=Modifier.fillMaxWidth()){LText("选择日期范围")}
                    TextButton(onClick={vm.type="all";vm.fromDate=0;vm.toDate=Long.MAX_VALUE;vm.sheet=""}){LText("清除筛选")}
                }
                mode=="time"->{
                    LText("每天在这个时间附近检查并备份新增内容。")
                    val initial=runCatching{LocalTime.parse(data.settings.time)}.getOrDefault(LocalTime.of(2,0))
                    val timeState=rememberTimePickerState(initial.hour,initial.minute,true)
                    TimeInput(timeState,modifier=Modifier.align(Alignment.CenterHorizontally))
                    Helper("网络、充电与系统省电设置可能使执行时间延后。")
                    Button(onClick={vm.setString("time","%02d:%02d".format(java.util.Locale.ROOT,timeState.hour,timeState.minute));vm.sheet=""},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){LText("保存")}

                }
                mode=="mobile"->{
                    listOf(Triple("none","不使用移动流量","连接 Wi-Fi 后再备份"),Triple("photos","仅照片","视频等待 Wi-Fi"),Triple("all","照片和视频","可能消耗较多移动流量")).forEach{(key,label,description)->Choice(label,data.settings.mobile==key,description){vm.setMobile(key)}}
                }
                mode.startsWith("mobile-")->{
                    LText("当前不是 Wi-Fi 连接。本次备份可能消耗移动流量，长期网络设置不会改变。")
                    Button(onClick={val ids=vm.pendingManualIds;vm.sheet="";vm.start(ids,true)},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){LText("本次使用移动流量")}
                    TextButton(onClick={vm.sheet=""}){LText("等待 Wi-Fi")}
                }
                mode=="sd"->{
                    LText("选择想备份的文件夹，只授权你需要的范围。")
                    listOf("打开系统文件夹选择器","选择手机或 SD 卡上的照片文件夹","点击“使用此文件夹”并允许访问").forEachIndexed{i,text->SettingsRow("${i+1}  $text",onClick={}){}}
                    Button(onClick={onPickTree();vm.sheet=""},modifier=Modifier.fillMaxWidth()){LText("选择文件夹")}
                }
                mode=="background"->{
                    Helper("允许通知和后台运行，有助于备份在锁屏后继续。不同手机的自启动设置名称可能不同。")
                    Button(onClick={runCatching{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}} ,modifier=Modifier.fillMaxWidth()){LText("打开应用设置")}
                    OutlinedButton(onClick={runCatching{context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}},modifier=Modifier.fillMaxWidth()){LText("查看电池优化设置")}
                }
                mode=="concurrency"->{(1..3).forEach{n->Choice(if(n==2)"2 个（默认）" else "$n 个",data.settings.concurrency==n){vm.setInteger("concurrency",n)}}}
                mode=="timeout"->{listOf(30,60,120).forEach{n->Choice("$n 秒",data.settings.timeout==n){vm.setInteger("timeout",n)}}}
                mode.startsWith("end:")->{
                    LText("保留已成功备份的内容与历史记录，结束尚未完成的部分。不会删除手机或云端照片。")
                    Button(onClick={vm.end(mode.substringAfter(':'));vm.sheet=""},enabled=!vm.busy,modifier=Modifier.fillMaxWidth()){LText("结束任务")}
                }
                mode=="help"->{
                    SettingsRow("检查文件访问权限","只会显示已授权的内容",Icons.Outlined.PhotoLibrary,onClick={vm.sheet="background"})
                    SettingsRow("检查网络","确认网络与移动流量设置",Icons.Outlined.Wifi,onClick={vm.navigate("network")})
                    SettingsRow("后台备份中断","检查通知、省电和自启动设置",Icons.Outlined.BatteryChargingFull,onClick={vm.sheet="background"})
                    SettingsRow("账号连接异常","重新确认当前账号",Icons.Outlined.PersonOutline,onClick={vm.sheet="account"})
                }
                mode=="about"->{
                    EmptyState("PicHarbor","为珍贵的日常，留一份安心。",Icons.Outlined.CloudDone)
                    LText("版本 ${BuildConfig.VERSION_NAME} · 作者 cc_cece")
                    Helper("备份到 Google 相册，支持手动与自动备份、状态查看；释放空间请使用 Google 相册官方功能。不是 Google 官方客户端。")
                    Helper("本应用无自建业务服务器、无广告或分析上报。照片和必要认证数据直接发送至 Google 服务。")
                    TextButton(onClick={vm.sheet="licenses"}){LText("开源许可")}
                }
                mode=="licenses"->{
                    val notice=remember { context.assets.open("NOTICE.txt").bufferedReader().use{it.readText()} }
                    val license=remember { context.assets.open("LICENSE.txt").bufferedReader().use{it.readText()} }
                    LText(notice,style=MaterialTheme.typography.bodySmall);LText(license,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
