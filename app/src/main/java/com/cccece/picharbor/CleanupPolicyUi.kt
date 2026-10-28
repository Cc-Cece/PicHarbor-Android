package com.cccece.picharbor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CleanupPolicyNotice(policy:CleanupPolicy) {
    if(policy.complete)Helper("完整核验：内容匹配、最新云端状态与原件可访问性。检查通过后仍需你确认清理。",Icons.Outlined.Security)
    else Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().padding(vertical=8.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("已降低核验强度",style=MaterialTheme.typography.titleSmall)
            policy.skipped.forEach{Text("未核验：$it",style=MaterialTheme.typography.bodySmall)}
            Text("基础内容匹配不证明云端原件完整可用或符合原画质要求。",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun CleanupPolicyScreen(vm:HarborViewModel,data:HarborUiData) {
    val policy=data.settings.cleanupPolicy
    val enabled=!vm.busy&&!data.cleanup.running&&vm.pendingDelete.isEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("空间检查流程",vm)
        Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            CleanupPolicyNotice(policy)
            Text("基础检查始终开启",style=MaterialTheme.typography.titleMedium)
            Text("当前账号验证、读取文件并计算内容哈希、服务端内容匹配、删除前本地变化检查，以及用户和系统确认。")
            Text("额外核验",style=MaterialTheme.typography.titleMedium)
            SettingGroup {
                ToggleRow("最新云端状态核验","回收站、处理状态、原始画质与大小；需要同步云端列表",policy.cloudState,Icons.Outlined.CloudDone,enabled){vm.requestCleanupPolicy(policy.copy(cloudState=it))}
                ToggleRow("云端原件访问核验","获取原件地址，检查可访问性与大小，不完整下载",policy.originalAccess,Icons.Outlined.VerifiedUser,enabled){vm.requestCleanupPolicy(policy.copy(originalAccess=it))}
            }
            Helper("关闭任一项需输入风险确认语。变更流程会使已有清理资格失效，对下一次检查生效。已开启的检查失败时，文件仍会保留，不会自动跳过。")
            if(!enabled)Helper("请先结束正在进行的检查或清理操作，再调整流程。")
            if(!policy.complete)Button(onClick={vm.requestCleanupPolicy(CleanupPolicy())},enabled=enabled,modifier=Modifier.fillMaxWidth()){Text("恢复完整核验")}
            Spacer(Modifier.height(24.dp))
        }
    }
}
