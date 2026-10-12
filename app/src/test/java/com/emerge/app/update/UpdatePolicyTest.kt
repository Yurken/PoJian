package com.emerge.app.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val release = UpdatePolicy.release(27, "0.5.5", "https://updates.example.com/app.apk", "ab".repeat(32))
    private val installed = ApkIdentity("com.zhiheng.app", 26, "0.5.4", 29, setOf("fixed-signer"))
    private val archive = installed.copy(versionCode = 27, versionName = "0.5.5")

    @Test fun acceptsHigherCodeWithMatchingPackageAndFixedSigningKey() {
        UpdatePolicy.verifyApk(installed, archive, release, 35)
        // Version names are display values, never compared lexicographically.
        UpdatePolicy.verifyApk(installed, archive.copy(versionName = "0.5.10"), release.copy(versionName = "0.5.10"), 35)
    }

    @Test fun rejectsDowngradeWrongPackageWrongVersionOrUnsupportedSdk() {
        listOf(archive.copy(packageName = "another.app"), archive.copy(versionCode = 28),
            archive.copy(versionName = "0.5.6"), archive.copy(minSdk = 36)).forEach {
            assertThrows(UpdateException::class.java) { UpdatePolicy.verifyApk(installed, it, release, 35) }
        }
        assertThrows(UpdateException::class.java) { UpdatePolicy.verifyApk(archive, archive, release, 35) }
        assertThrows(UpdateException::class.java) { UpdatePolicy.verifyApk(archive.copy(versionCode = 28), archive, release, 35) }
    }

    @Test fun rejectsMissingDifferentOrPartiallyMatchingSignatures() {
        listOf(emptySet(), setOf("temporary-debug-key"), setOf("fixed-signer", "extra")).forEach { signers ->
            assertThrows(UpdateException::class.java) { UpdatePolicy.verifyApk(installed, archive.copy(signers = signers), release, 35) }
        }
        assertThrows(UpdateException::class.java) {
            UpdatePolicy.verifyApk(installed.copy(signers = emptySet()), archive.copy(signers = emptySet()), release, 35)
        }
    }

    @Test fun acceptsHttpsQueriesButRejectsUnsafeSchemesCredentialsFragmentsAndInvalidPorts() {
        assertEquals("https://cdn.example.com/app.apk?token=abc", UpdatePolicy.httpsUrl(" https://cdn.example.com/app.apk?token=abc "))
        listOf("http://example.com/a", "file:///a", "content://a", "https://user:secret@example.com/a",
            "https://example.com/a#x", "https:///a", "https://example.com:0/a", "https://example.com:65536/a").forEach {
            assertThrows(it, Exception::class.java) { UpdatePolicy.httpsUrl(it) }
        }
    }

    @Test fun rejectsMalformedMetadataAndNormalizesHash() {
        assertEquals("ab".repeat(32), UpdatePolicy.release(1, "v1", release.apkUrl, "AB".repeat(32)).sha256)
        listOf("", "00", "xx".repeat(32)).forEach {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.release(1, "v1", release.apkUrl, it) }
        }
        listOf("", " ", "a\nb", "x".repeat(65)).forEach {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.release(1, it, release.apkUrl, release.sha256) }
        }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.release(0, "v1", release.apkUrl, release.sha256) }
    }

    @Test fun checkCooldownRetriesFailuresAndRecoversFromClockRollback() {
        val now = 100_000_000L
        assertTrue(UpdatePolicy.checkDue(now, 0, 0))
        assertFalse(UpdatePolicy.checkDue(now, now - 1000, 0))
        assertTrue(UpdatePolicy.checkDue(now, now - UpdatePolicy.RETRY_INTERVAL_MS, 0))
        assertFalse(UpdatePolicy.checkDue(now, now - UpdatePolicy.RETRY_INTERVAL_MS, now - 1000))
        assertTrue(UpdatePolicy.checkDue(now, now - UpdatePolicy.CHECK_INTERVAL_MS, now - UpdatePolicy.CHECK_INTERVAL_MS))
        assertTrue(UpdatePolicy.checkDue(now, now + 1000, now + 1000))
    }
}
