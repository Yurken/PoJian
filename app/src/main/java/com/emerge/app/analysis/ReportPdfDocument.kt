package com.emerge.app.analysis

/** Reading order and card relationships are independent of the paginated drawing engine. */
internal data class ReportPdfDocument(
    val title: String,
    val period: String,
    val metadata: List<String>,
    val purpose: String,
    val metrics: List<PdfDashboardMetric>,
    val sections: List<PdfDashboardSection>,
    val appendix: ReportPdfContent
) {
    companion object {
        fun from(report: CognitiveReport, appNames: Map<String, String> = emptyMap()): ReportPdfDocument =
            ReportPdfDocumentBuilder(report, appNames).build()
    }
}

internal data class PdfDashboardMetric(val label: String, val value: String, val details: List<String> = emptyList())
internal enum class PdfDashboardTone { BLUE, TEAL, AMBER, ROSE, NEUTRAL }
internal data class PdfDashboardGauge(val label: String, val valueText: String, val fraction: Double?, val detail: String = "")
internal data class PdfDashboardCard(
    val title: String,
    val blocks: List<ReportPdfBlock>,
    val badge: String = "",
    val tone: PdfDashboardTone = PdfDashboardTone.BLUE,
    val gauge: PdfDashboardGauge? = null
)
internal data class PdfDashboardRow(val cards: List<PdfDashboardCard>, val weights: List<Float> = emptyList())
internal data class PdfDashboardSection(
    val title: String,
    val rows: List<PdfDashboardRow>,
    val intro: List<ReportPdfBlock> = emptyList()
)
