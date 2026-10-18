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

class BackupRepository(private val graph:AppGraph) {
    private val lock=Mutex()
    private val enqueueLock=Mutex()
    private val stopping=AtomicBoolean(false)
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
    }
    suspend fun enqueue(ids:List<String>?=null,automatic:Boolean=false,allowMobile:Boolean=false):BackupSubmission?=enqueueLock.withLock {
        if(graph.switching.value)throw EngineFailure("ACCOUNT_CHANGED")
        val settings=graph.preferences.get()
        if(settings.account.isEmpty())throw EngineFailure("LOGIN_REQUIRED")
        val known=graph.dao.backups(settings.account).associateBy{it.mediaId}
        val explicit=ids?.toSet()
        val candidates=graph.dao.media().filter { media ->
            (if(explicit!=null)media.id in explicit else settings.includes(media)) &&
                (explicit!=null||!BackupPolicy.matches(media,known[media.id]))
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

    suspend fun pause() {
        stopping.set(true)
        activeJob.value.takeIf{it.isNotEmpty()}?.let { graph.dao.jobState(it,"pausing") }
    }
    suspend fun stopAndWait() {
        stopping.set(true)
        lock.withLock { activeJob.value="";progress.value=TransferProgress();inFlight.value=emptyMap() }
    }
    suspend fun resume(jobId:String) {
        val job=graph.dao.job(jobId)?:return
        if(job.account!=graph.preferences.get().account)return
        graph.dao.putJob(job.copy(state="queued",automatic=false))
        graph.scheduler.launchManual(job.allowMobile)
    }
    suspend fun retry(jobId:String) {
        val job=graph.dao.job(jobId)?:return
        if(job.account!=graph.preferences.get().account)return
        graph.dao.items(jobId).filter{it.state in setOf("failed","album_pending","cancelled")}.forEach {
            graph.dao.putItem(it.copy(state=if(it.remoteKey.isEmpty())"queued" else "album_pending",error=""))
        }
        graph.dao.putJob(job.copy(state="queued",automatic=false));graph.scheduler.launchManual(job.allowMobile)
    }
    suspend fun end(jobId:String) {
        if(activeJob.value==jobId)stopAndWait()
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
                val items=graph.dao.items(job.id).filter{it.state in setOf("queued","album_pending","hashing","checking","uploading","finalizing","finalizing_album")}
                val semaphore=Semaphore(settings.concurrency)
                val authenticationFailure=AtomicBoolean(false)
                val preempted=AtomicBoolean(false)
                val completed=java.util.concurrent.atomic.AtomicInteger(graph.dao.items(job.id).count{it.state in setOf("done","exists")})
                val total=graph.dao.items(job.id).size
                supervisorScope {
                    items.map { item -> async(Dispatchers.IO) { semaphore.withPermit {
                        if(stopping.get()||externalCancelled()||authenticationFailure.get()||graph.switching.value)return@withPermit
                        if(graph.dao.item(item.id)?.jobId!=job.id)return@withPermit
                        if(job.automatic&&graph.dao.pendingManual(account)>0){preempted.set(true);return@withPermit}
                        val media=graph.dao.media(item.mediaId)
                        if(!onWifi()&&!job.allowMobile&&settings.mobile=="photos"&&media?.video==true)return@withPermit
                        process(job,item,settings,{graph.switching.value||stopping.get()||externalCancelled()||authenticationFailure.get()||(settings.wifi&&!job.allowMobile&&!onWifi())})
                        if(graph.dao.item(item.id)?.error in setOf("LOGIN_REQUIRED","DEVICE_VERIFICATION","ENCRYPTED_TOKEN"))authenticationFailure.set(true)
                        val done=completed.incrementAndGet();notify("正在备份 · $done / $total",if(total>0)done*100/total else 0)
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
    private suspend fun process(job:BackupJob,item:BackupItem,settings:HarborSettings,cancelled:()->Boolean) {
        var latest=item
        try {
            val media=graph.dao.media(item.mediaId)?:throw EngineFailure("FILE_ACCESS")
            val before=graph.media.current(media)?:throw EngineFailure("FILE_ACCESS")
            if(before.first!=media.size||before.second!=media.modified)throw EngineFailure("SOURCE_CHANGED")
            graph.dao.putItem(item.copy(state="hashing"))
            val request=JSONObject().put("_account",job.account).put("uri",media.id).put("name",media.name).put("modified",media.modified)
                .put("folder",media.albumFolder).put("quality",job.quality).put("album_mode",job.albumMode).put("album_value",job.albumValue)
                .put("retries",settings.retries).put("same_name",job.automatic&&settings.sameName)
                .put("remote_key",item.remoteKey).put("origin",item.origin)
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
            graph.dao.putItem(latest.copy(state=state,error=result.optString("error"),attempt=item.attempt+1))
        } catch(error:Exception) {
            val code=(error as? EngineFailure)?.code?:if(cancelled())"CANCELLED" else "FILE_ACCESS"
            graph.dao.putItem(latest.copy(state=if(code=="CANCELLED") {if(latest.remoteKey.isEmpty())"queued" else "album_pending"} else "failed",error=code,attempt=item.attempt+1))
            if(code=="LOGIN_REQUIRED")notice.value=Messages.error(code)
        } finally {
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
