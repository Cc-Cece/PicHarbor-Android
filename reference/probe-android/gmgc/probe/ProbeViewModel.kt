package io.gmgc.probe

import android.app.Application
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.util.AtomicFile

class ProgressSink(private val accept: (String) -> Unit) {
    @Suppress("unused") fun onProgress(json: String) = accept(json)
}

class ProbeViewModel(private val app: Application) : AndroidViewModel(app) {
    var busy by mutableStateOf(false); private set
    var account by mutableStateOf(""); private set
    var status by mutableStateOf("正在初始化"); private set
    var selectedCount by mutableIntStateOf(0); private set
    var expiry by mutableLongStateOf(0L); private set
    var reportText by mutableStateOf(""); private set
    var results by mutableStateOf(listOf<String>()); private set
    var startupReady by mutableStateOf(false); private set
    private var selected = JSONArray()
    private val credentials = CredentialStore(app)
    private val reportFile = AtomicFile(File(app.filesDir, "validation-report.json"))
    private var report = JSONObject()
    private val engine get() = Python.getInstance().getModule("android_probe")
    val engineReady get() = (app as ProbeApplication).engineReady

    init {
        report = try { JSONObject(reportFile.openRead().bufferedReader().use { it.readText() }) }
        catch (_: Exception) { JSONObject() }
        report.put("app_version", BuildConfig.VERSION_NAME).put("android_api", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("engine_runtime", if (engineReady) "imported" else "blocked")
            .put("overall", "unverified")
        if (!engineReady) report.put("engine_error", (app as ProbeApplication).engineError)
        try {
            report.put("engine_baseline", JSONObject(app.assets.open("engine-baseline.json").bufferedReader().use { it.readText() }))
        } catch (_: Exception) { report.put("baseline", "missing") }
        report.optJSONObject("upload_result")?.optJSONArray("files")?.let { rows ->
            results = (0 until rows.length()).map { rows.getJSONObject(it).toString() }
        }
        refreshReport()
        if (!engineReady) { status = "引擎初始化失败，需检查兼容性；未修改 gpmc。"; startupReady = true }
        else runOperation("检查已保存凭据") {
            // A debug-only, run-as injected file supports diagnostic isolation.
            val diagnostic = File(app.filesDir, "diagnostic-auth.txt")
            try {
                val injected = if (BuildConfig.DEBUG && diagnostic.exists()) {
                    require(diagnostic.length() in 16..16384)
                    diagnostic.readText().trim()
                } else null
                val saved = injected ?: credentials.load()
                if (saved != null) authenticate(saved, if (injected != null) "diagnostic" else credentials.method(), injected == null)
                else status = "请登录 Google 账号。"
            } finally {
                if (BuildConfig.DEBUG) diagnostic.delete()
                startupReady = true
            }
        }
    }

    private fun runOperation(label: String, action: suspend () -> Unit) {
        if (busy || !engineReady) return
        busy = true; status = label
        viewModelScope.launch {
            try { action() }
            catch (_: Exception) { status = "操作失败（存储或运行环境错误），请重试。"; report.put("last_error", "PLATFORM_ERROR") }
            finally { busy = false; startupReady = true; refreshReport() }
        }
    }
    private suspend fun call(name: String, vararg args: Any): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(engine.callAttr(name, *args).toString())
    }
    private fun check(result: JSONObject): Boolean {
        if (result.optBoolean("ok")) return true
        val code = result.optString("error", "ENGINE_ERROR")
        report.put("last_error", code)
        status = when (code) {
            "LOGIN_REQUIRED", "CREDENTIAL_REJECTED" -> "凭据无效或需要重新登录。"
            "DEVICE_VERIFICATION_REQUIRED" -> "Google 要求额外设备验证，当前认证链路阻塞。"
            "ENCRYPTED_TOKEN_UNSUPPORTED" -> "Google 返回加密令牌，原样 gpmc 尚不支持，认证阻塞。"
            "NETWORK_OR_IO_ERROR" -> "网络或文件访问失败，请检查后重试。"
            else -> "引擎调用失败（$code），未确认操作成功。"
        }
        return false
    }
    private suspend fun authenticate(value: String, method: String, restoring: Boolean = false) {
        if (!restoring) {
            report.remove("credential_reuse"); report.remove("token_refresh"); report.remove("token_check")
            report.remove("upload_result"); results = emptyList()
        }
        val result = call("authenticate", value)
        if (!check(result)) {
            account = ""
            report.put("authentication", "blocked").put("auth_error", result.optString("error"))
            report.remove("credential_reuse"); report.remove("token_refresh")
            return
        }
        withContext(Dispatchers.IO) { credentials.save(result.getString("credential"), method) }
        account = result.optString("account", "Google Account")
        expiry = result.optLong("expiry")
        report.put("authentication", if (method == "webview") "self_service_authenticated" else "diagnostic_only")
            .put("auth_method", method).put("expiry", expiry)
        if (restoring && method == "webview") report.put("credential_reuse", "passed")
        status = if (method == "webview") "账号连接成功。上传结果的原画质与配额仍需验证。" else "诊断凭据有效；不计入自助登录验收。"
    }
    fun login(token: String) = runOperation("正在交换并验证凭据") { authenticate(token, "webview") }
    fun loginFailed(code: String?) {
        status = if (code == null) "已取消登录。" else "Google 拒绝 WebView 登录环境，自助认证阻塞。"
        report.put("authentication", if (code == null) "cancelled" else "blocked")
            .put("auth_error", code ?: "LOGIN_CANCELLED")
        report.remove("credential_reuse"); report.remove("token_refresh")
        refreshReport()
    }
    fun checkToken() = runOperation("检查令牌与实际续期") {
        val result = call("check_token")
        if (check(result)) {
            expiry = result.optLong("expiry")
            report.put("token_check", result)
            if (result.optBoolean("actual_expiry_refresh_observed")) {
                report.put("token_refresh", "passed")
                status = "已观察到实际过期后的令牌续期。"
            } else status = "令牌有效，尚未观察到实际过期后的续期。"
        }
    }
    fun select(uris: List<Uri>) {
        if (uris.isEmpty()) return
        runOperation("准备媒体文件，保持应用在前台") {
            selected = JSONArray(); selectedCount = 0
            val prepared = withContext(Dispatchers.IO) {
                val directory = File(app.filesDir, "runtime/media")
                directory.listFiles()?.forEach { it.delete() }
                val array = JSONArray()
                try { uris.take(10).forEach { array.put(MediaFiles.prepare(app, it)) }; array }
                catch (exc: Exception) { directory.listFiles()?.forEach { it.delete() }; throw exc }
            }
            selected = prepared; selectedCount = prepared.length()
            status = "已准备 $selectedCount 个媒体文件（最多 10 个），未压缩或转码。"
        }
    }
    fun upload() = runOperation("开始上传，请保持应用在前台") {
        require(selected.length() > 0 && account.isNotEmpty())
        // Discard previous evidence before beginning a new batch.
        report.remove("upload_result"); results = emptyList()
        val result = call("upload", selected.toString(), ProgressSink { value ->
            val event = JSONObject(value)
            val stage = when (event.optString("phase")) {
                "hashing" -> "计算哈希"; "uploading" -> "上传中"; "finalizing" -> "提交中"
                "complete" -> "文件处理完成"; "retrying" -> "自动重试"; else -> "处理媒体"
            }
            viewModelScope.launch(Dispatchers.Main) {
                status = "$stage ${event.optLong("bytes_completed")} / ${event.optLong("bytes_total")} 字节"
            }
        })
        report.put("upload_result", result)
        val rows = result.optJSONArray("files") ?: JSONArray()
        results = (0 until rows.length()).map { rows.getJSONObject(it).toString() }
        if (check(result)) status = "批次处理结束，请逐项核实上传结果。原画质与配额尚未证实。"
    }
    fun attest(id: String, field: String, value: Boolean) {
        if (busy || field !in setOf("official_original", "official_free", "official_free_rechecked")) return
        val rows = report.optJSONObject("upload_result")?.optJSONArray("files") ?: return
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("id") == id && row.optString("upload") == "submitted") {
                row.put(field, value).put("manual_evidence_at", System.currentTimeMillis())
                if (value) row.put("${field}_observed_at", System.currentTimeMillis())
                else row.remove("${field}_observed_at")
                if (field == "official_free" && !value) row.put("official_free_rechecked", false)
                break
            }
        }
        results = (0 until rows.length()).map { rows.getJSONObject(it).toString() }
        refreshReport()
    }
    fun compareOriginal(id: String, uri: Uri) = runOperation("核对下载原始文件 SHA-256") {
        val hash = withContext(Dispatchers.IO) { MediaFiles.digest(app, uri) }
        val rows = report.optJSONObject("upload_result")?.optJSONArray("files") ?: return@runOperation
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("id") == id && row.optString("upload") == "submitted") {
                val matches = row.getString("sha256") == hash
                row.put("roundtrip", if (matches) "sha256_matched" else "sha256_mismatch")
                    .put("download_sha256", hash).put("comparison_at", System.currentTimeMillis())
                status = if (matches) "原始文件 SHA-256 一致。配额仍需官方条目证据。" else "SHA-256 不一致，原文件保真验证未通过。"
            }
        }
        results = (0 until rows.length()).map { rows.getJSONObject(it).toString() }
    }
    fun logout() = runOperation("清除本地凭据与会话") {
        credentials.clear()
        call("logout")
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
        selected = JSONArray(); selectedCount = 0; account = ""; expiry = 0
        val baseline = report.optJSONObject("engine_baseline")
        report = JSONObject().put("authentication", "signed_out").put("overall", "unverified")
            .put("engine_baseline", baseline).put("app_version", BuildConfig.VERSION_NAME)
            .put("android_api", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("engine_runtime", "imported")
        results = emptyList(); status = "已退出账号并清除本地凭据。云端文件保留。"
    }
    private fun refreshReport() {
        report.optJSONObject("upload_result")?.optJSONArray("files")?.let { rows ->
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val evidence = SampleEvidence(row.optString("upload"), row.optBoolean("official_original"),
                    row.optString("roundtrip") == "sha256_matched", row.optBoolean("official_free"),
                    row.optBoolean("official_free_rechecked"))
                row.put("original_quality", EvidencePolicy.quality(evidence)).put("quota", EvidencePolicy.quota(evidence))
            }
        }
        report.put("report_updated_at", System.currentTimeMillis())
        // A report records observations, never turns local UI checks into an overall guarantee.
        report.put("overall", "requires_device_evidence_review")
        reportText = report.toString(2)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = reportFile.startWrite()
            stream.write(reportText.toByteArray(Charsets.UTF_8))
            reportFile.finishWrite(stream)
        } catch (_: Exception) { stream?.let { reportFile.failWrite(it) } }
    }
}
