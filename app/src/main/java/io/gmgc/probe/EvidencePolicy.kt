package io.gmgc.probe

/** Evidence refers only to a newly submitted sample, never to account-wide entitlement. */
data class SampleEvidence(
    val upload: String,
    val officialOriginal: Boolean,
    val originalHashMatches: Boolean,
    val officialFree: Boolean,
    val freeRechecked: Boolean
)

object EvidencePolicy {
    fun quality(sample: SampleEvidence): String = when {
        sample.upload != "submitted" -> "excluded"
        sample.officialOriginal && sample.originalHashMatches -> "verified_for_sample"
        else -> "unknown"
    }
    fun quota(sample: SampleEvidence): String = when {
        sample.upload != "submitted" -> "excluded"
        sample.officialFree && sample.freeRechecked -> "manually_confirmed_for_sample"
        else -> "unknown"
    }
}
