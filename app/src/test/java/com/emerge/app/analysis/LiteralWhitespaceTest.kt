package com.emerge.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteralWhitespaceTest {
    @Test fun optimizedPathMatchesTheRuntimeRegexForEveryRecognizedWhitespaceCharacter() {
        val expression = Regex("\\s+")
        val characters = CharArray(65536) { it.toChar() }.concatToString()
        val values = expression.findAll(characters).map { "甲${it.value}乙" }.toList() +
            listOf("", "没有空白的中文内容", " a  b\t\n c ", "甲\u0085乙", "甲\u00a0乙", "甲\u200b乙")
        values.forEach { assertEquals(expression.replace(it, " "), LiteralWhitespace.collapse(it)) }
    }
}
