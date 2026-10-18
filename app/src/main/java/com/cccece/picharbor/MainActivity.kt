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
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*

class MainActivity:ComponentActivity() {
    private val model:HarborViewModel by viewModels()
    private var deleteKind=""
    private var cleanupIds=listOf<String>()
    private var localIds=listOf<String>()
    private var legacyIndex=0
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){model.permissionsChanged()}
    private val login=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK)result.data?.getStringExtra("oauth_token")?.let{token->model.afterAction{model.login(token)}}
        else if(result.resultCode==Activity.RESULT_FIRST_USER)model.say("Google 暂未接受此登录环境，请稍后重试")
    }
    private val picker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty())model.afterAction{model.importFiles(uris)}}
    private val tree=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)model.afterAction{model.addTree(uri)}}
    private val report=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri ->
        if(uri!=null)lifecycleScope.launch {
            val text=model.report()
            val success=withContext(Dispatchers.IO){runCatching {contentResolver.openOutputStream(uri,"wt")!!.use{it.write(text.toByteArray(Charsets.UTF_8))}}.isSuccess}
            model.say(if(success)"诊断报告已导出" else "未能导出，请重试")
        }
    }
    private val deletion=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result ->
        val approved=result.resultCode==Activity.RESULT_OK
        if(Build.VERSION.SDK_INT==29) {
            if(!approved)legacyIndex++
            legacyNext()
        } else lifecycleScope.launch {
            if(deleteKind=="cleanup") {
                if(model.pendingDelete.isEmpty())model.restoreDelete(cleanupIds)
                model.afterAction{model.deletionResult(approved)}
            } else {
                if(approved)deleteSafLocals()
                model.clearSelection();model.refresh();model.say(if(approved)"手机文件已处理，云端备份会保留" else "已取消删除")
            }
            cleanupIds=emptyList();localIds=emptyList()
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if(intent.getBooleanExtra("open_history",false))model.navigate("history")
        if(intent.getBooleanExtra("open_cleanup",false))model.afterAction{model.openCleanup(intent.getStringExtra("cleanup_scan").orEmpty())}
        deleteKind=savedInstanceState?.getString("delete_kind").orEmpty()
        cleanupIds=savedInstanceState?.getStringArrayList("cleanup_ids")?:emptyList()
        localIds=savedInstanceState?.getStringArrayList("local_ids")?:emptyList()
        legacyIndex=savedInstanceState?.getInt("legacy_index")?:0
        lifecycleScope.launch {
            model.graph.preferences.integer("bootSession",bootCount(this@MainActivity));model.graph.scheduler.configure();model.graph.scheduler.restorePending()
        }
        setContent {
            HarborRoot(model,onLogin={login.launch(Intent(this,LoginActivity::class.java))},onPermissions=::requestPermissions,
                onPickFiles={picker.launch(arrayOf("image/*","video/*"))},onPickTree={tree.launch(null)},
                onCleanup=::requestCleanup,onLocalDelete=::requestLocalDelete,onExport={report.launch("PicHarbor-diagnostics.json")})
        }
    }
    private fun requestPermissions() {
        val list=if(Build.VERSION.SDK_INT>=33)mutableListOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)
            else mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if(Build.VERSION.SDK_INT>=34)list+=Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        if(Build.VERSION.SDK_INT>=33)list+=Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(list.toTypedArray())
    }
    private fun requestCleanup() {
        model.sheet=""
        model.action {
            val records=model.prepareCleanup()
            if(records.isEmpty()){model.say("核验结果已过期或文件变化，请重新检查");return@action}
            cleanupIds=records.map{it.id};localIds=records.map{it.mediaId};deleteKind="cleanup";legacyIndex=0
            if(Build.VERSION.SDK_INT>=30) {
                val request=model.graph.cleanup.systemRequest(records)
                if(request!=null)withContext(Dispatchers.Main){deletion.launch(IntentSenderRequest.Builder(request).build())}
                else model.completeDelete(true)
            } else withContext(Dispatchers.Main){legacyNext()}
        }
    }
    private fun requestLocalDelete() {
        model.sheet="";localIds=model.selected.toList().take(100);deleteKind="local";legacyIndex=0
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
            if(deleteKind=="cleanup"&&model.pendingDelete.isEmpty())model.restoreDelete(cleanupIds)
            while(legacyIndex<localIds.size) {
                val id=localIds[legacyIndex]
                try {
                    withContext(Dispatchers.IO) {
                        if(deleteKind=="cleanup")model.pendingDelete.find{it.mediaId==id}?.let{model.graph.cleanup.deleteLegacy(it)}
                        else if(id.startsWith("content://media/"))contentResolver.delete(Uri.parse(id),null,null)
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
            if(deleteKind=="cleanup")model.afterAction{model.deletionResult(true)}
            else {model.clearSelection();model.refresh();model.say("手机文件已处理，云端备份会保留")}
            cleanupIds=emptyList();localIds=emptyList()
        }
    }
    override fun onStart(){super.onStart();model.graph.visible.set(true)}
    override fun onStop(){model.graph.visible.set(false);super.onStop()}
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent);setIntent(intent)
        if(intent.getBooleanExtra("open_history",false))model.navigate("history")
        if(intent.getBooleanExtra("open_cleanup",false))model.afterAction{model.openCleanup(intent.getStringExtra("cleanup_scan").orEmpty())}
    }
    override fun onResume(){super.onResume();model.permissionsChanged();lifecycleScope.launch{model.graph.startup.await();model.graph.cleanup.restorePending()}}
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("delete_kind",deleteKind);outState.putStringArrayList("cleanup_ids",ArrayList(cleanupIds))
        outState.putStringArrayList("local_ids",ArrayList(localIds));outState.putInt("legacy_index",legacyIndex)
        super.onSaveInstanceState(outState)
    }
}
