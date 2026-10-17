package com.emerge.app.monitoring

internal data class MonitorOverlayLayout(
    val width: Int,
    val height: Int,
    val top: Int,
    val bubbleSize: Int,
    val bubbleTop: Int,
    val panelTop: Int,
    val panelHeight: Int
)

/** Pixel geometry in the system's usable window frame; the bubble keeps its own vertical anchor. */
internal fun monitorOverlayLayout(
    viewportWidth: Int,
    viewportHeight: Int,
    bubbleDiameter: Int,
    panelWidth: Int,
    anchorY: Int,
    panelContentHeight: Int,
    gap: Int,
    expanded: Boolean
): MonitorOverlayLayout {
    val availableWidth = viewportWidth.coerceAtLeast(1)
    val availableHeight = viewportHeight.coerceAtLeast(1)
    val size = bubbleDiameter.coerceIn(1, minOf(availableWidth, availableHeight))
    val anchor = anchorY.coerceIn(0, availableHeight - size)
    if (!expanded) return MonitorOverlayLayout(size, size, anchor, size, 0, 0, 0)
    val spacing = gap.coerceAtLeast(0)
    val below = (availableHeight - anchor - size - spacing).coerceAtLeast(0)
    val above = (anchor - spacing).coerceAtLeast(0)
    val opensAbove = panelContentHeight > below && above > below
    val panelHeight = panelContentHeight.coerceIn(0, if (opensAbove) above else below)
    val offset = if (panelHeight > 0) panelHeight + spacing else 0
    return MonitorOverlayLayout(
        width = panelWidth.coerceIn(size, availableWidth),
        height = size + offset,
        top = if (opensAbove) anchor - offset else anchor,
        bubbleSize = size,
        bubbleTop = if (opensAbove) offset else 0,
        panelTop = if (opensAbove || panelHeight == 0) 0 else size + spacing,
        panelHeight = panelHeight
    )
}

internal data class MonitorOverlayText(val text: String, val expandable: Boolean)

internal fun monitorOverlayFindingText(finding: MonitorFinding?, expanded: Boolean): MonitorOverlayText {
    if (finding == null) return MonitorOverlayText("", false)
    val full = buildString {
        append(finding.label)
        append('\n')
        append(finding.claim)
        if (finding.explanation.isNotBlank()) {
            append('\n')
            append(finding.explanation)
        }
    }
    val expandable = full.codePointCount(0, full.length) > 160
    val text = if (expandable && !expanded) full.substring(0, full.offsetByCodePoints(0, 160)) + "…" else full
    return MonitorOverlayText(text, expandable)
}
