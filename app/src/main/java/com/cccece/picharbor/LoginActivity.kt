@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.cccece.picharbor

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale

/** Native app chrome around Google's unchanged EmbeddedSetup page. */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var loading by mutableStateOf(true)
    private var pageProgress by mutableIntStateOf(0)
    private var pageError by mutableStateOf("")
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private val poll = object : Runnable {
        override fun run() {
            capture()
            if (!finished) handler.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Preserve the real WebView UA and the already validated login chain.
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) { pageProgress = value }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val host = uri.host.orEmpty().lowercase(Locale.ROOT)
                    val allowed = uri.scheme == "https" && (host == "google.com" || host.endsWith(".google.com"))
                    if (!allowed && request.isForMainFrame) pageError = "请留在 Google 登录页面完成验证。"
                    if (uri.isHierarchical && uri.getQueryParameter("error") == "disallowed_useragent") blocked()
                    return !allowed
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loading = true; pageProgress = 0; pageError = ""; capture()
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false; capture()
                    view.evaluateJavascript("""(function(){var t=document.body?document.body.innerText:'';return /disallowed_useragent|This browser or app may not be secure|此浏览器或应用可能不安全|这个浏览器或应用可能不安全/i.test(t)})()""") {
                        if (it == "true" && !finished) blocked()
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) { loading = false; pageError = "暂时无法连接 Google，请检查网络后重试。" }
                }
                override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                    sslHandler.cancel(); loading = false
                    pageError = "暂时无法建立安全连接，请检查网络和手机时间后重试。"
                }
            }
        }
        setContent {
            val settings by (application as PicHarborApp).graph.preferences.flow.collectAsState(initial=HarborSettings())
            PicHarborTheme(settings) {
                val compact=LocalConfiguration.current.screenHeightDp<480
                val keyboard=WindowInsets.ime.getBottom(LocalDensity.current)>0
                BackHandler { if(web.canGoBack())web.goBack() else finish() }
                Scaffold(modifier=Modifier.imePadding(),topBar={
                    TopAppBar(title={Text("登录 Google")},navigationIcon={
                        IconButton(onClick={finish()}){Icon(Icons.Outlined.Close,"取消登录")}
                    },actions={Icon(Icons.Outlined.Lock,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(end=20.dp).size(20.dp))})
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        AnimatedVisibility(!compact&&!keyboard) {
                          Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Outlined.CloudDone,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(12.dp).size(26.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text("连接账号，安心备份",style=MaterialTheme.typography.titleMedium)
                                Text("在 Google 页面完成登录和验证",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                          }
                        }
                        Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Lock,null,modifier=Modifier.size(14.dp),tint=MaterialTheme.colorScheme.primary)
                                Text("Google 登录 · HTTPS",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
                                AnimatedContent(loading,label="login-status") { pending -> Text(if(pending)"正在加载" else "请继续登录",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        AnimatedVisibility(loading&&pageError.isEmpty()) {
                            val value by animateFloatAsState(pageProgress/100f,tween(160),label="login-progress")
                            LinearProgressIndicator(progress={value},modifier=Modifier.fillMaxWidth())
                        }
                        AnimatedVisibility(pageError.isNotEmpty()) {
                            Surface(color=MaterialTheme.colorScheme.errorContainer,modifier=Modifier.fillMaxWidth().animateContentSize()) {
                                Row(Modifier.padding(start=20.dp,end=12.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Outlined.Info,null,tint=MaterialTheme.colorScheme.onErrorContainer)
                                    Text(pageError,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)
                                    TextButton(onClick=::retry){Text("重试")}
                                }
                            }
                        }
                        // Google's own content keeps its styling; no injected CSS or credential JS bridge.
                        AndroidView(factory={web},modifier=Modifier.fillMaxWidth().weight(1f).background(Color.White))
                        AnimatedVisibility(!keyboard&&!compact) {
                            Text("登录成功后将自动返回 PicHarbor",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp))
                        }
                    }
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            val saved = savedInstanceState?.getBundle("google_web_state")
            if (saved != null && web.restoreState(saved) != null) handler.post(poll)
            else removeAllCookies {
                if (!isFinishing && !isDestroyed) {
                    web.loadUrl("https://accounts.google.com/EmbeddedSetup")
                    handler.post(poll)
                }
            }
        }
    }
    private fun retry() {
        pageError="";loading=true
        if(web.url?.startsWith("https://")==true)web.reload() else web.loadUrl("https://accounts.google.com/EmbeddedSetup")
    }
    private fun capture() {
        if (finished) return
        val cookie = CookieManager.getInstance().getCookie("https://accounts.google.com/") ?: return
        val token = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("oauth_token=") }
            ?.substringAfter('=')?.takeIf { it.length in 16..8192 } ?: return
        finished = true
        setResult(RESULT_OK, Intent().putExtra("oauth_token", token)); finish()
    }
    private fun blocked() {
        if (finished) return
        finished = true
        setResult(RESULT_FIRST_USER, Intent().putExtra("error", "WEBVIEW_LOGIN_REJECTED")); finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if(::web.isInitialized)outState.putBundle("google_web_state",Bundle().also { web.saveState(it) })
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) { web.stopLoading(); web.destroy() }
        if(!isChangingConfigurations) {
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
            WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }
}
