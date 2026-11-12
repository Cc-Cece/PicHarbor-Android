package com.cccece.picharbor

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Semaphore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EngineCallbackTest {
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
}
