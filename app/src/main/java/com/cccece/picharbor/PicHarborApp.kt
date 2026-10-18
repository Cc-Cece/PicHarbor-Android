package com.cccece.picharbor

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import java.io.File

class PicHarborApp:Application(), coil.ImageLoaderFactory, androidx.work.Configuration.Provider {
    override val workManagerConfiguration get()=androidx.work.Configuration.Builder().setJobSchedulerJobIdRange(10000,19999).build()
    override fun newImageLoader()=coil.ImageLoader.Builder(this).components { add(coil.decode.VideoFrameDecoder.Factory()) }.build()
    lateinit var graph:AppGraph
        private set
    override fun onCreate() {
        super.onCreate()
        var engineReady=true
        try {
            Python.start(AndroidPlatform(this))
            Python.getInstance().getModule("picharbor_engine").callAttr("initialize",File(filesDir,"runtime").absolutePath)
        } catch (_:Exception){engineReady=false}
        graph=AppGraph(this,engineReady)
        graph.scope.launch {
            try {
            runCatching { graph.credentials.migrateLegacy(graph.preferences.get().account) }
            graph.dao.recoverJobs();graph.dao.recoverItems()
            // Staging contains only app-owned temporary copies, never original media.
            File(cacheDir,"staging").listFiles()?.forEach{it.delete()}
            graph.scheduler.configure()
            graph.scheduler.restorePending()
            graph.cleanup.restorePending()
            } finally { graph.startup.complete(Unit) }
        }
        graph.media.observe(graph.scope) {
            val settings=graph.preferences.get()
            if(settings.auto&&!settings.scheduled)graph.scheduler.checkSoon()
        }
    }
}
class AppGraph(val app:Application,val engineReady:Boolean) {
    val startup=CompletableDeferred<Unit>()
    val visible=java.util.concurrent.atomic.AtomicBoolean(false)
    val switching=kotlinx.coroutines.flow.MutableStateFlow(false)
    private val accountLock=kotlinx.coroutines.sync.Mutex()
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val database=HarborDatabase.open(app)
    val dao=database.dao()
    val preferences=Preferences(app)
    val credentials=CredentialStore(app)
    val media=MediaRepository(app,dao,preferences)
    val engine=Engine(app)
    val backup=BackupRepository(this)
    val cleanup=CleanupRepository(this)
    val scheduler=Scheduler(this)
    suspend fun ensureAccount():String {
        startup.await()
        if(switching.value)throw EngineFailure("ACCOUNT_CHANGED")
        if(!engineReady)throw EngineFailure("ENGINE_ERROR")
        val expected=preferences.get().account
        val credential=runCatching{credentials.load(expected)}.getOrNull()?:throw EngineFailure("LOGIN_REQUIRED")
        val result=engine.connect(credential,preferences.get().timeout)
        val actual=result.getString("account")
        if(switching.value||expected.isBlank()||actual!=expected||preferences.get().account!=expected)throw EngineFailure("ACCOUNT_CHANGED")
        credentials.needsLogin(expected,false)
        return actual
    }
    suspend fun login(token:String)=activate(token,null)
    suspend fun selectAccount(account:String) {
        val credential=runCatching{credentials.load(account)}.getOrNull()?:throw EngineFailure("LOGIN_REQUIRED")
        activate(credential,account)
    }
    private suspend fun activate(credential:String,target:String?) {
        startup.await();accountLock.lock()
        val previous=preferences.get().account
        val previousJob=backup.activeJob.value
        switching.value=true
        try {
            scheduler.cancelTransfers();backup.stopAndWait();cleanup.stopAndWait()
            val result=engine.connect(credential,preferences.get().timeout)
            val account=result.getString("account")
            if(target!=null&&target!=account)throw EngineFailure("LOGIN_REQUIRED")
            credentials.save(account,result.getString("credential"))
            if(previous!=account){dao.pauseAccountJobs(previous);dao.expireCleanup(previous,"ACCOUNT_CHANGED")}
            preferences.activateAccount(account)
            backup.notice.value=""
            dao.restoreAuthJobs(account);dao.restoreAuthItems(account)
        } catch(error:Exception) {
            if(target!=null)credentials.needsLogin(target,true)
            runCatching{credentials.load(previous)}.getOrNull()?.let{runCatching{engine.connect(it,preferences.get().timeout)}}
            if(previousJob.isNotBlank())dao.jobState(previousJob,"queued")
            throw error
        } finally {
            switching.value=false;accountLock.unlock()
            scheduler.configure();scheduler.restorePending()
        }
    }
    suspend fun removeAccount(account:String) {
        startup.await();accountLock.lock();switching.value=true
        try {
            if(preferences.get().account==account){
                scheduler.cancelTransfers();backup.stopAndWait();cleanup.stopAndWait()
                dao.pauseAccountJobs(account);dao.expireCleanup(account,"ACCOUNT_CHANGED")
                if(engineReady)engine.call("logout")
                preferences.activateAccount("")
                backup.notice.value=""
            }
            credentials.remove(account);preferences.forgetAccount(account)
        } finally {switching.value=false;accountLock.unlock();scheduler.configure()}
    }
    suspend fun logout()=removeAccount(preferences.get().account)
}
