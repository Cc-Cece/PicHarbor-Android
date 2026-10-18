package com.cccece.picharbor

import android.app.PendingIntent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import androidx.room.withTransaction
import androidx.work.*
import org.json.JSONObject
import java.util.UUID

data class CleanupProgress(val running:Boolean=false,val checked:Int=0,val total:Int=0,val message:String="",val id:String="",val state:String="",val account:String="",val mode:String="full",val metrics:String="{}",val elapsedMs:Long=0,val effectiveParallel:Int=2)
@OptIn(ExperimentalCoroutinesApi::class)
class CleanupRepository(private val graph:AppGraph) {
    private val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
    private val lock=kotlinx.coroutines.sync.Mutex()
    private val enqueueLock=kotlinx.coroutines.sync.Mutex()
    private val activeStates=setOf("queued","syncing","running","waiting","cancelling")
    val progress=graph.preferences.flow.map{it.account}.distinctUntilChanged().flatMapLatest{graph.dao.cleanupScanFlow(it)}.map { scan ->
        if(scan==null)CleanupProgress() else CleanupProgress(scan.state in activeStates,scan.checked,scan.total,when(scan.state){
            "queued"->"检查已排队，等待开始";"syncing"->"正在核对云端备份";"running"->"正在检查 ${scan.checked} / ${scan.total} 项"
            "waiting"->"进度已保存，等待继续";"cancelling"->"正在停止检查";"cancelled"->"已停止检查，手机原件保留"
            "completed"->"检查已完成，请查看核验结果";"failed"->Messages.error(scan.error);else->""
        },scan.id,scan.state,scan.account,scan.mode,scan.metrics,scan.elapsedMs,scan.effectiveParallel)
    }.stateIn(graph.scope,SharingStarted.Eagerly,CleanupProgress())

    fun cancel(){cancelled.set(true);graph.scope.launch{stopAndWait()}}
    suspend fun stopAndWait() {
        cancelled.set(true)
        val scan=graph.dao.latestCleanupScan(graph.preferences.get().account)
        if(scan!=null&&scan.state in activeStates) {
            graph.dao.putCleanupScan(scan.copy(state="cancelling"))
            WorkManager.getInstance(graph.app).cancelUniqueWork("cleanup-${scan.id}")
        }
        lock.withLock { }
        if(scan!=null&&scan.state in activeStates) {
            graph.dao.cleanupScan(scan.id)?.let{graph.dao.putCleanupScan(it.copy(state="cancelled",completedAt=System.currentTimeMillis()))}
            if(scan.mode=="full")graph.dao.expireCleanup(scan.account)
            CleanupNotifications.cancel(graph.app)
        }
    }
    suspend fun enqueue(ids:List<String>?=null,scheduleWork:Boolean=true,mode:String="full"):CleanupScan=enqueueLock.withLock {
        require(mode in setOf("full","quick"))
        val settings=graph.preferences.get()
        val account=settings.account
        if(account.isEmpty())throw EngineFailure("LOGIN_REQUIRED")
        if(graph.switching.value)throw EngineFailure("ACCOUNT_CHANGED")
        graph.dao.latestCleanupScan(account)?.takeIf{it.state in activeStates}?.let { existing ->
            if(scheduleWork)schedule(existing)
            return@withLock existing
        }
        val records=graph.dao.backups(account).associateBy{it.mediaId}
        val chosen=ids?.toSet()
        val candidates=graph.dao.media().filter{chosen?.contains(it.id)?: (BackupPolicy.matches(it,records[it.id])||(mode=="quick"&&settings.includes(it)))}
        val scan=CleanupScan(UUID.randomUUID().toString(),account,System.currentTimeMillis(),total=candidates.size,mode=mode,effectiveParallel=settings.cleanupConcurrency)
        graph.database.withTransaction {
            if(mode=="full")graph.dao.expireCleanup(account)
            graph.dao.putCleanupScan(scan)
            candidates.chunked(500).forEach{chunk->graph.dao.putCleanupScanItems(chunk.map{CleanupScanItem(scan.id,it.id)})}
        }
        if(scheduleWork)schedule(scan)
        scan
    }
    private fun schedule(scan:CleanupScan) {
        WorkManager.getInstance(graph.app).enqueueUniqueWork("cleanup-${scan.id}",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CleanupWorker>().setInputData(workDataOf("scan_id" to scan.id))
                .addTag("cleanup").setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        CleanupNotifications.progress(graph.app,scan)
    }
    suspend fun restorePending(){
        val scan=graph.dao.latestCleanupScan(graph.preferences.get().account)?:return
        if(scan.state=="cancelling") {
            graph.dao.putCleanupScan(scan.copy(state="cancelled",completedAt=System.currentTimeMillis()))
            if(scan.mode=="full")graph.dao.expireCleanup(scan.account)
            CleanupNotifications.cancel(graph.app)
        } else if(scan.state in activeStates)schedule(scan)
    }
    /** Synchronous entry only for signed device diagnostics; UI always enqueues a Worker. */
    suspend fun verify(ids:List<String>?=null) {runScan(enqueue(ids,false).id)}
    suspend fun runScan(id:String,externalCancelled:()->Boolean={false}):Boolean=lock.withLock {
        var scan=graph.dao.cleanupScan(id)?:return@withLock true
        if(scan.state !in activeStates)return@withLock true
        if(scan.state=="cancelling"){graph.dao.putCleanupScan(scan.copy(state="cancelled"));return@withLock true}
        if(scan.account!=graph.preferences.get().account||graph.switching.value){graph.dao.putCleanupScan(scan.copy(state="cancelled",error="ACCOUNT_CHANGED"));return@withLock true}
        cancelled.set(false)
        fun stopped()=cancelled.get()||externalCancelled()||graph.switching.value
        val started=android.os.SystemClock.elapsedRealtime()
        val previousElapsed=scan.elapsedMs
        val metrics=runCatching{JSONObject(scan.metrics)}.getOrDefault(JSONObject())
        val stateLock=kotlinx.coroutines.sync.Mutex()
        val settings=graph.preferences.get()
        val policy=CheckParallelPolicy(settings.cleanupConcurrency,settings.cleanupAdaptive)
        val readers=java.util.concurrent.Semaphore(settings.cleanupReaders,true)
        fun merge(values:JSONObject?) {
            if(values==null)return
            values.keys().forEach { key ->
                val value=values.optJSONObject(key)?:return@forEach
                val total=metrics.optJSONObject(key)?:JSONObject().put("ms",0L).put("count",0L).also{metrics.put(key,it)}
                total.put("ms",total.optLong("ms")+value.optLong("ms"))
                total.put("count",total.optLong("count")+value.optLong("count"))
            }
        }
        fun snapshot()=scan.copy(metrics=metrics.toString(),elapsedMs=previousElapsed+android.os.SystemClock.elapsedRealtime()-started,effectiveParallel=policy.limit)
        suspend fun checkpoint(values:JSONObject?=null) = stateLock.withLock {
            merge(values);scan=snapshot();graph.dao.putCleanupScan(scan)
        }
        suspend fun syncCloud() {
            stateLock.withLock {scan=snapshot().copy(state="syncing");graph.dao.putCleanupScan(scan)}
            CleanupNotifications.progress(graph.app,scan)
            try {
                val result=graph.engine.call("synchronize",JSONObject().put("_account",scan.account).put("full",true),callback=EngineCallback(::stopped))
                checkpoint(result.optJSONObject("_metrics"))
            } catch(error:EngineFailure){checkpoint(error.metrics);throw error}
        }
        var finalState="completed";var errorCode=""
        try {
            val account=graph.ensureAccount()
            val records=graph.dao.backups(account).associateBy{it.mediaId}
            if(scan.mode=="full")syncCloud() else checkpoint()
            suspend fun persist(task:CleanupScanItem,result:CleanupRecord) = stateLock.withLock {
                val saveStart=android.os.SystemClock.elapsedRealtime()
                graph.database.withTransaction {
                    graph.dao.putCleanup(result)
                    graph.dao.putCleanupScanItem(task.copy(done=true))
                    scan=snapshot().copy(state="running",checked=graph.dao.cleanupChecked(id))
                    graph.dao.putCleanupScan(scan)
                }
                merge(JSONObject().put("persist",JSONObject().put("count",1).put("ms",android.os.SystemClock.elapsedRealtime()-saveStart)))
                CleanupNotifications.progress(graph.app,scan)
            }
            suspend fun inspect(task:CleanupScanItem) {
                currentCoroutineContext().ensureActive()
                if(stopped())return
                val media=graph.dao.media(task.mediaId)
                val evidenceId=java.security.MessageDigest.getInstance("SHA-256").digest("$account|${task.mediaId}".toByteArray()).joinToString(""){"%02x".format(it)}.let{if(scan.mode=="quick")"quick:$it" else it}
                if(media==null){
                    persist(task,CleanupRecord(evidenceId,account,task.mediaId,records[task.mediaId]?.size?:0,"","",System.currentTimeMillis(),"blocked","FILE_ACCESS"))
                    return
                }
                if(task.done) {
                    val previous=graph.dao.cleanupByIds(listOf(evidenceId)).firstOrNull()
                    if(scan.mode=="quick"||previous?.state=="blocked")return
                }
                val known=records[media.id]
                var result=CleanupRecord(evidenceId,account,media.id,media.size,"","",System.currentTimeMillis(),"blocked","CLOUD_STATE_UNKNOWN")
                for(attempt in 0..settings.cleanupRetries) {
                    if(stopped())return
                    try {
                        if(graph.preferences.get().account!=account)throw EngineFailure("ACCOUNT_CHANGED")
                        val before=graph.media.current(media)?:throw EngineFailure("FILE_ACCESS")
                        val value=JSONObject().put("_account",account).put("uri",media.id).put("name",media.name).put("check_mode",scan.mode)
                        if(known!=null)value.put("sha256",known.sha256)
                        val verified=graph.engine.call("verify_cleanup",value,EngineCallback(::stopped,readGate=readers))
                        checkpoint(verified.optJSONObject("_metrics"))
                        val unchanged=graph.media.current(media)==before&&before.first==media.size&&before.second==media.modified
                        val eligible=scan.mode=="full"&&verified.optBoolean("eligible")&&unchanged
                        val found=scan.mode=="quick"&&verified.optBoolean("found")&&unchanged
                        result=result.copy(sha256=verified.optString("sha256"),remoteKey=verified.optString("remote_key"),checkedAt=System.currentTimeMillis(),
                            state=if(eligible)"ready" else if(found)"quick_found" else "blocked",
                            reason=if(eligible)"" else if(!unchanged)"SOURCE_CHANGED" else verified.optString("reason").ifBlank{"CLOUD_STATE_UNKNOWN"})
                        if(eligible&&known==null)graph.dao.putBackup(BackupRecord(account,media.id,verified.getString("sha1"),result.sha256,result.remoteKey,media.size,media.modified,"original","content_exists",System.currentTimeMillis()))
                    } catch(error:Exception) {
                        if(error is CancellationException)throw error
                        val failure=error as? EngineFailure
                        checkpoint(failure?.metrics)
                        if(failure!=null&&CheckParallelPolicy.login(failure.code))throw failure
                        result=result.copy(reason=failure?.code?:"FILE_ACCESS")
                    }
                    policy.result(if(result.state in setOf("ready","quick_found"))"" else result.reason)
                    if(!CheckParallelPolicy.transient(result.reason)||attempt==settings.cleanupRetries)break
                    val waitStart=android.os.SystemClock.elapsedRealtime()
                    delay(if(result.reason=="RATE_LIMITED")5000L*(attempt+1) else 1000L*(attempt+1))
                    checkpoint(JSONObject().put("retry_wait",JSONObject().put("count",1).put("ms",android.os.SystemClock.elapsedRealtime()-waitStart)))
                }
                if(stopped())return
                persist(task,result)
            }
            val tasks=graph.dao.cleanupScanItems(id).toMutableList()
            var next=0
            var syncedAt=android.os.SystemClock.elapsedRealtime()
            coroutineScope {
                val running=mutableListOf<Deferred<Unit>>()
                while((next<tasks.size||running.isNotEmpty())&&!stopped()) {
                    currentCoroutineContext().ensureActive()
                    val refresh=scan.mode=="full"&&android.os.SystemClock.elapsedRealtime()-syncedAt>240000
                    if(refresh&&running.isEmpty()){syncCloud();syncedAt=android.os.SystemClock.elapsedRealtime();continue}
                    if(!refresh)while(next<tasks.size&&running.size<policy.limit&&!stopped()) {
                        val task=tasks[next++]
                        running+=async(Dispatchers.IO){inspect(task)}
                    }
                    if(running.isNotEmpty()) {
                        val completed=select<Deferred<Unit>> {running.forEach{deferred->deferred.onAwait{deferred}}}
                        running.remove(completed)
                    }
                }
                // Never return while Python readers still hold a snapshot or file descriptor.
                running.awaitAll()
            }
            if(stopped())finalState=if(cancelled.get()||graph.switching.value)"cancelled" else "waiting"
        } catch(error:CancellationException){finalState=if(cancelled.get()||graph.switching.value)"cancelled" else "waiting";throw error}
        catch(error:Exception){finalState="failed";errorCode=(error as? EngineFailure)?.code?:"ENGINE_ERROR"}
        finally {
            withContext(NonCancellable) {
                scan=snapshot().copy(state=finalState,error=errorCode,checked=graph.dao.cleanupChecked(id),completedAt=if(finalState=="waiting")0 else System.currentTimeMillis())
                graph.dao.putCleanupScan(scan)
                if(finalState=="cancelled"&&scan.mode=="full")graph.dao.expireCleanup(scan.account)
                if(finalState in setOf("completed","failed"))CleanupNotifications.finished(graph.app,scan)
                else CleanupNotifications.cancel(graph.app)
            }
        }
        finalState!="waiting"
    }
    suspend fun prepareDelete():List<CleanupRecord> {
        val account=graph.ensureAccount()
        if(graph.dao.latestCleanupScan(account)?.state in activeStates)throw EngineFailure("CLEANUP_RUNNING")
        val ready=graph.dao.readyCleanup(account).filter{BackupPolicy.cleanupFresh(it,account,System.currentTimeMillis())}.take(100)
        val checked=ArrayList<CleanupRecord>()
        for(record in ready) {
            val media=graph.dao.media(record.mediaId)?:continue
            val fingerprint=graph.engine.call("fingerprint",JSONObject().put("uri",media.id).put("name",media.name))
            if(fingerprint.getString("sha256")==record.sha256&&fingerprint.getLong("size")==record.bytes&&BackupPolicy.cleanupFresh(record,account,System.currentTimeMillis()))checked+=record
            else graph.dao.putCleanup(record.copy(state="expired",reason="SOURCE_CHANGED"))
        }
        return checked
    }
    suspend fun systemRequest(records:List<CleanupRecord>):IntentSender? {
        if(Build.VERSION.SDK_INT<30)return null
        val uris=records.filter{it.mediaId.startsWith("content://media/")}.map { record ->
            graph.media.deleteUri(graph.dao.media(record.mediaId)?:throw EngineFailure("FILE_ACCESS"))
        }.distinct()
        return if(uris.isEmpty())null else MediaStore.createDeleteRequest(graph.app.contentResolver,uris).intentSender
    }
    suspend fun finishDelete(records:List<CleanupRecord>,systemApproved:Boolean):Pair<Int,Long> {
        val account=graph.preferences.get().account
        var deleted=0;var bytes=0L
        for(record in records) {
            if(record.account!=account)continue
            val media=graph.dao.media(record.mediaId)?:continue
            if(!media.id.startsWith("content://media/")&&systemApproved&&BackupPolicy.cleanupFresh(record,account,System.currentTimeMillis())) {
                try {
                    val fresh=graph.engine.call("fingerprint",JSONObject().put("uri",media.id).put("name",media.name))
                    if(fresh.optString("sha256")==record.sha256)DocumentFile.fromSingleUri(graph.app,Uri.parse(media.id))?.takeIf{it.canWrite()}?.delete()
                } catch (_:Exception){ }
            }
            val removed=graph.media.presence(media.id)==false
            graph.dao.putCleanup(record.copy(state=if(removed)"deleted" else if(!systemApproved)"cancelled" else "blocked",deletedAt=if(removed)System.currentTimeMillis() else 0,reason=if(removed)"" else "FILE_ACCESS"))
            if(removed){graph.dao.removeMedia(media.id);deleted++;bytes+=record.bytes}
        }
        return deleted to bytes
    }
    /** Android 10: caller catches RecoverableSecurityException and asks for its actionIntent. */
    suspend fun deleteLegacy(record:CleanupRecord) {
        val account=graph.preferences.get().account
        require(BackupPolicy.cleanupFresh(record,account,System.currentTimeMillis()))
        val media=graph.dao.media(record.mediaId)?:return
        val fresh=graph.engine.call("fingerprint",JSONObject().put("uri",media.id).put("name",media.name))
        require(fresh.optString("sha256")==record.sha256)
        graph.app.contentResolver.delete(Uri.parse(media.id),null,null)
    }
}
