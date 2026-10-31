package com.cccece.picharbor

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

object ComputerNotifications {
    const val ID=7302
    private const val RESULT=7303
    fun channel(context:Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("computer-link","传到电脑",NotificationManager.IMPORTANCE_LOW))
    }
    fun ongoing(context:Context,text:String,percent:Int):Notification {
        channel(context)
        val open=PendingIntent.getActivity(context,8,Intent(context,MainActivity::class.java).putExtra("open_computer",true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getBroadcast(context,9,Intent(context,ComputerLinkStopReceiver::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder=NotificationCompat.Builder(context,"computer-link").setSmallIcon(R.drawable.ic_harbor).setContentTitle("传到这台电脑")
            .setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).addAction(0,"停止",stop)
        if(percent>=0)builder.setProgress(100,percent,false)
        return builder.build()
    }
    fun finished(context:Context,text:String) {
        channel(context)
        val open=PendingIntent.getActivity(context,10,Intent(context,MainActivity::class.java).putExtra("open_computer",true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(NotificationManager::class.java).notify(RESULT,NotificationCompat.Builder(context,"computer-link")
            .setSmallIcon(R.drawable.ic_harbor).setContentTitle("传到这台电脑").setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }
}

class ComputerLinkService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job:Job?=null
    private var generation=0
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val graph=(application as PicHarborApp).graph
        val initial=ComputerNotifications.ongoing(this,"准备发送",-1)
        if(Build.VERSION.SDK_INT>=29)startForeground(ComputerNotifications.ID,initial,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ComputerNotifications.ID,initial)
        if(job?.isActive==true)return START_NOT_STICKY
        val mine=++generation
        val wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"picharbor:computer-link")
        wake.setReferenceCounted(false)
        wake.acquire(10L*60*60*1000)
        graph.link.notifier={state->
            val percent=if(state.total>0)state.done*100/state.total else -1
            val text=state.error.ifBlank{state.phase.ifBlank{"正在发送"}}
            runCatching{getSystemService(NotificationManager::class.java).notify(ComputerNotifications.ID,ComputerNotifications.ongoing(this,text,percent))}
        }
        job=scope.launch {
            try { graph.link.execute() }
            finally {
                runCatching{if(wake.isHeld)wake.release()}
                if(generation==mine) {
                    graph.link.notifier={}
                    val state=graph.link.progress.value
                    val text=state.error.ifBlank{state.phase.ifBlank{"传输已结束"}}
                    runCatching{ComputerNotifications.finished(this@ComputerLinkService,text)}
                    @Suppress("DEPRECATION") stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if(job?.isActive==true)(application as PicHarborApp).graph.link.cancel()
        job?.cancel()
        super.onDestroy()
    }
}

class ComputerLinkStopReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        (context.applicationContext as PicHarborApp).graph.link.cancel()
    }
}
