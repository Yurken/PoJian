package com.emerge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.emerge.app.analysis.SemanticGuidance

@Composable
internal fun GuidanceEvidenceCard(guidance: SemanticGuidance) {
    if (!guidance.isOverExtrapolation || !guidance.spanVerified) return
    Column(Modifier.fillMaxWidth().testTag("guidance-evidence"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("原文引用事实", style = MaterialTheme.typography.labelMedium)
                Text(guidance.factSpan, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("过度推断结论", style = MaterialTheme.typography.labelMedium)
                Text(guidance.conclusionSpan, style = MaterialTheme.typography.bodyMedium)
                listOf(guidance.scopeExtrapolation, guidance.certaintyUpgrade, guidance.emotionAmplification)
                    .filter(String::isNotBlank).distinct().forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
