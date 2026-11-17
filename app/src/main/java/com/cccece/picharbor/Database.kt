package com.cccece.picharbor

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media", indices = [Index("folderKey"), Index("captured")])
data class MediaEntry(
    @PrimaryKey val id: String, val name: String, val mime: String, val size: Long,
    val modified: Long, val captured: Long, val folderKey: String, val folderName: String,
    val albumFolder: String = folderName, val source: String = "media", val seenAt: Long = 0,
    val pairingID: String = ""
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
    val albumMode: String = "none", val albumValue: String = "", val quality: String = "original",
    val archivedAt: Long = 0, val hidden: Boolean = false, val unarchivedAt: Long = 0,
    val liveStrategy: String = "original", val editPolicy: String = "both", val proxyPolicy: String = "", val manualPriority: Boolean = false
)

@Entity(tableName = "items", indices = [Index("jobId"), Index("account"), Index(value = ["jobId", "mediaId"], unique = true)])
data class BackupItem(
    @PrimaryKey val id: String, val jobId: String, val account: String, val mediaId: String,
    val name: String, val bytes: Long, val state: String = "queued", val remoteKey: String = "",
    val sha1: String = "", val sha256: String = "", val modified: Long = 0,
    val origin: String = "", val error: String = "", val attempt: Int = 0, val hidden: Boolean = false,
    val archivedAt: Long = 0, val unarchivedAt: Long = 0
)

data class JobStatistics(val jobId:String,val total:Int,val succeeded:Int,val failed:Int,val skipped:Int,val bytes:Long,val finished:Int=0)

/** Shared decisions and native projections share one transactional database. */
@Entity(tableName = "core_tasks")
data class CoreTaskSnapshot(@PrimaryKey val id: String, val payload: String, val revision: Long)

@Entity(tableName = "suppressed_assets", primaryKeys = ["account", "mediaId", "modified"])
data class SuppressedAsset(val account: String, val mediaId: String, val modified: Long)

@Dao
interface HarborDao {
    @Query("SELECT * FROM core_tasks") suspend fun coreTasks(): List<CoreTaskSnapshot>
    @Query("SELECT * FROM core_tasks WHERE id=:id") suspend fun coreTask(id: String): CoreTaskSnapshot?
    @Upsert suspend fun putCoreTask(task: CoreTaskSnapshot)
    @Query("SELECT COUNT(*) FROM suppressed_assets WHERE account=:account AND mediaId=:id AND modified=:modified") suspend fun suppressed(account: String,id: String,modified: Long): Int
    @Upsert suspend fun suppress(asset: SuppressedAsset)
    @Query("UPDATE jobs SET archivedAt=:at,unarchivedAt=CASE WHEN :at=0 THEN :now ELSE unarchivedAt END WHERE id=:id AND state='completed'") suspend fun archiveJob(id: String,at: Long,now: Long=System.currentTimeMillis())
    @Query("UPDATE jobs SET hidden=1 WHERE id=:id") suspend fun hideJob(id: String)
    @Query("UPDATE items SET archivedAt=:at,unarchivedAt=CASE WHEN :at=0 THEN :now ELSE unarchivedAt END WHERE id=:id AND state IN ('done','exists')") suspend fun archiveItem(id:String,at:Long,now:Long=System.currentTimeMillis())
    @Query("SELECT * FROM media ORDER BY captured DESC, id") fun mediaFlow(): Flow<List<MediaEntry>>
    @Query("SELECT * FROM media ORDER BY captured DESC") suspend fun media(): List<MediaEntry>
    @Query("SELECT * FROM media WHERE id=:id") suspend fun media(id: String): MediaEntry?
    @Upsert suspend fun putMedia(items: List<MediaEntry>)
    @Query("UPDATE media SET pairingID=:pairingID WHERE id=:id AND modified=:modified AND size=:size")
    suspend fun updatePairingID(id:String,modified:Long,size:Long,pairingID:String)
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
    @Query("SELECT * FROM jobs WHERE account=:account AND state IN ('queued','running','waiting') AND (:allowAutomatic OR automatic=0 OR manualPriority=1) ORDER BY CASE WHEN automatic=0 OR manualPriority=1 THEN 0 ELSE 1 END,created LIMIT 1") suspend fun nextJob(account: String,allowAutomatic:Boolean=true): BackupJob?
    @Query("SELECT * FROM jobs WHERE account=:account AND state='paused' ORDER BY created LIMIT 1") suspend fun pausedJob(account: String): BackupJob?
    @Query("SELECT * FROM jobs WHERE id=:id") suspend fun job(id: String): BackupJob?
    @Query("UPDATE jobs SET state='queued' WHERE account=:account AND state='auth_required'") suspend fun restoreAuthJobs(account:String)
    @Query("UPDATE items SET state='queued',error='' WHERE account=:account AND state='failed' AND error IN ('LOGIN_REQUIRED','DEVICE_VERIFICATION','ENCRYPTED_TOKEN')") suspend fun restoreAuthItems(account:String)
    @Upsert suspend fun putJob(job: BackupJob)
    @Query("UPDATE jobs SET state=:state WHERE id=:id") suspend fun jobState(id: String, state: String)
    @Query("UPDATE items SET state='queued' WHERE id=:id AND state='failed' AND attempt=:attempt AND EXISTS(SELECT 1 FROM jobs WHERE jobs.id=items.jobId AND jobs.state='running')")
    suspend fun queueNetworkRetry(id:String,attempt:Int):Int
    @Query("UPDATE jobs SET state='queued' WHERE state IN ('running','pausing')") suspend fun recoverJobs()
    @Query("UPDATE items SET state=CASE WHEN remoteKey='' THEN 'queued' ELSE 'album_pending' END WHERE state IN ('hashing','checking','uploading','finalizing','finalizing_album')") suspend fun recoverItems()
    @Query("UPDATE jobs SET state='ended' WHERE account=:account AND state IN ('queued','running','waiting','paused','pausing')") suspend fun endAccountJobs(account: String)
    @Query("SELECT * FROM items WHERE account=:account ORDER BY rowid DESC LIMIT 10000") fun itemsFlow(account: String): Flow<List<BackupItem>>
    @Query("SELECT * FROM items WHERE jobId=:job ORDER BY rowid") suspend fun items(job: String): List<BackupItem>
    @Query("SELECT * FROM items WHERE id=:id") suspend fun item(id: String): BackupItem?
    @Query("SELECT COUNT(*) FROM items i JOIN jobs j ON i.jobId=j.id WHERE i.account=:account AND i.mediaId=:mediaId AND j.state IN ('queued','running','waiting','paused','pausing') AND i.state NOT IN ('failed','cancelled','filename_skipped')") suspend fun activeCount(account: String, mediaId: String): Int
    @Query("SELECT i.* FROM items i JOIN jobs j ON i.jobId=j.id WHERE i.account=:account AND i.mediaId=:mediaId AND j.state IN ('queued','running','waiting','paused','pausing','auth_required') AND i.state IN ('queued','hashing','checking','uploading','finalizing','finalizing_album','album_pending','paused','waiting_confirmation','waiting_network','interrupted') LIMIT 1") suspend fun activeItem(account:String,mediaId:String):BackupItem?
    @Query("SELECT * FROM items WHERE id IN (:ids)") fun observeItems(ids:List<String>):Flow<List<BackupItem>>
    /** Latest done/exists item for a media id, used to attach reconcile evidence to a core task id. */
    @Query("SELECT * FROM items WHERE account=:account AND mediaId=:mediaId AND state IN ('done','exists') ORDER BY rowid DESC LIMIT 1") suspend fun latestDoneItem(account:String,mediaId:String):BackupItem?
    @Query("SELECT COUNT(*) FROM jobs WHERE account=:account AND automatic=0 AND state IN ('queued','running','waiting')") suspend fun pendingManual(account:String):Int
    @Query("UPDATE jobs SET state='paused' WHERE account=:account AND state IN ('queued','running','waiting','pausing')") suspend fun pauseAccountJobs(account:String)
    @Upsert suspend fun putItems(items: List<BackupItem>)
    @Upsert suspend fun putItem(item: BackupItem)
}

@Database(entities = [MediaEntry::class, BackupRecord::class, BackupJob::class, BackupItem::class, CoreTaskSnapshot::class, SuppressedAsset::class], version = 6, exportSchema = true)
abstract class HarborDatabase : RoomDatabase() {
    abstract fun dao(): HarborDao
    companion object {
        val MIGRATION_5_6=object:androidx.room.migration.Migration(5,6) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN pairingID TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE jobs ADD COLUMN archivedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN unarchivedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN liveStrategy TEXT NOT NULL DEFAULT 'original'")
                db.execSQL("ALTER TABLE jobs ADD COLUMN editPolicy TEXT NOT NULL DEFAULT 'both'")
                db.execSQL("ALTER TABLE jobs ADD COLUMN proxyPolicy TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE jobs ADD COLUMN manualPriority INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN archivedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN unarchivedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS core_tasks (id TEXT NOT NULL PRIMARY KEY,payload TEXT NOT NULL,revision INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS suppressed_assets (account TEXT NOT NULL,mediaId TEXT NOT NULL,modified INTEGER NOT NULL,PRIMARY KEY(account,mediaId,modified))")
            }
        }
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
        val MIGRATION_4_5=object:androidx.room.migration.Migration(4,5) {
            override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
                // Stage-two decision (§8/D1): the old self-built cleanup
                // tables go away with the feature they served. The migration
                // itself only runs when the app opens the database (code
                // level here); backup records, jobs and items are untouched.
                db.execSQL("DROP TABLE IF EXISTS cleanup_scan_items")
                db.execSQL("DROP TABLE IF EXISTS cleanup_scans")
                db.execSQL("DROP TABLE IF EXISTS cleanups")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, HarborDatabase::class.java, "picharbor.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6).build()
    }
}

object BackupPolicy {
    fun matches(media: MediaEntry, record: BackupRecord?) = record != null && record.remoteKey.isNotBlank() &&
        record.size == media.size && record.modified == media.modified && record.sha256.isNotBlank()
    val terminalItems = setOf("done", "exists", "filename_skipped", "failed", "cancelled")
}
