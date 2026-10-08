package com.emerge.app.audio

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** The model is downloaded only by the explicit UI action, never by capture/import. */
internal class SpeechModelStore(context: Context) {
    private val root = File(context.filesDir, "speech-models")
    val directory = File(root, MODEL_ID)
    private val connection = AtomicReference<HttpURLConnection?>()
    val isInstalled: Boolean get() = File(directory, ".verified").isFile && FILES.all { File(directory, it.name).length() == it.size }

    fun abortDownload() { connection.getAndSet(null)?.disconnect() }

    suspend fun verifyInstalled() {
        check(isInstalled) { "请先下载中文语音模型" }
        for (spec in FILES) {
            currentCoroutineContext().ensureActive()
            if (hash(File(directory, spec.name)) != spec.sha256) {
                File(directory, ".verified").delete()
                error("语音模型校验失败，请重新下载")
            }
        }
    }

    suspend fun download(onProgress: (Float) -> Unit) {
        root.mkdirs()
        val staging = File(root, "$MODEL_ID.part")
        staging.deleteRecursively()
        staging.mkdirs()
        val total = FILES.sumOf { it.size }
        check(StatFs(root.absolutePath).availableBytes > total * 2 + 16L * 1024 * 1024) { "存储空间不足，需要约 80 MB 可用空间" }
        var completed = 0L
        try {
            for (spec in FILES) {
                currentCoroutineContext().ensureActive()
                val output = File(staging, spec.name)
                val digest = MessageDigest.getInstance("SHA-256")
                val url = "$MODEL_BASE/${spec.name}"
                val http = openHttps(url)
                connection.set(http)
                try {
                    check(http.responseCode == HttpURLConnection.HTTP_OK) { "模型服务器暂时不可用（${http.responseCode}）" }
                    val declared = http.contentLengthLong
                    check(declared < 0 || declared == spec.size) { "模型文件大小不匹配" }
                    var received = 0L
                    http.inputStream.use { input ->
                        output.outputStream().use { file ->
                            val bytes = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(bytes)
                                if (count < 0) break
                                received += count
                                check(received <= spec.size) { "模型文件超过预期大小" }
                                digest.update(bytes, 0, count)
                                file.write(bytes, 0, count)
                                onProgress((completed + received).toFloat() / total)
                            }
                            file.fd.sync()
                        }
                    }
                    check(received == spec.size && digest.digest().hex() == spec.sha256) { "模型文件校验失败，请重试" }
                    completed += received
                } finally {
                    connection.compareAndSet(http, null)
                    http.disconnect()
                }
            }
            currentCoroutineContext().ensureActive()
            File(staging, ".verified").writeText(MODEL_REVISION)
            // No partial set of files is ever visible as an installed model.
            if (directory.exists()) directory.deleteRecursively()
            check(staging.renameTo(directory)) { "模型安装失败，请重试" }
            onProgress(1f)
        } finally {
            abortDownload()
            staging.deleteRecursively()
        }
    }

    private suspend fun hash(file: File): String {
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
        return digest.digest().hex()
    }

    private suspend fun openHttps(initial: String): HttpURLConnection {
        var location = initial
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val url = URI(location).toURL()
            check(url.protocol == "https") { "模型下载只支持 HTTPS" }
            val http = url.openConnection() as HttpURLConnection
            connection.set(http)
            http.connectTimeout = 20_000
            http.readTimeout = 20_000
            http.instanceFollowRedirects = false
            http.setRequestProperty("User-Agent", "Emerge-Android/0.4")
            val code = http.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val next = http.getHeaderField("Location") ?: error("模型下载重定向无效")
                location = url.toURI().resolve(next).toString()
                http.disconnect()
            } else return http
        }
        error("模型下载重定向过多")
    }

    private data class ModelFile(val name: String, val size: Long, val sha256: String)
    companion object {
        const val MODEL_ID = "zipformer-zh-14m-int8-v1"
        private const val MODEL_REVISION = "204ad334e2e683fd295359930cc16fc0432a23ac"
        private const val MODEL_BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/$MODEL_REVISION"
        private val FILES = listOf(
            ModelFile("encoder-epoch-99-avg-1.int8.onnx", 21_621_684, "1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1"),
            ModelFile("decoder-epoch-99-avg-1.onnx", 7_509_745, "5ee0f03a2768ff1d5c83ef3a493243c7935d316cd41280037b14783a3467cc78"),
            ModelFile("joiner-epoch-99-avg-1.int8.onnx", 1_795_562, "a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169"),
            ModelFile("tokens.txt", 48_697, "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac"),
        )
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
