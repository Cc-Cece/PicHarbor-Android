package io.gmgc.probe

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class ProbeApplication : Application() {
    var engineReady = false
        private set
    var engineError = ""
        private set
    override fun onCreate() {
        super.onCreate()
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            val result = Python.getInstance().getModule("android_probe")
                .callAttr("initialize", File(filesDir, "runtime").absolutePath).toString()
            engineReady = org.json.JSONObject(result).optBoolean("ok")
        } catch (error: Exception) {
            // Do not log a Python traceback: remote exceptions may contain tokens.
            engineReady = false
            engineError = if (error.message.orEmpty().contains("signal only works in main thread"))
                "SIGNAL_MAIN_THREAD_REQUIRED" else error.javaClass.simpleName
        }
    }
}
