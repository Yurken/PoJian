package com.emerge.app.analysis

import org.junit.Test

class StructuredSemanticsTest {
    @Test fun retainedEvidenceAndChronologyRegressionsPass() {
        StructuredSemanticsCases.cases().forEach { case ->
            try { case.verify() } catch (error: Throwable) {
                throw AssertionError(case.name, error)
            }
        }
    }
}
