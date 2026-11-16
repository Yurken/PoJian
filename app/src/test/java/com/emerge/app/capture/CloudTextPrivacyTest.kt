package com.emerge.app.capture

import org.junit.Assert.*
import org.junit.Test

class CloudTextPrivacyTest {
    @Test fun masksMainlandPhoneIdEmailAndNotificationBeforeCloud() {
        val source = "电话13800138000；身份证11010519900101123X；邮箱abc+tag@example.com\n微信：张三发来验证码 123456\n公开行业数据"
        val (masked, count) = CloudTextPrivacy.sanitize(source)
        assertEquals(4, count)
        assertFalse(masked.contains("13800138000"))
        assertFalse(masked.contains("11010519900101123X"))
        assertFalse(masked.contains("example.com"))
        assertFalse(masked.contains("123456"))
        assertTrue(masked.endsWith("公开行业数据"))
        assertEquals(masked to 0, CloudTextPrivacy.sanitize(masked))
    }
    @Test fun avoidsMatchingPhoneInsideLongNumericIdentifiers() {
        val public = "2026年行业招聘需求增长，编号9138001380001。微信推出公开新功能。"
        assertEquals(public to 0, CloudTextPrivacy.sanitize(public))
        assertEquals(CloudTextPrivacy.MASK to 1, CloudTextPrivacy.sanitize("+86 13800138000"))
    }
}
