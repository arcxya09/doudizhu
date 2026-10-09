package com.arcxya.doudizhu

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

class UpdatePolicyTest {
    private val digest = "a3".repeat(32)
    private val notes = "界面优化\n\nDDZ-OFFICIAL-SIGNER-SHA256: ${UpdatePolicy.OFFICIAL_SIGNER}\n\nAPK SHA256: $digest\n"
    private fun asset(version: String = "3.8.0") = UpdateAsset(
        UpdatePolicy.apkName(version), UpdatePolicy.downloadUrl(version), 8_000_000, "sha256:$digest"
    )
    private fun select(
        tag: String = "v3.8.0", current: String = "3.7.0", draft: Boolean = false, prerelease: Boolean = false,
        body: String = notes, page: String = UpdatePolicy.pageUrl("3.8.0"), assets: List<UpdateAsset> = listOf(asset())
    ) = UpdatePolicy.selectRelease(current, tag, draft, prerelease, body, page, assets)

    @Test fun numericVersionsHandleDoubleDigitsAndRejectAmbiguousFormats() {
        assertTrue(UpdatePolicy.isNewer("3.10.0", "3.9.99"))
        assertTrue(UpdatePolicy.isNewer("4.0.0", "3.99.99"))
        assertTrue(UpdatePolicy.isNewer("3.8.1", "3.8.0"))
        assertFalse(UpdatePolicy.isNewer("3.8.0", "3.8.0"))
        assertFalse(UpdatePolicy.isNewer("3.8.0", "4.0.0"))
        for (version in listOf("3.8", "3.8.0-rc1", "3.8.0+build", "v3.8.0", "03.8.0", "3.8.0 ", "3.-8.0", "99999999999.0.0")) {
            assertNull("$version must not order as a stable version", UpdatePolicy.versionNumbers(version))
        }
    }

    @Test fun stableReleaseRequiresExactOfficialAssetAndMatchingDigest() {
        val result = select()!!
        assertEquals("3.8.0", result.version)
        assertEquals("界面优化", result.notes)
        assertEquals(digest, result.sha256)
        assertTrue(UpdatePolicy.validateDownload(result))
        assertEquals(result, select(assets = listOf(asset().copy(digest = null))))
        assertEquals(result, select(assets = listOf(asset().copy(digest = "sha256:${digest.uppercase()}"))))
        assertNull(select(draft = true))
        assertNull(select(prerelease = true))
        assertNull(select(current = "3.8.0"))
        assertNull(select(current = "4.0.0"))
    }

    @Test fun forgedOrIncompleteReleasesFailClosed() {
        val invalid = listOf<() -> UpdateRelease?>(
            { select(tag = "v3.8.0-rc1") },
            { select(page = "https://github.com/other/doudizhu/releases/tag/v3.8.0") },
            { select(body = notes.replace(UpdatePolicy.OFFICIAL_SIGNER, digest)) },
            { select(body = notes.replace("DDZ-OFFICIAL-SIGNER-SHA256:", "SIGNER:")) },
            { select(body = "$notes\nDDZ-OFFICIAL-SIGNER-SHA256: ${UpdatePolicy.OFFICIAL_SIGNER}") },
            { select(body = notes.replace("APK SHA256:", "SHA256:")) },
            { select(body = "$notes\nAPK SHA256: $digest") },
            { select(assets = emptyList()) },
            { select(assets = listOf(asset(), asset())) },
            { select(assets = listOf(asset().copy(uploaded = false))) },
            { select(assets = listOf(asset().copy(name = "app-release.apk"))) },
            { select(assets = listOf(asset().copy(downloadUrl = "https://github.com/other/doudizhu/releases/download/v3.8.0/doudizhu-3.8.0-native.apk"))) },
            { select(assets = listOf(asset().copy(downloadUrl = asset().downloadUrl + "?redirect=bad"))) },
            { select(assets = listOf(asset().copy(sizeBytes = 0))) },
            { select(assets = listOf(asset().copy(sizeBytes = UpdatePolicy.MAX_APK_BYTES + 1))) },
            { select(assets = listOf(asset().copy(digest = "sha256:${"b4".repeat(32)}"))) },
            { select(assets = listOf(asset().copy(digest = "md5:$digest"))) }
        )
        invalid.forEachIndexed { index, candidate ->
            assertThrows("invalid release $index", IllegalArgumentException::class.java) { candidate() }
        }
    }

    @Test fun redirectsAllowOnlyHttpsGithubHostsWithoutCredentialsOrNonstandardPorts() {
        for (url in listOf(
            UpdatePolicy.API_URL, asset().downloadUrl,
            "https://release-assets.githubusercontent.com/github-production-release-asset/a?signature=abc",
            "https://objects.githubusercontent.com/asset", "https://github.com:443/asset"
        )) assertTrue(url, UpdatePolicy.trustedConnection(url))
        for (url in listOf(
            "http://github.com/asset", "https://github.com.evil.example/asset", "https://evilgithub.com/asset",
            "https://github.com@evil.example/asset", "https://user:password@github.com/asset", "https://github.com:8443/asset",
            "https://github.com/asset#fragment", "file:///tmp/update.apk", "https://127.0.0.1/asset", "%%%"
        )) assertFalse(url, UpdatePolicy.trustedConnection(url))
    }

    @Test fun directDownloadCannotBypassReleaseValidation() {
        val release = select()!!
        for (invalid in listOf(
            release.copy(version = "../../escape"), release.copy(pageUrl = "https://example.org"),
            release.copy(downloadUrl = "https://example.org/app.apk"), release.copy(sizeBytes = Long.MAX_VALUE),
            release.copy(sha256 = ""), release.copy(sha256 = "g".repeat(64))
        )) assertFalse(UpdatePolicy.validateDownload(invalid))
    }

    private class Connection(
        url: URL,
        private val bytes: ByteArray,
        private val status: Int = 200,
        private val size: Long = bytes.size.toLong(),
        private val headers: Map<String, String> = emptyMap()
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = size
        override fun getHeaderField(name: String) = headers[name]
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }

    private fun releaseFor(bytes: ByteArray) = select()!!.copy(
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
        sizeBytes = bytes.size.toLong()
    )

    @Test fun downloadPublishesOnlyVerifiedCompleteFileAndReusesVerifiedCache() {
        val directory = Files.createTempDirectory("ddz-update-test").toFile()
        try {
            val bytes = ByteArray(70_000) { (it % 251).toByte() }
            val release = releaseFor(bytes)
            var calls = 0
            lateinit var connection: Connection
            val repository = UpdateRepository { url -> Connection(url, bytes).also { calls++; connection = it } }
            val progress = mutableListOf<Int>()
            val downloaded = repository.download(release, directory) { progress.add(it) }
            assertArrayEquals(bytes, downloaded.readBytes())
            assertEquals(listOf(UpdatePolicy.apkName(release.version)), directory.list()!!.toList())
            assertEquals(0, progress.first())
            assertEquals(100, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
            assertTrue(connection.disconnected)
            assertEquals(downloaded, repository.download(release, directory) {})
            assertEquals("a verified cache does not need another network request", 1, calls)
        } finally { directory.deleteRecursively() }
    }

    @Test fun truncatedOversizedAndCorruptDownloadsNeverBecomeInstallableFiles() {
        val bytes = ByteArray(100) { it.toByte() }
        val release = releaseFor(bytes)
        for ((label, returned) in listOf(
            "truncated" to bytes.copyOf(99), "oversized" to bytes.copyOf(101), "corrupt" to ByteArray(100) { 1 }
        )) {
            val directory = Files.createTempDirectory("ddz-update-$label").toFile()
            try {
                val repository = UpdateRepository { url -> Connection(url, returned, size = -1) }
                assertThrows(label, IOException::class.java) { repository.download(release, directory) {} }
                assertTrue("$label must remove the partial file", directory.list()!!.isEmpty())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun untrustedRedirectAndCancellationCleanPartialDownloads() {
        val bytes = ByteArray(100) { it.toByte() }
        val release = releaseFor(bytes)
        val directory = Files.createTempDirectory("ddz-update-cancel").toFile()
        try {
            var requests = 0
            val redirected = UpdateRepository { url ->
                requests++
                Connection(url, byteArrayOf(), status = 302, headers = mapOf("Location" to "https://example.org/malicious.apk"))
            }
            assertThrows(IOException::class.java) { redirected.download(release, directory) {} }
            assertEquals("an untrusted redirect must not be requested", 1, requests)
            assertTrue(directory.list()!!.isEmpty())
            val cancelled = UpdateRepository { url -> Connection(url, bytes) }
            assertThrows(InterruptedIOException::class.java) {
                cancelled.download(release, directory) { if (it == 0) Thread.currentThread().interrupt() }
            }
            assertTrue(directory.list()!!.isEmpty())
        } finally {
            Thread.interrupted()
            directory.deleteRecursively()
        }
    }
}
