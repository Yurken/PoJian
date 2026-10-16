@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.emerge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emerge.app.analysis.TopicConcentration
import kotlin.math.roundToInt

@Composable
internal fun SwissTopicConcentration(data: TopicConcentration) {
    val scope = data.scope
    val sufficient = scope?.sufficient == true
    val concentrated = sufficient && scope!!.share >= .75
    val color = if (concentrated) DesignTokens.Rose else DesignTokens.Emerald
    SwissCard(Modifier.testTag("topic-concentration-${data.topic}")) {
        Text(data.topic, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("内容集中度", color = Muted, style = MaterialTheme.typography.labelMedium)
                Text(if (!sufficient) "观察不足" else if (concentrated) "同向观点高度集中" else "未见明显集中",
                    color = if (sufficient) color else Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (sufficient) "${(scope!!.share * 100).roundToInt()}%" else "—",
                fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        }
        if (scope != null) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("主导观点占比", color = Muted, style = MaterialTheme.typography.labelSmall)
                Text("已识别 ${scope.classified}/${scope.works}篇", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(DesignTokens.Border)) {
                if (sufficient) Box(Modifier.fillMaxWidth(scope.share.toFloat()).fillMaxHeight().background(color))
            }
            Text(listOf(scope.subject, scope.dimension, scope.conditions).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Muted)
            if (scope.classified > 0) Text(scope.stance, style = MaterialTheme.typography.bodyMedium)
        }
        Text("主题内容 ${data.works}篇 · 已识别观点 ${data.classified}篇", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}
