package com.arcxya.doudizhu

import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdateInstallerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun withApk(source: File? = null, block: (File) -> Unit) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, "installer-test-${System.nanoTime()}.apk")
        try {
            if (source == null) file.writeBytes(byteArrayOf(1, 2, 3, 4)) else source.copyTo(file)
            block(file)
        } finally { file.delete() }
    }

    private fun expectRejected(message: String, block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail(message)
    }

    private fun releaseFor(file: File, version: String = "99.0.0"): UpdateRelease {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return UpdateRelease(version, "", "https://github.com/arcxya09/doudizhu/releases/tag/v$version",
            "https://github.com/arcxya09/doudizhu/releases/download/v$version/doudizhu-$version-native.apk",
            sha256, file.length())
    }

    @Test fun updateProviderExposesOnlyReadAccessToTheChosenApk() = withApk { apk ->
        val resolver = context.contentResolver
        val uri = UpdateApkProvider.uriForFile(context, apk)
        val provider = context.packageManager.resolveContentProvider(uri.authority!!, 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(UpdateApkProvider.APK_MIME_TYPE, resolver.getType(uri))
        resolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(apk.length(), it.getLong(0))
            assertEquals(apk.name, it.getString(1))
            assertFalse(it.moveToNext())
        }
        resolver.openInputStream(uri)!!.use { assertArrayEquals(apk.readBytes(), it.readBytes()) }
        listOf("w", "rw", "rwt", "wa").forEach { mode ->
            expectRejected("provider must reject $mode") { resolver.openFileDescriptor(uri, mode)?.close() }
        }
        expectRejected("provider must not delete") { resolver.delete(uri, null, null) }
        expectRejected("provider must not insert") { resolver.insert(uri, ContentValues()) }
        expectRejected("provider must not update") { resolver.update(uri, ContentValues(), null, null) }
        assertTrue(apk.exists())
        val intent = UpdateInstaller.installIntent(context, apk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("content", intent.data!!.scheme)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
    }

    @Test fun updateProviderRejectsTraversalPartialFilesAndUnrelatedPrivateFiles() = withApk { apk ->
        val owner = context
        val provider = UpdateApkProvider().apply {
            attachInfo(owner, ProviderInfo().apply {
                authority = "${owner.packageName}.updates"
                exported = false
                grantUriPermissions = true
            })
        }
        val prefix = "content://${context.packageName}.updates"
        val attacks = listOf(
            "$prefix/apk/../${apk.name}", "$prefix/apk/%2e%2e%2f${apk.name}",
            "$prefix/apk/subdir%2f${apk.name}", "$prefix/apk/subdir/${apk.name}",
            "$prefix/apk/${apk.name}.part", "$prefix/apk/${apk.name}?other=file",
            "$prefix/apk/${apk.name}#other", "$prefix/${apk.name}",
            "content://other.updates/apk/${apk.name}", "file://${apk.path}"
        )
        attacks.forEach { address ->
            expectRejected("provider accepted $address") { provider.openFile(Uri.parse(address), "r").close() }
        }
        val outside = File(context.cacheDir, "outside-installer-test-${System.nanoTime()}.apk")
        try {
            outside.writeText("private data")
            expectRejected("outside APK must not be shared") { UpdateApkProvider.uriForFile(context, outside) }
        } finally { outside.delete() }
        val partial = File(apk.parentFile, "${apk.name}.part")
        try {
            partial.writeText("partial data")
            expectRejected("partial APK must not be shared") { UpdateApkProvider.uriForFile(context, partial) }
        } finally { partial.delete() }
    }

    @Test fun installerRejectsCorruptionOtherPackagesAndAlreadyInstalledVersions() {
        withApk { apk ->
            val release = releaseFor(apk)
            expectRejected("length mismatch must be rejected") {
                UpdateInstaller.verify(context, apk, release.copy(sizeBytes = release.sizeBytes + 1))
            }
            expectRejected("hash mismatch must be rejected") {
                UpdateInstaller.verify(context, apk, release.copy(sha256 = "0".repeat(64)))
            }
            expectRejected("invalid APK must be rejected despite a matching hash") {
                UpdateInstaller.verify(context, apk, release)
            }
        }
        withApk(File(instrumentation.context.applicationInfo.sourceDir)) { apk ->
            val exception = runCatching { UpdateInstaller.verify(context, apk, releaseFor(apk)) }.exceptionOrNull()
            assertNotNull("a real APK for another package must be rejected", exception)
            assertTrue(exception!!.message.orEmpty().contains("不属于"))
        }
        withApk(File(context.applicationInfo.sourceDir)) { apk ->
            @Suppress("DEPRECATION")
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            val exception = runCatching {
                UpdateInstaller.verify(context, apk, releaseFor(apk, installed.versionName!!))
            }.exceptionOrNull()
            assertNotNull("the current APK must not be installed as an upgrade", exception)
            assertTrue(exception!!.message.orEmpty().contains("版本"))
        }
    }

    @Suppress("DEPRECATION")
    @Test fun archiveIdentityRequiresSupportedSdkReleaseModeAndThePinnedSigner() {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val candidate = manager.getPackageArchiveInfo(context.applicationInfo.sourceDir, flags)!!
        candidate.versionName = "99.0.0"
        candidate.versionCode = Int.MAX_VALUE
        val app = candidate.applicationInfo!!
        val minSdk = app.minSdkVersion
        app.minSdkVersion = Build.VERSION.SDK_INT + 1
        fun rejectionContains(message: String) {
            val error = runCatching {
                UpdateInstaller.verifyIdentity(context.packageName, "99.0.0", installed, candidate)
            }.exceptionOrNull()
            assertNotNull("invalid identity must be rejected", error)
            assertTrue("wrong rejection: ${error!!.message}", error.message.orEmpty().contains(message))
        }
        rejectionContains("Android")
        app.minSdkVersion = minSdk
        app.flags = app.flags or ApplicationInfo.FLAG_DEBUGGABLE
        rejectionContains("不是正式版本")
        app.flags = app.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        // Raising metadata cannot turn the independently signed debug APK into an official update.
        rejectionContains("正式签名验证失败")
    }
}
