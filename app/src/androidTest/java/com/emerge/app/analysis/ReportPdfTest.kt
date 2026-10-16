package com.emerge.app.analysis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportPdfTest {
    @Test fun multipageChineseReportExportsAndRendersWithoutTouchingUserDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
        val records = List(18) { index -> BrowseEvent(index + 1L, "com.example.reader",
            now - (index % 3) * 86_400_000L - (index / 3) * 300_000L, 32,
            if (index % 4 == 0) "当前就业形势很差，因为不同地区的招聘技能要求提高，建议比较具体岗位的数据"
            else "当前就业形势很差，因为招聘岗位同比减少20%") }
        val first = records.first()
        val model = EventSemantics(first.id, first.visibleText, now, listOf(ArgumentUnit(
            "当前就业形势很差", "就业", "就业形势", "技能要求", "提高", "不同地区的招聘技能要求提高", uncertain = false)))
        val report = CognitiveAnalyzer().analyze(records, StatisticsPeriod.WEEK, now,
            purpose = "合成测试：比较就业信息", semantics = listOf(model))
        assertEquals(1, report.structuredAnalyzedRecords)
        assertTrue(ReportExporter.text(report).contains("角度：技能要求；立场：提高"))
        val output = File(context.cacheDir, "report-qa.pdf")
        output.outputStream().use { ReportExporter.writePdf(report, it) }
        assertTrue(output.length() > 500)
        PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            assertTrue(renderer.pageCount >= 3)
            for (index in 0 until renderer.pageCount) {
                renderer.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(context.cacheDir, "report-qa-page-${index + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }
}
