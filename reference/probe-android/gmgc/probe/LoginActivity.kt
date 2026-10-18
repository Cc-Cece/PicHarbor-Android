package io.gmgc.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class LoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        status = TextView(this).apply {
            text = "在 Google 页面登录并完成验证。若 Google 拒绝此登录环境，将记录为阻塞。"
            setPadding(24, 24, 24, 24)
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Use the actual WebView UA; no impersonation or JS credential bridge.
        }
        val cancel = Button(this).apply { text = "取消登录"; setOnClickListener { finish() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(status)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(cancel)
        })
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host.orEmpty().lowercase()
                val allowed = uri.scheme == "https" && (host == "google.com" || host.endsWith(".google.com"))
                if (!allowed) status.text = "已阻止离开 Google HTTPS 登录页面。"
                if (uri.isHierarchical && uri.getQueryParameter("error") == "disallowed_useragent") blocked("WEBVIEW_LOGIN_REJECTED")
                return !allowed
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { capture() }
            override fun onPageFinished(view: WebView, url: String?) {
                capture()
                view.evaluateJavascript("""(function(){var t=document.body?document.body.innerText:'';return /disallowed_useragent|This browser or app may not be secure|此浏览器或应用可能不安全|这个浏览器或应用可能不安全/i.test(t)})()""") {
                    if (it == "true" && !finished) blocked("WEBVIEW_LOGIN_REJECTED")
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) status.text = "登录页面网络错误。请检查手机网络后重试。"
            }
            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
                sslHandler.cancel()
                status.text = "TLS 证书验证失败，已停止加载。"
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            removeAllCookies {
                if (!isFinishing) {
                    web.loadUrl("https://accounts.google.com/EmbeddedSetup")
                    handler.post(poll)
                }
            }
        }
    }
    private fun capture() {
        if (finished) return
        val cookie = CookieManager.getInstance().getCookie("https://accounts.google.com/") ?: return
        val token = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("oauth_token=") }
            ?.substringAfter('=')?.takeIf { it.length in 16..8192 } ?: return
        finished = true
        setResult(RESULT_OK, Intent().putExtra("oauth_token", token))
        finish()
    }
    private fun blocked(code: String) {
        if (finished) return
        finished = true
        setResult(RESULT_FIRST_USER, Intent().putExtra("error", code))
        finish()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) { web.stopLoading(); web.destroy() }
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        android.webkit.WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }
}
