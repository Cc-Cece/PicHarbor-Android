@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.cccece.picharbor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ProxyScreen(vm:HarborViewModel,data:HarborUiData) {
    val proxy=vm.graph.proxy
    val library by proxy.library.collectAsState()
    val health by proxy.health.collectAsState()
    val testing by proxy.testing.collectAsState()
    val message by proxy.message.collectAsState()
    var name by remember {mutableStateOf("")}
    var url by remember {mutableStateOf("")}
    var text by remember {mutableStateOf("")}
    var imported by remember {mutableStateOf(false)}
    LaunchedEffect(vm.scanResults) {
        if(vm.scanPurpose=="proxy" && vm.scanResults.size==1) {url=vm.scanResults.first();vm.clearScan()}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("应用内代理",vm)
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            ToggleRow("仅代理 PicHarbor","仅用于本应用连接 Google，不影响其他应用",data.settings.proxyEnabled,enabled=!vm.busy){vm.setBoolean("proxyEnabled",it)}
            SectionLabel("配置与订阅")
            OutlinedTextField(name,{name=it},label={LText("配置名称")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(url,{url=it},label={LText("订阅地址")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            if(vm.scanPurpose=="proxy" && vm.scanResults.size>1)vm.scanResults.forEachIndexed{i,value->TextButton(onClick={url=value;vm.clearScan()}){LText("订阅 ${i+1}")}}
            Row {
                TextButton(onClick={vm.scanQr("proxy",false)}){LText("扫描二维码")}
                TextButton(onClick={vm.scanQr("proxy",true)}){LText("从图片识别")}
            }
            Button(onClick={vm.action{proxy.download(url,name);url="";text=""}},enabled=!vm.busy&&url.isNotBlank()){LText("下载并添加")}
            TextButton(onClick={imported=!imported}){LText("粘贴配置")}
            if(imported){
                OutlinedTextField(text,{text=it},label={LText("Clash YAML / JSON")},minLines=4,maxLines=10,modifier=Modifier.fillMaxWidth())
                Button(onClick={vm.action{proxy.importText(text,name);text="";imported=false}},enabled=!vm.busy&&text.isNotBlank()){LText("保存配置")}
            }
            val profiles=library.getJSONArray("profiles")
            for(i in 0 until profiles.length()) {
                val profile=profiles.getJSONObject(i)
                val active=library.optString("active")==profile.getString("id")
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    LText(profile.getString("name"),style=MaterialTheme.typography.titleMedium)
                    LText("${profile.getJSONArray("nodes").length()} 个节点${if(active)" · 正在使用" else ""}")
                    Row {
                        TextButton(onClick={vm.action{proxy.activate(profile.getString("id"))}},enabled=!vm.busy&&!active){LText("使用此配置")}
                        if(profile.optString("url").isNotBlank())TextButton(onClick={vm.action{proxy.download(profile.getString("url"),profile.getString("name"),profile.getString("id"))}},enabled=!vm.busy){LText("更新订阅")}
                        TextButton(onClick={vm.action{proxy.remove(profile.getString("id"))}},enabled=!vm.busy){LText("移除")}
                    }
                } }
            }
            SectionLabel("节点选择与切换")
            Row {
                RadioButton(library.optString("selection","automatic")=="automatic",onClick={vm.action{proxy.policy("selection","automatic")}});LText("自动")
                RadioButton(library.optString("selection")=="manual",onClick={vm.action{proxy.policy("selection","manual")}});LText("手动")
            }
            ToggleRow("所有节点不可用时允许直连","关闭时等待连接，不自动绕过代理",library.optString("exhaustion")=="direct",enabled=!vm.busy){vm.action{proxy.policy("exhaustion",if(it)"direct" else "wait")}}
            proxy.profile()?.getJSONArray("nodes")?.let { nodes ->
                for(i in 0 until nodes.length()) {
                    val nodeName=nodes.getJSONObject(i).getString("name");val h=health[nodeName]
                    Row {
                        RadioButton(library.optString("primary")==nodeName,onClick={vm.action{proxy.policy("primary",nodeName);proxy.policy("selection","manual")}})
                        Column {LText(nodeName);LText(if(h==null)"未测速" else if(!h.healthy)"不可用" else "${h.milliseconds} 毫秒",style=MaterialTheme.typography.bodySmall)}
                    }
                    if(library.optString("selection")=="manual"&&library.optString("primary")!=nodeName) {
                        val backups=library.optJSONArray("backups")?:org.json.JSONArray()
                        val names=(0 until backups.length()).map{backups.getString(it)}
                        Row {
                            Checkbox(nodeName in names,onCheckedChange={checked->vm.action{proxy.backups(if(checked)names+nodeName else names-nodeName)}})
                            LText("备用节点")
                        }
                    }
                }
            }
            if(testing) {LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick=proxy::cancelTest){LText("停止测速")}}
            else Button(onClick=proxy::testAll,enabled=proxy.profile()!=null&&!vm.busy){LText("测速全部节点")}
            if(message.isNotBlank())LText(message)
            Helper("测试访问 Google 的请求延时，并非上传速度。配置凭据保存在本机安全存储中。")
        }
    }
}

@Composable
fun TaskProxyScreen(vm:HarborViewModel,data:HarborUiData) {
    val id by vm.inspectedJob.collectAsState()
    val job=data.jobs.firstOrNull{it.id==id}
    val global by vm.graph.proxy.library.collectAsState()
    val saved=remember(job?.proxyPolicy){runCatching{org.json.JSONObject(job?.proxyPolicy.orEmpty())}.getOrDefault(org.json.JSONObject())}
    var follow by remember(job?.id){mutableStateOf(job?.proxyPolicy.isNullOrBlank())}
    var automatic by remember(job?.id){mutableStateOf(saved.optString("selection","automatic")=="automatic")}
    var primary by remember(job?.id){mutableStateOf(saved.optString("primary",global.optString("primary")))}
    var direct by remember(job?.id){mutableStateOf(saved.optString("exhaustion","wait")=="direct")}
    var backups:List<String> by remember(job?.id){mutableStateOf<List<String>>(saved.optJSONArray("backups")?.let{array->(0 until array.length()).map{array.getString(it)}}?:emptyList())}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("任务连接策略",vm)
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            ToggleRow("跟随全局","关闭后仅更改此任务",follow,enabled=!vm.busy){follow=it}
            if(!follow) {
                ToggleRow("自动选择节点","保持正常连接，失败时切换",automatic,enabled=!vm.busy){automatic=it}
                if(!automatic)vm.graph.proxy.profile()?.getJSONArray("nodes")?.let{nodes->
                    for(i in 0 until nodes.length()) {
                        val name=nodes.getJSONObject(i).getString("name")
                        Row {RadioButton(primary==name,onClick={primary=name});LText(name)}
                        if(primary!=name)Row{Checkbox(name in backups,onCheckedChange={checked->backups=if(checked)backups+name else backups-name});LText("备用节点")}
                    }
                }
                ToggleRow("所有节点不可用时允许直连","关闭时等待连接",direct,enabled=!vm.busy){direct=it}
            }
            Button(onClick={
                val policy=if(follow)"" else org.json.JSONObject().put("selection",if(automatic)"automatic" else "manual").put("primary",primary)
                    .put("backups",org.json.JSONArray(backups.filter{it!=primary})).put("exhaustion",if(direct)"direct" else "wait").toString()
                vm.taskProxy(policy)
            },enabled=job!=null&&!vm.busy){LText("保存")}
            Helper("只用于此任务，不会改变全局设置。修改后重新连接，手动暂停的任务保持暂停。")
        }
    }
}
