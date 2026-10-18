package io.gmgc.probe

import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun exportReport(activity: ComponentActivity, uri: Uri, snapshot: String) {
    activity.lifecycleScope.launch {
        val success = withContext(Dispatchers.IO) {
            runCatching {
                requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use {
                    it.write(snapshot.toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
        }
        Toast.makeText(activity, if (success) "报告已导出" else "报告导出失败", Toast.LENGTH_SHORT).show()
    }
}
