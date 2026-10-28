package com.cccece.picharbor

data class CleanupPolicy(val cloudState:Boolean=true,val originalAccess:Boolean=true) {
    val id get()=when {cloudState&&originalAccess->"full";cloudState->"state_only";originalAccess->"access_only";else->"basic"}
    val complete get()=cloudState&&originalAccess
    val label get()=if(complete)"完整核验" else "已降低核验强度"
    val skipped get()=buildList {
        if(!cloudState)add("最新云端状态（回收站、处理状态、原始画质与大小）")
        if(!originalAccess)add("云端原件可访问性与大小")
    }
    fun weakerThan(previous:CleanupPolicy)=(!cloudState&&previous.cloudState)||(!originalAccess&&previous.originalAccess)
    companion object {
        const val ACKNOWLEDGEMENT="我已确认该功能的风险并仍旧切换"
        fun fromId(id:String)=when(id){"state_only"->CleanupPolicy(true,false);"access_only"->CleanupPolicy(false,true);"basic"->CleanupPolicy(false,false);else->CleanupPolicy()}
        fun recognized(id:String)=id in setOf("full","state_only","access_only","basic")
    }
}
data class PendingCleanupPolicy(val account:String,val previous:CleanupPolicy,val desired:CleanupPolicy)

object CleanupResultPolicy {
    fun category(record:CleanupRecord?,account:String,now:Long,scanPolicy:String,currentPolicy:String):String=when {
        record==null->"pending"
        record.account!=account->"attention"
        record.state=="deleted"->"removed"
        record.state=="quick_found"->"found"
        scanPolicy==currentPolicy&&BackupPolicy.cleanupFresh(record,account,now,currentPolicy)->"ready"
        record.state in setOf("ready","expired","cancelled")->"pending"
        else->"attention"
    }
}
