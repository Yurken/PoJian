package com.emerge.app.monitoring

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorPayloadCipherTest {
    @Test fun queuedTextUsesRandomIvAndSurvivesCipherRecreation() {
        val text = "招聘岗位正在减少，原始材料只在认证解密后读取"
        val a = MonitorPayloadCipher().seal(text, "task-a")
        val b = MonitorPayloadCipher().seal(text, "task-a")
        assertNotEquals(a, b)
        assertFalse(a.contains("招聘"))
        assertEquals(text, MonitorPayloadCipher().open(a, "task-a"))
    }

    @Test fun changingTaskIdentityOrCiphertextIsRejected() {
        val envelope = MonitorPayloadCipher().seal("队列材料", "task-a")
        assertTrue(runCatching { MonitorPayloadCipher().open(envelope, "task-b") }.isFailure)
        val bytes = android.util.Base64.decode(envelope.substring(2), android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val changed = "1:" + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        assertTrue(runCatching { MonitorPayloadCipher().open(changed, "task-a") }.isFailure)
    }
}
