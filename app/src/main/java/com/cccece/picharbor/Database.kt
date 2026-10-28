package com.cccece.picharbor

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media", indices = [Index("folderKey"), Index("captured")])
data class MediaEntry(
    @PrimaryKey val id: String, val name: String, val mime: String, val size: Long,
    val modified: Long, val captured: Long, val folderKey: String, val folderName: String,
    val albumFolder: String = folderName, val source: String = "media", val seenAt: Long = 0
) { @get:Ignore val video get() = mime.startsWith("video/") }

@Entity(tableName = "backups", primaryKeys = ["account", "mediaId"], indices = [Index("account")])
data class BackupRecord(
    val account: String, val mediaId: String, val sha1: String, val sha256: String,
    val remoteKey: String, val size: Long, val modified: Long, val quality: String,
    val origin: String, val verifiedAt: Long
)

@Entity(tableName = "jobs", indices = [Index("account")])
data class BackupJob(
    @PrimaryKey val id: String, val account: String, val created: Long, val automatic: Boolean,
    val state: String = "queued", val completedAt: Long = 0, val allowMobile: Boolean = false,
    val albumMode: String = "folder", val albumValue: String = "", val quality: String = "original"
)

@Entity(tableName = "items", indices = [Index("jobId"), Index("account"), Index(value = ["jobId", "mediaId"], unique = true)])
data class BackupItem(
    @PrimaryKey val id: String, val jobId: String, val account: String, val mediaId: String,
    val name: String, val bytes: Long, val state: String = "queued", val remoteKey: String = "",
    val sha1: String = "", val sha256: String = "", val modified: Long = 0,
    val origin: String = "", val error: String = "", val attempt: Int = 0
)

@Entity(tableName = "cleanups", indices = [Index("account")])
data class CleanupRecord(
    @PrimaryKey val id: String, val account: String, val mediaId: String, val bytes: Long,
    val sha256: String, val remoteKey: String, val checkedAt: Long, val state: String,
    val reason: String = "", val deletedAt: Long = 0,
    @ColumnInfo(defaultValue="'full'") val policy:String="full",
    @ColumnInfo(defaultValue="''") val scanId:String=""
)

data class JobStatistics(val jobId:String,val total:Int,val succeeded:Int,val failed:Int,val skipped:Int,val bytes:Long,val finished:Int=0)

@Entity(tableName="cleanup_scans",indices=[Index("account")])
data class CleanupScan(@PrimaryKey val id:String,val account:String,val created:Long,
    val state:String="queued",val checked:Int=0,val total:Int=0,val completedAt:Long=0,val error:String="",
    @ColumnInfo(defaultValue="'full'") val mode:String="full",
    @ColumnInfo(defaultValue="'{}'") val metrics:String="{}",
    @ColumnInfo(defaultValue="0") val elapsedMs:Long=0,
    @ColumnInfo(defaultValue="2") val effectiveParallel:Int=2,
    @ColumnInfo(defaultValue="'full'") val policy:String="full")
@Entity(tableName="cleanup_scan_items",primaryKeys=["scanId","mediaId"],indices=[Index("scanId")])
data class CleanupScanItem(val scanId:String,val mediaId:String,val done:Boolean=false)

@Dao
interface HarborDao {
    @Query("SELECT * FROM cleanup_scans WHERE account=:account ORDER BY created DESC LIMIT 1") fun cleanupScanFlow(account:String):Flow<CleanupScan?>
    @Query("SELECT * FROM cleanup_scans WHERE account=:account ORDER BY created DESC LIMIT 1") suspend fun latestCleanupScan(account:String):CleanupScan?
    @Query("SELECT * FROM cleanup_scans WHERE id=:id") suspend fun cleanupScan(id:String):CleanupScan?
    @Upsert suspend fun putCleanupScan(scan:CleanupScan)
    @Upsert suspend fun putCleanupScanItems(items:List<CleanupScanItem>)
    @Upsert suspend fun putCleanupScanItem(item:CleanupScanItem)
    @Query("SELECT * FROM cleanup_scan_items WHERE scanId=:id ORDER BY rowid") suspend fun cleanupScanItems(id:String):List<CleanupScanItem>
    @Query("SELECT * FROM cleanup_scan_items WHERE scanId=:id ORDER BY rowid") fun cleanupScanItemsFlow(id:String):Flow<List<CleanupScanItem>>
    @Query("SELECT COUNT(*) FROM cleanup_scan_items WHERE scanId=:id AND done=1") suspend fun cleanupChecked(id:String):Int
    @Query("SELECT * FROM media ORDER BY captured DESC, id") fun mediaFlow(): Flow<List<MediaEntry>>
    @Query("SELECT * FROM media ORDER BY captured DESC") suspend fun media(): List<MediaEntry>
    @Query("SELECT * FROM media WHERE id=:id") suspend fun media(id: String): MediaEntry?
    @Upsert suspend fun putMedia(items: List<MediaEntry>)
    @Query("DELETE FROM media WHERE id=:id") suspend fun removeMedia(id: String)
    @Query("DELETE FROM media WHERE seenAt != :stamp AND source IN (:sources)") suspend fun finishScan(stamp: Long, sources: List<String>)
    @Query("SELECT * FROM backups WHERE account=:account") fun backupsFlow(account: String): Flow<List<BackupRecord>>
    @Query("SELECT * FROM backups WHERE account=:account") suspend fun backups(account: String): List<BackupRecord>
    @Query("SELECT * FROM backups WHERE account=:account AND mediaId=:id") suspend fun backup(account: String, id: String): BackupRecord?
    @Upsert suspend fun putBackup(record: BackupRecord)
    @Query("DELETE FROM backups WHERE account=:account AND mediaId=:id") suspend fun invalidate(account: String, id: String)
    @Query("SELECT * FROM jobs WHERE account=:account ORDER BY created DESC") fun jobsFlow(account: String): Flow<List<BackupJob>>
    @Query("SELECT jobId,COUNT(*) AS total,SUM(CASE WHEN state IN ('done','exists') THEN 1 ELSE 0 END) AS succeeded,SUM(CASE WHEN state IN ('failed','album_pending') THEN 1 ELSE 0 END) AS failed,SUM(CASE WHEN state='filename_skipped' THEN 1 ELSE 0 END) AS skipped,SUM(bytes) AS bytes,SUM(CASE WHEN state IN ('done','exists','filename_skipped','failed','cancelled','album_pending') THEN 1 ELSE 0 END) AS finished FROM items WHERE account=:account GROUP BY jobId") fun statisticsFlow(account:String):Flow<List<JobStatistics>>
    @Query("SELECT * FROM items WHERE jobId=:job ORDER BY rowid") fun jobItemsFlow(job:String):Flow<List<BackupItem>>
    @Query("SELECT * FROM jobs WHERE account=:account AND state IN ('queued','running','waiting') AND (:allowAutomatic OR automatic=0) ORDER BY automatic,created LIMIT 1") suspend fun nextJob(account: String,allowAutomatic:Boolean=true): BackupJob?
    @Query("SELECT * FROM jobs WHERE account=:account AND state='paused' ORDER BY created LIMIT 1") suspend fun pausedJob(account: String): BackupJob?
    @Query("SELECT * FROM jobs WHERE id=:id") suspend fun job(id: String): BackupJob?
    @Query("UPDATE jobs SET state='queued' WHERE account=:account AND state='auth_required'") suspend fun restoreAuthJobs(account:String)
    @Query("UPDATE items SET state='queued',error='' WHERE account=:account AND state='failed' AND error IN ('LOGIN_REQUIRED','DEVICE_VERIFICATION','ENCRYPTED_TOKEN')") suspend fun restoreAuthItems(account:String)
    @Upsert suspend fun putJob(job: BackupJob)
    @Query("UPDATE jobs SET state=:state WHERE id=:id") suspend fun jobState(id: String, state: String)
    @Query("UPDATE jobs SET state='queued' WHERE state IN ('running','pausing')") suspend fun recoverJobs()
    @Query("UPDATE items SET state=CASE WHEN remoteKey='' THEN 'queued' ELSE 'album_pending' END WHERE state IN ('hashing','checking','uploading','finalizing','finalizing_album')") suspend fun recoverItems()
    @Query("UPDATE jobs SET state='ended' WHERE account=:account AND state IN ('queued','running','waiting','paused','pausing')") suspend fun endAccountJobs(account: String)
    @Query("SELECT * FROM items WHERE account=:account ORDER BY rowid DESC LIMIT 10000") fun itemsFlow(account: String): Flow<List<BackupItem>>
    @Query("SELECT * FROM items WHERE jobId=:job ORDER BY rowid") suspend fun items(job: String): List<BackupItem>
    @Query("SELECT * FROM items WHERE id=:id") suspend fun item(id: String): BackupItem?
    @Query("SELECT COUNT(*) FROM items i JOIN jobs j ON i.jobId=j.id WHERE i.account=:account AND i.mediaId=:mediaId AND j.state IN ('queued','running','waiting','paused','pausing') AND i.state NOT IN ('failed','cancelled','filename_skipped')") suspend fun activeCount(account: String, mediaId: String): Int
    @Query("SELECT i.* FROM items i JOIN jobs j ON i.jobId=j.id WHERE i.account=:account AND i.mediaId=:mediaId AND j.state IN ('queued','running','waiting','paused','pausing','auth_required') AND i.state IN ('queued','hashing','checking','uploading','finalizing','finalizing_album','album_pending') LIMIT 1") suspend fun activeItem(account:String,mediaId:String):BackupItem?
    @Query("SELECT * FROM items WHERE id IN (:ids)") fun observeItems(ids:List<String>):Flow<List<BackupItem>>
    @Query("SELECT COUNT(*) FROM jobs WHERE account=:account AND automatic=0 AND state IN ('queued','running','waiting')") suspend fun pendingManual(account:String):Int
    @Query("UPDATE jobs SET state='paused' WHERE account=:account AND state IN ('queued','running','waiting','pausing')") suspend fun pauseAccountJobs(account:String)
    @Upsert suspend fun putItems(items: List<BackupItem>)
    @Upsert suspend fun putItem(item: BackupItem)
    @Query("SELECT * FROM cleanups WHERE account=:account ORDER BY checkedAt DESC") fun cleanupFlow(account: String): Flow<List<CleanupRecord>>
    @Query("SELECT * FROM cleanups WHERE account=:account AND state='ready'") suspend fun readyCleanup(account: String): List<CleanupRecord>
    @Upsert suspend fun putCleanup(record: CleanupRecord)
    @Query("SELECT * FROM cleanups WHERE id IN (:ids)") suspend fun cleanupByIds(ids:List<String>):List<CleanupRecord>
    @Query("UPDATE cleanups SET state='expired', reason=:reason WHERE account=:account AND state='ready'") suspend fun expireCleanup(account:String,reason:String="RECHECK_REQUIRED")
}

@Database(entities = [MediaEntry::class, BackupRecord::class, BackupJob::class, BackupItem::class, CleanupRecord::class,CleanupScan::class,CleanupScanItem::class], version = 4, exportSchema = true)
abstract class HarborDatabase : RoomDatabase() {
    abstract fun dao(): HarborDao
    companion object {
        val MIGRATION_1_2=object:androidx.room.migration.Migration(1,2) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS cleanup_scans (id TEXT NOT NULL PRIMARY KEY,account TEXT NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,checked INTEGER NOT NULL,total INTEGER NOT NULL,completedAt INTEGER NOT NULL,error TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cleanup_scans_account ON cleanup_scans(account)")
                db.execSQL("CREATE TABLE IF NOT EXISTS cleanup_scan_items (scanId TEXT NOT NULL,mediaId TEXT NOT NULL,done INTEGER NOT NULL,PRIMARY KEY(scanId,mediaId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cleanup_scan_items_scanId ON cleanup_scan_items(scanId)")
            }
        }
        val MIGRATION_2_3=object:androidx.room.migration.Migration(2,3) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cleanup_scans ADD COLUMN mode TEXT NOT NULL DEFAULT 'full'")
                db.execSQL("ALTER TABLE cleanup_scans ADD COLUMN metrics TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE cleanup_scans ADD COLUMN elapsedMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cleanup_scans ADD COLUMN effectiveParallel INTEGER NOT NULL DEFAULT 2")
            }
        }
        val MIGRATION_3_4=object:androidx.room.migration.Migration(3,4) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cleanup_scans ADD COLUMN policy TEXT NOT NULL DEFAULT 'full'")
                db.execSQL("ALTER TABLE cleanups ADD COLUMN policy TEXT NOT NULL DEFAULT 'full'")
                db.execSQL("ALTER TABLE cleanups ADD COLUMN scanId TEXT NOT NULL DEFAULT ''")
                // Old results cannot be unambiguously assigned to a particular session.
                db.execSQL("UPDATE cleanups SET state='expired',reason='RECHECK_REQUIRED' WHERE state='ready'")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, HarborDatabase::class.java, "picharbor.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4).build()
    }
}

object BackupPolicy {
    fun matches(media: MediaEntry, record: BackupRecord?) = record != null && record.remoteKey.isNotBlank() &&
        record.size == media.size && record.modified == media.modified && record.sha256.isNotBlank()
    fun cleanupFresh(record: CleanupRecord, account: String, now: Long,policy:String="full") = record.account == account && record.policy==policy && CleanupPolicy.recognized(policy) &&
        record.state == "ready" && record.checkedAt <= now && now - record.checkedAt < 5 * 60_000
    val terminalItems = setOf("done", "exists", "filename_skipped", "failed", "cancelled")
}
