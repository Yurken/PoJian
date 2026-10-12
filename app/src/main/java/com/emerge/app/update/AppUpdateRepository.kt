package com.emerge.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal class AppUpdateRepository(private val context: Context, private val transport: UpdateTransport = HttpsUpdateTransport()) {
    suspend fun check(endpoint: String): UpdateRelease? {
        if (endpoint.isBlank()) return null
        return withTimeout(30_000) {
            val data = ByteArrayOutputStream()
            transport.read(UpdatePolicy.httpsUrl(endpoint), UpdatePolicy.MAX_MANIFEST_BYTES) { bytes, count, _ ->
                data.write(bytes, 0, count)
            }
            decodeManifest(data.toString("UTF-8"), installed().versionCode)
        }
    }

    suspend fun download(release: UpdateRelease, onProgress: (Int?) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "app-updates").apply { check(isDirectory || mkdirs()) }
        directory.listFiles()?.filter { it.name.startsWith("update-") &&
            System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val part = File.createTempFile("update-", ".part", directory)
        val apk = File(directory, part.name.removeSuffix(".part") + ".apk")
        var completed = false
        try {
            withTimeout(15 * 60_000L) {
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var reported = -1
                part.outputStream().use { output ->
                    transport.read(release.apkUrl, UpdatePolicy.MAX_APK_BYTES) { buffer, count, length ->
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        val progress = if (length > 0) (received * 100 / length).toInt().coerceIn(0, 100) else null
                        if (progress != null && progress != reported) { reported = progress; onProgress(progress) }
                    }
                }
                if (digest.digest().hex() != release.sha256) throw UpdateException("更新包校验失败，请重试。")
                verifyPackage(part, release)
                currentCoroutineContext().ensureActive()
                check(part.renameTo(apk)) { "Cannot finish update file" }
                completed = true
                apk
            }
        } finally {
            part.delete()
            if (!completed) apk.delete()
        }
    }

    suspend fun verifyForInstall(file: File, release: UpdateRelease) = withContext(Dispatchers.IO) {
        if (!file.isFile) throw UpdateException("更新包已失效，请重新下载。")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().hex() != release.sha256) throw UpdateException("更新包校验失败，请重新下载。")
        verifyPackage(file, release)
    }

    @Suppress("DEPRECATION")
    private fun installed() = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).identity()

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, release: UpdateRelease) {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateException("无法读取更新包，请重新下载。")
        UpdatePolicy.verifyApk(installed(), info.identity(), release, Build.VERSION.SDK_INT)
    }

    private fun PackageInfo.identity() = ApkIdentity(packageName, longVersionCode, versionName,
        applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
        signingInfo?.apkContentsSigners.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet())
}

internal fun decodeManifest(json: String, installedCode: Long): UpdateRelease? {
    val value = JSONObject(json)
    val code = value.get("versionCode").let { require(it is Number); it.toString().toLong() }
    require(code > 0)
    if (code <= installedCode) return null
    fun string(key: String) = value.get(key).let { require(it is String); it }
    return UpdatePolicy.release(code, string("versionName"), string("apkUrl"), string("sha256"))
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
