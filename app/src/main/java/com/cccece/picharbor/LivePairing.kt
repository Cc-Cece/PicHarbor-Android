package com.cccece.picharbor

import android.content.Context
import android.net.Uri

/**
 * Two-part Live Photos only: one still and one video sharing a filename stem in one folder.
 * A native motion photo stays a single file and is not paired.
 */
object LivePairing {
    private val images=setOf("jpg","jpeg","heic","heif")
    private val videos=setOf("mov","mp4")
    private val marker="MotionPhoto".toByteArray(Charsets.US_ASCII)

    data class StemFile(val id:String,val name:String,val folderKey:String)
    data class Match(val photoId:String,val videoId:String)

    fun match(files:List<StemFile>):List<Match> {
        val found=ArrayList<Match>()
        for(group in files.groupBy{it.folderKey+"\u0000"+stem(it.name).lowercase()}.values) {
            val photos=group.filter{ext(it.name) in images}
            val movies=group.filter{ext(it.name) in videos}
            if(photos.size!=1||movies.size!=1)continue
            found+=Match(photos[0].id,movies[0].id)
        }
        return found
    }

    fun stem(name:String):String {
        val base=name.substringAfterLast('/').substringAfterLast('\\')
        val dot=base.lastIndexOf('.')
        return if(dot<=0)base else base.substring(0,dot)
    }

    fun ext(name:String):String {
        val base=name.substringAfterLast('/').substringAfterLast('\\')
        val dot=base.lastIndexOf('.')
        if(dot<=0||dot==base.lastIndex)return ""
        return base.substring(dot+1).lowercase()
    }

    fun containsMotion(bytes:ByteArray):Boolean {
        if(marker.size>bytes.size)return false
        for(index in 0..bytes.size-marker.size) {
            var same=true
            for(offset in marker.indices)if(bytes[index+offset]!=marker[offset]){same=false;break}
            if(same)return true
        }
        return false
    }

    /** True when the still already embeds a motion photo. Null when the file cannot be read. */
    fun isMotionContainer(context:Context,uri:String):Boolean?=try {
        val descriptor=context.contentResolver.openFileDescriptor(Uri.parse(uri),"r")?:return null
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val size=descriptor.statSize
            if(size<0)return null
            val headLength=minOf(size,512L*1024).toInt()
            val head=ByteArray(headLength)
            if(readFully(input,head)!=headLength)return null
            if(containsMotion(head))return true
            val tailLength=minOf(size,64L*1024).toInt()
            if(size<=headLength) {
                val start=(size-tailLength).toInt().coerceAtLeast(0)
                return containsMotion(head.copyOfRange(start,(start+tailLength).coerceAtMost(head.size)))
            }
            input.channel.position(size-tailLength)
            val tail=ByteArray(tailLength)
            if(readFully(input,tail)!=tailLength)return null
            containsMotion(tail)
        }
    } catch(_:Exception){null}

    private fun readFully(input:java.io.InputStream,buffer:ByteArray):Int {
        var filled=0
        while(filled<buffer.size) {
            val count=input.read(buffer,filled,buffer.size-filled)
            if(count<0)return filled
            filled+=count
        }
        return filled
    }
}
