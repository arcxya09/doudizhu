package com.arcxya.doudizhu

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Uses Android's real JSON parser, while every HTTP response is supplied locally. */
@RunWith(AndroidJUnit4::class)
class UpdateMetadataTest {
    private val digest = "a3".repeat(32)
    private val version = "3.8.0"
    private fun fixture() = """
        {
          "url": "https://api.github.com/repos/arcxya09/doudizhu/releases/123456",
          "html_url": "https://github.com/arcxya09/doudizhu/releases/tag/v3.8.0",
          "id": 123456,
          "tag_name": "v3.8.0",
          "target_commitish": "main",
          "name": "闲来斗地主 v3.8.0 · 正式版",
          "draft": false,
          "prerelease": false,
          "created_at": "2026-10-09T00:00:00Z",
          "published_at": "2026-10-09T00:01:00Z",
          "assets": [
            {
              "id": 987654,
              "name": "doudizhu-3.8.0-native.apk",
              "state": "uploaded",
              "content_type": "application/vnd.android.package-archive",
              "size": 8000000,
              "digest": "sha256:$digest",
              "download_count": 1,
              "browser_download_url": "https://github.com/arcxya09/doudizhu/releases/download/v3.8.0/doudizhu-3.8.0-native.apk"
            },
            {
              "id": 987655,
              "name": "upgrade-passed.txt",
              "state": "uploaded",
              "content_type": "text/plain",
              "size": 512,
              "digest": null,
              "browser_download_url": "https://github.com/arcxya09/doudizhu/releases/download/v3.8.0/upgrade-passed.txt"
            }
          ],
          "body": "改进更新体验\n\nDDZ-OFFICIAL-SIGNER-SHA256: ${UpdatePolicy.OFFICIAL_SIGNER}\n\nAPK SHA256: $digest\n"
        }
    """.trimIndent()

    private class Connection(
        url: URL,
        response: String,
        private val status: Int = 200,
        private val headers: Map<String, String> = emptyMap(),
        private val declaredSize: Long? = null
    ) : HttpURLConnection(url) {
        private val bytes = response.toByteArray(Charsets.UTF_8)
        var disconnected = false
        var streamClosed = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = declaredSize ?: bytes.size.toLong()
        override fun getHeaderField(name: String) = headers[name]
        override fun getInputStream() = object : ByteArrayInputStream(bytes) {
            override fun close() { streamClosed = true; super.close() }
        }
    }

    private fun repository(response: String = fixture()) = UpdateRepository { url ->
        assertEquals("metadata must use the fixed repository endpoint", UpdatePolicy.API_URL, url.toString())
        Connection(url, response)
    }

    @Test fun officialStableReleaseUsesRealAndroidJsonAndClosesTheResponse() {
        lateinit var connection: Connection
        val repository = UpdateRepository { url ->
            assertEquals(UpdatePolicy.API_URL, url.toString())
            Connection(url, fixture()).also { connection = it }
        }
        val result = repository.latest("3.7.0")!!
        assertEquals(version, result.version)
        assertEquals("改进更新体验", result.notes)
        assertEquals(UpdatePolicy.pageUrl(version), result.pageUrl)
        assertEquals(UpdatePolicy.downloadUrl(version), result.downloadUrl)
        assertEquals(digest, result.sha256)
        assertEquals(8_000_000L, result.sizeBytes)
        assertTrue(connection.disconnected)
        assertTrue(connection.streamClosed)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
    }

    @Test fun equalOlderDraftAndPrereleaseVersionsAreNotOffered() {
        assertNull(repository().latest("3.8.0"))
        assertNull(repository().latest("4.0.0"))
        assertNull(repository(JSONObject(fixture()).put("draft", true).toString()).latest("3.7.0"))
        assertNull(repository(JSONObject(fixture()).put("prerelease", true).toString()).latest("3.7.0"))
    }

    @Test fun legacyNullAssetDigestUsesTheMatchingPublishedChecksum() {
        val response = JSONObject(fixture())
        response.getJSONArray("assets").getJSONObject(0).put("digest", JSONObject.NULL)
        assertEquals(digest, repository(response.toString()).latest("3.7.0")!!.sha256)
    }

    @Test fun missingApkMalformedJsonAndContradictoryDigestAreExplicitErrors() {
        val missingApk = JSONObject(fixture()).put("assets", JSONArray()).toString()
        val missing = assertThrows(IOException::class.java) { repository(missingApk).latest("3.7.0") }
        assertTrue(missing.message.orEmpty().contains("正式安装包尚未就绪"))
        val malformed = assertThrows(IOException::class.java) { repository("{\"tag_name\":").latest("3.7.0") }
        assertTrue(malformed.message.orEmpty().contains("无法读取更新信息"))
        val response = JSONObject(fixture())
        response.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:${"b4".repeat(32)}")
        val mismatch = assertThrows(IOException::class.java) { repository(response.toString()).latest("3.7.0") }
        assertTrue(mismatch.message.orEmpty().contains("校验信息不一致"))
    }

    @Test fun rateLimitsReturnChineseRetryMessageAndCloseConnections() {
        for (status in listOf(403, 429)) {
            lateinit var connection: Connection
            val repository = UpdateRepository { url ->
                Connection(url, "{\"message\":\"API rate limit exceeded\"}", status, mapOf("X-RateLimit-Remaining" to "0"))
                    .also { connection = it }
            }
            val error = assertThrows(IOException::class.java) { repository.latest("3.7.0") }
            assertEquals("检查更新过于频繁，请稍后再试", error.message)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun metadataSizeIsBoundedWithOrWithoutContentLength() {
        for (declared in listOf(600_000L, -1L)) {
            lateinit var connection: Connection
            val repository = UpdateRepository { url ->
                Connection(url, "x".repeat(600_000), declaredSize = declared).also { connection = it }
            }
            val error = assertThrows(IOException::class.java) { repository.latest("3.7.0") }
            assertTrue(error.message.orEmpty().contains("更新信息过大"))
            assertTrue(connection.disconnected)
            if (declared < 0) assertTrue(connection.streamClosed)
        }
    }
}
