package com.cccece.picharbor

/** Session-local admission limit. In-flight requests drain normally when the limit falls. */
class CheckParallelPolicy(maximum:Int,private val adaptive:Boolean=true) {
    val maximum=maximum.coerceIn(1,20)
    @Volatile var limit=this.maximum
        private set
    private var failures=0
    private var successes=0
    @Synchronized fun result(code:String) {
        if(!adaptive)return
        if(transient(code)) {
            successes=0;failures++
            if(code=="RATE_LIMITED"||failures>=2){limit=(limit/2).coerceAtLeast(1);failures=0}
        } else if(code.isEmpty()) {
            failures=0;successes++
            if(successes>=8){limit=(limit+1).coerceAtMost(maximum);successes=0}
        }
    }
    companion object {
        fun transient(code:String)=code in setOf("NETWORK_ERROR","ORIGINAL_NETWORK_ERROR","RATE_LIMITED")
        fun login(code:String)=code in setOf("LOGIN_REQUIRED","DEVICE_VERIFICATION","ENCRYPTED_TOKEN","ACCOUNT_CHANGED")
    }
}
