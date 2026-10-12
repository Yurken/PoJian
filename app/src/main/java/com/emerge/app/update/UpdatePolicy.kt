package com.emerge.app.update

import java.net.URI

internal data class UpdateRelease(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String)

internal data class ApkIdentity(val packageName: String, val versionCode: Long, val versionName: String?,
    val minSdk: Int, val signers: Set<String>)

internal class UpdateException(message: String) : Exception(message)

internal object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val RETRY_INTERVAL_MS = 15 * 60 * 1000L
    const val MAX_MANIFEST_BYTES = 64 * 1024L
    const val MAX_APK_BYTES = 512 * 1024 * 1024L

    fun httpsUrl(value: String): String {
        val url = URI(value.trim())
        require(url.scheme.equals("https", ignoreCase = true) && !url.host.isNullOrBlank() &&
            url.rawUserInfo == null && url.rawFragment == null && (url.port == -1 || url.port in 1..65535))
        return url.toASCIIString()
    }

    fun release(versionCode: Long, versionName: String, apkUrl: String, sha256: String): UpdateRelease {
        require(versionCode > 0)
        require(versionName.isNotBlank() && versionName.length <= 64 && versionName.none { it.isISOControl() })
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
        return UpdateRelease(versionCode, versionName, httpsUrl(apkUrl), sha256.lowercase())
    }

    // Clock rollback must not disable checks indefinitely.
    fun checkDue(now: Long, lastAttempt: Long, lastSuccess: Long): Boolean =
        elapsed(now, lastAttempt, RETRY_INTERVAL_MS) && elapsed(now, lastSuccess, CHECK_INTERVAL_MS)

    private fun elapsed(now: Long, then: Long, interval: Long) = then <= 0 || now < then || now - then >= interval

    fun verifyApk(installed: ApkIdentity, archive: ApkIdentity, release: UpdateRelease, deviceSdk: Int) {
        if (archive.packageName != installed.packageName || archive.versionCode != release.versionCode ||
            archive.versionName != release.versionName || archive.versionCode <= installed.versionCode) {
            throw UpdateException("更新包与当前应用或目标版本不匹配。")
        }
        if (archive.minSdk > deviceSdk) throw UpdateException("当前系统不支持此更新。")
        // This distribution uses a fixed signing key. Do not offer uninstall as a workaround.
        if (installed.signers.isEmpty() || archive.signers != installed.signers) {
            throw UpdateException("更新包签名不兼容，无法覆盖安装。")
        }
    }
}
