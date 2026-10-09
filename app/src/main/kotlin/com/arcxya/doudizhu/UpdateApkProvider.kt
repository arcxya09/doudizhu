package com.arcxya.doudizhu

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** A single-purpose, read-only provider. No app data or partial downloads are shared. */
class UpdateApkProvider : ContentProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        check(!info.exported) { "The update provider must not be exported" }
        check(info.grantUriPermissions) { "The update provider requires temporary URI grants" }
        super.attachInfo(context, info)
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        resolve(uri)
        return APK_MIME_TYPE
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val file = resolve(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("更新安装包只允许读取")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("只读")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("只读")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("只读")

    private fun resolve(uri: Uri): File {
        val owner = requireNotNull(context)
        val segments = uri.pathSegments
        if (uri.scheme != "content" || uri.authority != authority(owner) ||
            uri.query != null || uri.fragment != null || segments.size != 2 || segments[0] != "apk" ||
            !isApkName(segments[1])) {
            throw FileNotFoundException("无效的更新安装包地址")
        }
        return checkedFile(owner, File(File(owner.cacheDir, "updates"), segments[1]))
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private fun authority(context: Context) = "${context.packageName}.updates"
        private fun isApkName(name: String) = name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk"))

        internal fun checkedFile(context: Context, file: File): File {
            val root = File(context.cacheDir, "updates").canonicalFile
            val canonical = file.canonicalFile
            if (!isApkName(file.name) || canonical.parentFile != root || !canonical.isFile ||
                canonical.name != file.name) {
                throw FileNotFoundException("更新安装包不存在或路径无效，请重新下载。")
            }
            return canonical
        }

        fun uriForFile(context: Context, file: File): Uri {
            val checked = checkedFile(context, file)
            return Uri.Builder().scheme("content").authority(authority(context))
                .appendPath("apk").appendPath(checked.name).build()
        }
    }
}
