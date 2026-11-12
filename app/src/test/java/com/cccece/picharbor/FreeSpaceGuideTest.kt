package com.cccece.picharbor

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

/**
 * Official free-up-space handoff: PicHarbor must never delete local assets
 * itself and must only hand the user to Google Photos' official cleanup.
 * These tests cover the Intent chain only; launch() needs a device and is
 * covered by the chain tests (same intents, same order).
 */
class FreeSpaceGuideTest {
    @Test fun freeUpSpaceLinkIsTheVerifiedUniversalLink() {
        val intent = FreeSpaceGuide.freeUpSpaceLinkIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://photos.google.com/link/freeupspace", intent.data.toString())
    }

    @Test fun photosAppIntentTargetsOnlyThePhotosPackage() {
        val intent = FreeSpaceGuide.photosAppIntent()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(FreeSpaceGuide.PHOTOS_PACKAGE, intent.`package`)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
    }

    @Test fun storeFallbacksPointAtPhotos() {
        assertEquals("market://details?id=com.google.android.apps.photos",
            FreeSpaceGuide.storeIntent().data.toString())
        assertEquals("https://play.google.com/store/apps/details?id=com.google.android.apps.photos",
            FreeSpaceGuide.webStoreIntent().data.toString())
    }

    @Test fun noIntentCarriesADeleteAction() {
        val intents = FreeSpaceGuide.attempts(true).map { it.second } +
            FreeSpaceGuide.attempts(false).map { it.second }
        assertTrue(intents.isNotEmpty())
        intents.forEach { intent ->
            assertNotEquals(Intent.ACTION_DELETE, intent.action)
            assertTrue(intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_MAIN)
        }
    }

    @Test fun fallbackOrderPrefersVerifiedLinkThenPhotosThenStore() {
        val installed = FreeSpaceGuide.attempts(true).map { it.first }
        assertEquals(listOf(FreeSpaceGuide.Outcome.OPENED_LINK,
            FreeSpaceGuide.Outcome.OPENED_PHOTOS,
            FreeSpaceGuide.Outcome.OPENED_STORE,
            FreeSpaceGuide.Outcome.OPENED_STORE), installed)
        val missing = FreeSpaceGuide.attempts(false).map { it.first }
        assertEquals(listOf(FreeSpaceGuide.Outcome.OPENED_LINK,
            FreeSpaceGuide.Outcome.OPENED_STORE,
            FreeSpaceGuide.Outcome.OPENED_STORE), missing)
    }

    @Test fun attemptsNeverIncludeAManualGuidanceIntent() {
        // MANUAL_GUIDANCE is a return value, not an Intent in the chain.
        assertFalse(FreeSpaceGuide.attempts(true).any { it.first == FreeSpaceGuide.Outcome.MANUAL_GUIDANCE })
        assertFalse(FreeSpaceGuide.attempts(false).any { it.first == FreeSpaceGuide.Outcome.MANUAL_GUIDANCE })
    }
}
