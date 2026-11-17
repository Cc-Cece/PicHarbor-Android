package com.cccece.picharbor

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

data class BackupSubmission(val itemIds:List<String>,val created:Int,val reused:Int)

data class TransferProgress(val job:String="",val item:String="",val name:String="",val phase:String="",val bytes:Long=0,val total:Long=0,val speed:Long=0)

/**
 * One reconcile evidence item: a core task id with its cloud-confirmation
 * fact. Built only from reconcile() / cloud-confirmation results — never
 * invented. [confirmedAt] is epoch seconds of the reconcile that confirmed it.
 */
data class CleanupEvidenceItem(val taskId:String,val mediaId:String,val cloudConfirmed:Boolean,val confirmedAt:Long)

class BackupRepository(private val graph:AppGraph) {
    private suspend fun requireAvailableVersions(policy:String) {
        val result=graph.harborCore.dispatch("select_versions",JSONObject().put("policy",policy)
            .put("available",org.json.JSONArray().put("current")).put("has_edits",JSONObject.NULL))
        if(!result.optBoolean("ok"))throw EngineFailure(result.optString("error","ENGINE_ERROR"))
    }
    private val suspendedItems=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cancelledItems=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    suspend fun itemAction(id:String,action:String) {
        val item=graph.dao.item(id)?:return
        if(item.account!=graph.preferences.get().account)return
        val job=graph.dao.job(item.jobId)?:return
        if(item.state in setOf("done","exists","cancelled"))return
        val task=graph.harborCore.task(id)
        when(action) {
            "pause"->{suspendedItems.add(id);task?.let{graph.harborCore.transition(it,"paused")};graph.dao.putItem(item.copy(state="paused"))}
            "resume"->{
                task?.takeUnless{it.optString("state")=="waiting_confirmation"}?.let{val result=graph.harborCore.transition(it,"queued");if(!result.optBoolean("ok"))throw EngineFailure(result.optString("error","ENGINE_ERROR"))}
                suspendedItems.remove(id)
                graph.dao.putItem(item.copy(state=if(item.remoteKey.isBlank())"queued" else "album_pending",error=""))
                graph.dao.putJob(job.copy(state="queued",manualPriority=true));graph.scheduler.launchManual(job.allowMobile)
            }
            "remove"->{
                cancelledItems.add(id);suspendedItems.add(id)
                task?.let{graph.harborCore.transition(it,"cancelled")}
                graph.dao.suppress(SuppressedAsset(item.account,item.mediaId,item.modified))
                graph.dao.putItem(item.copy(state="cancelled",hidden=true))
            }
        }
    }
    private val identityCache=java.util.concurrent.ConcurrentHashMap<String,String>()
    private val keyframeHints=java.util.concurrent.ConcurrentHashMap<String,Boolean>()
    private val identityMutex=Mutex()
    private var identityRevision=-1L
    private var identitySnapshot:List<MediaEntry>?=null
    suspend fun identifiedMedia():List<MediaEntry> = identityMutex.withLock {
        val revision=graph.media.revision.get()
        identitySnapshot?.takeIf{identityRevision==revision}?.let{return@withLock it}
        val all=graph.dao.media()
        val currentKeys=all.map{"${it.id}:${it.modified}:${it.size}"}.toSet()
        identityCache.keys.retainAll(currentKeys);keyframeHints.keys.retainAll(currentKeys)
        val identified=all.map { media ->
            val supported=media.mime in setOf("image/jpeg","image/heic","image/heif","video/quicktime","video/mp4") ||
                media.name.substringAfterLast('.').lowercase() in setOf("jpg","jpeg","heic","heif","mov","mp4")
            if(!supported)media else {
                val key="${media.id}:${media.modified}:${media.size}"
                val identifier=identityCache[key]?:run {
                    val response=try {graph.engine.call("media_identity",JSONObject().put("uri",media.id).put("name",media.name).put("video",media.video))}
                    catch(error:EngineFailure){
                        if(error.code in setOf("FILE_ACCESS","ACCESS_LOST"))return@map media
                        throw error
                    }
                    val found=if(response.optBoolean("embedded_motion"))LivePhotoPairing.EMBEDDED_MOTION else response.optString("identifier")
                    keyframeHints[key]=response.optBoolean("still_image_time")
                    identityCache[key]=found;found
                }
                media.copy(pairingID=identifier)
            }
        }
        val previous=all.associateBy{it.id}
        graph.database.withTransaction {
            identified.filter{it.pairingID!=previous[it.id]?.pairingID}.forEach{graph.dao.updatePairingID(it.id,it.modified,it.size,it.pairingID)}
        }
        if(graph.media.revision.get()==revision){identityRevision=revision;identitySnapshot=identified}
        identified
    }
    private val lock=Mutex()
    private val enqueueLock=Mutex()
    private val stopping=AtomicBoolean(false)
    val shouldStop get()=stopping.get()
    /** Live-photo pair claims: pairKey (photo media id) -> claiming item id. */
    /** Items that must be processed as single resources (live-photo stepwise fallback). */
    /** Latest per-item cleanup evidence, populated by [reconcile]; empty means "no evidence". */
    @Volatile
    var lastCleanupEvidence: List<CleanupEvidenceItem> = emptyList()
        private set
    @Volatile
    var lastCleanupEvidenceAt: Long = 0
        private set
    val running get()=lock.isLocked
    val progress=MutableStateFlow(TransferProgress())
    val inFlight=MutableStateFlow<Map<String,TransferProgress>>(emptyMap())
    val activeJob=MutableStateFlow("")
    val notice=MutableStateFlow("")
    fun onWifi():Boolean {
        val manager=graph.app.getSystemService(ConnectivityManager::class.java)
        val active=manager.getNetworkCapabilities(manager.activeNetwork)?:return false
        if(active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return true
        return active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.let{n->n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&n.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}==true
        }
    }
    suspend fun reconcile() {
        val account=graph.ensureAccount()
        val records=graph.dao.backups(account)
        if(records.isEmpty())return
        val response=graph.engine.call("reconcile",JSONObject().put("keys",org.json.JSONArray(records.map{it.remoteKey}.distinct())))
        val missing=response.getJSONArray("missing")
        val keys=(0 until missing.length()).map{missing.getString(it)}.toSet()
        if(account!=graph.preferences.get().account)return
        records.filter{it.remoteKey in keys}.forEach{graph.dao.invalidate(account,it.mediaId)}
        // Per-item cleanup evidence, from this reconcile only: every record
        // that is still present in the cloud library is confirmed at this
        // moment. Items without such an entry have NO evidence — decide_cleanup
        // must not release them per item (page-level guidance only).
        val confirmedAt=System.currentTimeMillis()/1000
        val evidence=ArrayList<CleanupEvidenceItem>()
        for(record in records) {
            if(record.remoteKey in keys)continue
            val item=graph.dao.latestDoneItem(account,record.mediaId)?:continue
            evidence+=CleanupEvidenceItem(item.id,record.mediaId,true,confirmedAt)
        }
        lastCleanupEvidence=evidence
        lastCleanupEvidenceAt=System.currentTimeMillis()
    }
    /**
     * Per-item official-cleanup guidance for one core task. Only true when
     * (a) reconcile evidence contains this task with cloud_confirmed=true,
     * and (b) the core decide_cleanup agrees on that evidence. Without
     * evidence this returns false: the UI may still show page-level
     * guidance (openOfficialFreeSpace), but must not claim this item is
     * cleared. Evidence is never fabricated.
     */
    suspend fun cleanupGuidanceEligible(itemId:String):Boolean {
        val itemEvidence=lastCleanupEvidence.firstOrNull{it.taskId==itemId&&it.cloudConfirmed}?:return false
        if(System.currentTimeMillis()/1000-itemEvidence.confirmedAt>3600)return false
        val loaded=runCatching{graph.harborCore.storeLoad()}.getOrNull()?:return false
        val task=loaded.optJSONObject("tasks")?.optJSONObject(itemId)?:return false
        val itemsArray=org.json.JSONArray().put(JSONObject()
            .put("task_id",itemEvidence.taskId).put("cloud_confirmed",true).put("confirmed_at",itemEvidence.confirmedAt))
        val evidence=JSONObject()
            .put("cloud_state","confirmed_original")
            .put("checked_at",itemEvidence.confirmedAt)
            .put("policy",true).put("items",itemsArray)
        val result=runCatching{graph.harborCore.decideCleanup(task,evidence)}.getOrNull()?:return false
        return result.optBoolean("eligible")
    }
    suspend fun enqueue(ids:List<String>?=null,automatic:Boolean=false,allowMobile:Boolean=false):BackupSubmission?=enqueueLock.withLock {
        if(graph.switching.value)throw EngineFailure("ACCOUNT_CHANGED")
        val settings=graph.preferences.get()
        if(settings.account.isEmpty())throw EngineFailure("LOGIN_REQUIRED")
        requireAvailableVersions(settings.editPolicy)
        val known=graph.dao.backups(settings.account).associateBy{it.mediaId}
        val allMedia=identifiedMedia()
        val explicit=ids?.map { id -> allMedia.firstOrNull{it.id==id}?.takeIf{it.video}?.let{LivePhotoPairing.findPhotoPartner(it,allMedia)?.id}?:id }?.toSet()
        // Scope is decided by the shared core (evaluate_scope) for automatic
        // candidates. Explicit user selection bypasses scope filtering: the
        // user chose those items. The cheap local includes() check below is
        // only an index pre-filter over the exact inputs the scope JSON
        // carries (folders/videos); the core remains the authority for the
          // surviving candidates (date/type/size). An
        // unknown-version scope error denies the whole enqueue instead of
        // degrading into "include everything".
        val candidates=ArrayList<MediaEntry>()
        for(media in allMedia) {
            if(media.video && LivePhotoPairing.findPhotoPartner(media,allMedia)!=null)continue
            if(explicit!=null) {
                if(media.id in explicit)candidates+=media
                continue
            }
            if(automatic && graph.dao.suppressed(settings.account,media.id,media.modified)>0)continue
            val partner=if(!media.video)LivePhotoPairing.findPair(media,allMedia) else null
            if(LivePhotoPairing.backed(media,allMedia,known))continue
            if(!settings.includes(media)&&!(partner!=null&&!media.video&&settings.includesFolder(partner)))continue
            when(val decision=ScopePolicy.evaluate(graph.harborCore,settings,media,partner)) {
                ScopePolicy.ScopeDecision.Included, is ScopePolicy.ScopeDecision.LocalFallback -> {
                    if(decision is ScopePolicy.ScopeDecision.LocalFallback && !decision.included)continue
                    candidates+=media
                }
                ScopePolicy.ScopeDecision.Excluded -> {}
                is ScopePolicy.ScopeDecision.DeniedUnknownScopeVersion -> {
                    notice.value="备份范围版本不受支持，请更新应用后再试"
                    Diagnostics.event("scope.denied",attrs=JSONObject().put("error",decision.error))
                    return null
                }
            }
        }
        val job=BackupJob(UUID.randomUUID().toString(),settings.account,System.currentTimeMillis(),automatic,allowMobile=allowMobile,
            albumMode=settings.albumMode,albumValue=settings.albumValue,quality=settings.quality,liveStrategy=settings.livePhotoPairing,editPolicy=settings.editPolicy,strictConfirmation=settings.strictConfirmation)
        val selected=ArrayList<String>();var created=0;var reused=0
        graph.database.withTransaction {
            for(media in candidates) {
                val existing=graph.dao.activeItem(settings.account,media.id)
                if(existing!=null&&automatic)continue
                if(existing!=null&&existing.state !in setOf("queued","album_pending")) {
                    selected+=existing.id;reused++;continue
                }
                if(created==0)graph.dao.putJob(job)
                val item=if(existing==null)BackupItem(UUID.randomUUID().toString(),job.id,settings.account,media.id,media.name,media.size,modified=media.modified)
                    else existing.copy(jobId=job.id,state=if(existing.remoteKey.isEmpty())"queued" else "album_pending",error="")
                graph.dao.putItem(item)
                if(existing!=null)graph.dao.putItem(existing.copy(id=UUID.randomUUID().toString(),state="cancelled",error="PROMOTED"))
                selected+=item.id;created++
            }
        }
        if(selected.isEmpty())null else BackupSubmission(selected,created,reused)
    }

    /**
     * Mirrors a batch pause/resume into the core state machine, per task.
     * Input is {task_ids, tasks} (stage-two batch_pause / batch_resume);
     * the result carries per-task outcomes and a partial mirror never
     * blocks the local state change — the local Room state stays
     * authoritative and the failure is logged. When the synced core predates
     * the batch actions (UNKNOWN_OPERATION) each task is mirrored with a
     * plain transition instead. There is no "re-sync on next entry" gap:
     * the mirror happens here, synchronously with the local change.
     * Returns true only when every mirrored task was accepted.
     */
    private suspend fun mirrorBatchToCore(action:String,itemIds:List<String>):Boolean {
        if(itemIds.isEmpty())return true
        val loaded=runCatching{graph.harborCore.storeLoad()}.getOrNull()
        val tasks=loaded?.optJSONObject("tasks")?:JSONObject()
        val present=itemIds.filter{tasks.has(it)}
        if(present.isEmpty())return true
        val subset=JSONObject()
        present.forEach{subset.put(it,tasks.get(it))}
        val result=if(action=="batch_pause")graph.harborCore.batchPause(present,subset)
            else graph.harborCore.batchResume(present,subset)
        if(result.optBoolean("ok")) {
            val parsed=HarborCore.parseBatchResults(result)
            val failed=present.filter{parsed[it]?.optBoolean("ok")!=true}
            if(failed.isNotEmpty())Diagnostics.event("batch.mirror_partial",attrs=JSONObject().put("action",action).put("failed",org.json.JSONArray(failed)))
            return failed.isEmpty()
        }
        if(graph.harborCore.isUnknownOperation(result)) {
            var ok=true
            for(id in present) {
                val task=tasks.optJSONObject(id)?:continue
                val target=if(action=="batch_pause")HarborCoreState.PAUSED else HarborCoreState.QUEUED
                val single=runCatching{graph.harborCore.transition(task,target)}.getOrNull()
                if(single==null||!single.optBoolean("ok"))ok=false
            }
            return ok
        }
        Diagnostics.event("batch.mirror_failed",attrs=JSONObject().put("action",action).put("error",result.optString("error")))
        return false
    }

    /**
     * Batch pause: mirrors every non-terminal item of each job to the core
     * (batch_pause) and then pauses locally. Per-job mirror success is
     * returned; a failed mirror never blocks the local pause.
     */
    suspend fun pauseJobs(jobIds:List<String>):Map<String,Boolean> {
        stopping.set(true)
        val results=mutableMapOf<String,Boolean>()
        for(jobId in jobIds) {
            val itemIds=graph.dao.items(jobId).filter{it.state !in BackupPolicy.terminalItems}.map{it.id}
            val mirrored=runCatching{mirrorBatchToCore("batch_pause",itemIds)}.getOrDefault(false)
            graph.dao.jobState(jobId,"paused")
            results[jobId]=mirrored
            Diagnostics.event("job.state_changed",DiagnosticContext(job=jobId),JSONObject().put("state","paused").put("core_mirrored",mirrored))
        }
        return results
    }

    /** Batch resume: mirrors each job's items to the core (batch_resume) and re-queues locally. */
    suspend fun resumeJobs(jobIds:List<String>):Map<String,Boolean> {
        val results=mutableMapOf<String,Boolean>()
        for(jobId in jobIds) {
            val job=graph.dao.job(jobId)?:continue
            if(job.account!=graph.preferences.get().account)continue
            val itemIds=graph.dao.items(jobId).filter{it.state !in BackupPolicy.terminalItems}.map{it.id}
            // batch_resume only accepts paused / interrupted tasks (plus an
            // idempotent replay of already-queued ones): tasks left mid-flight
            // (uploading/submitting/confirming) are first moved to
            // interrupted, exactly as restart recovery does.
            runCatching {
                val loaded=graph.harborCore.storeLoad()
                val tasks=loaded.optJSONObject("tasks")
                if(loaded.optBoolean("ok")&&tasks!=null) {
                    for(id in itemIds) {
                        val task=tasks.optJSONObject(id)?:continue
                        if(task.optString("state") in setOf(HarborCoreState.UPLOADING,HarborCoreState.SUBMITTING,HarborCoreState.CONFIRMING)) {
                            runCatching{graph.harborCore.transition(task,HarborCoreState.INTERRUPTED)}
                        }
                    }
                }
            }
            val mirrored=runCatching{mirrorBatchToCore("batch_resume",itemIds)}.getOrDefault(false)
            graph.dao.putJob(job.copy(state="queued",manualPriority=true))
            results[jobId]=mirrored
        }
        return results
    }

    /** Batch cancel: per-item core transition to cancelled (best-effort), then local end. */
    suspend fun cancelJobs(jobIds:List<String>) {
        val loaded=runCatching{graph.harborCore.storeLoad()}.getOrNull()
        val tasks=loaded?.optJSONObject("tasks")
        for(jobId in jobIds) {
            if(tasks!=null) {
                for(item in graph.dao.items(jobId)) {
                    val task=tasks.optJSONObject(item.id)?:continue
                    runCatching{graph.harborCore.transition(task,HarborCoreState.CANCELLED)}
                }
            }
            end(jobId)
        }
    }

    suspend fun pause() {
        Diagnostics.event("cancel.requested",attrs=JSONObject().put("kind","backup_pause"))
        stopping.set(true)
        activeJob.value.takeIf{it.isNotEmpty()}?.let {
            graph.dao.jobState(it,"pausing")
            runCatching{pauseJobs(listOf(it))}
        }
    }
    suspend fun stopAndWait() {
        stopping.set(true)
        lock.withLock { activeJob.value="";progress.value=TransferProgress();inFlight.value=emptyMap() }
    }
    suspend fun resume(jobId:String) {
        val job=graph.dao.job(jobId)?:return
        if(job.account!=graph.preferences.get().account)return
        resumeJobs(listOf(jobId))
        graph.scheduler.launchManual(job.allowMobile)
    }
    suspend fun retry(jobId:String) {
        val job=graph.dao.job(jobId)?:return
        if(job.account!=graph.preferences.get().account)return
        val requeued=graph.dao.items(jobId).filter{it.state in setOf("failed","album_pending")}
        requeued.forEach {
            graph.dao.putItem(it.copy(state=if(it.remoteKey.isEmpty())"queued" else "album_pending",error="",attempt=0,confirmationElapsedMs=0))
        }
        // Retried items go back through the core state machine as well.
        // batch_resume only accepts paused / interrupted tasks, so failed
        // items resume through the single-task retry edge (failed -> queued),
        // which the state machine allows.
        runCatching {
            val loaded=graph.harborCore.storeLoad()
            val tasks=loaded.optJSONObject("tasks")
            if(loaded.optBoolean("ok")&&tasks!=null) {
                for(item in requeued) {
                    val task=tasks.optJSONObject(item.id)?:continue
                    runCatching{graph.harborCore.transition(task,HarborCoreState.QUEUED)}
                }
            }
        }
        graph.dao.putJob(job.copy(state="queued",manualPriority=true));graph.scheduler.launchManual(job.allowMobile)
    }
    /**
     * Best-effort recovery of shared-core tasks after a process restart.
     * Core tasks left in uploading/submitting/confirming cannot be resumed
     * mid-flight, so they are marked interrupted in the core and may be
     * retried; the Room side is already reset to queued by recoverItems().
     * Never throws: when the core is absent this is a no-op.
     */
    suspend fun recoverCoreTasks() {
        val loaded=runCatching{graph.harborCore.storeLoad()}.getOrNull()?:return
        if(!loaded.optBoolean("ok"))return
        val tasks=loaded.optJSONObject("tasks")?:return
        val keys=tasks.keys()
        while(keys.hasNext()) {
            val task=tasks.optJSONObject(keys.next())?:continue
            val state=task.optString("state")
            if(state in setOf("done","cancelled"))clearTaskArtifacts(task.optString("task_id"))
            val item=graph.dao.item(task.optString("task_id"))
            if(item!=null && graph.dao.job(item.jobId)?.state!="ended") {
                if(state=="cancelled")graph.dao.putItem(item.copy(state="cancelled"))
                if(state=="paused")graph.dao.putItem(item.copy(state="paused"))
                if(state=="done") {
                    val asset=task.optJSONObject("asset")
                    val key=task.optString("remote_key").takeUnless{it=="null"}.orEmpty().ifBlank{item.remoteKey}
                    val media=graph.dao.media(item.mediaId)
                    if(asset!=null&&key.isNotBlank()&&media!=null)graph.database.withTransaction {
                        graph.dao.putBackup(BackupRecord(item.account,item.mediaId,asset.getString("sha1"),asset.getString("sha256"),key,asset.getLong("size"),item.modified,"unknown","recovered",System.currentTimeMillis()))
                        graph.dao.putItem(item.copy(state="done",remoteKey=key))
                    }
                }
            }
            if(state==HarborCoreState.CONFIRMING&&task.optBoolean("cloud_confirmed")) {
                graph.harborCore.transition(task,"waiting_confirmation")
                if(item!=null)graph.dao.putItem(item.copy(state="waiting_confirmation"))
            } else if(state in setOf(HarborCoreState.UPLOADING,HarborCoreState.SUBMITTING,HarborCoreState.CONFIRMING)) {
                runCatching{graph.harborCore.transition(task,HarborCoreState.INTERRUPTED)}
            }
        }
    }
    private fun clearTaskArtifacts(id:String) {
        if(!id.matches(Regex("[a-zA-Z0-9-]{1,100}")))return
        val runtime=java.io.File(graph.app.filesDir,"runtime")
        for(task in listOf(id,"$id-motion"))for(suffix in listOf(".receipt",".submitted",".digest.json",".part"))
            java.io.File(runtime,"ordinary-staging/$task$suffix").delete()
        for(suffix in listOf(".photo.receipt",".video.receipt",".submitted",".photo-submitted",".motion.jpg",".motion.heic",".motion.part",".motion.sources.json"))
            java.io.File(runtime,"pair-staging/$id$suffix").delete()
    }
    suspend fun end(jobId:String) {
        if(activeJob.value==jobId)stopAndWait()
        // Mirror the cancel into the core first (best-effort per task);
        // the local state change below stays authoritative.
        runCatching {
            val loaded=graph.harborCore.storeLoad()
            val tasks=loaded.optJSONObject("tasks")
            if(loaded.optBoolean("ok")&&tasks!=null) {
                for(item in graph.dao.items(jobId)) {
                    val task=tasks.optJSONObject(item.id)?:continue
                    runCatching{graph.harborCore.transition(task,HarborCoreState.CANCELLED)}
                }
            }
        }
        graph.dao.items(jobId).filter { it.state !in setOf("done","exists") }.forEach {
            graph.dao.suppress(SuppressedAsset(it.account,it.mediaId,it.modified))
        }
        graph.dao.jobState(jobId,"ended")
        graph.dao.items(jobId).filter{it.state !in BackupPolicy.terminalItems}.forEach{graph.dao.putItem(it.copy(state="cancelled"))}
    }
    suspend fun run(externalCancelled:()->Boolean={false},notify:(String,Int)->Unit={_,_->}):Boolean {
        graph.startup.await()
        if(graph.switching.value)return true
        lock.lock()
        if(graph.switching.value||externalCancelled()){lock.unlock();return false}
        stopping.set(false)
        try {
            val account=graph.ensureAccount()
            while(!externalCancelled()&&!stopping.get()) {
                val storedSettings=graph.preferences.get()
                val protection=graph.harborCore.dispatch("resolve_execution",JSONObject().put("overrides",storedSettings.executionPolicy.takeIf{it.isNotBlank()}?.let{JSONObject(it)}?:JSONObject.NULL))
                if(!protection.optBoolean("ok"))throw EngineFailure("INVALID_EXECUTION_POLICY")
                val settings=storedSettings.copy(retries=protection.getJSONObject("policy").getInt("retries"))
                graph.pipeline.configure(settings)
                val charging=graph.app.getSystemService(android.os.BatteryManager::class.java).isCharging
                val job=graph.dao.nextJob(account,settings.auto&&(!settings.charging||charging))?:break
                graph.ensureAccount(job.proxyPolicy)
                if(job.account!=settings.account)break
                if(settings.wifi&&!job.allowMobile&&!onWifi()) {
                    graph.dao.jobState(job.id,"waiting");notice.value="等待 Wi-Fi 连接";return false
                }
                activeJob.value=job.id;graph.dao.jobState(job.id,"running")
                val jobDiagnostic=DiagnosticContext(job=job.id)
                Diagnostics.event("job.state_changed",jobDiagnostic,JSONObject().put("state","running").put("concurrency",settings.concurrency).put("setting_timeout_ms",settings.timeout*1000).put("retry_limit",settings.retries))
                val items=graph.dao.items(job.id).filter{it.state in setOf("queued","album_pending","waiting_confirmation","hashing","checking","uploading","finalizing","finalizing_album")}
                val limits=graph.pipeline.effective.value
                val semaphore=Semaphore(listOf("preparation","lookup","upload","commit","confirmation").sumOf{limits.optInt(it)}.coerceAtLeast(1))
                val authenticationFailure=AtomicBoolean(false)
                val preempted=AtomicBoolean(false)
                val completed=java.util.concurrent.atomic.AtomicInteger(graph.dao.items(job.id).count{it.state in setOf("done","exists")})
                val total=graph.dao.items(job.id).size
                supervisorScope {
                    items.map { item -> async(Dispatchers.IO) {
                        val waiting=jobDiagnostic.child().copy(item=item.id)
                        val waitStarted=android.os.SystemClock.elapsedRealtime()
                        Diagnostics.event("wait.started",waiting,JSONObject().put("kind","permit"))
                        var acquired=false
                        try { semaphore.withPermit {
                        acquired=true
                        Diagnostics.event("permit.acquired",waiting)
                        Diagnostics.event("wait.finished",waiting,JSONObject().put("kind","permit").put("wait_ms",android.os.SystemClock.elapsedRealtime()-waitStarted))
                        if(stopping.get()||externalCancelled()||authenticationFailure.get()||graph.switching.value)return@withPermit
                        if(graph.dao.item(item.id)?.jobId!=job.id)return@withPermit
                        if(job.automatic&&graph.dao.pendingManual(account)>0){preempted.set(true);return@withPermit}
                        val media=graph.dao.media(item.mediaId)
                        if(!onWifi()&&!job.allowMobile&&settings.mobile=="photos"&&media?.video==true)return@withPermit
                        process(job,item,settings,{item.id in suspendedItems||graph.switching.value||stopping.get()||externalCancelled()||authenticationFailure.get()||(settings.wifi&&!job.allowMobile&&!onWifi())})
                        if(item.id in suspendedItems)graph.dao.item(item.id)?.let{current->
                            graph.dao.putItem(current.copy(state=if(item.id in cancelledItems)"cancelled" else "paused",hidden=current.hidden||item.id in cancelledItems))
                        }
                        if(graph.dao.item(item.id)?.error in setOf("LOGIN_REQUIRED","DEVICE_VERIFICATION","ENCRYPTED_TOKEN"))authenticationFailure.set(true)
                        val done=completed.incrementAndGet();notify("正在备份 · $done / $total",if(total>0)done*100/total else 0)
                    } } finally {
                        Diagnostics.event(if(acquired)"permit.released" else "wait.finished",waiting,JSONObject().put("kind","permit").put("outcome",if(acquired)"released" else "cancelled"))
                    } } }.awaitAll()
                }
                var updated=graph.dao.items(job.id)
                val networkRetries=updated.filter {
                    it.state=="failed" && it.error in setOf("NETWORK_ERROR","RATE_LIMITED","STAGE_TIMEOUT","NO_PROGRESS_TIMEOUT") &&
                        it.attempt in 1..settings.retries
                }
                if(networkRetries.isNotEmpty()&&!stopping.get()&&!externalCancelled()&&!authenticationFailure.get()) {
                    // Reconnect only after all concurrent transfers have finished.
                    // The next wave can then choose another healthy proxy safely.
                    val delayMs=graph.harborCore.retryDelayMs(networkRetries.maxOf{it.attempt}-1)
                    notice.value="网络暂时不可用，稍后重试"
                    var remaining=delayMs
                    while(remaining>0&&!stopping.get()&&!externalCancelled()&&!graph.switching.value) {
                        val interval=minOf(remaining,250L);delay(interval);remaining-=interval
                    }
                    if(!stopping.get()&&!externalCancelled()&&!graph.switching.value) {
                        networkRetries.forEach{graph.dao.queueNetworkRetry(it.id,it.attempt)}
                        updated=graph.dao.items(job.id)
                    }
                }
                val paused=stopping.get()||externalCancelled()
                val state=when {
                    stopping.get()->"paused"
                    externalCancelled()->"queued"
                    authenticationFailure.get()->"auth_required"
                    preempted.get()->"queued"
                    updated.any{it.state in setOf("failed","album_pending")}->"needs_attention"
                    updated.any{it.state in setOf("queued","waiting_confirmation")}->"waiting"
                    updated.any{it.state=="paused"}->"paused"
                    updated.all{it.state=="cancelled"}->"ended"
                    else->"completed"
                }
                Diagnostics.event("job.state_changed",jobDiagnostic,JSONObject().put("state",state))
                graph.dao.putJob(job.copy(state=state,completedAt=if(state in setOf("paused","waiting","auth_required","queued"))0 else System.currentTimeMillis()))
                if(!job.automatic&&state in setOf("completed","needs_attention")&&!graph.visible.get()) {
                    TransferNotifications.completed(graph.app,job,updated)
                }
                if(state=="waiting")return false
                if(paused||authenticationFailure.get())break
            }
            return !externalCancelled()
        } catch (error:Exception) {
            notice.value=Messages.error((error as? EngineFailure)?.code?:"ENGINE_ERROR")
            return false
        } finally {
            withContext(NonCancellable) {
                activeJob.value.takeIf{it.isNotEmpty()}?.let { id ->
                    graph.dao.items(id).filter{it.state in setOf("hashing","checking","uploading","finalizing","finalizing_album")}.forEach{graph.dao.putItem(it.copy(state="queued"))}
                    if(graph.dao.job(id)?.state in setOf("running","pausing"))graph.dao.jobState(id,if(stopping.get())"paused" else "queued")
                }
                activeJob.value="";progress.value=TransferProgress();inFlight.value=emptyMap();lock.unlock()
            }
        }
    }
    /**
     * Entry point for one item. Every media kind goes through the shared
     * core now (photo, video, live_photo); there is no legacy direct-call
     * fallback any more — stage two removed it, so there is exactly one
     * business core. The engine "process" call survives only as the
     * transport adapter ([transportItem]): it moves bytes and commits, the
     * core decides. When the core cannot be reached at all the item is left
     * queued for a later run (and the user is told) instead of silently
     * running a second business implementation.
     */
    private suspend fun process(job:BackupJob,item:BackupItem,settings:HarborSettings,cancelled:()->Boolean) {
        val media=graph.dao.media(item.mediaId)
        if(media==null) {
            graph.dao.putItem(item.copy(state="failed",error="FILE_ACCESS",attempt=item.attempt+1))
            return
        }
        if(media.modified!=item.modified||media.size!=item.bytes) {
            graph.dao.putItem(item.copy(state="failed",error="SOURCE_CHANGED",attempt=item.attempt+1));return
        }
        // The run loop snapshots items up front; a Live Photo partner may
        // already have been finished by its pair's joint task. Re-read.
        if(graph.dao.item(item.id)?.state !in setOf("queued","album_pending","waiting_confirmation","hashing","checking","uploading","finalizing","finalizing_album"))return
        val all=identifiedMedia()
        val identified=all.firstOrNull{it.id==media.id}?:media
        val savedTask=graph.harborCore.task(item.id)
        if(item.remoteKey.isNotBlank()&&(item.state=="waiting_confirmation"||savedTask?.optBoolean("cloud_confirmed")==true&&savedTask.optString("state")!="done")) {
            finishStrictConfirmation(job,item,cancelled);return
        }
        try {requireAvailableVersions(job.editPolicy)} catch(error:EngineFailure) {
            graph.dao.putItem(item.copy(state="failed",error=error.code,attempt=item.attempt+1));return
        }
        val embeddedMotion=identified.pairingID==LivePhotoPairing.EMBEDDED_MOTION
        val group=LivePhotoPairing.groupFor(identified,all,job.liveStrategy,embeddedMotion)
        if(identified.pairingID.isNotBlank()&&!embeddedMotion&&group?.video==null) {
            graph.dao.putItem(item.copy(state="failed",error="LIVE_PAIR_RESOURCE_MISSING",attempt=item.attempt+1))
            return
        }
        if(group?.video!=null) {
            processLivePhotoWithCore(job,item,media,group,settings,cancelled)
            return
        }
        if(!processSingleWithCore(job,item,identified,settings,cancelled)) {
            val current=graph.dao.item(item.id)
            if(current!=null&&graph.dao.job(job.id)?.state!="ended"&&current.state !in setOf("done","exists","cancelled","paused"))
                graph.dao.putItem(current.copy(state="queued",error="ENGINE_ERROR"))
            notice.value="共享核心暂不可用，稍后会自动重试"
        }
    }
    /** One local file's digests plus its cloud lookup key, for one resource. */
    private data class ResourceReady(val media:MediaEntry,val sha1:String,val sha256:String,val size:Long,val remoteKey:String,val durationSeconds:Long?)
    private fun keyframeUs(media:MediaEntry):Long?=runCatching {
        if(keyframeHints["${media.id}:${media.modified}:${media.size}"]!=true)return@runCatching null
        val extractor=android.media.MediaExtractor()
        try {
            extractor.setDataSource(graph.app,android.net.Uri.parse(media.id),null)
            val tracks=(0 until extractor.trackCount).filter{extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("application/")==true}
            if(tracks.size!=1)return@runCatching null
            extractor.selectTrack(tracks.single())
            extractor.sampleTime.takeIf{it>=0}
        } finally {extractor.release()}
    }.getOrNull()

    /** Video duration in whole seconds, or null when it cannot be read. Never throws. */
    private fun videoDurationSeconds(media:MediaEntry):Long? {
        return runCatching {
            val retriever=android.media.MediaMetadataRetriever()
            try {
                graph.app.contentResolver.openFileDescriptor(android.net.Uri.parse(media.id),"r")?.use { fd ->
                    retriever.setDataSource(fd.fileDescriptor)
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000)
                }
            } finally {
                runCatching{retriever.release()}
            }
        }.getOrNull()
    }

    /**
     * Fingerprints one resource and looks its content up in the cloud. No
     * bytes are uploaded and nothing is committed; the caller (the core
     * flow) decides from the returned remote key.
     */
    private suspend fun prepareResource(job:BackupJob,media:MediaEntry,diagnostic:DiagnosticContext):ResourceReady {
        val before=graph.media.current(media)?:throw EngineFailure("FILE_ACCESS")
        if(before.first!=media.size||before.second!=media.modified)throw EngineFailure("SOURCE_CHANGED")
        val print=graph.engine.call("fingerprint",JSONObject().put("size",media.size).put("_diagnostics",diagnostic.json()).put("_account",job.account).put("uri",media.id).put("name",media.name))
        val sha1=print.optString("sha1");val sha256=print.optString("sha256");val size=print.optLong("size",media.size)
        if(sha1.isEmpty()||sha256.isEmpty()||size!=media.size)throw EngineFailure("SOURCE_CHANGED")
        val lookup=graph.engine.call("lookup",JSONObject().put("_diagnostics",diagnostic.json()).put("_account",job.account).put("uri",media.id).put("name",media.name))
        return ResourceReady(media,sha1,sha256,size,lookup.optString("remote_key"),if(media.video)videoDurationSeconds(media) else null)
    }

    /**
     * Live Photo / multi-resource flow through HarborCore: one core task
     * (media_kind=live_photo for a pair, motion_photo for an embedded
     * single-file Motion Photo) for the item, one decide_prepare with
     * asset.resources + remote_keys keyed by role, joint submission
     * (missing resources are transported, then ONE submitting ->
     * confirming -> done for the pair), and stepwise fallback (returns
     * false; the caller processes each resource as its own single core
     * task) when the core declines the joint task.
     * A Live Photo is confirmed as one media item, never per half, and an
     * unverified Live Photo is never cleaned up (this function never
     * deletes anything; it only backs up).
     * Returns false only when the core could not be reached / declined the
     * joint task; returns true once the core owns the task (including its
     * failure handling).
     */
    private suspend fun processLivePhotoWithCore(job:BackupJob,item:BackupItem,media:MediaEntry,group:LivePhotoPairing.LivePhotoGroup,settings:HarborSettings,cancelled:()->Boolean):Boolean {
        val video=group.video?:return false
        var task:JSONObject?=null
        suspend fun move(state:String,confirmed:Boolean?=null,remoteKey:String?=null) {
            val result=graph.harborCore.transition(task!!,state,cloudConfirmed=confirmed,remoteKey=remoteKey)
            task=graph.harborCore.updatedTask(result)?:throw EngineFailure(result.optString("error","ENGINE_ERROR"))
        }
        try {
            val photo=prepareResource(job,group.photo,DiagnosticContext(job=job.id,item=item.id))
            val movie=prepareResource(job,video,DiagnosticContext(job=job.id,item=item.id))
            val resources=org.json.JSONArray().put(JSONObject().put("role","photo").put("sha1",photo.sha1).put("sha256",photo.sha256).put("size_bytes",photo.size))
                .put(JSONObject().put("role","video").put("sha1",movie.sha1).put("sha256",movie.sha256).put("size_bytes",movie.size))
            val asset=JSONObject().put("local_id",group.photo.id).put("filename",group.photo.name).put("sha1",photo.sha1).put("sha256",photo.sha256).put("size",photo.size).put("media_kind","live_photo").put("resources",resources)
            task=graph.harborCore.updatedTask(graph.harborCore.createTask(item.id,asset,"android:${group.photo.id}:${group.photo.modified}"))?:throw EngineFailure("ENGINE_ERROR")
            if(task!!.optString("state")=="waiting_confirmation")move("confirming")
            else if(task!!.optString("state")=="confirming") { /* Retry album sync after confirmed pair. */ }
            else {move("preparing");move("uploading");move("submitting");move("confirming")}
            fun resource(value:MediaEntry)=JSONObject().put("uri",value.id).put("name",value.name)
            val request=JSONObject().put("size",group.photo.size+video.size).put("_diagnostics",DiagnosticContext(job=job.id,item=item.id).json()).put("_account",job.account).put("task_id",item.id).put("photo",resource(group.photo)).put("video",resource(video))
                .put("quality",job.quality).put("strategy",job.liveStrategy).put("created",group.photo.captured).put("modified",group.photo.modified)
                .put("album_mode",job.albumMode).put("album_value",job.albumValue).put("folder",group.photo.albumFolder)
            if(job.liveStrategy=="motion")keyframeUs(video)?.let{request.put("keyframe_us",it)}
            graph.dao.putItem(item.copy(state="uploading"))
            val result=graph.engine.call("process_pair",request,EngineCallback(cancelled){event->
                progress.value=TransferProgress(job.id,item.id,media.name,event.optString("phase"),event.optLong("completed"),event.optLong("total"))
            })
            if(cancelled()||graph.dao.job(job.id)?.state=="ended")throw EngineFailure("JOB_PAUSED")
            if(result.optString("state")=="waiting_confirmation") {
                move("waiting_confirmation")
                graph.dao.putItem(item.copy(state="waiting_confirmation",error="PAIR_CONFIRMATION_PENDING"))
                return true
            }
            val albumPending=result.optString("state")=="album_pending"
            if(result.optString("state")!="done"&&!albumPending)throw EngineFailure(result.optString("error","PAIR_CONFIRMATION_PENDING"))
            val key=result.getString("remote_key")
            val dynamicPending=!albumPending&&job.strictConfirmation&&!dynamicConfirmed(job,key,cancelled)
            if(dynamicPending)move("waiting_confirmation",true,key) else if(!albumPending)move("done",true,key)
            graph.database.withTransaction {
                for((entry,role) in listOf(group.photo to "photo",video to "video")) {
                    val value=result.getJSONObject(role)
                    graph.dao.putBackup(BackupRecord(job.account,entry.id,value.getString("sha1"),value.getString("sha256"),key,value.getLong("size"),entry.modified,job.quality,"paired",System.currentTimeMillis()))
                }
                graph.dao.putItem(item.copy(state=if(dynamicPending)"waiting_confirmation" else if(albumPending)"album_pending" else "done",error=if(dynamicPending)"REMOTE_MOTION_PENDING" else result.optString("error"),remoteKey=key,sha1=photo.sha1,sha256=photo.sha256,origin="paired",attempt=item.attempt+1))
            }
            return true
        } catch(error:Exception) {
            if(graph.dao.job(job.id)?.state=="ended")return true
            task?.let{runCatching{graph.harborCore.transition(it,if(cancelled())"paused" else "failed",errorCode=(error as? EngineFailure)?.code?:"ENGINE_ERROR")}}
            graph.dao.putItem(item.copy(state=if(cancelled())"queued" else "failed",error=(error as? EngineFailure)?.code?:"ENGINE_ERROR",attempt=item.attempt+1))
            return true
        }
    }
    /**
     * Single-resource flow through HarborCore, for photos and videos alike:
     * create_task -> transition preparing -> engine lookup -> decide_prepare
     * (dedup_hit skips the upload; the remote_key hit is the single dedup
     * decision — the transport no longer re-derives it, see [transportItem])
     * -> transition uploading -> transport -> submitting/confirming -> done
     * only after the cloud commit returned a remote key. The core task dict
     * is threaded through every transition (the core is stateless); a failed
     * transition is a best-effort mirror failure and never blocks the local
     * flow. Video assets carry media_kind=video plus duration_seconds and
     * size, on the same flow as photos. Content hashes are used for the
     * core decision and never written to logs.
     * Returns false only when the core could not be reached / declined the
     * task (the item stays queued); returns true once the core owns the
     * task (including its failure handling).
     */
    private suspend fun processSingleWithCore(job:BackupJob,item:BackupItem,media:MediaEntry,settings:HarborSettings,cancelled:()->Boolean):Boolean {
        val diagnostic=DiagnosticContext(job=job.id,item=item.id,attempt=UUID.randomUUID().toString())
        var latest=item
        var coreTask:JSONObject?=null
        fun advance(result:JSONObject):JSONObject? {
            val updated=graph.harborCore.updatedTask(result)
            if(updated==null)throw EngineFailure(result.optString("error","ENGINE_ERROR"))
            coreTask=updated
            return updated
        }
        val mediaKind=if(media.video)"video" else if(media.pairingID==LivePhotoPairing.EMBEDDED_MOTION)"motion_photo" else "photo"
        try {
            graph.dao.putItem(item.copy(state="hashing"))
            val ready=prepareResource(job,media,diagnostic)
            latest=latest.copy(sha1=ready.sha1,sha256=ready.sha256)
            val asset=JSONObject().put("local_id",media.id).put("filename",media.name).put("sha1",ready.sha1).put("sha256",ready.sha256).put("size",ready.size).put("media_kind",mediaKind)
            if(mediaKind=="video"&&ready.durationSeconds!=null)asset.put("duration_seconds",ready.durationSeconds)
            val created=graph.harborCore.createTask(item.id,asset,"android:${media.id}:${media.modified}")
            if(advance(created)==null) {
                if(created.optString("error") in setOf("INVALID_DIGEST","INVALID_ARGUMENT","INVALID_MEDIA_KIND")) {
                    graph.dao.putItem(latest.copy(state="failed",error=created.optString("error"),attempt=item.attempt+1))
                    return true
                }
                return false
            }
            runCatching{graph.harborCore.logEvent("item.state_changed",item.id,JSONObject().put("state","created").put("filename",media.name))}
            if(coreTask!!.optString("state") in setOf("waiting_confirmation","confirming")&&item.remoteKey.isBlank()) {
                if(ready.remoteKey.isBlank()) {
                    graph.dao.putItem(latest.copy(state="waiting_confirmation",error="REMOTE_NOT_VISIBLE"));return true
                }
                advance(graph.harborCore.transition(coreTask!!,"confirming",remoteKey=ready.remoteKey,cloudConfirmed=true))
                latest=latest.copy(remoteKey=ready.remoteKey,origin="content_exists")
                graph.dao.putBackup(BackupRecord(job.account,media.id,ready.sha1,ready.sha256,ready.remoteKey,ready.size,media.modified,"unknown","content_exists",System.currentTimeMillis()))
                if(!syncConfirmedAlbum(job,latest,media,ready.remoteKey,cancelled))return true
                if(mediaKind=="motion_photo"&&job.strictConfirmation) {
                    graph.dao.putItem(latest.copy(state="waiting_confirmation",error="REMOTE_MOTION_PENDING"))
                    if(!dynamicConfirmed(job,ready.remoteKey,cancelled)) {
                        advance(graph.harborCore.transition(coreTask!!,"waiting_confirmation",remoteKey=ready.remoteKey,cloudConfirmed=true));return true
                    }
                }
                advance(graph.harborCore.transition(coreTask!!,"done",remoteKey=ready.remoteKey,cloudConfirmed=true))
                graph.dao.putItem(latest.copy(state="exists",error=""));return true
            }
            if(coreTask!!.optString("state") in setOf("confirming","dedup_hit")&&item.remoteKey.isNotBlank()) {
                if(ready.remoteKey!=item.remoteKey)throw EngineFailure("REMOTE_MISSING")
                if(!syncConfirmedAlbum(job,latest,media,item.remoteKey,cancelled))return true
                advance(graph.harborCore.transition(coreTask!!,HarborCoreState.DONE,remoteKey=item.remoteKey,cloudConfirmed=true))
                graph.dao.putItem(latest.copy(state=if(item.origin=="content_exists")"exists" else "done",error="",attempt=item.attempt+1))
                return true
            }
            if(advance(graph.harborCore.transition(coreTask!!,HarborCoreState.PREPARING))==null)return false
            val remoteKey=ready.remoteKey
            val prepared=graph.harborCore.decidePrepare(asset,remoteKey.ifEmpty{null})
            if(!prepared.optBoolean("ok")) {
                val code=prepared.optString("error")
                if(code in setOf("INVALID_DIGEST","INVALID_ARGUMENT","INVALID_MEDIA_KIND")) {
                    graph.dao.putItem(latest.copy(state="failed",error=code,attempt=item.attempt+1))
                    runCatching{graph.harborCore.transition(coreTask!!,HarborCoreState.FAILED,errorCode=code)}
                    return true
                }
                runCatching{graph.harborCore.transition(coreTask!!,HarborCoreState.INTERRUPTED)}
                return false
            }
            if(HarborCore.isDedupHit(prepared)) {
                val hitKey=prepared.optString("remote_key")
                if(hitKey.isNotEmpty()) {
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.DEDUP_HIT,remoteKey=hitKey))
                    val record=BackupRecord(job.account,media.id,ready.sha1,ready.sha256,hitKey,ready.size,media.modified,"unknown","content_exists",System.currentTimeMillis())
                    graph.dao.putBackup(record)
                    latest=latest.copy(remoteKey=hitKey,origin="content_exists")
                    if(!syncConfirmedAlbum(job,latest,media,hitKey,cancelled))return true
                    if(mediaKind=="motion_photo"&&job.strictConfirmation) {
                        advance(graph.harborCore.transition(coreTask!!,HarborCoreState.CONFIRMING,remoteKey=hitKey,cloudConfirmed=true))
                        graph.dao.putItem(latest.copy(state="waiting_confirmation",error="REMOTE_MOTION_PENDING",attempt=item.attempt+1))
                        if(!dynamicConfirmed(job,hitKey,cancelled)) {
                            advance(graph.harborCore.transition(coreTask!!,"waiting_confirmation",remoteKey=hitKey,cloudConfirmed=true));return true
                        }
                    }
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.DONE,remoteKey=hitKey,cloudConfirmed=true))
                    graph.dao.putItem(latest.copy(state="exists",remoteKey=hitKey,sha1=ready.sha1,sha256=ready.sha256,origin="content_exists",attempt=item.attempt+1))
                    Diagnostics.event("item.state_changed",diagnostic,JSONObject().put("state","exists").put("code",""))
                    runCatching{graph.harborCore.logEvent("item.state_changed",item.id,JSONObject().put("state","dedup_hit").put("filename",media.name))}
                    return true
                }
                // Hit without a usable key: let the transport confirm
                // the content lookup itself instead of recording a bad key.
            }
            advance(graph.harborCore.transition(coreTask!!,HarborCoreState.UPLOADING))
            // The lookup above already decided dedup: the transport is told
            // the lookup result (lookup_fresh) and must not derive it a
            // second time from the cloud.
            transportItem(job,latest,settings,cancelled,lookupRemoteKey=remoteKey,lookupFresh=true)
            val final=graph.dao.item(item.id)
            when(final?.state) {
                "done","exists"->{
                    // Cloud confirmation here is the commit_upload response
                    // carrying a remote key; the periodic reconcile() remains
                    // the independent check against the cloud library.
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.SUBMITTING))
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.CONFIRMING,remoteKey=final.remoteKey,cloudConfirmed=true))
                    if(mediaKind=="motion_photo"&&job.strictConfirmation) {
                        graph.dao.putItem(final.copy(state="waiting_confirmation",error="REMOTE_MOTION_PENDING"))
                        if(!dynamicConfirmed(job,final.remoteKey,cancelled)) {
                            advance(graph.harborCore.transition(coreTask!!,"waiting_confirmation",remoteKey=final.remoteKey,cloudConfirmed=true));return true
                        }
                    }
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.DONE,remoteKey=final.remoteKey,cloudConfirmed=true))
                    graph.dao.putItem(final.copy(error=""))
                    runCatching{graph.harborCore.logEvent("item.state_changed",item.id,JSONObject().put("state","done").put("filename",media.name))}
                }
                "album_pending"->{
                    // Content is committed; only the album step is pending.
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.SUBMITTING))
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.CONFIRMING))
                }
                "waiting_confirmation"->{
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.SUBMITTING))
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.CONFIRMING))
                    advance(graph.harborCore.transition(coreTask!!,"waiting_confirmation"))
                }
                "failed"->{
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.FAILED,errorCode=final.error.ifBlank{null}))
                    if(final.error in setOf("NETWORK_ERROR","RATE_LIMITED")) {
                        val delayMs=runCatching{graph.harborCore.retryDelayMs(item.attempt)}.getOrDefault(-1)
                        if(delayMs>=0)Diagnostics.event("retry.scheduled",diagnostic,JSONObject().put("code",final.error).put("retry_attempt",item.attempt+1).put("retry_limit",settings.retries).put("delay_ms",delayMs))
                    }
                }
                "cancelled"->advance(graph.harborCore.transition(coreTask!!,HarborCoreState.CANCELLED))
                else->{}
            }
            return true
        } catch(error:Exception) {
            coreTask?.let{task->runCatching{graph.harborCore.transition(task,HarborCoreState.INTERRUPTED)}}
            if(error is EngineFailure&&error.code in setOf("FILE_ACCESS","SOURCE_CHANGED","NETWORK_ERROR","RATE_LIMITED","STAGE_TIMEOUT","NO_PROGRESS_TIMEOUT")) {
                coreTask?.let{task->runCatching{graph.harborCore.transition(task,"failed",errorCode=error.code)}}
                graph.dao.putItem(latest.copy(state="failed",error=error.code,attempt=item.attempt+1))
                return true
            }
            // Core unreachable mid-flight: leave the item queued for retry.
            return false
        } finally {
            inFlight.update{it-item.id}
        }
    }
    /**
     * Platform transport adapter: moves bytes through the engine and
     * commits, nothing more. It makes NO business decisions — the core has
     * already decided (create/prepare/dedup) before this runs, and the
     * result state is read back by the core flow. The SOURCE_CHANGED
     * re-checks here are transport integrity guards, not decisions.
     * Dedup is NOT re-derived here: when the core flow already looked the
     * content up ([lookupFresh]), the lookup result ([lookupRemoteKey],
     * "" when absent) is authoritative and the engine skips its own
     * find_remote_media_by_hash call. Without those flags the engine keeps
     * its old behaviour (direct engine callers outside the core flow).
     */
    private suspend fun dynamicConfirmed(job:BackupJob,key:String,cancelled:()->Boolean):Boolean {
        return graph.engine.call("motion_status",JSONObject().put("_diagnostics",DiagnosticContext(job=job.id,item="confirmation").json()).put("_account",job.account).put("keys",org.json.JSONArray().put(key)),EngineCallback(cancelled)).optBoolean("recognized")
    }
    private suspend fun finishStrictConfirmation(job:BackupJob,item:BackupItem,cancelled:()->Boolean) {
        val saved=graph.harborCore.task(item.id)?:throw EngineFailure("ENGINE_ERROR")
        val start=if(saved.optString("state") in setOf("interrupted","failed"))graph.harborCore.updatedTask(graph.harborCore.transition(saved,"queued"))?:throw EngineFailure("ENGINE_ERROR") else saved
        val task=graph.harborCore.updatedTask(graph.harborCore.transition(start,"confirming",remoteKey=item.remoteKey,cloudConfirmed=true))?:throw EngineFailure("ENGINE_ERROR")
        val policy=graph.harborCore.dispatch("resolve_execution",JSONObject().put("overrides",graph.preferences.get().executionPolicy.takeIf{it.isNotBlank()}?.let{JSONObject(it)}?:JSONObject.NULL))
        if(!policy.optBoolean("ok"))throw EngineFailure("INVALID_EXECUTION_POLICY")
        if(job.strictConfirmation&&item.confirmationElapsedMs>=policy.getJSONObject("policy").getLong("cloud_minutes")*60000) {
            graph.harborCore.transition(task,"failed",errorCode="CLOUD_CONFIRMATION_TIMEOUT")
            graph.dao.putItem(item.copy(state="failed",error="CLOUD_CONFIRMATION_TIMEOUT"));return
        }
        val began=android.os.SystemClock.elapsedRealtime()
        try {
            if(job.strictConfirmation&&!dynamicConfirmed(job,item.remoteKey,cancelled)) {
                graph.harborCore.transition(task,"waiting_confirmation",remoteKey=item.remoteKey,cloudConfirmed=true)
                return
            }
        } catch(error:Exception) {
            graph.harborCore.transition(task,"waiting_confirmation",remoteKey=item.remoteKey,cloudConfirmed=true)
            throw error
        } finally {
            graph.dao.item(item.id)?.let{current->graph.dao.putItem(current.copy(confirmationElapsedMs=current.confirmationElapsedMs+android.os.SystemClock.elapsedRealtime()-began))}
        }
        if(cancelled())return
        val result=graph.harborCore.transition(task,"done",remoteKey=item.remoteKey,cloudConfirmed=true)
        if(!result.optBoolean("ok"))throw EngineFailure(result.optString("error","ENGINE_ERROR"))
        graph.dao.putItem(item.copy(state="done",error=""))
    }
    private suspend fun syncConfirmedAlbum(job:BackupJob,item:BackupItem,media:MediaEntry,key:String,cancelled:()->Boolean):Boolean {
        try {
            if(cancelled())throw EngineFailure("CANCELLED")
            graph.engine.call("sync_album",JSONObject().put("_diagnostics",DiagnosticContext(job=job.id,item=item.id).json()).put("_account",job.account).put("remote_key",key)
                .put("album_mode",job.albumMode).put("album_value",job.albumValue).put("folder",media.albumFolder),EngineCallback(cancelled))
            return true
        } catch(error:Exception) {
            graph.dao.putItem(item.copy(state="album_pending",remoteKey=key,error=(error as? EngineFailure)?.code?:"ENGINE_ERROR",attempt=item.attempt+1))
            return false
        }
    }
    private suspend fun transportItem(job:BackupJob,item:BackupItem,settings:HarborSettings,cancelled:()->Boolean,lookupRemoteKey:String="",lookupFresh:Boolean=false) {
        var latest=item
        val diagnostic=DiagnosticContext(job=job.id,item=item.id,attempt=UUID.randomUUID().toString())
        Diagnostics.event("item.state_changed",diagnostic,JSONObject().put("state","started").put("filename",item.name).put("retry_attempt",item.attempt+1))
        try {
            val media=graph.dao.media(item.mediaId)?:throw EngineFailure("FILE_ACCESS")
            val before=graph.media.current(media)?:throw EngineFailure("FILE_ACCESS")
            if(before.first!=media.size||before.second!=media.modified)throw EngineFailure("SOURCE_CHANGED")
            graph.dao.putItem(item.copy(state="hashing"))
            val request=JSONObject().put("task_id",item.id).put("size",media.size).put("_diagnostics",diagnostic.json()).put("_account",job.account).put("uri",media.id).put("name",media.name).put("modified",media.modified)
                .put("folder",media.albumFolder).put("quality",job.quality).put("album_mode",job.albumMode).put("album_value",job.albumValue)
                .put("retries",settings.retries).put("same_name",job.automatic&&settings.sameName)
                .put("remote_key",item.remoteKey).put("origin",item.origin)
                .put("lookup_remote_key",lookupRemoteKey).put("lookup_fresh",lookupFresh)
            if(item.sha256.isNotEmpty())request.put("sha256",item.sha256)
            var previousBytes=0L;var previousAt=System.nanoTime();var emittedAt=0L
            val callback=EngineCallback(cancelled) { event ->
                val now=System.nanoTime()
                if(event.optString("phase")=="uploaded") {
                    runBlocking { latest=uploaded(job,media,latest,event) }
                    val current=TransferProgress(job.id,item.id,media.name,"finalizing_album",media.size,media.size)
                    progress.value=current;inFlight.update{it+(item.id to current)}
                } else if(now-emittedAt>200_000_000||event.optString("phase")!="uploading") {
                    val bytes=event.optLong("completed");val elapsed=(now-previousAt).coerceAtLeast(1)
                    val current=TransferProgress(job.id,item.id,media.name,event.optString("phase"),bytes,event.optLong("total"),((bytes-previousBytes).coerceAtLeast(0)*1_000_000_000/elapsed))
                    progress.value=current;inFlight.update{it+(item.id to current)}
                    previousBytes=bytes;previousAt=now;emittedAt=now
                }
            }
            val result=graph.engine.call("process",request,callback)
            val state=result.optString("state","failed")
            if(state in setOf("done","exists","album_pending")) {
                if(graph.media.current(media)!=before){graph.dao.invalidate(job.account,media.id);throw EngineFailure("SOURCE_CHANGED")}
                latest=uploaded(job,media,latest,result)
            }
            Diagnostics.event("item.state_changed",diagnostic,JSONObject().put("state",state).put("code",result.optString("error")))
            graph.dao.putItem(latest.copy(state=state,error=result.optString("error"),attempt=item.attempt+1))
        } catch(error:Exception) {
            val code=(error as? EngineFailure)?.code?:if(cancelled())"CANCELLED" else "FILE_ACCESS"
            Diagnostics.event("error.captured",diagnostic,DiagnosticFields.error(error).put("code",code),"ERROR")
            graph.dao.putItem(latest.copy(state=if(code=="CANCELLED") {if(latest.remoteKey.isEmpty())"queued" else "album_pending"} else "failed",error=code,attempt=item.attempt+1))
            if(code=="LOGIN_REQUIRED")notice.value=Messages.error(code)
        } finally {
            Diagnostics.event("item.attempt_finished",diagnostic,JSONObject().put("cancelled",cancelled()))
            inFlight.update{it-item.id}
        }
    }
    private suspend fun uploaded(job:BackupJob,media:MediaEntry,item:BackupItem,data:JSONObject):BackupItem {
        if(graph.dao.job(job.id)?.state=="ended" || graph.preferences.get().account!=job.account)
            throw EngineFailure("JOB_CANCELLED")
        val key=data.optString("remote_key")
        if(key.isEmpty())return item
        val origin=data.optString("origin")
        val record=BackupRecord(job.account,media.id,data.getString("sha1"),data.getString("sha256"),key,data.getLong("size"),media.modified,if(origin=="content_exists")"unknown" else job.quality,origin,System.currentTimeMillis())
        graph.dao.putBackup(record)
        val updated=item.copy(state="finalizing_album",remoteKey=key,sha1=record.sha1,sha256=record.sha256,origin=record.origin)
        graph.dao.putItem(updated)
        return updated
    }
}
