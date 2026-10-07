package com.zhiheng.app.analysis

data class AnalysisState(val running: Boolean = false, val report: CognitiveReport? = null,
    val error: String? = null, val exporting: Boolean = false, val exportMessage: String? = null)
