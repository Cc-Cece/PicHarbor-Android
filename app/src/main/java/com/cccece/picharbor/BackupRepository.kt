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
    private val lock=Mutex()
    private val enqueueLock=Mutex()
    private val stopping=AtomicBoolean(false)
    /** Live-photo pair claims: pairKey (photo media id) -> claiming item id. */
    private val livePhotoClaims=ConcurrentHashMap<String,String>()
    /** Items that must be processed as single resources (live-photo stepwise fallback). */
    private val stepwiseItems=ConcurrentHashMap.newKeySet<String>()
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
        val known=graph.dao.backups(settings.account).associateBy{it.mediaId}
        val explicit=ids?.toSet()
        // Scope is decided by the shared core (evaluate_scope) for automatic
        // candidates. Explicit user selection bypasses scope filtering: the
        // user chose those items. The cheap local includes() check below is
        // only an index pre-filter over the exact inputs the scope JSON
        // carries (folders/videos); the core remains the authority for the
        // surviving candidates (date/type/size), and when the core predates
        // evaluate_scope the local check is the documented fallback. An
        // unknown-version scope error denies the whole enqueue instead of
        // degrading into "include everything".
        val candidates=ArrayList<MediaEntry>()
        val allMedia=graph.dao.media()
        for(media in allMedia) {
            if(explicit!=null) {
                if(media.id in explicit)candidates+=media
                continue
            }
            if(BackupPolicy.matches(media,known[media.id]))continue
            if(!settings.includes(media))continue
            when(val decision=ScopePolicy.evaluate(graph.harborCore,settings,media)) {
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
            albumMode=settings.albumMode,albumValue=settings.albumValue,quality=settings.quality)
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
            graph.dao.putJob(job.copy(state="queued",automatic=false))
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
        val requeued=graph.dao.items(jobId).filter{it.state in setOf("failed","album_pending","cancelled")}
        requeued.forEach {
            graph.dao.putItem(it.copy(state=if(it.remoteKey.isEmpty())"queued" else "album_pending",error=""))
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
        graph.dao.putJob(job.copy(state="queued",automatic=false));graph.scheduler.launchManual(job.allowMobile)
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
            if(state in setOf(HarborCoreState.UPLOADING,HarborCoreState.SUBMITTING,HarborCoreState.CONFIRMING)) {
                runCatching{graph.harborCore.transition(task,HarborCoreState.INTERRUPTED)}
            }
        }
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
                val settings=graph.preferences.get()
                val charging=graph.app.getSystemService(android.os.BatteryManager::class.java).isCharging
                val job=graph.dao.nextJob(account,settings.auto&&(!settings.charging||charging))?:break
                if(job.account!=settings.account)break
                if(settings.wifi&&!job.allowMobile&&!onWifi()) {
                    graph.dao.jobState(job.id,"waiting");notice.value="等待 Wi-Fi 连接";return false
                }
                activeJob.value=job.id;graph.dao.jobState(job.id,"running")
                val jobDiagnostic=DiagnosticContext(job=job.id)
                Diagnostics.event("job.state_changed",jobDiagnostic,JSONObject().put("state","running").put("concurrency",settings.concurrency).put("setting_timeout_ms",settings.timeout*1000).put("retry_limit",settings.retries))
                val items=graph.dao.items(job.id).filter{it.state in setOf("queued","album_pending","hashing","checking","uploading","finalizing","finalizing_album")}
                val semaphore=Semaphore(settings.concurrency)
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
                        process(job,item,settings,{graph.switching.value||stopping.get()||externalCancelled()||authenticationFailure.get()||(settings.wifi&&!job.allowMobile&&!onWifi())})
                        if(graph.dao.item(item.id)?.error in setOf("LOGIN_REQUIRED","DEVICE_VERIFICATION","ENCRYPTED_TOKEN"))authenticationFailure.set(true)
                        val done=completed.incrementAndGet();notify("正在备份 · $done / $total",if(total>0)done*100/total else 0)
                    } } finally {
                        Diagnostics.event(if(acquired)"permit.released" else "wait.finished",waiting,JSONObject().put("kind","permit").put("outcome",if(acquired)"released" else "cancelled"))
                    } } }.awaitAll()
                }
                val updated=graph.dao.items(job.id)
                val paused=stopping.get()||externalCancelled()
                val state=when {
                    stopping.get()->"paused"
                    externalCancelled()->"queued"
                    authenticationFailure.get()->"auth_required"
                    preempted.get()->"queued"
                    updated.any{it.state in setOf("failed","album_pending")}->"needs_attention"
                    updated.any{it.state=="queued"}->"waiting"
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
        // The run loop snapshots items up front; a Live Photo partner may
        // already have been finished by its pair's joint task. Re-read.
        if(graph.dao.item(item.id)?.state !in setOf("queued","album_pending","hashing","checking","uploading","finalizing","finalizing_album"))return
        val pairing=settings.livePhotoPairing
        if(!stepwiseItems.contains(item.id)) {
            val group=try {
                val all=graph.dao.media()
                // Only Motion Photo pairing scans a JPEG for embedded video;
                // under "original" pairing a Motion Photo file without a
                // separate video partner is an ordinary photo (its embedded
                // video rides along in the file bytes).
                val embedded=pairing==LivePhotoPairing.MOTION&&!media.video&&media.mime=="image/jpeg"&&
                    LivePhotoPairing.hasEmbeddedMotion(graph.app.contentResolver,media.id)
                LivePhotoPairing.groupFor(media,all,pairing,embedded)
            } catch (_:Exception) { null }
            if(group!=null) {
                // If the partner is mid-flight on this pair, wait briefly
                // for it to finish (it updates this item); only give up
                // and return when it is still holding the claim.
                var waits=0
                while(livePhotoClaims[group.pairKey]?.let{it!=item.id}==true&&waits<50) { delay(200);waits++ }
                if(graph.dao.item(item.id)?.state !in setOf("queued","album_pending","hashing","checking","uploading","finalizing","finalizing_album"))return
                if(!stepwiseItems.contains(item.id)) {
                    val claimer=livePhotoClaims.putIfAbsent(group.pairKey,item.id)
                    if(claimer!=null&&claimer!=item.id) {
                        // The partner item is already processing this pair; it
                        // updates this item's state when the joint task finishes.
                        return
                    }
                    try {
                        if(processLivePhotoWithCore(job,item,media,group,settings,cancelled))return
                    } finally {
                        livePhotoClaims.remove(group.pairKey,item.id)
                    }
                    // The core declined the joint live-photo task (e.g. it does
                    // not know live_photo yet): fall back to stepwise, one core
                    // task per resource. Remember both sides so neither side
                    // re-enters the joint flow on its own turn.
                    stepwiseItems+=item.id
                    try {
                        graph.dao.items(job.id).firstOrNull{it.mediaId==group.video?.id}?.let{stepwiseItems+=it.id}
                        graph.dao.items(job.id).firstOrNull{it.mediaId==group.photo.id}?.let{stepwiseItems+=it.id}
                    } catch (_:Exception) { }
                }
            }
        }
        if(!processSingleWithCore(job,item,media,settings,cancelled)) {
            notice.value="共享核心暂不可用，稍后会自动重试"
        }
    }
    /** One local file's digests plus its cloud lookup key, for one resource. */
    private data class ResourceReady(val media:MediaEntry,val sha1:String,val sha256:String,val size:Long,val remoteKey:String,val durationSeconds:Long?)

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
        val print=graph.engine.call("fingerprint",JSONObject().put("_diagnostics",diagnostic.json()).put("_account",job.account).put("uri",media.id).put("name",media.name))
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
        val diagnostic=DiagnosticContext(job=job.id,item=item.id,attempt=UUID.randomUUID().toString())
        var coreTask:JSONObject?=null
        fun advance(result:JSONObject):JSONObject? {
            val updated=graph.harborCore.updatedTask(result)
            if(updated!=null)coreTask=updated
            return updated
        }
        suspend fun itemFor(entry:MediaEntry):BackupItem? =
            if(entry.id==item.mediaId)item else graph.dao.items(job.id).firstOrNull{it.mediaId==entry.id}
        try {
            val partnerMedia=group.video
            val photoItem=itemFor(group.photo)
            val videoItem=partnerMedia?.let{itemFor(it)}
            // Both resources need an item in this job, or an existing backup
            // record (then the resource is a lookup away from a dedup hit).
            // Otherwise the pair spans jobs: decline joint, go stepwise.
            if(photoItem==null&&!BackupPolicy.matches(group.photo,graph.dao.backup(job.account,group.photo.id)))return false
            if(partnerMedia!=null&&videoItem==null&&!BackupPolicy.matches(partnerMedia,graph.dao.backup(job.account,partnerMedia.id)))return false
            graph.dao.putItem(item.copy(state="hashing"))
            val photoRes=prepareResource(job,group.photo,diagnostic)
            val videoRes=partnerMedia?.let{prepareResource(job,it,diagnostic)}
            val resourceList=listOfNotNull(photoRes to "photo",videoRes?.let{it to "video"})
            val resources=org.json.JSONArray()
            for((res,role) in resourceList) {
                // Core resource shape: {role: photo|video, sha1?, sha256?,
                // size_bytes?, filename?} — "role", not "kind".
                val entry=JSONObject().put("role",role).put("filename",res.media.name)
                    .put("sha1",res.sha1).put("sha256",res.sha256).put("size_bytes",res.size)
                resources.put(entry)
            }
            // ONE per-resource core decision: decide_prepare with the full
            // asset (resources) and remote_keys keyed by role. A single
            // remote_key must not be sent together with resources (the core
            // rejects it as INVALID_ARGUMENT). The answer carries
            // resources[{role, exists, remote_key?}] and top-level exists
            // only when every resource already exists.
            val asset=JSONObject().put("local_id",group.photo.id).put("filename",group.photo.name)
                .put("sha1",photoRes.sha1).put("sha256",photoRes.sha256).put("size",photoRes.size)
                .put("media_kind",if(partnerMedia!=null)"live_photo" else "motion_photo")
                .put("resources",resources)
            if(group.embeddedMotion)asset.put("embedded_motion",true)
            val remoteKeys=JSONObject()
            if(photoRes.remoteKey.isNotEmpty())remoteKeys.put("photo",photoRes.remoteKey)
            if(videoRes!=null&&videoRes.remoteKey.isNotEmpty())remoteKeys.put("video",videoRes.remoteKey)
            val decision=graph.harborCore.decidePrepare(asset,remoteKeys)
            if(!decision.optBoolean("ok"))return false
            val dedupHit=mutableMapOf<String,Boolean>()
            val hitKeys=mutableMapOf<String,String>()
            val decisions=decision.optJSONArray("resources")
            if(decisions!=null) {
                for(i in 0 until decisions.length()) {
                    val entry=decisions.optJSONObject(i)?:continue
                    val role=entry.optString("role")
                    val mediaId=if(role=="video")videoRes?.media?.id else photoRes.media.id
                    if(mediaId!=null) {
                        dedupHit[mediaId]=entry.optBoolean("exists")
                        if(entry.optBoolean("exists"))hitKeys[mediaId]=entry.optString("remote_key")
                    }
                }
            } else {
                // No per-resource breakdown (older core): top-level only.
                val allHit=HarborCore.isDedupHit(decision)
                dedupHit[photoRes.media.id]=allHit
                if(videoRes!=null)dedupHit[videoRes.media.id]=allHit
                if(allHit) {
                    hitKeys[photoRes.media.id]=decision.optString("remote_key")
                    if(videoRes!=null)hitKeys[videoRes.media.id]=decision.optString("remote_key")
                }
            }
            val created=graph.harborCore.createTask(item.id,asset,"android:live:${group.photo.id}:${group.photo.modified}")
            if(advance(created)==null)return false
            if(advance(graph.harborCore.transition(coreTask!!,HarborCoreState.PREPARING))==null)return false
            advance(graph.harborCore.transition(coreTask!!,HarborCoreState.UPLOADING))
            val transported=mutableMapOf<String,BackupItem>()
            for((res,_) in resourceList) {
                val resItem=(if(res.media.id==group.photo.id)photoItem else videoItem)?.copy(sha1=res.sha1,sha256=res.sha256)
                if(dedupHit[res.media.id]==true) {
                    val key=hitKeys[res.media.id]?:res.remoteKey
                    val record=BackupRecord(job.account,res.media.id,res.sha1,res.sha256,key,res.size,res.media.modified,"unknown","content_exists",System.currentTimeMillis())
                    graph.dao.putBackup(record)
                    if(resItem!=null) {
                        graph.dao.putItem(resItem.copy(state="exists",remoteKey=key,origin="content_exists",attempt=resItem.attempt+1))
                        transported[res.media.id]=resItem.copy(state="exists",remoteKey=key,origin="content_exists")
                    } else {
                        transported[res.media.id]=BackupItem("","",job.account,res.media.id,res.media.name,res.size,"exists",key,res.sha1,res.sha256)
                    }
                } else {
                    // A resource without an item in this job cannot be
                    // transported as part of the joint task: decline joint.
                    if(resItem==null)return false
                    transportItem(job,resItem,settings,cancelled,lookupRemoteKey=res.remoteKey,lookupFresh=true)
                    val final=graph.dao.item(resItem.id)
                    if(final?.state !in setOf("done","exists","album_pending")) {
                        advance(graph.harborCore.transition(coreTask!!,HarborCoreState.FAILED,errorCode=final?.error?.ifBlank{null}))
                        return true
                    }
                    transported[res.media.id]=final!!
                }
            }
            // Single joint confirmation for the pair.
            advance(graph.harborCore.transition(coreTask!!,HarborCoreState.SUBMITTING))
            advance(graph.harborCore.transition(coreTask!!,HarborCoreState.CONFIRMING))
            advance(graph.harborCore.transition(coreTask!!,HarborCoreState.DONE,cloudConfirmed=true))
            // Mirror the joint outcome onto the other side's item so it is
            // not processed again on its own turn.
            for((mediaId,done) in transported) {
                if(mediaId==item.mediaId)continue
                val other=graph.dao.items(job.id).firstOrNull{it.mediaId==mediaId}?:continue
                graph.dao.putItem(other.copy(state=if(done.state=="exists")"exists" else "done",remoteKey=done.remoteKey,sha1=done.sha1,sha256=done.sha256,origin=done.origin,attempt=other.attempt+1))
            }
            Diagnostics.event("item.state_changed",diagnostic,JSONObject().put("state","done").put("code",""))
            runCatching{graph.harborCore.logEvent("item.state_changed",item.id,JSONObject().put("state","done").put("filename",media.name))}
            return true
        } catch(error:Exception) {
            coreTask?.let{task->runCatching{graph.harborCore.transition(task,HarborCoreState.INTERRUPTED)}}
            if(error is EngineFailure&&error.code in setOf("FILE_ACCESS","SOURCE_CHANGED")) {
                graph.dao.putItem(item.copy(state="failed",error=error.code,attempt=item.attempt+1))
                return true
            }
            return false
        } finally {
            inFlight.update{it-item.id}
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
            if(updated!=null)coreTask=updated
            return updated
        }
        val mediaKind=if(media.video)"video" else "photo"
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
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.DONE,cloudConfirmed=true))
                    val record=BackupRecord(job.account,media.id,ready.sha1,ready.sha256,hitKey,ready.size,media.modified,"unknown","content_exists",System.currentTimeMillis())
                    graph.dao.putBackup(record)
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
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.CONFIRMING))
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.DONE,cloudConfirmed=true))
                    runCatching{graph.harborCore.logEvent("item.state_changed",item.id,JSONObject().put("state","done").put("filename",media.name))}
                }
                "album_pending"->{
                    // Content is committed; only the album step is pending.
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.SUBMITTING))
                    advance(graph.harborCore.transition(coreTask!!,HarborCoreState.CONFIRMING))
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
            if(error is EngineFailure&&error.code in setOf("FILE_ACCESS","SOURCE_CHANGED")) {
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
    private suspend fun transportItem(job:BackupJob,item:BackupItem,settings:HarborSettings,cancelled:()->Boolean,lookupRemoteKey:String="",lookupFresh:Boolean=false) {
        var latest=item
        val diagnostic=DiagnosticContext(job=job.id,item=item.id,attempt=UUID.randomUUID().toString())
        Diagnostics.event("item.state_changed",diagnostic,JSONObject().put("state","started").put("filename",item.name).put("retry_attempt",item.attempt+1))
        try {
            val media=graph.dao.media(item.mediaId)?:throw EngineFailure("FILE_ACCESS")
            val before=graph.media.current(media)?:throw EngineFailure("FILE_ACCESS")
            if(before.first!=media.size||before.second!=media.modified)throw EngineFailure("SOURCE_CHANGED")
            graph.dao.putItem(item.copy(state="hashing"))
            val request=JSONObject().put("_diagnostics",diagnostic.json()).put("_account",job.account).put("uri",media.id).put("name",media.name).put("modified",media.modified)
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
