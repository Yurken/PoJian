package com.emerge.app.analysis

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HistoryComparisonTest(private val case: HistoryComparisonCases.Case) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = HistoryComparisonCases.cases().map { arrayOf<Any>(it) }
    }

    @Test fun followsDesignContract() = case.verify()
}
