package com.cccece.picharbor

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

class Scheduler(private val graph:AppGraph) {
    private val sequence=AtomicInteger((android.os.SystemClock.elapsedRealtime()%90000).toInt())
    private val work get()=WorkManager.getInstance(graph.app)
    suspend fun configure() {
        val settings=graph.preferences.get()
        if(!settings.auto||settings.account.isEmpty()||(!settings.restart&&settings.bootSession!=bootCount(graph.app))) {
            work.cancelUniqueWork("automatic");work.cancelUniqueWork("daily");work.cancelUniqueWork("media-change");return
        }
        if(settings.scheduled) {
            work.cancelUniqueWork("automatic")
            val request=OneTimeWorkRequestBuilder<BackupWorker>().setInputData(workDataOf("automatic" to true,"daily" to true))
                .setConstraints(constraints(settings,true)).setInitialDelay(SchedulePolicy.nextDelay(settings.time),TimeUnit.MILLISECONDS).build()
            work.enqueueUniqueWork("daily",ExistingWorkPolicy.REPLACE,request)
        } else {
            work.cancelUniqueWork("daily")
            val request=PeriodicWorkRequestBuilder<BackupWorker>(15,TimeUnit.MINUTES)
                .setInputData(workDataOf("automatic" to true)).setConstraints(constraints(settings,true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            work.enqueueUniquePeriodicWork("automatic",ExistingPeriodicWorkPolicy.UPDATE,request)
        }
    }
    suspend fun checkSoon() {
        val settings=graph.preferences.get()
        if(!settings.auto||settings.scheduled||settings.account.isEmpty())return
        work.enqueueUniqueWork("media-change",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(workDataOf("automatic" to true)).setConstraints(constraints(settings,true)).build())
    }
    suspend fun launchManual(allowMobile:Boolean=false) {
        // Every durable submission gets a wake-up, including while the previous runner exits.
        val id=700000+sequence.incrementAndGet()%90000
        if(Build.VERSION.SDK_INT>=34) {
            val request=android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                // Builder otherwise silently excludes VPN, leaving accepted jobs waiting forever.
                .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            val info=JobInfo.Builder(id,ComponentName(graph.app,TransferJob::class.java)).setUserInitiated(true).setRequiredNetwork(request)
                .setEstimatedNetworkBytes(0,graph.dao.media().sumOf{it.size}.coerceAtLeast(1)).build()
            if(runCatching { graph.app.getSystemService(JobScheduler::class.java).schedule(info)==JobScheduler.RESULT_SUCCESS }.getOrDefault(false)) {
                // Independent watchdog only if Android never starts the accepted UIDT job.
                work.enqueueUniqueWork("watchdog-$id",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<BackupWorker>()
                    .addTag("transfer").setInitialDelay(8,TimeUnit.SECONDS).setConstraints(constraints(graph.preferences.get(),false,allowMobile)).build())
                return
            }
        }
        work.enqueueUniqueWork("manual-${UUID.randomUUID()}",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag("transfer").setInputData(workDataOf("automatic" to false)).setConstraints(constraints(graph.preferences.get(),false,allowMobile)).build())
    }
    suspend fun restorePending() {
        val settings=graph.preferences.get()
        if(settings.account.isEmpty()||(!settings.restart&&settings.bootSession!=bootCount(graph.app)))return
        val job=graph.dao.nextJob(settings.account,settings.auto)?:return
        if(job.automatic&&!settings.auto)return
        work.enqueueUniqueWork("recovery-${UUID.randomUUID()}",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag("transfer").setConstraints(constraints(settings,job.automatic,job.allowMobile)).build())
    }
    suspend fun cancelTransfers()=withContext(Dispatchers.IO) {
        graph.app.getSystemService(JobScheduler::class.java).allPendingJobs.filter{it.service.className==TransferJob::class.java.name}.forEach {
            graph.app.getSystemService(JobScheduler::class.java).cancel(it.id)
        }
        work.cancelAllWorkByTag("transfer").result.get()
        for(name in listOf("manual","recovery","automatic","daily","media-change"))work.cancelUniqueWork(name).result.get()
    }
    private fun constraints(settings:HarborSettings,automatic:Boolean,allowMobile:Boolean=false)=Constraints.Builder()
        .setRequiredNetworkType(if(automatic&&settings.wifi&&!allowMobile)NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(automatic&&settings.charging).setRequiresStorageNotLow(automatic).build()
}

object TransferNotifications {
    const val ID=7301
    fun create(context:Context,text:String,percent:Int=-1):Notification {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("backup","备份进度",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause=PendingIntent.getBroadcast(context,1,Intent(context,PauseReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context,"backup").setSmallIcon(R.drawable.ic_harbor).setContentTitle("PicHarbor")
            .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100,percent.coerceAtLeast(0),percent<0).addAction(0,"暂停",pause).build()
    }
    fun foreground(context:Context)=ForegroundInfo(ID,create(context,"准备备份"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    fun completed(context:Context,job:BackupJob,items:List<BackupItem>) {
        val success=items.count{it.state in setOf("done","exists")}
        val failed=items.count{it.state in setOf("failed","album_pending")}
        val open=PendingIntent.getActivity(context,job.id.hashCode(),Intent(context,MainActivity::class.java)
            .putExtra("open_history",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { context.getSystemService(NotificationManager::class.java).notify(job.id.hashCode(),
            NotificationCompat.Builder(context,"backup").setSmallIcon(R.drawable.ic_harbor).setContentTitle(if(failed==0)"备份完成" else "备份有项目需要处理")
                .setContentText("成功 $success 项 · 需要处理 $failed 项").setContentIntent(open).setAutoCancel(true).setSilent(true).build()) }
    }
}

class BackupWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun getForegroundInfo()=TransferNotifications.foreground(applicationContext)
    override suspend fun doWork():Result {
        val graph=(applicationContext as PicHarborApp).graph
        val automatic=inputData.getBoolean("automatic",false)
        var dailyFinished=true
        try {
            val settings=graph.preferences.get()
            if(automatic&&(!settings.auto||settings.account.isEmpty()||(!settings.restart&&settings.bootSession!=bootCount(applicationContext))))return Result.success()
            setForeground(getForegroundInfo())
            if(automatic){graph.media.scan();graph.backup.reconcile();graph.backup.enqueue(automatic=true)}
            val success=graph.backup.run({isStopped}) {text,percent ->
                runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(TransferNotifications.ID,TransferNotifications.create(applicationContext,text,percent)) }
            }
            return if(success)Result.success() else if(runAttemptCount<3){dailyFinished=false;Result.retry()} else Result.failure()
        } catch (_:Exception){return if(runAttemptCount<3){dailyFinished=false;Result.retry()} else Result.failure()}
        finally { if(dailyFinished&&inputData.getBoolean("daily",false))withContext(NonCancellable){graph.scheduler.configure()} }
    }
}
fun bootCount(context:Context)=runCatching{android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT)}.getOrDefault(0)

class TransferJob:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val tokens=java.util.concurrent.ConcurrentHashMap<Int,AtomicBoolean>()
    override fun onStartJob(params:JobParameters):Boolean {
        val token=AtomicBoolean(false);tokens[params.jobId]=token
        WorkManager.getInstance(this).cancelUniqueWork("watchdog-${params.jobId}")
        if(Build.VERSION.SDK_INT>=34)setNotification(params,params.jobId,TransferNotifications.create(this,"准备备份"),JOB_END_NOTIFICATION_POLICY_REMOVE)
        scope.launch {
            val graph=(application as PicHarborApp).graph
            val success=graph.backup.run({token.get()}) {text,percent -> runCatching {
                getSystemService(NotificationManager::class.java).notify(params.jobId,TransferNotifications.create(this@TransferJob,text,percent))
            } }
            if(!token.get())jobFinished(params,!success)
            tokens.remove(params.jobId,token)
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {tokens.remove(params.jobId)?.set(true);return true}
    override fun onDestroy(){tokens.values.forEach{it.set(true)};scope.cancel();super.onDestroy()}
}
class PauseReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val pending=goAsync();val graph=(context.applicationContext as PicHarborApp).graph
        graph.scope.launch {try{graph.backup.pause()}finally{pending.finish()}}
    }
}
class RestoreReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val pending=goAsync();val graph=(context.applicationContext as PicHarborApp).graph
        graph.scope.launch {try {
            val settings=graph.preferences.get()
            if(intent.action!=Intent.ACTION_BOOT_COMPLETED||settings.restart){graph.scheduler.configure();graph.scheduler.restorePending();graph.cleanup.restorePending();if(!settings.scheduled)graph.scheduler.checkSoon()}
        } finally {pending.finish()}}
    }
}
