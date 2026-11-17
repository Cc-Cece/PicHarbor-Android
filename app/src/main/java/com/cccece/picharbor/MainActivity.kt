package com.cccece.picharbor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class MainActivity:ComponentActivity() {
    private val model:HarborViewModel by viewModels()
    private var reportRange=LogRange()
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){model.permissionsChanged()}
    private val login=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK)result.data?.getStringExtra("oauth_token")?.let{token->model.afterAction{model.login(token)}}
        else if(result.resultCode==Activity.RESULT_FIRST_USER)model.say("Google 暂未接受此登录环境，请稍后重试")
    }
    private val picker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty())model.afterAction{model.importFiles(uris)}}
    private val tree=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)model.afterAction{model.addTree(uri)}}
    private val report=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri ->
        if(uri!=null)lifecycleScope.launch {
            model.say("???????")
            val message=withContext(Dispatchers.IO){
                runCatching {contentResolver.openOutputStream(uri,"wt")!!.use{Diagnostics.export(it,reportRange)}}.getOrElse { error ->
                    runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver,uri) }
                    Diagnostics.event("bundle.export_failed",attrs=DiagnosticFields.error(error),level="WARN")
                    if(error.message=="NO_LOGS_IN_RANGE")"????????" else if(error.message=="EXPORT_BUSY")"????????????" else "??????????"
                }
            }
            model.say(message)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if(savedInstanceState!=null)reportRange=LogRange(savedInstanceState.getLong("log_start",Long.MIN_VALUE),savedInstanceState.getLong("log_end",Long.MAX_VALUE),savedInstanceState.getString("log_zone")?:java.time.ZoneId.systemDefault().id)
        if(intent.getBooleanExtra("open_history",false))model.navigate("history")
        lifecycleScope.launch {
            model.graph.preferences.integer("bootSession",bootCount(this@MainActivity));model.graph.scheduler.configure();model.graph.scheduler.restorePending()
        }
        setContent {
            var logDialog by remember { mutableStateOf(false) }
            if(logDialog)DiagnosticExportDialog(onDismiss={logDialog=false},onExport={range->
                reportRange=range;logDialog=false
                report.launch("PicHarbor-logs-${java.time.LocalDate.now()}.zip")
            })
            HarborRoot(model,onLogin={login.launch(Intent(this,LoginActivity::class.java))},onPermissions=::requestPermissions,
                onPickFiles={picker.launch(arrayOf("image/*","video/*"))},onPickTree={tree.launch(null)},
                onExport={logDialog=true})
        }
    }
    private fun requestPermissions() {
        val list=if(Build.VERSION.SDK_INT>=33)mutableListOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)
            else mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if(Build.VERSION.SDK_INT>=34)list+=Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        if(Build.VERSION.SDK_INT>=33)list+=Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(list.toTypedArray())
    }
    override fun onStart(){super.onStart();model.graph.visible.set(true)}
    override fun onStop(){model.graph.visible.set(false);super.onStop()}
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent);setIntent(intent)
        if(intent.getBooleanExtra("open_history",false))model.navigate("history")
    }
    override fun onResume(){super.onResume();model.permissionsChanged()}
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putLong("log_start",reportRange.start);outState.putLong("log_end",reportRange.end);outState.putString("log_zone",reportRange.zone)
        super.onSaveInstanceState(outState)
    }
}
