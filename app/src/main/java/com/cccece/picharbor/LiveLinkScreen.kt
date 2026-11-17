package com.cccece.picharbor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LiveLinkScreen(vm:HarborViewModel,data:HarborUiData) {
    val link=vm.graph.computer
    val progress by link.progress.collectAsState()
    var host by rememberSaveable {mutableStateOf("")}
    var port by rememberSaveable {mutableStateOf("")}
    var fingerprint by rememberSaveable {mutableStateOf("")}
    var code by remember {mutableStateOf("")}
    var saved by remember {mutableStateOf(link.saved())}
    LaunchedEffect(progress.busy){if(!progress.busy)saved=link.saved()}
    LaunchedEffect(vm.scanResults){
        if(vm.scanPurpose=="computer"&&vm.scanResults.isNotEmpty()){
            val target=vm.scanResults.firstNotNullOfOrNull{runCatching{LinkWire.qr(it)}.getOrNull()}
            if(target==null)vm.say("这不是连接电脑的二维码。")
            else {host=target.host;port=target.port.toString();fingerprint=target.fingerprint;code=target.code}
            vm.clearScan()
        }
    }
    DisposableEffect(Unit){onDispose{link.stopSending()}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("传到这台电脑",vm)
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            saved?.let{target->
                LText("已保存的电脑",style=MaterialTheme.typography.titleMedium)
                LText("${target.host}:${target.port}")
                Row {
                    TextButton(onClick={host=target.host;port=target.port.toString();fingerprint=target.fingerprint;code=""},enabled=!progress.busy){LText("使用已保存的电脑")}
                    TextButton(onClick={link.forget();saved=null},enabled=!progress.busy){LText("忘记这台电脑")}
                }
            }
            Row {
                TextButton(onClick={vm.scanQr("computer",false)},enabled=!progress.busy){LText("扫描电脑二维码")}
                TextButton(onClick={vm.scanQr("computer",true)},enabled=!progress.busy){LText("从图片识别")}
            }
            OutlinedTextField(host,{host=it},label={LText("地址")},singleLine=true,enabled=!progress.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(port,{port=it},label={LText("端口")},singleLine=true,enabled=!progress.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(fingerprint,{fingerprint=it},label={LText("证书前 16 位或完整指纹")},singleLine=true,enabled=!progress.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(code,{code=it},label={LText("配对码")},singleLine=true,enabled=!progress.busy,modifier=Modifier.fillMaxWidth())
            LText("将发送当前照片范围，共 ${data.scope.size} 项，不需要 Google 账号。")
            Helper("仅在同一局域网内直接传输。发送前需要电脑确认；请保持应用在前台，锁屏后发送停止。")
            if(progress.busy){LinearProgressIndicator(progress={progress.fraction},modifier=Modifier.fillMaxWidth());Button(onClick=link::stopSending){LText("停止发送")}}
            else Button(onClick={runCatching{LinkWire.target(host,port,fingerprint,code)}.onSuccess(link::send).onFailure{vm.say("请检查地址、端口、证书指纹和配对码。")}},enabled=data.scope.isNotEmpty()){LText("开始发送")}
            if(progress.status.isNotBlank())LText(progress.status)
            if(progress.sent+progress.existing+progress.failed>0)LText("已发送 ${progress.sent} · 电脑已有 ${progress.existing} · 失败 ${progress.failed}")
        }
    }
}
