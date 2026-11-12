package com.cccece.picharbor

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*

class MainActivity:ComponentActivity() {
    private val model:HarborViewModel by viewModels()
    private var reportRange=LogRange()
    private var localIds=listOf<String>()
    private var legacyIndex=0
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
    private val deletion=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result ->
        val approved=result.resultCode==Activity.RESULT_OK
        if(Build.VERSION.SDK_INT==29) {
            if(!approved)legacyIndex++
            legacyNext()
        } else lifecycleScope.launch {
            if(approved)deleteSafLocals()
            model.clearSelection();model.refresh();model.say(if(approved)"手机文件已处理，云端备份会保留" else "已取消删除")
            localIds=emptyList()
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if(savedInstanceState!=null)reportRange=LogRange(savedInstanceState.getLong("log_start",Long.MIN_VALUE),savedInstanceState.getLong("log_end",Long.MAX_VALUE),savedInstanceState.getString("log_zone")?:java.time.ZoneId.systemDefault().id)
        if(intent.getBooleanExtra("open_history",false))model.navigate("history")
        localIds=savedInstanceState?.getStringArrayList("local_ids")?:emptyList()
        legacyIndex=savedInstanceState?.getInt("legacy_index")?:0
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
                onLocalDelete=::requestLocalDelete,onExport={logDialog=true})
        }
    }
    private fun requestPermissions() {
        val list=if(Build.VERSION.SDK_INT>=33)mutableListOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)
            else mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if(Build.VERSION.SDK_INT>=34)list+=Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        if(Build.VERSION.SDK_INT>=33)list+=Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(list.toTypedArray())
    }
    private fun requestLocalDelete() {
        model.sheet="";localIds=model.selected.toList().take(100);legacyIndex=0
        if(localIds.isEmpty())return
        if(Build.VERSION.SDK_INT>=30) {
            model.action {
                val uris=localIds.filter{it.startsWith("content://media/")}.map { id ->
                    model.graph.media.deleteUri(model.graph.dao.media(id)?:throw EngineFailure("FILE_ACCESS"))
                }.distinct()
                if(uris.isNotEmpty())deletion.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(contentResolver,uris,true).intentSender).build())
                else {deleteSafLocals();model.clearSelection();model.refresh()}
            }
        } else legacyNext()
    }
    private suspend fun deleteSafLocals()=withContext(Dispatchers.IO) {
        localIds.filter{!it.startsWith("content://media/")}.forEach{runCatching{DocumentFile.fromSingleUri(this@MainActivity,Uri.parse(it))?.takeIf{doc->doc.canWrite()}?.delete()}}
    }
    private fun legacyNext() {
        lifecycleScope.launch {
            while(legacyIndex<localIds.size) {
                val id=localIds[legacyIndex]
                try {
                    withContext(Dispatchers.IO) {
                        if(id.startsWith("content://media/"))contentResolver.delete(Uri.parse(id),null,null)
                        else DocumentFile.fromSingleUri(this@MainActivity,Uri.parse(id))?.takeIf{it.canWrite()}?.delete()
                    }
                    legacyIndex++
                } catch(error:Exception) {
                    if(error is RecoverableSecurityException) {
                        deletion.launch(IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build());return@launch
                    }
                    legacyIndex++
                }
            }
            model.clearSelection();model.refresh();model.say("手机文件已处理，云端备份会保留")
            localIds=emptyList()
        }
    }
    override fun onStart(){super.onStart();model.graph.visible.set(true)}
    override fun onStop(){model.graph.visible.set(false);super.onStop()}
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent);setIntent(intent)
        if(intent.getBooleanExtra("open_history",false))model.navigate("history")
    }
    override fun onResume(){super.onResume();model.permissionsChanged()}
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putStringArrayList("local_ids",ArrayList(localIds));outState.putInt("legacy_index",legacyIndex)
        outState.putLong("log_start",reportRange.start);outState.putLong("log_end",reportRange.end);outState.putString("log_zone",reportRange.zone)
        super.onSaveInstanceState(outState)
    }
}
