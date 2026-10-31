@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.cccece.picharbor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ComputerLinkScreen(vm:HarborViewModel) {
    val link=vm.graph.link
    val computers by link.computers.collectAsState()
    val progress by link.progress.collectAsState()
    val listening by link.listening.collectAsState()
    val hint by link.hint.collectAsState()
    var host by rememberSaveable{mutableStateOf("")}
    var port by rememberSaveable{mutableStateOf("")}
    var pin by rememberSaveable{mutableStateOf("")}
    var manualCode by rememberSaveable{mutableStateOf("")}
    var qr by rememberSaveable{mutableStateOf("")}
    var ask by remember{mutableStateOf<DiscoveredComputer?>(null)}
    var askCode by remember{mutableStateOf("")}
    DisposableEffect(Unit){link.startDiscovery();onDispose{link.stopDiscovery()}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("传到这台电脑",vm)
        Column(Modifier.padding(horizontal=20.dp)) {
            Text("把照片发到正在等待的电脑。",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(top=8.dp,bottom=12.dp))
            val count=vm.computerIds?.size
            Text(if(count==null)"将发送已读取的照片和视频，每份原文件只发一次。" else "将发送选中的 $count 项，每份原文件只发一次。",style=MaterialTheme.typography.bodyMedium)
            Helper("开始发送后可以关闭屏幕，手机会继续传输。请让电脑保持等待。")
            Helper("不需要登录 Google 账号。只在同一局域网发送，不经过互联网中转。")
            if(progress.running||progress.phase.isNotEmpty()) {
                Text(progress.phase,style=MaterialTheme.typography.titleMedium)
                if(progress.total>0)Text("${progress.done} / ${progress.total} · ${formatSize(progress.bytes)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                if(progress.error.isNotEmpty())Text(progress.error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=4.dp))
                if(progress.running)TextButton(onClick={link.cancel()}){Text("停止发送")}
            }
            Button(onClick={link.startDiscovery()},enabled=!progress.running,modifier=Modifier.fillMaxWidth()){Text(if(listening)"重新扫描" else "扫描局域网")}
            if(hint.isNotEmpty())Helper(hint)
            Text(if(computers.isEmpty()){if(listening)"正在寻找电脑…" else "还没有发现电脑。"} else "已发现的电脑",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp))
            if(computers.isNotEmpty())SettingGroup {
                computers.forEach { computer ->
                    val paired=link.paired(computer.fingerprint)
                    SettingsRow(computer.name,"${computer.host}:${computer.port}"+if(paired)" · 已配对" else " · 需要配对码",Icons.Outlined.Computer,enabled=!progress.running,onClick={
                        if(paired) {
                            if(link.launch(LinkTarget(computer.host,computer.port,computer.fingerprint,"",computer.name),vm.computerIds))vm.say("开始发送。可以关闭屏幕，电脑请保持等待。")
                        } else {ask=computer;askCode=""}
                    })
                }
            }
            SectionLabel("二维码")
            OutlinedTextField(qr,{qr=it},label={Text("粘贴二维码内容")},modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={
                val target=LinkProtocol.parseQr(qr)
                if(target==null)vm.say("二维码内容无法识别")
                else if(link.launch(target,vm.computerIds))vm.say("开始发送。可以关闭屏幕，电脑请保持等待。")
            },enabled=!progress.running,modifier=Modifier.fillMaxWidth().padding(top=8.dp)){Text("使用二维码发送")}
            SectionLabel("手动连接")
            OutlinedTextField(host,{host=it},label={Text("电脑地址")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(port,{port=it.filter{ch->ch.isDigit()}.take(5)},label={Text("端口")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(pin,{pin=it.filter{ch->ch.isDigit()||ch.lowercaseChar() in 'a'..'f'}.take(16)},label={Text("证书指纹前 16 位")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(manualCode,{manualCode=it.filter{ch->ch.isDigit()}.take(6)},label={Text("6 位配对码")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(onClick={
                val target=LinkProtocol.manual(host,port,pin,manualCode)
                if(target==null)vm.say("请填写地址、端口、16 位指纹和 6 位配对码")
                else if(link.launch(target,vm.computerIds))vm.say("开始发送。可以关闭屏幕，电脑请保持等待。")
            },enabled=!progress.running,modifier=Modifier.fillMaxWidth().padding(top=8.dp,bottom=24.dp)){Text("手动连接并发送")}
        }
    }
    val computer=ask
    if(computer!=null)AlertDialog(onDismissRequest={ask=null},title={Text(computer.name)},
        text={OutlinedTextField(askCode,{askCode=it.filter{ch->ch.isDigit()}.take(6)},label={Text("6 位配对码")},singleLine=true,modifier=Modifier.fillMaxWidth())},
        confirmButton={TextButton(onClick={
            if(link.launch(LinkTarget(computer.host,computer.port,computer.fingerprint,askCode,computer.name),vm.computerIds))vm.say("开始发送。可以关闭屏幕，电脑请保持等待。")
            ask=null
        },enabled=askCode.length==6&&!progress.running){Text("发送")}},
        dismissButton={TextButton(onClick={ask=null}){Text("取消")}})
}
