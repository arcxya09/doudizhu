package com.arcxya.doudizhu

import java.net.URI

data class UpdateRelease(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long
)

internal data class UpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val digest: String?,
    val uploaded: Boolean = true
)

/** Pure release policy: neither a tag nor a GitHub asset name alone establishes an update. */
internal object UpdatePolicy {
    const val REPOSITORY = "arcxya09/doudizhu"
    const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val OFFICIAL_SIGNER = "22764e23837be56b301b9c52d4738695d147a254e404a0bfdc3f2d299e5aa31a"
    const val MAX_APK_BYTES = 64L * 1024 * 1024
    private val versionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
    private val digestPattern = Regex("[0-9a-fA-F]{64}")
    private val signerLine = Regex("(?m)^DDZ-OFFICIAL-SIGNER-SHA256: *([0-9a-fA-F]{64})[ \\t]*\\r?$")
    private val digestLine = Regex("(?m)^APK SHA256: *([0-9a-fA-F]{64})[ \\t]*\\r?$")
    private val trustedHosts = setOf(
        "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"
    )

    fun versionNumbers(version: String): List<Int>? {
        if (!versionPattern.matches(version)) return null
        return version.split('.').map { it.toIntOrNull() ?: return null }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val next = versionNumbers(candidate) ?: return false
        val previous = versionNumbers(current) ?: return false
        for (index in next.indices) if (next[index] != previous[index]) return next[index] > previous[index]
        return false
    }

    fun apkName(version: String) = "doudizhu-$version-native.apk"
    fun pageUrl(version: String) = "https://github.com/$REPOSITORY/releases/tag/v$version"
    fun downloadUrl(version: String) = "https://github.com/$REPOSITORY/releases/download/v$version/${apkName(version)}"

    fun trustedConnection(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in trustedHosts && uri.rawUserInfo == null &&
            uri.port in listOf(-1, 443) && uri.rawFragment == null
    }.getOrDefault(false)

    fun validateDownload(release: UpdateRelease): Boolean =
        versionNumbers(release.version) != null && release.pageUrl == pageUrl(release.version) &&
            release.downloadUrl == downloadUrl(release.version) && digestPattern.matches(release.sha256) &&
            release.sizeBytes in 1..MAX_APK_BYTES

    /** An invalid purported newer release is an error, rather than a misleading "already latest". */
    fun selectRelease(
        currentVersion: String,
        tag: String,
        draft: Boolean,
        prerelease: Boolean,
        body: String,
        page: String,
        assets: List<UpdateAsset>
    ): UpdateRelease? {
        require(versionNumbers(currentVersion) != null) { "无法识别当前版本号" }
        if (draft || prerelease) return null
        require(tag.startsWith('v') && versionNumbers(tag.drop(1)) != null) { "更新版本号格式无效" }
        val version = tag.drop(1)
        if (!isNewer(version, currentVersion)) return null
        require(page == pageUrl(version)) { "更新页面不属于官方仓库" }
        val signers = signerLine.findAll(body).map { it.groupValues[1].lowercase() }.toList()
        require(signers == listOf(OFFICIAL_SIGNER)) { "更新缺少正式签名声明" }
        val matching = assets.filter { it.name == apkName(version) }
        require(matching.size == 1 && matching.single().uploaded) { "正式安装包尚未就绪，请稍后再试" }
        val asset = matching.single()
        require(asset.downloadUrl == downloadUrl(version)) { "安装包下载地址不属于官方仓库" }
        require(asset.sizeBytes in 1..MAX_APK_BYTES) { "安装包大小无效" }
        val notesDigests = digestLine.findAll(body).map { it.groupValues[1].lowercase() }.toList()
        require(notesDigests.size == 1) { "更新缺少安装包校验值" }
        val digest = notesDigests.single()
        if (!asset.digest.isNullOrEmpty()) {
            require(asset.digest.startsWith("sha256:") && asset.digest.drop(7).lowercase() == digest) {
                "安装包校验信息不一致"
            }
        }
        val notes = body.lineSequence().filterNot {
            it.startsWith("DDZ-OFFICIAL-SIGNER-SHA256:") || it.startsWith("APK SHA256:")
        }.joinToString("\n").trim().take(16_000)
        return UpdateRelease(version, notes, page, asset.downloadUrl, digest, asset.sizeBytes)
    }
}
