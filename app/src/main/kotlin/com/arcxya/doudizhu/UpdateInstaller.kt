package com.arcxya.doudizhu

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The release API is discovery only: every APK must also prove its Android identity. */
object UpdateInstaller {
    internal const val RELEASE_CERTIFICATE_SHA256 = UpdatePolicy.OFFICIAL_SIGNER

    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File, release: UpdateRelease) {
        val file = UpdateApkProvider.checkedFile(context, apk)
        if (release.sizeBytes <= 0 || file.length() != release.sizeBytes) {
            throw IOException("更新安装包不完整，请重新下载。")
        }
        if (!release.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw IOException("此版本缺少有效的安装包校验信息。")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (!digest.digest().hex().equals(release.sha256, ignoreCase = true)) {
            throw IOException("更新安装包校验失败，请重新下载。")
        }
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val candidate = manager.getPackageArchiveInfo(file.path, flags)
            ?: throw IOException("无法读取更新安装包，请重新下载。")
        val installed = manager.getPackageInfo(context.packageName, flags)
        verifyIdentity(context.packageName, release.version, installed, candidate)
    }

    internal fun verifyIdentity(packageName: String, expectedVersion: String, installed: PackageInfo, candidate: PackageInfo) {
        if (candidate.packageName != packageName) {
            throw IOException("更新安装包不属于闲来斗地主，已停止安装。")
        }
        val candidateApp = candidate.applicationInfo
            ?: throw IOException("更新安装包缺少应用信息。")
        if (candidateApp.minSdkVersion > Build.VERSION.SDK_INT) {
            throw IOException("新版本需要更高的 Android 系统版本，当前设备暂不支持。")
        }
        if (candidate.versionName != expectedVersion || versionCode(candidate) <= versionCode(installed)) {
            throw IOException("更新安装包版本不匹配，或当前已安装相同或更新版本。")
        }
        if (candidateApp.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            throw IOException("更新安装包不是正式版本，已停止安装。")
        }
        val newSigners = signerDigests(candidate)
        if (newSigners != setOf(RELEASE_CERTIFICATE_SHA256)) {
            throw IOException("更新安装包的正式签名验证失败，已停止安装。")
        }
        if (signerDigests(installed) != newSigners) {
            throw IOException("当前安装的是不同签名的版本，无法直接覆盖更新。请保留当前应用及存档，勿卸载。")
        }
    }

    /** Only called after verification and an explicit tap on the install button. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = UpdateApkProvider.uriForFile(context, apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, UpdateApkProvider.APK_MIME_TYPE)
            clipData = ClipData.newRawUri("闲来斗地主更新", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else info.signatures
        return signatures?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
        }?.toSet().orEmpty()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
