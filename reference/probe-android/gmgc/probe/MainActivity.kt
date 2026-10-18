package io.gmgc.probe

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val model: ProbeViewModel by viewModels()
    private var compareId: String? = null
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            val token = it.data?.getStringExtra("oauth_token")
            if (token != null) model.login(token) else model.loginFailed("MISSING_COOKIE")
        } else model.loginFailed(it.data?.getStringExtra("error"))
    }
    private val select = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.select(it) }
    private val compare = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = compareId
        compareId = null
        if (uri != null && id != null) model.compareOriginal(id, uri)
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            // Report contains only allowlisted observations, no account credentials.
            val snapshot = model.reportText
            exportReport(this, uri, snapshot)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compareId = savedInstanceState?.getString("compare_id")
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF23594B), secondary = Color(0xFF5D5A72),
                background = Color(0xFFF6F7F3), surface = Color.White
            )) {
                val view = LocalView.current
                DisposableEffect(model.busy) {
                    view.keepScreenOn = model.busy
                    onDispose { view.keepScreenOn = false }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("GMGC / ANDROID PROBE", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text("验证两件事。", style = MaterialTheme.typography.headlineLarge)
                        Text("手机自助登录 · 原画质不计配额", style = MaterialTheme.typography.titleMedium)
                        Text("上传成功后，还需独立核实云端原文件和空间占用。", style = MaterialTheme.typography.bodyMedium)
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("01  账号与凭据", style = MaterialTheme.typography.titleMedium)
                                Text(model.account.ifEmpty { "尚未连接 Google 账号" })
                                if (model.expiry > 0) Text("令牌到期：" + DateFormat.getDateTimeInstance().format(Date(model.expiry * 1000)),
                                    style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { login.launch(Intent(this@MainActivity, LoginActivity::class.java)) },
                                    enabled = model.engineReady && model.startupReady && !model.busy && model.account.isEmpty()) {
                                    Text("登录 Google")
                                }
                                if (model.account.isNotEmpty()) {
                                    OutlinedButton(onClick = model::checkToken, enabled = !model.busy) { Text("检查凭据 / 续期") }
                                    Text("实际过期后再次检查，才能验证自动续期。", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = model::logout, enabled = model.engineReady && model.startupReady && !model.busy) { Text("退出并清除本地会话") }
                            }
                        }
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("02  原画质上传", style = MaterialTheme.typography.titleMedium)
                                Text("使用未上传过的 JPEG、HEIC、MP4，最多 10 个。保持应用在前台。")
                                OutlinedButton(onClick = { select.launch(arrayOf("image/*", "video/*")) },
                                    enabled = model.engineReady && model.startupReady && !model.busy) { Text("选择媒体（${model.selectedCount}）") }
                                Button(onClick = model::upload, enabled = !model.busy && model.account.isNotEmpty() && model.selectedCount > 0) {
                                    Text("使用 gpmc 原画质模式上传")
                                }
                            }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE6EFE8))) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(model.status)
                            }
                        }
                        if (model.results.isNotEmpty()) {
                            Text("03  独立验证", style = MaterialTheme.typography.titleLarge)
                            Text("在官方 Google 相册打开对应条目查看备份详情，再下载“原始文件”进行核对。以下勾选仅记录人工观察。")
                            OutlinedButton(onClick = {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.google.com/"))) }
                                    .onFailure { Toast.makeText(this@MainActivity, "未找到可打开相册的浏览器", Toast.LENGTH_SHORT).show() }
                            }, enabled = !model.busy) { Text("打开官方 Google 相册") }
                            model.results.forEachIndexed { index, value ->
                                val row = JSONObject(value)
                                val id = row.getString("id")
                                Card {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("文件 ${index + 1} · ${row.optLong("size")} 字节", style = MaterialTheme.typography.titleMedium)
                                        Text(when (row.optString("upload")) {
                                            "submitted" -> "上传已提交；质量与配额待验证"
                                            "duplicate_excluded" -> "云端已存在，排除本次验收"
                                            "source_changed" -> "准备后的文件发生变化，未上传"
                                            "failed" -> "上传失败"
                                            else -> "未完成"
                                        })
                                        Text("SHA-256: ${row.optString("sha256")}", style = MaterialTheme.typography.bodySmall)
                                        if (row.optString("upload") == "submitted") {
                                            EvidenceCheck("官方详情显示原画质", row.optBoolean("official_original"), !model.busy) { model.attest(id, "official_original", it) }
                                            EvidenceCheck("官方详情明确显示不占空间", row.optBoolean("official_free"), !model.busy) { model.attest(id, "official_free", it) }
                                            EvidenceCheck("处理完成后再次确认不占空间", row.optBoolean("official_free_rechecked"), !model.busy && row.optBoolean("official_free")) { model.attest(id, "official_free_rechecked", it) }
                                            OutlinedButton(onClick = { compareId = id; compare.launch(arrayOf("*/*")) }, enabled = !model.busy) {
                                                Text("选择下载原始文件并比对")
                                            }
                                            Text(when (row.optString("roundtrip")) {
                                                "sha256_matched" -> "回下载 SHA-256 一致"
                                                "sha256_mismatch" -> "回下载 SHA-256 不一致"
                                                else -> "回下载原文件尚未验证"
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = { export.launch("gmgc-validation-${System.currentTimeMillis()}.json") },
                            enabled = !model.busy && model.startupReady) { Text("导出脱敏验证报告") }
                        Text("gpmc 源码原样复制。免配额由服务端判定；本地缓存的零配额不作为证据。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("compare_id", compareId)
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun EvidenceCheck(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row {
        Checkbox(checked = checked, onCheckedChange = change, enabled = enabled)
        Text(label, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
