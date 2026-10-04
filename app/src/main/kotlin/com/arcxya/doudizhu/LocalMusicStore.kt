package com.arcxya.doudizhu

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Called only by AudioEngine's file worker. Imported media never needs a URI grant again. */
internal class LocalMusicStore(private val context: Context) {
    data class Selection(val file: File, val name: String)
    private val directory = File(context.filesDir, "local-music")
    private val selectionFile get() = AtomicFile(File(directory, "selection.json"))

    fun read(): Selection? = try {
        val json = selectionFile.openRead().bufferedReader().use { JSONObject(it.readText()) }
        val filename = json.optString("file")
        val file = File(directory, filename)
        if (filename.isEmpty() || file.parentFile?.canonicalFile != directory.canonicalFile || !file.isFile) null
        else Selection(file, cleanName(json.optString("name")))
    } catch (_: Exception) { null }

    fun stage(uri: Uri): Selection {
        var name = "本地音乐"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex) && cursor.getLong(sizeIndex) > MAX_BYTES)
                    throw IOException("请选择不超过 32 MB 的音频文件")
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            }
        }
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
        return stage(input, name)
    }

    fun stage(input: InputStream, displayName: String): Selection {
        var target: File? = null
        try {
            input.use { source ->
                if (!directory.exists() && !directory.mkdirs()) throw IOException("无法保存音乐文件")
                val file = File.createTempFile("music-", ".audio", directory)
                target = file
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > MAX_BYTES) throw IOException("请选择不超过 32 MB 的音频文件")
                        output.write(buffer, 0, read)
                    }
                    if (count == 0L) throw IOException("所选音频文件是空的")
                    output.fd.sync()
                }
            }
            return Selection(target!!, cleanName(displayName))
        } catch (error: Exception) {
            target?.delete()
            throw error
        }
    }

    /** The already decoded file is immutable; only this small pointer is replaced atomically. */
    fun commit(selection: Selection?) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("无法保存音乐设置")
        val atomic = selectionFile
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            val json = JSONObject().put("file", selection?.file?.name ?: "").put("name", selection?.name ?: "")
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun cleanName(name: String) = name.replace(Regex("[\\p{Cntrl}]"), " ").trim().take(80).ifEmpty { "本地音乐" }

    companion object { const val MAX_BYTES = 32L * 1024 * 1024 }
}
