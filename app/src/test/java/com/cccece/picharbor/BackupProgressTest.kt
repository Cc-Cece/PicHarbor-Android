package com.cccece.picharbor

import org.junit.Assert.*
import org.junit.Test

class BackupProgressTest {
    @Test fun includesBothConcurrentUploads() {
        val streams=listOf(TransferProgress(phase="uploading",bytes=50,total=100),TransferProgress(phase="uploading",bytes=25,total=100))
        assertEquals(.4375f,BackupProgressPolicy.fraction(1,4,streams),.0001f)
    }
    @Test fun readingHashesIsNotCountedAsUploaded() {
        assertEquals(0f,BackupProgressPolicy.fraction(0,1,listOf(TransferProgress(phase="hashing",bytes=100,total=100))),0f)
    }
    @Test fun waitsForCommitBeforeCallingTheFileComplete() {
        assertTrue(BackupProgressPolicy.fraction(0,1,listOf(TransferProgress(phase="finalizing",bytes=100,total=100)))<1f)
        assertEquals(1f,BackupProgressPolicy.fraction(1,1,emptyList()),0f)
    }
    @Test fun restoredQueueKeepsCompletedFileProgress() {
        assertEquals(.5f,BackupProgressPolicy.fraction(3,6,emptyList()),0f)
        assertEquals(0f,BackupProgressPolicy.fraction(0,0,emptyList()),0f)
    }
}
