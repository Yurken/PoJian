package com.emerge.app.update

import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val hash = "ab".repeat(32)
    private fun manifest(code: String = "27", name: String = "0.5.5", url: String = "https://example.com/app.apk", digest: String = hash) =
        """{"versionCode":$code,"versionName":"$name","apkUrl":"$url","sha256":"$digest"}"""

    @Test fun blankEndpointNeverReadsNetwork() = runBlocking {
        var calls = 0
        val repository = AppUpdateRepository(context, UpdateTransport { _, _, _ -> calls++ })
        assertNull(repository.check(""))
        assertNull(repository.check("  "))
        assertEquals(0, calls)
    }

    @Test fun decodesOnlyNewerVersionsAndRejectsMalformedManifest() {
        assertEquals(27L, decodeManifest(manifest(), 26)?.versionCode)
        assertNull(decodeManifest(manifest(), 27))
        assertNull(decodeManifest(manifest(), 28))
        listOf("{}", "not json", manifest(code = "27.5"), manifest(code = "\"27\""),
            manifest(code = "-1"), manifest(digest = "bad"), manifest(url = "http://example.com/app.apk")).forEach {
            assertThrows(Exception::class.java) { decodeManifest(it, 26) }
        }
    }

    @Test fun downloadRejectsWrongHashAndInvalidApkAndCleansPartialFiles() {
        val bytes = "not-an-apk".toByteArray()
        val repository = AppUpdateRepository(context, UpdateTransport { _, _, emit -> emit(bytes, bytes.size, bytes.size.toLong()) })
        val directory = File(context.cacheDir, "app-updates")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        listOf(hash, actual).forEach { digest ->
            assertThrows(UpdateException::class.java) { runBlocking {
                repository.download(UpdatePolicy.release(27, "0.5.5", "https://example.com/app.apk", digest)) { }
            } }
        }
        assertEquals(before, directory.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun providerOnlySharesUpdateCacheAndRemainsPrivate() {
        val authority = "${context.packageName}.updates"
        val provider = context.packageManager.resolveContentProvider(authority, 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
        val file = File(context.cacheDir, "app-updates/provider-test.apk").apply { parentFile!!.mkdirs(); writeText("fixture") }
        val outside = File(context.cacheDir, "outside-update-test.txt").apply { writeText("private") }
        try {
            val uri = FileProvider.getUriForFile(context, authority, file)
            assertEquals("content", uri.scheme)
            assertEquals("fixture", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, authority, outside) }
        } finally { file.delete(); outside.delete() }
    }

    @Test fun invalidDeltaFallsBackToFullDownload() = runBlocking {
        val base = File(context.applicationInfo.sourceDir)
        val baseHash = MessageDigest.getInstance("SHA-256").let { digest ->
            base.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val urls = mutableListOf<String>()
        val repository = AppUpdateRepository(context, UpdateTransport { url, _, emit ->
            urls += url
            val bytes = "invalid".toByteArray()
            emit(bytes, bytes.size, bytes.size.toLong())
        })
        val code = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val release = UpdatePolicy.release(code + 1, "test", "https://example.com/full.apk", hash).copy(
            patches = listOf(UpdatePatch(code, baseHash, "https://example.com/delta", hash, 7)))
        assertThrows(UpdateException::class.java) { runBlocking { repository.download(release) {} } }
        assertEquals(listOf("https://example.com/delta", "https://example.com/full.apk"), urls)
    }

    @Test fun optionalPatchMetadataDoesNotBlockLegacyManifest() {
        val patch = """{"format":"pjdiff1","fromVersionCode":26,"baseSha256":"$hash","url":"https://example.com/delta","sha256":"$hash","size":123}"""
        val json = manifest().dropLast(1) + ",\"patches\":[$patch]}"
        assertEquals(26L, decodeManifest(json, 26)!!.patches.single().fromVersionCode)
        assertTrue(decodeManifest(json.replace("pjdiff1", "unsupported"), 26)!!.patches.isEmpty())
        assertTrue(decodeManifest(json.replace("https://example.com/delta", "http://example.com/delta"), 26)!!.patches.isEmpty())
    }
}
