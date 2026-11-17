package com.cccece.picharbor

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

/**
 * Official free-up-space handoff: PicHarbor must never delete local assets
 * itself and must only hand the user to Google Photos' official cleanup.
 * These tests cover the Intent chain only; launch() needs a device and is
 * covered by the chain tests (same intents, same order).
 *
 * The chain is asserted as pure data (FreeSpaceGuide.attemptSpecs) rather
 * than as built Intents: local JVM unit tests run against android.jar stubs,
 * so constructing an Intent or calling Uri.parse() throws
 * RuntimeException("Stub!") before any assertion runs. attempts() is built
 * from the same specs, so the tested data and the launched Intents cannot
 * drift apart.
 */
class FreeSpaceGuideTest {
    @Test fun freeUpSpaceLinkIsTheVerifiedUniversalLink() {
        val first = FreeSpaceGuide.attemptSpecs(true).first()
        assertEquals(FreeSpaceGuide.Outcome.OPENED_LINK, first.outcome)
        assertEquals(Intent.ACTION_VIEW, first.action)
        assertEquals("https://photos.google.com/link/freeupspace", first.data)
    }

    @Test fun photosAppIntentTargetsOnlyThePhotosPackage() {
        val photos = FreeSpaceGuide.attemptSpecs(true)
            .first { it.outcome == FreeSpaceGuide.Outcome.OPENED_PHOTOS }
        assertEquals(Intent.ACTION_MAIN, photos.action)
        assertEquals(FreeSpaceGuide.PHOTOS_PACKAGE, photos.pkg)
        assertTrue(photos.categories.contains(Intent.CATEGORY_LAUNCHER))
    }

    @Test fun storeFallbacksPointAtPhotos() {
        val store = FreeSpaceGuide.attemptSpecs(true)
            .filter { it.outcome == FreeSpaceGuide.Outcome.OPENED_STORE }
        assertEquals(2, store.size)
        assertEquals("market://details?id=com.google.android.apps.photos", store[0].data)
        assertEquals("https://play.google.com/store/apps/details?id=com.google.android.apps.photos",
            store[1].data)
    }

    @Test fun noIntentCarriesADeleteAction() {
        val specs = FreeSpaceGuide.attemptSpecs(true) + FreeSpaceGuide.attemptSpecs(false)
        assertTrue(specs.isNotEmpty())
        specs.forEach { spec ->
            assertNotEquals(Intent.ACTION_DELETE, spec.action)
            assertTrue(spec.action == Intent.ACTION_VIEW || spec.action == Intent.ACTION_MAIN)
        }
    }

    @Test fun fallbackOrderPrefersVerifiedLinkThenPhotosThenStore() {
        val installed = FreeSpaceGuide.attemptSpecs(true).map { it.outcome }
        assertEquals(listOf(FreeSpaceGuide.Outcome.OPENED_LINK,
            FreeSpaceGuide.Outcome.OPENED_PHOTOS,
            FreeSpaceGuide.Outcome.OPENED_STORE,
            FreeSpaceGuide.Outcome.OPENED_STORE), installed)
        val missing = FreeSpaceGuide.attemptSpecs(false).map { it.outcome }
        assertEquals(listOf(FreeSpaceGuide.Outcome.OPENED_LINK,
            FreeSpaceGuide.Outcome.OPENED_STORE,
            FreeSpaceGuide.Outcome.OPENED_STORE), missing)
    }

    @Test fun attemptsNeverIncludeAManualGuidanceIntent() {
        // MANUAL_GUIDANCE is a return value, not an Intent in the chain.
        assertFalse(FreeSpaceGuide.attemptSpecs(true).any { it.outcome == FreeSpaceGuide.Outcome.MANUAL_GUIDANCE })
        assertFalse(FreeSpaceGuide.attemptSpecs(false).any { it.outcome == FreeSpaceGuide.Outcome.MANUAL_GUIDANCE })
    }
}
