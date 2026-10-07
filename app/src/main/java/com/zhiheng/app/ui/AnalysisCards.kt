package com.zhiheng.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiheng.app.analysis.*
import com.zhiheng.app.data.BrowseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun AnalysisCards(state: AnalysisState, period: StatisticsPeriod, events: List<BrowseEvent>,
    onAnalyze: (String) -> Unit, onExport: (CognitiveReport, Boolean) -> Unit) {
    var purpose by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<ClaimGroup?>(null) }
    var hour by remember { mutableStateOf<Int?>(null) }
    val report = state.report?.takeIf { it.period == period.label }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        WhiteCard {
            SectionTitle("分析我的信息环境", "离线可解释原型 · 不上传浏览内容")
            OutlinedTextField(value = purpose, onValueChange = { purpose = it.take(120) },
                label = { Text("浏览目的（选填）") }, placeholder = { Text("例如：了解就业、专题学习、休闲") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("从已记录文字中提取观点候选，比较同主题的历史依据，再生成可下载报告。规则结果需核查，无法理解全部语义。", color = Muted, fontSize = 12.sp)
            Button(onClick = { onAnalyze(purpose) }, enabled = !state.running && !state.exporting && events.isNotEmpty()) {
                Text(if (state.running) "正在分析…" else if (report == null) "分析${period.label}" else "重新分析最新记录")
            }
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (events.isEmpty()) Text("请先采集文字内容，再开始分析。", color = Muted)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (report == null) {
            WhiteCard {
                SectionTitle("观点与信息茧房观察")
                Text(if (state.running) "正在处理已记录的内容" else "尚未分析当前区间", fontSize = 22.sp)
                Text("分析后会展示观点引文、主题分布、依据变化及观察范围。短期集中阅读不会直接被判为信息茧房。", color = Muted, fontSize = 13.sp)
            }
        } else {
            WhiteCard {
                SectionTitle("我接触了哪些观点", "${report.claims.size} 个候选 · ${report.usableSnapshots} 个可用画面快照")
                Text("分析至 ${Instant.ofEpochMilli(report.end).atZone(ZoneId.of(report.zone)).format(DateTimeFormatter.ofPattern("M/d HH:mm"))}", color = Muted, fontSize = 12.sp)
                if (report.claims.isEmpty()) Text("未提取到明确观点候选。请核查记录是否有正文；短评、娱乐与界面标签可能无法分析。", color = Muted)
                else {
                    val shares = report.topics.take(5).map { AppShare(it.topic, it.count) } +
                        if (report.topics.size > 5) listOf(AppShare("其他主题", report.topics.drop(5).sumOf { it.count })) else emptyList()
                    DonutChart(shares, report.claims.size, "个观点候选")
                    shares.forEachIndexed { index, share ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(ChartColors[index]))
                            Text(share.packageName, modifier = Modifier.weight(1f).padding(start = 8.dp), fontSize = 13.sp)
                            Text("${share.count} 个 · ${ReportExporter.percent(share.count, report.claims.size)}", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Text("占比分母为观点候选，不是阅读时长或独立帖子。点击下方引文查看分析依据。", color = Muted, fontSize = 12.sp)
                    report.groups.take(10).forEach { group ->
                        HorizontalDivider(color = PageBackground)
                        Column(Modifier.fillMaxWidth().clickable { selected = group }.padding(vertical = 8.dp)) {
                            Text(group.quote, fontSize = 14.sp)
                            Text("${group.topic} · ${group.observations.size} 个近似措辞候选 · 查看依据 ›", color = Blue, fontSize = 12.sp)
                        }
                    }
                    if (report.groups.size > 10) Text("页面展示前 10 组，完整索引见下载报告。", color = Muted, fontSize = 12.sp)
                }
            }
            WhiteCard {
                SectionTitle("我的信息环境是否在收窄", "基于已采集的文字与跨日记录")
                report.findings.forEach { finding ->
                    Text(finding.topic, fontWeight = FontWeight.SemiBold)
                    Text(finding.title, color = Accent, fontSize = 16.sp)
                    Text(finding.explanation, color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                }
                Text("这是待验证的规则观察，未识别作者、真实立场或完整材料源头；不判断用户认同、情绪或认知受损。", color = Muted, fontSize = 12.sp)
            }
            WhiteCard {
                SectionTitle("依据有没有变化", "相对于已保留的同主题浏览历史")
                Increment.entries.forEach { kind ->
                    val count = report.claims.count { it.increment == kind }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(kind.label, fontSize = 13.sp)
                        Text("$count 个", color = Muted, fontSize = 13.sp)
                    }
                }
                Text("新增片段不等于新事实或知识增长。同屏依据可能并不支撑该观点，需点击引文复核。", color = Muted, fontSize = 12.sp)
            }
            WhiteCard {
                SectionTitle("按小时回顾观点", if (period == StatisticsPeriod.WEEK) "七天按小时合计 · 点击查看引文" else "手机本地小时 · 点击查看引文")
                for (row in 0..3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (column in 0..5) {
                            val index = row * 6 + column
                            TextButton(onClick = { hour = index }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                                Text("${index.toString().padStart(2, '0')}时\n${report.hourlyClaims[index]}", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            WhiteCard {
                SectionTitle("下载观察报告", "包括观点、依据、时间索引、观察结果与限制")
                Text("原始 ${report.rawRecords} 条，合并 ${report.mergedSnapshots} 条重复快照，跳过 ${report.skippedRecords} 条界面/无正文记录。报告只对应本次分析快照；新增记录需重新分析。", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onExport(report, true) }, enabled = !state.exporting && !state.running) { Text("保存 PDF") }
                    OutlinedButton(onClick = { onExport(report, false) }, enabled = !state.exporting && !state.running) { Text("保存文本") }
                }
                if (state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.exportMessage?.let { Text(it, color = Accent, fontSize = 12.sp) }
                Text("PDF 附录最多 100 个候选，完整索引保存在文本报告。报告含浏览引文；清空知衡不会删除已导出的文件。", color = Muted, fontSize = 12.sp)
            }
        }
    }
    selected?.let { group ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("观点候选与原文") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(group.observations) { item ->
                        Text("#${item.eventId} · ${item.packageName}", color = Accent, fontSize = 12.sp)
                        Text("估计可见停留：${item.durationMillis?.let { "${it / 1000} 秒" } ?: "旧记录未知"}", color = Muted)
                        Text(item.quote, fontWeight = FontWeight.SemiBold)
                        Text("${item.increment.label} · 先前事件 #${item.previousEventId ?: "无"}", color = Muted)
                        Text("适用条件：${item.conditions.ifBlank { "未知" }}；归属：${if (item.uncertainAttribution) "引用/转折，未知" else "待核查"}")
                        item.evidence.forEach { Text("依据候选：$it", fontSize = 12.sp) }
                        if (item.evidence.isEmpty()) Text("没有提取到依据片段，信息增量未知。", color = Muted)
                        events.firstOrNull { it.id == item.eventId }?.let { Text("当前采集原文：\n${it.visibleText}", fontSize = 12.sp) }
                        item.previousEventId?.let { previous ->
                            events.firstOrNull { it.id == previous }?.let { Text("先前事件原文：\n${it.visibleText}", fontSize = 12.sp) }
                        }
                        HorizontalDivider()
                    }
                }
            }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
    hour?.let { value ->
        val candidates = report?.claims?.filter { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.of(report.zone)).hour == value }.orEmpty()
        AlertDialog(onDismissRequest = { hour = null }, title = { Text("${value}时观点候选 · ${candidates.size} 个") },
            text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (candidates.isEmpty()) item { Text("此时段没有识别出的观点候选，不代表没有浏览。") }
                items(candidates) { item ->
                    Text("#${item.eventId} ${item.quote}\n估计可见停留：${item.durationMillis?.let { "${it / 1000} 秒" } ?: "旧记录未知"}", Modifier.padding(vertical = 8.dp))
                }
            } }, confirmButton = { TextButton(onClick = { hour = null }) { Text("关闭") } })
    }
}
