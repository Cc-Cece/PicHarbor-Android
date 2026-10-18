package com.cccece.picharbor

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HarborViewModel(app:Application):AndroidViewModel(app) {
    val graph=(app as PicHarborApp).graph
    val settings=graph.preferences.flow.stateIn(viewModelScope,SharingStarted.Eagerly,HarborSettings())
    val accounts=graph.credentials.accounts
    val accountSwitching=graph.switching
    val media=graph.dao.mediaFlow().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val records=settings.map{it.account}.distinctUntilChanged().flatMapLatest{graph.dao.backupsFlow(it)}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val jobs=settings.map{it.account}.distinctUntilChanged().flatMapLatest{graph.dao.jobsFlow(it)}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val items=settings.map{it.account}.distinctUntilChanged().flatMapLatest{graph.dao.itemsFlow(it)}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val statistics=settings.map{it.account}.distinctUntilChanged().flatMapLatest{graph.dao.statisticsFlow(it)}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    private val inspectedJob=MutableStateFlow("")
    val jobDetails=inspectedJob.flatMapLatest{graph.dao.jobItemsFlow(it)}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    var detailLimit by mutableIntStateOf(200)
    fun showJob(id:String){inspectedJob.value=id;detailLimit=200;sheet="job:$id"}
    val cleanups=settings.map{it.account}.distinctUntilChanged().flatMapLatest{graph.dao.cleanupFlow(it)}.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    var page by mutableStateOf("photos");private set
    private val stack=mutableListOf<String>()
    var selected by mutableStateOf(setOf<String>());private set
    var selectionMode by mutableStateOf(false);private set
    var activeMedia by mutableStateOf<MediaEntry?>(null);private set
    var filter by mutableStateOf("all")
    var backupListKind by mutableStateOf("pending");private set
    fun showBackupList(kind:String){backupListKind=kind;navigate("backup-list")}
    var type by mutableStateOf("all")
    var fromDate by mutableStateOf(0L)
    var toDate by mutableStateOf(Long.MAX_VALUE)
    var sheet by mutableStateOf("")
    var busy by mutableStateOf(false);private set
    var message by mutableStateOf("");private set
    var loginRequired by mutableStateOf(false);private set
    var permissionRevision by mutableIntStateOf(0);private set
    var albumChoices by mutableStateOf(listOf<Pair<String,String>>());private set
    var pendingDelete by mutableStateOf(listOf<CleanupRecord>());private set
    var pendingManualIds:List<String>?=null;private set
    var deleteSummary by mutableStateOf("");private set
    val engineReady get()=graph.engineReady
    init { refresh() }
    fun navigate(destination:String){stack+=page;page=destination;sheet="";selected=emptySet();selectionMode=false}
    fun tab(destination:String){stack.clear();page=destination;sheet="";selected=emptySet();selectionMode=false}
    fun back(){if(sheet.isNotEmpty()){sheet="";return};if(selectionMode){clearSelection();return};page=stack.removeLastOrNull()?:"photos"}
    fun open(media:MediaEntry){if(selectionMode){select(media.id);return};activeMedia=media;navigate("detail")}
    fun select(id:String){selectionMode=true;selected=if(id in selected)selected-id else selected+id}
    fun selectAll(ids:List<String>){selectionMode=true;selected=selected+ids}
    fun clearSelection(){selectionMode=false;selected=emptySet()}
    fun clearMessage(){message=""}
    fun say(value:String){message=value}
    /** System activity results must not be dropped while a refresh is finishing. */
    fun afterAction(block:()->Unit){viewModelScope.launch{while(busy)delay(50);block()}}
    fun action(block:suspend ()->Unit) {
        if(busy)return
        busy=true
        viewModelScope.launch {
            try{block()}catch(error:Exception){val code=(error as? EngineFailure)?.code?:"ENGINE_ERROR";message=Messages.error(code);if(code=="LOGIN_REQUIRED"){loginRequired=true;graph.credentials.needsLogin(settings.value.account,true)}}
            finally{busy=false}
        }
    }
    fun refresh()=action {
        withContext(Dispatchers.IO){graph.media.scan()};permissionRevision++
    }
    fun permissionsChanged(){permissionRevision++;refresh()}
    fun login(token:String)=action {
        graph.login(token);loginRequired=false;message="账号已连接"
        if(graph.dao.nextJob(graph.preferences.get().account)!=null)graph.scheduler.launchManual()
        if(!settings.value.onboarded)navigate("folders")
    }
    fun logout()=action {
        graph.logout();loginRequired=false;sheet="";message="已退出，云端备份会保留";tab("me")
        withContext(Dispatchers.Main){android.webkit.CookieManager.getInstance().removeAllCookies(null);android.webkit.WebStorage.getInstance().deleteAllData()}
    }
    fun switchAccount(onLogin:()->Unit)=action {
        sheet="";withContext(Dispatchers.Main){onLogin()}
    }
    fun selectAccount(email:String)=action {
        graph.selectAccount(email);loginRequired=false;albumChoices=emptyList();pendingDelete=emptyList();clearSelection()
        activeMedia=null;sheet="";page=if(page=="detail")"photos" else page
        message="已切换备份账号"
    }
    fun removeAccount(email:String)=action {
        graph.removeAccount(email);loginRequired=false;pendingDelete=emptyList();sheet="";clearSelection();message="已从此设备移除账号，云端内容保留"
        withContext(Dispatchers.Main){android.webkit.CookieManager.getInstance().removeAllCookies(null);android.webkit.WebStorage.getInstance().deleteAllData()}
    }
    fun setBoolean(key:String,value:Boolean)=action {
        graph.preferences.boolean(key,value);graph.scheduler.configure()
    }
    fun setString(key:String,value:String)=action {
        graph.preferences.string(key,value);graph.scheduler.configure()
    }
    fun setInteger(key:String,value:Int)=action {graph.preferences.integer(key,value)}
    fun setMobile(value:String)=action {graph.preferences.mobile(value);graph.scheduler.configure();sheet=""}
    fun folder(key:String,checked:Boolean)=action {
        val current=graph.preferences.get();var chosen=current.folders
        if(key=="@camera")chosen=if(checked)chosen+key else chosen-key
        else {
            val mediaFolder=media.value.find{it.folderKey==key}
            if(mediaFolder?.folderName.equals("Camera",true)&&"@camera" in chosen)chosen=chosen-"@camera"
            chosen=if(checked)chosen+key else chosen-key
        }
        graph.preferences.folders(chosen);graph.scheduler.configure()
    }
    fun addTree(uri:Uri)=action {
        withContext(Dispatchers.IO) {
            runCatching { graph.app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.getOrElse { graph.app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val current=graph.preferences.get();graph.preferences.trees(current.trees+uri.toString());graph.preferences.folders(current.folders+uri.toString());graph.media.scan()
        };permissionRevision++
    }
    fun importFiles(uris:List<Uri>)=action {
        for(uri in uris)runCatching{graph.app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        val ids=graph.media.importUris(uris);selected=ids.toSet();selectionMode=true;tab("photos");selected=ids.toSet();selectionMode=true
    }
    fun finishSetup()=action {
        graph.preferences.boolean("onboarded",true);graph.preferences.boolean("auto",true);graph.scheduler.configure();graph.scheduler.checkSoon();tab("backup");message="自动备份已开启"
    }
    fun start(ids:List<String>?=null,allowMobile:Boolean=false) {
        if(settings.value.account.isEmpty()||loginRequired){sheet="login";return}
        if(settings.value.wifi&&!allowMobile&&!graph.backup.onWifi()){pendingManualIds=ids;sheet=if(ids!=null)"mobile-selected" else "mobile-all";return}
        action {
            val submission=graph.backup.enqueue(ids,allowMobile=allowMobile)
            if(submission==null) {
                val account=graph.preferences.get().account
                if(graph.dao.pendingManual(account)>0){graph.scheduler.launchManual(allowMobile);message="正在启动待备份队列"}
                else message="没有需要新增备份的项目"
            } else {
                graph.scheduler.launchManual(allowMobile)
                message=if(submission.created>0)"已加入备份，将在后台处理" else "这些照片正在备份中"
                watchSubmission(submission,graph.preferences.get().account)
            }
        }
    }
    private val watched=mutableSetOf<String>()
    private fun watchSubmission(submission:BackupSubmission,account:String) {
        val key=submission.itemIds.sorted().joinToString("|")
        if(!watched.add(key))return
        viewModelScope.launch {
            try {
                val chunks=submission.itemIds.chunked(800).map{graph.dao.observeItems(it)}
                val finished=combine(combine(chunks){it.flatMap{rows->rows}},settings){rows,s->rows to s.account}
                    .first{(rows,current)->current!=account||(rows.size==submission.itemIds.size&&rows.all{it.state in BackupPolicy.terminalItems||it.state=="album_pending"})}
                if(finished.second!=account)return@launch
                val rows=finished.first
                val success=rows.count{it.state in setOf("done","exists")}
                val failed=rows.count{it.state in setOf("failed","album_pending")}
                message=when {
                    failed>0->"备份结束：成功 $success 项，$failed 项需要处理，可在任务记录重试"
                    rows.all{it.state=="exists"}->"云端已有相同内容，已确认备份"
                    rows.all{it.state=="cancelled"}->"本次备份已取消"
                    else->"备份完成，共 $success 项"
                }
            } finally {watched.remove(key)}
        }
    }
    fun pause()=action {graph.backup.pause()}
    fun resume(id:String)=action {graph.backup.resume(id)}
    fun retry(id:String)=action {graph.backup.retry(id)}
    fun end(id:String)=action {graph.backup.end(id)}
    fun albums() {
        sheet="albums"
        action {graph.ensureAccount();val response=graph.engine.call("albums");val array=response.getJSONArray("albums");albumChoices=(0 until array.length()).map{array.getJSONObject(it).let{v->v.getString("id") to v.getString("name")}}}
    }
    fun album(mode:String,value:String="")=action {
        graph.preferences.string("albumMode",mode);graph.preferences.string("albumValue",value);sheet=""
    }
    fun checkAccount()=action{graph.ensureAccount();loginRequired=false;message="账号连接正常，登录凭据会自动续期"}
    fun verifyCleanup(ids:List<String>?=null,quick:Boolean=false)=action {
        graph.cleanup.enqueue(ids,mode=if(quick)"quick" else "full")
        message=if(androidx.core.app.NotificationManagerCompat.from(graph.app).areNotificationsEnabled())"已开始检查，可离开页面，完成后会通知你" else "检查已在后台开始；允许通知后可收到进度与完成提醒"
    }
    fun openCleanup(id:String="")=action {
        val scan=if(id.isEmpty())null else graph.dao.cleanupScan(id)
        if(scan!=null&&scan.account!=graph.preferences.get().account){sheet="account";message="请先切换到此次检查使用的账号"}
        else navigate("cleanup")
    }
    suspend fun prepareCleanup():List<CleanupRecord> {
        val records=graph.cleanup.prepareDelete();pendingDelete=records;return records
    }
    suspend fun restoreDelete(ids:List<String>){pendingDelete=graph.dao.cleanupByIds(ids)}
    suspend fun completeDelete(approved:Boolean) {
        val result=graph.cleanup.finishDelete(pendingDelete,approved);pendingDelete=emptyList()
        deleteSummary=if(approved)"已释放 ${formatSize(result.second)}，移除 ${result.first} 个手机文件。云端备份会保留。" else "已取消清理，手机文件保留。"
        sheet="cleanup-result";graph.media.scan()
    }
    fun deletionResult(approved:Boolean)=action {
        completeDelete(approved)
    }
    fun localDeleteCandidates():List<MediaEntry> = media.value.filter{it.id in selected}
    fun report():String=JSONObject().put("app","PicHarbor").put("version",BuildConfig.VERSION_NAME).put("author","cc_cece")
        .put("device",android.os.Build.MODEL).put("android",android.os.Build.VERSION.SDK_INT).put("account_connected",settings.value.account.isNotBlank())
        .put("visible_media",media.value.size).put("completed_jobs",jobs.value.count{it.state=="completed"})
        .put("failed_items",items.value.count{it.state=="failed"})
        .put("saved_accounts",accounts.value.size)
        .put("task_states",JSONObject(items.value.groupingBy{it.state}.eachCount()))
        .put("task_errors",JSONObject(items.value.filter{it.error.isNotBlank()}.groupingBy{it.error}.eachCount()))
        .put("cleanup_states",JSONObject(cleanups.value.groupingBy{it.state}.eachCount()))
        .put("cleanup_reasons",JSONObject(cleanups.value.filter{it.reason.isNotBlank()}.groupingBy{it.reason}.eachCount()))
        .put("cleanup_check",JSONObject().put("state",graph.cleanup.progress.value.state).put("checked",graph.cleanup.progress.value.checked).put("total",graph.cleanup.progress.value.total))
        .put("wifi_available",graph.backup.onWifi()).put("auto_enabled",settings.value.auto)
        .put("time",System.currentTimeMillis()).toString(2)
}

fun formatSize(bytes:Long):String=when {
    bytes>=1024L*1024*1024->"%.1f GB".format(bytes.toDouble()/(1024*1024*1024))
    bytes>=1024*1024->"%.1f MB".format(bytes.toDouble()/(1024*1024))
    bytes>=1024->"%.1f KB".format(bytes/1024.0)
    else->"$bytes B"
}
