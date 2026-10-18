package com.cccece.picharbor

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Semaphore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CheckParallelPolicyTest {
    @Test fun limitIsBoundedAndRecoversGradually() {
        val policy=CheckParallelPolicy(25)
        assertEquals(20,policy.limit)
        policy.result("RATE_LIMITED");assertEquals(10,policy.limit)
        repeat(8){policy.result("")};assertEquals(11,policy.limit)
        repeat(2){policy.result("NETWORK_ERROR")};assertEquals(5,policy.limit)
        repeat(30){policy.result("RATE_LIMITED")};assertEquals(1,policy.limit)
        assertEquals(1,CheckParallelPolicy(0).limit)
    }
    @Test fun semanticFailuresDoNotTriggerNetworkRetryOrSpeedChange() {
        val policy=CheckParallelPolicy(8)
        listOf("SOURCE_CHANGED","REMOTE_MISSING","CLOUD_STATE_UNKNOWN","ORIGINAL_UNAVAILABLE").forEach {
            policy.result(it);assertFalse(CheckParallelPolicy.transient(it))
        }
        assertEquals(8,policy.limit)
        assertTrue(CheckParallelPolicy.login("LOGIN_REQUIRED"))
        assertFalse(CheckParallelPolicy.transient("LOGIN_REQUIRED"))
        val fixed=CheckParallelPolicy(20,false)
        repeat(5){fixed.result("RATE_LIMITED")};assertEquals(20,fixed.limit)
    }
    @Test fun twentyRequestsRespectSeparateReaderLimit() {
        val gate=Semaphore(2,true)
        val callback=EngineCallback(readGate=gate)
        val active=AtomicInteger();val peak=AtomicInteger()
        val executor=Executors.newFixedThreadPool(20)
        try {
            val tasks=(1..20).map {executor.submit {
                assertTrue(callback.acquireRead())
                try {val now=active.incrementAndGet();peak.accumulateAndGet(now,Math::max);Thread.sleep(10)}
                finally {active.decrementAndGet();callback.releaseRead()}
            }}
            tasks.forEach{it.get(5,TimeUnit.SECONDS)}
            assertEquals(2,peak.get());assertEquals(2,gate.availablePermits())
        } finally {executor.shutdownNow()}
    }
    @Test fun cancelledReaderDoesNotAcquirePermit() {
        val gate=Semaphore(0)
        val cancelled=AtomicBoolean(true)
        assertFalse(EngineCallback(cancelled::get,readGate=gate).acquireRead())
        assertEquals(0,gate.availablePermits())
    }
    @Test fun quickResultCannotQualifyForCleanup() {
        val record=CleanupRecord("quick:test","a","m",1,"hash","key",100,"quick_found")
        assertFalse(BackupPolicy.cleanupFresh(record,"a",101))
    }
}
