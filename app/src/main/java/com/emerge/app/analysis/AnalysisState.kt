package com.emerge.app.analysis

data class AnalysisState(val running: Boolean = false, val report: CognitiveReport? = null,
    val error: String? = null, val exporting: Boolean = false, val exportMessage: String? = null,
    val purpose: String = "", val active: Boolean = false, val status: String? = null,
    val stale: Boolean = false)
