package com.arcxya.doudizhu

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.SSLException

internal interface UpdateSource {
    fun latest(currentVersion: String): UpdateRelease?
    fun download(release: UpdateRelease, directory: File, onProgress: (Int) -> Unit): File
}

/** Blocking I/O. Call on a worker; interruption cancels between reads and removes partial APKs. */
internal class UpdateRepository(
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : UpdateSource {
    override fun latest(currentVersion: String): UpdateRelease? = translatedNetworkErrors {
        val deadline = System.nanoTime() + 30_000_000_000L
        val connection = connect(UpdatePolicy.API_URL, deadline, json = true)
        val response = try {
            val declared = connection.contentLengthLong
            if (declared > MAX_METADATA_BYTES) throw UpdateFailure("更新信息过大，请稍后重试")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkActive(deadline)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_METADATA_BYTES) throw UpdateFailure("更新信息过大，请稍后重试")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
        try {
            val release = JSONObject(response)
            val array = release.getJSONArray("assets")
            val assets = (0 until array.length()).map { index ->
                val asset = array.getJSONObject(index)
                UpdateAsset(
                    asset.getString("name"), asset.getString("browser_download_url"), asset.getLong("size"),
                    if (asset.isNull("digest")) null else asset.getString("digest"),
                    asset.optString("state") == "uploaded"
                )
            }
            UpdatePolicy.selectRelease(
                currentVersion, release.getString("tag_name"), release.getBoolean("draft"),
                release.getBoolean("prerelease"), release.optString("body"), release.getString("html_url"), assets
            )
        } catch (error: IllegalArgumentException) {
            throw UpdateFailure(error.message ?: "更新信息无效", error)
        } catch (error: org.json.JSONException) {
            throw UpdateFailure("无法读取更新信息，请稍后重试", error)
        }
    }

    override fun download(release: UpdateRelease, directory: File, onProgress: (Int) -> Unit): File {
        if (!UpdatePolicy.validateDownload(release)) throw UpdateFailure("安装包信息无效，请重新检查更新")
        checkActive()
        if ((!directory.isDirectory && !directory.mkdirs()) || !directory.isDirectory) {
            throw UpdateFailure("无法创建更新目录，请检查可用空间")
        }
        val target = File(directory, UpdatePolicy.apkName(release.version))
        val deadline = System.nanoTime() + 180_000_000_000L
        if (target.isFile && target.length() == release.sizeBytes && sha256(target, deadline) == release.sha256.lowercase()) {
            onProgress(100)
            return target
        }
        val partial = try { File.createTempFile("download-", ".part", directory) } catch (error: IOException) {
            throw UpdateFailure("无法保存安装包，请检查可用空间", error)
        }
        try {
            translatedNetworkErrors {
                val connection = connect(release.downloadUrl, deadline, json = false)
                try {
                    val declared = connection.contentLengthLong
                    if (declared >= 0 && declared != release.sizeBytes) throw UpdateFailure("安装包大小与发布信息不一致")
                    connection.inputStream.use { input ->
                        FileOutputStream(partial).use { output ->
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(32 * 1024)
                            var received = 0L
                            var lastProgress = -1
                            onProgress(0)
                            while (true) {
                                checkActive(deadline)
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > release.sizeBytes || received > UpdatePolicy.MAX_APK_BYTES) {
                                    throw UpdateFailure("安装包大小与发布信息不一致")
                                }
                                try { output.write(buffer, 0, count) } catch (error: IOException) {
                                    throw UpdateFailure("无法保存安装包，请检查可用空间", error)
                                }
                                digest.update(buffer, 0, count)
                                val progress = (received * 100 / release.sizeBytes).toInt().coerceAtMost(99)
                                if (progress != lastProgress) { onProgress(progress); lastProgress = progress }
                            }
                            if (received != release.sizeBytes) throw UpdateFailure("安装包下载不完整，请重新下载")
                            if (hex(digest.digest()) != release.sha256.lowercase()) throw UpdateFailure("安装包校验失败，请重新下载")
                            try { output.fd.sync() } catch (error: IOException) {
                                throw UpdateFailure("无法保存安装包，请检查可用空间", error)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            checkActive(deadline)
            try {
                // Both files live in the same private directory. No partial download is offered to the installer.
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: IOException) {
                throw UpdateFailure("无法保存安装包，请检查可用空间", error)
            }
            onProgress(100)
            return target
        } finally {
            partial.delete()
        }
    }

    private fun connect(initialUrl: String, deadline: Long, json: Boolean): HttpURLConnection {
        var address = initialUrl
        repeat(6) { redirect ->
            checkActive(deadline)
            if (!UpdatePolicy.trustedConnection(address)) throw UpdateFailure("更新服务返回了不受信任的下载地址")
            val connection = connectionFactory(URL(address))
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "Doudizhu-Android-Updater")
                connection.setRequestProperty("Accept", if (json) "application/vnd.github+json" else "application/octet-stream")
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (json) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                val status = connection.responseCode
                checkActive(deadline)
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw UpdateFailure("更新服务的跳转地址无效")
                    if (redirect >= 5) throw UpdateFailure("更新服务跳转过多，请稍后重试")
                    address = URL(URL(address), location).toExternalForm()
                    connection.disconnect()
                } else {
                    when {
                        status == HttpURLConnection.HTTP_OK -> return connection
                        status == 429 || (status == 403 && connection.getHeaderField("X-RateLimit-Remaining") == "0") ->
                            throw UpdateFailure("检查更新过于频繁，请稍后再试")
                        status == 404 -> throw UpdateFailure("暂时找不到正式安装包，请稍后再试")
                        status in 500..599 -> throw UpdateFailure("GitHub 更新服务暂不可用，请稍后再试")
                        else -> throw UpdateFailure("无法访问 GitHub 更新服务（$status），请稍后再试")
                    }
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw UpdateFailure("更新服务跳转过多，请稍后重试")
    }

    private fun sha256(file: File, deadline: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkActive(deadline)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun checkActive(deadline: Long = Long.MAX_VALUE) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("更新已取消")
        if (System.nanoTime() > deadline) throw UpdateFailure("更新连接超时，请稍后重试")
    }

    private fun <T> translatedNetworkErrors(block: () -> T): T = try {
        block()
    } catch (error: IOException) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("更新已取消")
        when (error) {
            is UpdateFailure -> throw error
            is SocketTimeoutException -> throw UpdateFailure("更新连接超时，请稍后重试", error)
            is UnknownHostException -> throw UpdateFailure("无法连接 GitHub，请检查网络后重试", error)
            is SSLException -> throw UpdateFailure("无法安全连接更新服务，请检查网络与设备时间", error)
            is InterruptedIOException -> throw error
            else -> throw UpdateFailure("网络连接中断，请稍后重试", error)
        }
    }

    private class UpdateFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

    private companion object {
        const val MAX_METADATA_BYTES = 512 * 1024
    }
}
