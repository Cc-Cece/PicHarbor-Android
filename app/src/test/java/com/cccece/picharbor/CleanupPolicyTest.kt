package com.cccece.picharbor

import org.junit.Test
import org.junit.Assert.*

class CleanupPolicyTest {
    private val record=CleanupRecord("r","a","m",1,"hash","key",100,"ready",policy="basic")
    @Test fun reducedProofCannotBeReusedForFullVerification() {
        assertFalse(BackupPolicy.cleanupFresh(record,"a",101))
        assertTrue(BackupPolicy.cleanupFresh(record,"a",101,"basic"))
        assertFalse(BackupPolicy.cleanupFresh(record,"b",101,"basic"))
        assertFalse(BackupPolicy.cleanupFresh(record,"a",300101,"basic"))
        assertFalse(BackupPolicy.cleanupFresh(record.copy(policy="unknown"),"a",101,"unknown"))
    }
    @Test fun everyWeakeningRequiresAcknowledgement() {
        val full=CleanupPolicy()
        assertTrue(CleanupPolicy(false,true).weakerThan(full))
        assertTrue(CleanupPolicy(true,false).weakerThan(full))
        assertTrue(CleanupPolicy(false,true).weakerThan(CleanupPolicy(true,false)))
        assertFalse(full.weakerThan(CleanupPolicy(false,false)))
    }
    @Test fun persistedFlagsWithoutMatchingConsentFailClosed() {
        assertEquals("full",HarborSettings(cleanupCloudState=false,cleanupOriginalAccess=false).cleanupPolicy.id)
        assertEquals("basic",HarborSettings(cleanupCloudState=false,cleanupOriginalAccess=false,cleanupConsent="basic").cleanupPolicy.id)
        assertEquals("full",HarborSettings(cleanupCloudState=false,cleanupOriginalAccess=true,cleanupConsent="basic").cleanupPolicy.id)
    }
    @Test fun categoriesAreMutuallyExclusiveAndExpire() {
        assertEquals("ready",CleanupResultPolicy.category(record,"a",101,"basic","basic"))
        assertEquals("pending",CleanupResultPolicy.category(record,"a",101,"basic","full"))
        assertEquals("pending",CleanupResultPolicy.category(record,"a",300101,"basic","basic"))
        assertEquals("pending",CleanupResultPolicy.category(null,"a",101,"basic","basic"))
        assertEquals("attention",CleanupResultPolicy.category(record.copy(state="blocked"),"a",101,"basic","basic"))
        assertEquals("found",CleanupResultPolicy.category(record.copy(state="quick_found"),"a",101,"quick","basic"))
        assertEquals("removed",CleanupResultPolicy.category(record.copy(state="deleted"),"a",101,"basic","basic"))
    }
}
