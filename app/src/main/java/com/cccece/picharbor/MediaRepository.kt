package com.cccece.picharbor

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class MediaRepository(private val context: Context, private val dao: HarborDao, private val preferences: Preferences) {
    private val scanMutex = Mutex()
    private var observer: ContentObserver? = null
    private var changeJob: Job? = null
    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO).any { ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED } ||
            (Build.VERSION.SDK_INT>=34 && ContextCompat.checkSelfPermission(context,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED)
    } else ContextCompat.checkSelfPermission(context,Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
    fun fullAccess(): Boolean = if (Build.VERSION.SDK_INT>=33) listOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO).all { ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED } else hasAccess()
    fun observe(scope: CoroutineScope, onChange: suspend () -> Unit) {
        if(observer!=null)return
        observer=object:ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange:Boolean) { changeJob?.cancel();changeJob=scope.launch { delay(2500);onChange() } }
        }
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"),true,observer!!) }
    }
    suspend fun scan() = withContext(Dispatchers.IO) { scanMutex.withLock {
        val stamp=System.currentTimeMillis()
        val completed=mutableListOf<String>()
        if(hasAccess()) {
            try {
                val volumes=MediaStore.getExternalVolumeNames(context)
                for(volume in volumes) scanVolume(volume,stamp)
                completed += "media"
            } catch (_:SecurityException) { /* Keep indexed rows: permission may be partial/revoked mid-scan. */ }
        }
        for(tree in preferences.get().trees) {
            try { scanTree(tree,stamp);completed+=tree } catch (_:Exception) { /* Re-authorize from folders page. */ }
        }
        if(completed.isNotEmpty()) dao.finishScan(stamp,completed)
    } }
    private suspend fun scanVolume(volume:String, stamp:Long) {
        val uri=MediaStore.Files.getContentUri(volume)
        val projection=arrayOf(MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,MediaStore.Files.FileColumns.SIZE,MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,MediaStore.Files.FileColumns.RELATIVE_PATH,MediaStore.Images.Media.DATE_TAKEN)
        val batch=ArrayList<MediaEntry>(200)
        val visible=if(Build.VERSION.SDK_INT>=30) " AND ${MediaStore.MediaColumns.IS_TRASHED}=0" else ""
        context.contentResolver.query(uri,projection,"${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?) AND ${MediaStore.Files.FileColumns.IS_PENDING}=0$visible",
            arrayOf("1","3"),null)?.use { cursor ->
            while(cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id=ContentUris.withAppendedId(uri,cursor.getLong(0)).toString()
                val relative=cursor.getString(6).orEmpty().trimEnd('/')
                val folder=relative.substringAfterLast('/').ifBlank { "照片" }
                batch+=MediaEntry(id,cursor.getString(1)?:"照片",cursor.getString(2)?:"image/jpeg",cursor.getLong(3),cursor.getLong(4)*1000,
                    cursor.getLong(7).takeIf{it>0}?:cursor.getLong(5)*1000,"$volume|$relative",folder,folder,"media",stamp)
                if(batch.size>=200){dao.putMedia(batch.toList());batch.clear()}
            }
        } ?: throw SecurityException("MEDIA_UNAVAILABLE")
        if(batch.isNotEmpty())dao.putMedia(batch)
    }
    private suspend fun scanTree(tree:String, stamp:Long) {
        val root=DocumentFile.fromTreeUri(context,Uri.parse(tree)) ?: throw SecurityException()
        if(!root.canRead())throw SecurityException()
        val folder=root.name?:"文件夹"
        val queue=ArrayDeque<Pair<DocumentFile,Int>>();queue.add(root to 0)
        val batch=ArrayList<MediaEntry>(100)
        while(queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directory,depth)=queue.removeFirst()
            if(depth>24)continue
            for(doc in directory.listFiles()) {
                if(doc.isDirectory){queue.add(doc to depth+1);continue}
                val mime=doc.type.orEmpty()
                if(!mime.startsWith("image/")&&!mime.startsWith("video/"))continue
                batch+=MediaEntry(doc.uri.toString(),doc.name?:"照片",mime,doc.length(),doc.lastModified(),doc.lastModified(),tree,folder,directory.name?:folder,tree,stamp)
                if(batch.size>=100){dao.putMedia(batch.toList());batch.clear()}
            }
        }
        if(batch.isNotEmpty())dao.putMedia(batch)
    }
    suspend fun importUris(uris:List<Uri>):List<String> = withContext(Dispatchers.IO) {
        val list=uris.distinct().mapNotNull { uri ->
            runCatching {
                var name="照片";var size=0L;var modified=0L
                context.contentResolver.query(uri,null,null,null,null)?.use { c -> if(c.moveToFirst()) {
                    fun string(key:String)=c.getColumnIndex(key).takeIf{it>=0}?.let{c.getString(it)}
                    name=string(OpenableColumns.DISPLAY_NAME)?:name
                    size=string(OpenableColumns.SIZE)?.toLongOrNull()?:0
                    modified=string(MediaStore.MediaColumns.DATE_MODIFIED)?.toLongOrNull()?.times(1000)?:0
                } }
                val doc=DocumentFile.fromSingleUri(context,uri)
                if(modified==0L)modified=doc?.lastModified()?:0
                if(size==0L)size=doc?.length()?:0
                MediaEntry(uri.toString(),name,context.contentResolver.getType(uri)?:"image/jpeg",size,modified,modified,"manual","手动选择","手动选择","manual",System.currentTimeMillis())
            }.getOrNull()
        }
        dao.putMedia(list);list.map{it.id}
    }
    fun current(media:MediaEntry):Pair<Long,Long>? = try {
        if(media.source=="media") {
            context.contentResolver.query(Uri.parse(media.id),arrayOf(MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.DATE_MODIFIED),null,null,null)?.use {
                if(it.moveToFirst())it.getLong(0) to it.getLong(1)*1000 else null
            }
        } else DocumentFile.fromSingleUri(context,Uri.parse(media.id))?.takeIf{it.exists()&&it.canRead()}?.let { it.length() to it.lastModified() }
    } catch (_:Exception){null}
    fun accessible(media:MediaEntry)=runCatching { context.contentResolver.openFileDescriptor(Uri.parse(media.id),"r")?.use { true }?:false }.getOrDefault(false)
    /** System delete/trash consent accepts image/video item URIs, not the Files index URI. */
    suspend fun deleteUri(media:MediaEntry):Uri=withContext(Dispatchers.IO) {
        val original=Uri.parse(media.id)
        require(original.authority==MediaStore.AUTHORITY)
        var volume=original.pathSegments.first()
        context.contentResolver.query(original,arrayOf(MediaStore.MediaColumns.VOLUME_NAME),null,null,null)?.use {
            if(it.moveToFirst())volume=it.getString(0)?:volume
        }
        val collection=if(media.video)MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
        ContentUris.withAppendedId(collection,ContentUris.parseId(original))
    }
    fun presence(id:String):Boolean?=try {
        context.contentResolver.query(Uri.parse(id),arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{it.moveToFirst()}
    } catch (_:SecurityException){null} catch (_:java.io.FileNotFoundException){false} catch (_:Exception){null}
}

/** Java methods called by Python; fd ownership stays with the returned handle. */
class SourceHandle(val path:String, val size:Long, private val input:android.os.ParcelFileDescriptor?, private val temporary:File?) : AutoCloseable {
    override fun close(){input?.close();temporary?.delete()}
}
class SourceBridge(private val context:Context) {
    fun open(uri:String,name:String):SourceHandle {
        val descriptor=context.contentResolver.openFileDescriptor(Uri.parse(uri),"r") ?: throw SecurityException("NO_ACCESS")
        val path="/proc/self/fd/${descriptor.fd}"
        try {
            val size=descriptor.statSize
            if(size>=0) {
                FileInputStream(path).use { it.channel.position(0) }
                return SourceHandle(path,size,descriptor,null)
            }
        } catch (_:Exception) { }
        val cache=File(context.cacheDir,"staging").apply{mkdirs()}
        val extension=name.substringAfterLast('.',"bin").filter{it.isLetterOrDigit()}.take(8)
        val file=File(cache,"${UUID.randomUUID()}.$extension")
        try {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> FileOutputStream(file).use { input.copyTo(it,1024*1024) } }
            return SourceHandle(file.absolutePath,file.length(),null,file)
        } catch(error:Exception){runCatching{descriptor.close()};file.delete();throw error}
    }
}
