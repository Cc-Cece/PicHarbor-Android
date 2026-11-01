package com.cccece.picharbor

import android.os.Environment
import java.io.File

/** Copy of the archive code under DCIM/PicHarbor. The phone never mints a code. */
object LinkArchiveFile {
    data class Report(val value:String?,val directoryReady:Boolean,val unreadable:Boolean)

    fun report():Report {
        val dcim=try{Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}catch(_:Exception){null}
            ?:return Report(null,false,true)
        if(!dcim.isDirectory||!dcim.canRead())return Report(null,false,true)
        val names=dcim.list()?:return Report(null,false,true)
        if(names.none{it=="PicHarbor"})return Report(null,false,false)
        val dir=File(dcim,"PicHarbor")
        if(!dir.isDirectory||!dir.canRead())return Report(null,false,true)
        val files=dir.list()?:return Report(null,true,true)
        if(files.none{it==".picharbor-serial"})return Report(null,true,false)
        return try {
            val text=File(dir,".picharbor-serial").readText(Charsets.UTF_8).trim()
            if(text.isEmpty())Report(null,true,false) else Report(text,true,false)
        } catch(_:Exception) {
            Report(null,true,true)
        }
    }

    fun write(value:String):Boolean {
        val trimmed=value.trim()
        if(!LinkProtocol.usableArchive(trimmed))return false
        return try {
            val dcim=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?:return false
            val dir=File(dcim,"PicHarbor")
            if(!dir.exists()&&!dir.mkdirs())return false
            File(dir,".picharbor-serial").writeText(trimmed,Charsets.UTF_8)
            true
        } catch(_:Exception) {
            false
        }
    }
}
