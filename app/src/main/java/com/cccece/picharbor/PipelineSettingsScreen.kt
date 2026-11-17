package com.cccece.picharbor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun PipelineSettingsScreen(vm:HarborViewModel,data:HarborUiData) {
    val keys=listOf("preparation","lookup","upload","commit","confirmation","buffer_mb")
    val titles=listOf("准备照片","上传检查","文件上传","保存与配对","云端确认","缓存上限（MB）")
    val ranges=listOf(1..3,1..4,1..12,1..4,1..4,128..8192)
    val defaults=listOf(1,2,4,2,1,512)
    val saved=remember(data.settings.pipelineLimits){runCatching{JSONObject(data.settings.pipelineLimits)}.getOrDefault(JSONObject())}
    var custom by remember{mutableStateOf(data.settings.pipelineMode=="custom")}
    val fields=remember{mutableStateListOf<String>().apply{keys.forEachIndexed{i,key->add(saved.optInt(key,defaults[i]).toString())}}}
    val executionKeys=listOf("retries","query_seconds","commit_seconds","idle_seconds","file_minutes","cloud_minutes")
    val executionTitles=listOf("自动重试次数","查询超时（秒）","提交超时（秒）","无进展超时（秒）","文件处理上限（分钟）","云端等待上限（分钟）")
    val executionRanges=listOf(0..10,15..300,15..600,30..1800,10..1440,10..240)
    val executionDefaults=listOf(3,60,90,120,120,60)
    val execution=remember{runCatching{JSONObject(data.settings.executionPolicy)}.getOrDefault(JSONObject())}
    var customProtection by remember{mutableStateOf(execution.length()>0)}
    val protection=remember{mutableStateListOf<String>().apply{executionKeys.forEachIndexed{i,key->add(execution.optInt(key,executionDefaults[i]).toString())}}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("高级配置",vm)
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            ToggleRow("使用自定义配置","自动模式根据设备资源调整",custom,enabled=!vm.busy){custom=it}
            if(custom)keys.forEachIndexed{i,_->OutlinedTextField(fields[i],{fields[i]=it},label={LText(titles[i])},singleLine=true,isError=fields[i].toIntOrNull() !in ranges[i],modifier=Modifier.fillMaxWidth())}
            Helper("准备照片 1–3，上传检查、保存与配对、云端确认 1–4，文件上传 1–12，缓存 128–8192 MB。")
            Helper("所有模式保留内存、温度和可用空间保护。配置将在下一批任务开始时应用，不中断正在上传的文件。")
            ToggleRow("自定义任务保护","限制等待和重试，不改变已确认的备份",customProtection,enabled=!vm.busy){customProtection=it}
            if(customProtection)executionKeys.forEachIndexed{i,_->OutlinedTextField(protection[i],{protection[i]=it},label={LText(executionTitles[i])},singleLine=true,isError=protection[i].toIntOrNull() !in executionRanges[i],modifier=Modifier.fillMaxWidth())}
            Button(onClick={
                val value=JSONObject();keys.forEachIndexed{i,key->value.put(key,fields[i].toIntOrNull()?:defaults[i])}
                val policy=JSONObject();executionKeys.forEachIndexed{i,key->policy.put(key,protection[i].toIntOrNull()?:executionDefaults[i])}
                vm.savePipeline(if(custom)"custom" else "automatic",value.toString(),if(customProtection)policy.toString() else "")
            },enabled=!vm.busy&&(!custom||fields.indices.all{fields[it].toIntOrNull() in ranges[it]})&&(!customProtection||protection.indices.all{protection[it].toIntOrNull() in executionRanges[it]})){LText("保存")}
        }
    }
}
