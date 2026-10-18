package io.gmgc.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class EvidencePolicyTest {
    @Test fun uploadAloneDoesNotProveQualityOrQuota() {
        val sample = SampleEvidence("submitted", false, false, false, false)
        assertEquals("unknown", EvidencePolicy.quality(sample))
        assertEquals("unknown", EvidencePolicy.quota(sample))
    }
    @Test fun duplicateCannotPassEvenWithStalePositiveEvidence() {
        val sample = SampleEvidence("duplicate_excluded", true, true, true, true)
        assertEquals("excluded", EvidencePolicy.quality(sample))
        assertEquals("excluded", EvidencePolicy.quota(sample))
    }
    @Test fun qualityAndQuotaAreIndependent() {
        val sample = SampleEvidence("submitted", true, true, true, false)
        assertEquals("verified_for_sample", EvidencePolicy.quality(sample))
        assertEquals("unknown", EvidencePolicy.quota(sample))
    }
    @Test fun quotaRequiresFirstObservationAndRecheck() {
        val invalid = SampleEvidence("submitted", false, false, false, true)
        assertEquals("unknown", EvidencePolicy.quota(invalid))
        assertEquals("manually_confirmed_for_sample", EvidencePolicy.quota(invalid.copy(officialFree = true)))
    }
}
