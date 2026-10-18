package io.gmgc.probe

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object MediaFiles {
    fun digest(context: Context, uri: Uri): String {
        val sha = MessageDigest.getInstance("SHA-256")
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val bytes = ByteArray(1024 * 1024)
            while (true) { val n = input.read(bytes); if (n < 0) break; sha.update(bytes, 0, n) }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    fun prepare(context: Context, uri: Uri): JSONObject {
        val resolver = context.contentResolver
        var name = "media"
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = it.getString(ni) ?: name
                val si = it.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !it.isNull(si)) declaredSize = it.getLong(si)
            }
        }
        name = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]"), "_")
        val extension = name.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri)) ?: "bin"
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "runtime/media").apply { mkdirs() }
        val file = File(directory, "$id.$extension")
        val hash = MessageDigest.getInstance("SHA-256")
        try {
            requireNotNull(resolver.openInputStream(uri)).use { input ->
                file.outputStream().use { output ->
                    val bytes = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        output.write(bytes, 0, n); hash.update(bytes, 0, n)
                    }
                }
            }
            require(file.length() > 0) { "EMPTY_FILE" }
            require(declaredSize == null || declaredSize == file.length()) { "SIZE_CHANGED" }
            return JSONObject().put("id", id).put("path", file.absolutePath).put("name", name)
                .put("size", file.length()).put("sha256", hash.digest().joinToString("") { "%02x".format(it) })
        } catch (exc: Exception) { file.delete(); throw exc }
    }
}
