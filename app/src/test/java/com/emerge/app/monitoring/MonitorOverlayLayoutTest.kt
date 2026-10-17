package com.emerge.app.monitoring

import org.junit.Assert.*
import org.junit.Test

class MonitorOverlayLayoutTest {
    @Test fun collapsedBubbleHasASquareWindowEvenWhenTheViewportIsNarrow() {
        val layout = monitorOverlayLayout(80, 600, 112, 400, 240, 0, 8, false)
        assertEquals(80, layout.bubbleSize)
        assertEquals(layout.width, layout.height)
        assertEquals(0, layout.bubbleTop)
        assertEquals(0, layout.panelHeight)
    }

    @Test fun panelOpensBelowWhenThereIsRoomWithoutMovingTheBubble() {
        val layout = monitorOverlayLayout(360, 760, 56, 296, 80, 300, 8, true)
        assertEquals(80, layout.top + layout.bubbleTop)
        assertEquals(64, layout.panelTop)
        assertEquals(300, layout.panelHeight)
        assertEquals(364, layout.height)
    }

    @Test fun bottomBubbleOpensAboveAndReturnsToTheSamePosition() {
        val expanded = monitorOverlayLayout(360, 760, 56, 296, 680, 400, 8, true)
        assertEquals(680, expanded.top + expanded.bubbleTop)
        assertEquals(0, expanded.panelTop)
        assertEquals(400, expanded.panelHeight)
        val collapsed = monitorOverlayLayout(360, 760, 56, 296, 680, 0, 8, false)
        assertEquals(collapsed.top, expanded.top + expanded.bubbleTop)
    }

    @Test fun tallContentGetsAScrollViewportAndCannotCoverTheBubble() {
        val layout = monitorOverlayLayout(320, 480, 96, 420, 160, 2400, 8, true)
        assertEquals(320, layout.width)
        assertEquals(216, layout.panelHeight)
        assertEquals(104, layout.panelTop)
        assertEquals(160, layout.top)
        assertEquals(480, layout.top + layout.height)
    }

    @Test fun rotationAndOversizedTextKeepEveryRectangleInsideTheViewport() {
        for (width in listOf(40, 240, 360, 800)) for (height in listOf(48, 240, 800)) {
            for (anchor in listOf(-20, 0, 260, 2000)) for (bubble in listOf(56, 96, 180)) {
                val layout = monitorOverlayLayout(width, height, bubble, 420, anchor, 1500, 8, true)
                assertTrue(layout.width in 1..width)
                assertTrue(layout.height in 1..height)
                assertTrue(layout.top >= 0)
                assertTrue(layout.top + layout.height <= height)
                assertTrue(layout.bubbleTop >= 0)
                assertTrue(layout.bubbleTop + layout.bubbleSize <= layout.height)
                assertTrue(layout.panelTop >= 0)
                assertTrue(layout.panelTop + layout.panelHeight <= layout.height)
                if (layout.panelHeight > 0) assertTrue(
                    layout.panelTop >= layout.bubbleTop + layout.bubbleSize + 8 ||
                        layout.panelTop + layout.panelHeight + 8 <= layout.bubbleTop)
            }
        }
    }

    @Test fun detailsAreLimitedUntilExplicitlyExpandedAndCollapseBackToThePreview() {
        val claim = "这是需要完整展示的原文。".repeat(60)
        val explanation = "这是核查结果的完整说明。".repeat(100)
        val finding = MonitorFinding("id", "key", "reader", 1, "fact", "insufficient", claim, explanation)
        val full = "${finding.label}\n$claim\n$explanation"
        val preview = monitorOverlayFindingText(finding, false)
        assertEquals(full.take(160) + "…", preview.text)
        assertTrue(preview.expandable)
        assertEquals(full, monitorOverlayFindingText(finding, true).text)
        assertTrue(monitorOverlayFindingText(finding, true).expandable)
        assertEquals(preview, monitorOverlayFindingText(finding, false))
    }

    @Test fun shortOrMissingDetailsDoNotOfferExpansion() {
        val finding = MonitorFinding("id", "key", "reader", 1, "fact", "insufficient", "当前原文", "")
        val text = monitorOverlayFindingText(finding, false)
        assertEquals("${finding.label}\n当前原文", text.text)
        assertFalse(text.expandable)
        assertEquals("", monitorOverlayFindingText(null, false).text)
        assertFalse(monitorOverlayFindingText(null, false).expandable)
    }

    @Test fun previewCountsUnicodeCharactersWithoutBreakingEmoji() {
        val finding = MonitorFinding("id", "key", "reader", 1, "fact", "insufficient", "😀".repeat(200), "")
        val full = monitorOverlayFindingText(finding, true).text
        val preview = monitorOverlayFindingText(finding, false).text
        assertEquals(full.substring(0, full.offsetByCodePoints(0, 160)) + "…", preview)
        assertEquals(161, preview.codePointCount(0, preview.length))
    }
}
