@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.emerge.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import com.emerge.app.monitoring.MonitorPolicy
import com.emerge.app.monitoring.SupplementKnownArgument
import com.emerge.app.monitoring.SupplementRequest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SwissViewpointsData(
    val workCount: Int,
    val topics: List<String>,
    val shares: List<ViewpointStanceShare>,
    val candidates: List<ViewpointShare>,
    val trend: List<SwissStanceDay>,
    val requests: List<Pair<TopicDigest, SupplementRequest>>,
    val repeatedWorks: Map<String, Int>
)

private fun prepareSwissViewpoints(
    events: List<BrowseEvent>,
    semantics: List<EventSemantics>,
    report: CognitiveReport?,
    period: StatisticsPeriod,
    now: Long,
    zone: ZoneId,
    selectedTopic: String?
): SwissViewpointsData {
    val buckets = ViewpointTimeline.build(events, period, now, zone, report, semantics)
    val works = ViewpointTimeline.details(buckets.flatMap { it.items })
    val topics = (works.flatMap { it.claims }.map { it.topic }.filter(String::isNotBlank) + listOfNotNull(selectedTopic)).distinct()
    val selected = swissTopicItems(works, selectedTopic)
    val groups = ViewpointTimeline.groups(selected)
    val shares = viewpointSpectrum(ViewpointDistribution.topics(groups))
    val candidates = groups.filter { it.items.any { item -> item.claims.any { claim -> claim.quote.isNotBlank() } } }
        .sortedByDescending { it.items.size }
    val week = ViewpointTimeline.build(events, StatisticsPeriod.WEEK, now, zone, semantics = semantics)
    val trend = week.map { swissStanceDay(it, selectedTopic, zone) }
    val byId = events.associateBy { it.id }
    val repeatedRecords = report?.claims.orEmpty().filter { claim ->
        val event = byId[claim.eventId]
        event != null && event.packageName == claim.packageName && event.visibleText.contains(claim.quote) &&
            claim.evidenceDetails.any { fragment ->
                event.visibleText.contains(fragment.text) && fragment.historyMatches.any { match ->
                    byId[match.eventId]?.let { it.timestamp < event.timestamp && it.visibleText.contains(match.quote) } == true
                }
            }
    }.map { it.eventId }.toSet()
    val repeatedWorks = candidates.associate { group ->
        group.key to group.items.count { item -> item.records.any { it.id in repeatedRecords } }
    }
    val requests = report?.topicDigests.orEmpty()
        .filter { selectedTopic == null || it.topic == selectedTopic || it.subject == selectedTopic }
        .mapNotNull { topic ->
            val viewpoint = topic.viewpoints.firstOrNull() ?: return@mapNotNull null
            val known = viewpoint.observations.filter { claim ->
                byId[claim.eventId]?.let {
                    it.packageName == claim.packageName && it.visibleText.contains(claim.quote)
                } == true
            }.distinctBy { it.quote to it.evidence }.take(6)
            if (known.isEmpty()) return@mapNotNull null
            topic to SupplementRequest(
                MonitorPolicy.hash("${report!!.generatedAt}|${report.start}|${report.end}|${topic.key}|${viewpoint.key}"),
                topic.topic,
                topic.subject,
                viewpoint.dimension,
                viewpoint.conditions,
                known.map { SupplementKnownArgument(it.quote, it.evidenceDetails.firstOrNull()?.text.orEmpty()) }
            )
        }
    return SwissViewpointsData(selected.size, topics, shares, candidates, trend, requests, repeatedWorks)
}

@Composable
internal fun SwissViewpointsScreen(
    events: List<BrowseEvent>,
    semantics: List<EventSemantics>,
    report: CognitiveReport?,
    period: StatisticsPeriod,
    now: Long,
    appNames: Map<String, String>,
    selectedTopic: String?,
    onTopicSelected: (String?) -> Unit,
    onSupplement: (SupplementRequest) -> Unit,
    modifier: Modifier = Modifier,
    onFactCheck: (() -> Unit)? = null,
    findingCount: Int = 0
) {
    val zone = remember { ZoneId.systemDefault() }
    val scopedReport = report?.takeIf { it.period == period.label }
    val inputKey = remember(events, semantics, scopedReport, period, now, selectedTopic) { Any() }
    val prepared by produceState<Pair<Any, SwissViewpointsData>?>(null, inputKey) {
        value = null
        value = inputKey to withContext(Dispatchers.Default) {
            prepareSwissViewpoints(events, semantics, scopedReport, period, now, zone, selectedTopic)
        }
    }
    val data = prepared?.takeIf { it.first === inputKey }?.second
    val topics = (data?.topics.orEmpty() + listOfNotNull(selectedTopic)).distinct()
    val shares = data?.shares.orEmpty()
    val candidates = data?.candidates.orEmpty()
    val trend = data?.trend.orEmpty()
    val requests = data?.requests.orEmpty()
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("swiss-viewpoints-screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶部工具栏：左侧横向话题筛选组 + 右侧固定「事实核查」入口胶囊
        item("topic-filter-bar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SwissFilterChip(
                        label = "全部",
                        selected = selectedTopic == null,
                        onClick = { onTopicSelected(null) },
                        modifier = Modifier.testTag("viewpoints-topic-all")
                    )
                    topics.forEach { topic ->
                        SwissFilterChip(
                            label = topic,
                            selected = selectedTopic == topic,
                            onClick = { onTopicSelected(topic) },
                            modifier = Modifier.testTag("viewpoints-topic-$topic")
                        )
                    }
                }
                if (onFactCheck != null) {
                    SwissFactCheckPill(
                        count = findingCount,
                        onClick = onFactCheck
                    )
                }
            }
        }

        // 01 / 观点分布概览与两端代表观点
        item("distribution-overview") {
            SwissCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = DesignTokens.Ink,
                            contentColor = Color.White
                        ) {
                            Text(
                                text = "01",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Text(
                            text = selectedTopic ?: "观点分布",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "SPECTRUM",
                        color = Muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                }

                Surface(
                    shape = DesignTokens.InnerShape,
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(vertical = 14.dp, horizontal = 8.dp)
                    ) {
                        SwissViewpointMetric(
                            label = "已浏览内容",
                            value = data?.let { "${it.workCount} 篇" } ?: "—",
                            modifier = Modifier.weight(1f),
                            emphasized = true
                        )
                        VerticalDivider(Modifier.fillMaxHeight(), color = DesignTokens.Border)
                        SwissViewpointMetric(
                            label = "独立新闻源",
                            value = "未核实",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                SwissStanceDistribution(shares)

                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SwissRepresentative(
                        label = "高频接收观点",
                        share = candidates.firstOrNull(),
                        accent = DesignTokens.Rose,
                        repeated = candidates.firstOrNull()?.let { data?.repeatedWorks?.get(it.key) } ?: 0,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) { detailId = it.record.id }
                    SwissRepresentative(
                        label = "较少接触观点",
                        share = candidates.lastOrNull()?.takeIf { candidates.size > 1 },
                        accent = DesignTokens.Blue,
                        repeated = candidates.lastOrNull()?.let { data?.repeatedWorks?.get(it.key) } ?: 0,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) { detailId = it.record.id }
                }
            }
        }

        // 02 / 7日观点倾向变化曲线
        item("stance-timeline") {
            SwissStanceTimeline(trend)
        }

        // 03 / 核查与认知补全中枢（整合「信息盲区与缺失事实补全」+「事实核查与重复线索」快捷入口）
        item("verification-hub") {
            SwissCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = DesignTokens.Ink,
                            contentColor = Color.White
                        ) {
                            Text(
                                text = "03",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Text(
                            text = "信息盲区与缺失事实补全",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (onFactCheck != null) {
                        PojianTextButton(
                            onClick = onFactCheck,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (findingCount > 0) "核查记录 ($findingCount)" else "核查记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = DesignTokens.Blue
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = DesignTokens.Blue
                            )
                        }
                    }
                }

                Surface(
                    shape = DesignTokens.InnerShape,
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(DesignTokens.Ink, RoundedCornerShape(2.dp))
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (requests.isEmpty()) {
                                Text(
                                    text = "暂无可核查观点",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted
                                )
                            }
                            requests.forEachIndexed { idx, (topic, request) ->
                                if (idx > 0) {
                                    HorizontalDivider(color = DesignTokens.Border.copy(alpha = 0.6f), thickness = 0.75.dp)
                                }
                                PojianTextButton(
                                    onClick = { onSupplement(request) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("viewpoints-supplement-${topic.key}"),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = topic.topic + topic.subject.takeIf { it.isNotBlank() && it != topic.topic }?.let { " · $it" }.orEmpty(),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = Ink
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                        contentDescription = "补全缺失事实",
                                        modifier = Modifier.size(18.dp),
                                        tint = Muted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 04 / 核心观点明细列表眉头
        if (candidates.isNotEmpty()) {
            item("claims-section-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                        .semantics { heading() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = DesignTokens.Ink,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = "04",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(
                        text = "核心观点明细",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink
                    )
                    Text(
                        text = "${candidates.size} 组",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Muted
                    )
                }
            }
        }

        items(candidates, key = { it.key }) { group ->
            val example = group.items.firstOrNull()?.claims?.firstOrNull()
            val repeatedCount = data?.repeatedWorks?.get(group.key) ?: 0
            SwissCard(
                onClick = { detailId = group.items.firstOrNull()?.record?.id },
                modifier = Modifier.testTag("viewpoints-claim-${group.key}")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "核心观点",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink
                        )
                    }
                    if (repeatedCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = DesignTokens.Amber.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "$repeatedCount 篇论据重复",
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFB45309)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${group.items.size} 篇",
                        color = Ink,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = group.items.firstNotNullOfOrNull { it.quote } ?: group.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Ink
                )
                example?.evidence?.takeIf(String::isNotBlank)?.let {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.background,
                        border = BorderStroke(1.dp, DesignTokens.Border.copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                val sources = group.items.map {
                    appNames[it.record.packageName] ?: swissKnownApps[it.record.packageName] ?: it.record.packageName
                }.distinct()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = sources.joinToString(" · "),
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Muted
                    )
                }
            }
        }
    }
    events.firstOrNull { it.id == detailId }?.let { event ->
        SwissWorkDetailSheet(
            event = event,
            events = events,
            semantics = semantics,
            report = scopedReport,
            appNames = appNames,
            onDismiss = { detailId = null },
            onRelated = { detailId = it.id }
        )
    }
}

@Composable
private fun SwissFactCheckPill(
    count: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = DesignTokens.ControlShape,
        color = DesignTokens.Ink,
        contentColor = Color.White,
        modifier = Modifier
            .heightIn(min = 38.dp)
            .testTag("viewpoints-fact-check-entry")
            .semantics { role = Role.Button }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (count > 0) DesignTokens.Amber else DesignTokens.Emerald)
            )
            Text(
                text = "事实核查",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (count > 0) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White
                ) {
                    Text(
                        text = "$count",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SwissViewpointMetric(
    label: String,
    value: String,
    modifier: Modifier,
    emphasized: Boolean = false
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Text(
            text = value,
            style = if (emphasized) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (emphasized) DesignTokens.Ink else Muted
        )
    }
}

internal fun swissStanceColor(stance: ViewpointStance): Color = when (stance) {
    ViewpointStance.SUPPORT -> DesignTokens.Blue
    ViewpointStance.NEUTRAL -> Color(0xFFA1A1AA)
    ViewpointStance.QUESTION -> DesignTokens.Rose
    ViewpointStance.MIXED -> DesignTokens.Amber
    ViewpointStance.UNKNOWN -> DesignTokens.Border
}

@Composable
internal fun SwissStanceDistribution(shares: List<ViewpointStanceShare>) {
    val order = listOf(
        ViewpointStance.QUESTION,
        ViewpointStance.NEUTRAL,
        ViewpointStance.SUPPORT,
        ViewpointStance.MIXED,
        ViewpointStance.UNKNOWN
    )
    val sorted = shares.sortedBy { order.indexOf(it.stance) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DesignTokens.Border),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            sorted.forEach { share ->
                Box(
                    modifier = Modifier
                        .weight(share.count.toFloat().coerceAtLeast(1f))
                        .fillMaxHeight()
                        .background(swissStanceColor(share.stance))
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            sorted.forEach { share ->
                val dotColor = swissStanceColor(share.stance)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                    Text(
                        text = "${share.stance.label} ${share.percent.roundToInt()}%",
                        color = if (share.stance == ViewpointStance.UNKNOWN) Muted else dotColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (shares.isEmpty()) {
                Text("暂无观点", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SwissRepresentative(
    label: String,
    share: ViewpointShare?,
    accent: Color,
    repeated: Int,
    modifier: Modifier,
    onOpen: (ViewpointItem) -> Unit
) {
    val item = share?.items?.firstOrNull()
    Surface(
        color = accent.copy(alpha = .045f),
        shape = DesignTokens.InnerShape,
        border = BorderStroke(1.dp, accent.copy(alpha = .18f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Text(
                    text = label,
                    color = accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (item == null) {
                Text("暂无其他观点", style = MaterialTheme.typography.bodySmall, color = Muted)
            } else {
                Text(
                    text = item.quote ?: "未识别",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f, fill = false))
                Text(
                    text = if (repeated > 0) "$repeated 篇论据重复" else "来源未核实",
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall
                )
                PojianTextButton(
                    onClick = { onOpen(item) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.heightIn(min = 32.dp)
                ) {
                    Text(
                        text = "${share.items.size} 篇",
                        color = accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = "查看原文",
                        modifier = Modifier.size(16.dp),
                        tint = accent
                    )
                }
            }
        }
    }
}

@Composable
internal fun SwissStanceTimeline(days: List<SwissStanceDay>) {
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val selectedIndex = days.indexOfFirst { it.date == selectedDate }.takeIf { it >= 0 } ?: days.lastIndex
    val values = days.map { it.value }
    fun select(index: Int) {
        days.getOrNull(index)?.let { selectedDate = it.date }
    }
    SwissCard(modifier = Modifier.testTag("swiss-stance-timeline")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = DesignTokens.Ink,
                    contentColor = Color.White
                ) {
                    Text(
                        text = "02",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    text = "7日观点倾向变化",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "7D TREND",
                color = Muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
        if (days.isEmpty()) {
            Text("加载中", color = Muted, style = MaterialTheme.typography.bodySmall)
            return@SwissCard
        }
        val selected = days[selectedIndex]
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("质疑 −1", color = DesignTokens.Rose, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text("支持 +1", color = DesignTokens.Blue, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
                .testTag("swiss-stance-plot")
                .pointerInput(days) {
                    detectTapGestures { point ->
                        val inset = 6.dp.toPx()
                        swissChartSelection(point.x - inset, size.width - 2 * inset, days.size, edgePoints = true)?.let(::select)
                    }
                }
                .semantics {
                    contentDescription = "${selected.date} ${selected.value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "未识别"}；" +
                        selected.counts.entries.joinToString("；") { (stance, count) -> "${stance.label} $count 篇" }
                    customActions = buildList {
                        if (selectedIndex > 0) add(CustomAccessibilityAction("前一天") { select(selectedIndex - 1); true })
                        if (selectedIndex < days.lastIndex) add(CustomAccessibilityAction("后一天") { select(selectedIndex + 1); true })
                    }
                }
        ) {
            val center = size.height / 2
            val inset = 6.dp.toPx()
            val width = size.width - 2 * inset
            val amplitude = center - inset
            drawLine(
                color = DesignTokens.Border,
                start = Offset(0f, center),
                end = Offset(size.width, center),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            )
            drawLine(DesignTokens.Border.copy(alpha = .5f), Offset(0f, inset), Offset(size.width, inset))
            drawLine(DesignTokens.Border.copy(alpha = .5f), Offset(0f, size.height - inset), Offset(size.width, size.height - inset))
            fun point(index: Int, value: Float) =
                Offset(inset + index * width / (values.size - 1).coerceAtLeast(1), center - value * amplitude)
            val selectedX = point(selectedIndex, 0f).x
            drawLine(
                color = DesignTokens.Ink.copy(alpha = .18f),
                start = Offset(selectedX, 0f),
                end = Offset(selectedX, size.height),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            )
            val areaBrush = Brush.verticalGradient(
                0f to DesignTokens.Blue.copy(alpha = .16f),
                .5f to DesignTokens.Rose.copy(alpha = .025f),
                1f to DesignTokens.Rose.copy(alpha = .16f),
                endY = size.height
            )
            val lineBrush = Brush.verticalGradient(listOf(DesignTokens.Blue, DesignTokens.Rose), endY = size.height)
            values.forEachIndexed { index, value ->
                if (value == null) return@forEachIndexed
                val current = point(index, value)
                if (index > 0 && values[index - 1] != null) {
                    val previous = point(index - 1, values[index - 1]!!)
                    val middle = (previous.x + current.x) / 2
                    val line = Path().apply {
                        moveTo(previous.x, previous.y)
                        cubicTo(middle, previous.y, middle, current.y, current.x, current.y)
                    }
                    val area = Path().apply {
                        addPath(line)
                        lineTo(current.x, center)
                        lineTo(previous.x, center)
                        close()
                    }
                    drawPath(area, areaBrush)
                    drawPath(line, lineBrush, style = Stroke(2.dp.toPx()))
                }
                val color = if (value >= 0) DesignTokens.Blue else DesignTokens.Rose
                if (index == selectedIndex) {
                    drawCircle(color.copy(alpha = .15f), 8.dp.toPx(), current)
                    drawCircle(Color.White, 5.dp.toPx(), current)
                }
                drawCircle(color, if (index == selectedIndex) 4.dp.toPx() else 3.dp.toPx(), current)
            }
        }
        SwissChartAxis(
            labels = days.map { day ->
                day.date.format(DateTimeFormatter.ofPattern("M/d")) to
                    (day.value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—")
            },
            selectedIndex = selectedIndex
        )
        SwissChartSelectionHeader(
            label = selected.date.toString(),
            previousLabel = "前一天",
            nextLabel = "后一天",
            previousEnabled = selectedIndex > 0,
            nextEnabled = selectedIndex < days.lastIndex,
            onPrevious = { select(selectedIndex - 1) },
            onNext = { select(selectedIndex + 1) },
            tag = "swiss-stance"
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("swiss-stance-readout")
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ViewpointStance.entries.forEach { stance ->
                SwissLegend("${stance.label} ${selected.counts.getValue(stance)} 篇", swissStanceColor(stance))
            }
        }
    }
}
