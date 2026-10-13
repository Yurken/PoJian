package com.emerge.app.analysis

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.MainActivity
import com.emerge.app.ui.ExportedReport
import com.emerge.app.ui.ReportFilePreview
import com.emerge.app.ui.theme.EmergeTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only regression specification. Host compilation does not establish a passing device run. */
@RunWith(AndroidJUnit4::class)
class ReportFilePreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun savedTextIsPagedAndCloseRemovesOnlyTemporaryPreviewCopy() {
        val file = File(context.cacheDir, "preview-test-${UUID.randomUUID()}.txt")
        val text = "导出文件中的原文\n" + "完整观点记录\n".repeat(1200) + "最后一条观点"
        file.writeText(text)
        try {
            open(file, false)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("report-preview-text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("report-preview-text").assertTextContains("导出文件中的原文", substring = true)
            compose.onNodeWithText("下一页").performClick()
            compose.onNodeWithText("上一页").assertIsEnabled()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("report-file-preview").assertDoesNotExist()
            compose.waitUntil(5000) { File(context.cacheDir, "report-previews").listFiles().orEmpty().none { it.extension == "tmp" } }
            assertEquals(text, file.readText())
        } finally { file.delete() }
    }

    @Test fun actualPdfPagesCanBeRenderedTurnedAndZoomed() {
        val file = File(context.cacheDir, "preview-test-${UUID.randomUUID()}.pdf")
        val pdf = PdfDocument()
        try {
            for (number in 1..2) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
                page.canvas.drawText("Exported page $number", 30f, 60f, Paint().apply { textSize = 20f })
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        try {
            open(file, true)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("report-preview-pdf-page").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            compose.onNodeWithText("放大").performClick()
            compose.onNodeWithText("200%").assertIsDisplayed()
            compose.onNodeWithText("下一页").performClick()
            compose.onNodeWithText("2 / 2").assertIsDisplayed()
            compose.onNodeWithText("下一页").assertIsNotEnabled()
            compose.onNodeWithText("关闭").performClick()
        } finally { file.delete() }
    }

    @Test fun missingSavedFileShowsRetryWithoutClaimingExportFailed() {
        val file = File(context.cacheDir, "missing-${UUID.randomUUID()}.pdf")
        open(file, true)
        compose.waitUntil(5000) { compose.onAllNodesWithText("无法预览，请重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("重试").assertIsEnabled()
        compose.onNodeWithText("关闭").performClick()
    }

    private fun open(file: File, pdf: Boolean) {
        val visible = mutableStateOf(true)
        compose.runOnUiThread { compose.activity.setContent { EmergeTheme {
            if (visible.value) ReportFilePreview(ExportedReport(Uri.fromFile(file), pdf)) { visible.value = false }
        } } }
    }
}
