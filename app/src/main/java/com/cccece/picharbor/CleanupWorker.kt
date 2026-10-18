package com.cccece.picharbor

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

object CleanupNotifications {
    const val ID=7403
    private fun manager(context:Context)=context.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(NotificationChannel("cleanup","空间检查进度",NotificationManager.IMPORTANCE_LOW))
        createNotificationChannel(NotificationChannel("cleanup-result","空间检查结果",NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun open(context:Context,scan:CleanupScan)=PendingIntent.getActivity(context,scan.id.hashCode(),
        Intent(context,MainActivity::class.java).putExtra("open_cleanup",true).putExtra("cleanup_scan",scan.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun notification(context:Context,scan:CleanupScan):Notification {
        manager(context)
        val cancel=PendingIntent.getBroadcast(context,ID,Intent(context,CleanupCancelReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context,"cleanup").setSmallIcon(R.drawable.ic_harbor).setContentTitle(if(scan.mode=="quick")"正在快速检查备份状态" else "正在检查可释放空间")
            .setContentText(if(scan.state=="syncing")"正在核对云端备份 · ${scan.checked} / ${scan.total}" else "已检查 ${scan.checked} / ${scan.total} 项")
            .setProgress(scan.total.coerceAtLeast(1),scan.checked,false).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open(context,scan)).addAction(0,"停止检查",cancel).build()
    }
    fun progress(context:Context,scan:CleanupScan){runCatching{manager(context).notify(ID,notification(context,scan))}}
    fun cancel(context:Context){manager(context).cancel(ID)}
    fun finished(context:Context,scan:CleanupScan) {
        cancel(context)
        runCatching{manager(context).notify(scan.id.hashCode(),NotificationCompat.Builder(context,"cleanup-result")
            .setSmallIcon(R.drawable.ic_harbor).setContentTitle(if(scan.state!="completed")"检查需要处理" else if(scan.mode=="quick")"备份状态检查已完成" else "空间检查已完成")
            .setContentText(if(scan.state=="completed")"已检查 ${scan.checked} 项，点按查看结果。手机原件尚未删除。" else Messages.error(scan.error))
            .setContentIntent(open(context,scan)).setAutoCancel(true).build())}
    }
}

class CleanupWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun getForegroundInfo():ForegroundInfo {
        val graph=(applicationContext as PicHarborApp).graph
        val scan=graph.dao.cleanupScan(inputData.getString("scan_id").orEmpty())?:CleanupScan("","",0)
        return ForegroundInfo(CleanupNotifications.ID,CleanupNotifications.notification(applicationContext,scan),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override suspend fun doWork():Result {
        val graph=(applicationContext as PicHarborApp).graph
        graph.startup.await()
        val id=inputData.getString("scan_id")?:return Result.failure()
        val scan=graph.dao.cleanupScan(id)?:return Result.failure()
        if(scan.account!=graph.preferences.get().account)return Result.success()
        return try {
            setForeground(getForegroundInfo())
            if(graph.cleanup.runScan(id){isStopped})Result.success() else Result.retry()
        } catch(error:CancellationException){throw error}
        catch(_:Exception){Result.retry()}
    }
}

class CleanupCancelReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent){(context.applicationContext as PicHarborApp).graph.cleanup.cancel()}
}
