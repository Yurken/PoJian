#!/usr/bin/env bash
# Offline JVM-only check. Does not build Android, run Room, or validate Compose/device behavior.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v kotlinc >/dev/null || { echo "kotlinc is required (tested with 1.9.0)." >&2; exit 1; }
command -v java >/dev/null || { echo "A Java runtime is required." >&2; exit 1; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# Annotation-only shims allow the ACTUAL BrowseEvent source to compile without the Android SDK.
# No analysis, storage, timing or matching behavior is mocked by these shims.
cat > "$WORK/RoomAnnotations.kt" <<'KOTLIN'
package androidx.room
import kotlin.reflect.KClass
annotation class Entity(val tableName: String = "", val foreignKeys: Array<ForeignKey> = [], val indices: Array<Index> = [])
annotation class ForeignKey(val entity: KClass<*>, val parentColumns: Array<String>, val childColumns: Array<String>, val onDelete: Int = 0) {
    companion object { const val CASCADE = 5 }
}
annotation class Index(vararg val value: String)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
annotation class ColumnInfo(val defaultValue: String = "")
KOTLIN
cat > "$WORK/AudioSessionType.kt" <<'KOTLIN'
package com.emerge.app.data
// Only used as a class literal in the foreign-key annotation. No database is instantiated.
class AudioSession
KOTLIN
MAIN="$ROOT/app/src/main/java/com/emerge/app"
TEST="$ROOT/app/src/test/java/com/emerge/app/analysis"
kotlinc "$WORK/RoomAnnotations.kt" "$WORK/AudioSessionType.kt" \
    "$MAIN/data/BrowseEvent.kt" "$MAIN/capture/ContentContinuity.kt" \
    "$MAIN/ui/BrowseStatistics.kt" "$MAIN/analysis/ObservationWindow.kt" \
    "$MAIN/analysis/StructuredSemantics.kt" "$MAIN/analysis/CognitiveAnalyzer.kt" "$MAIN/audioanalysis/AudioInsights.kt" \
    "$TEST/HistoryComparisonCases.kt" "$TEST/StructuredSemanticsCases.kt" -include-runtime -d "$WORK/history-core.jar"
java -cp "$WORK/history-core.jar" com.emerge.app.analysis.HistoryComparisonCases
java -cp "$WORK/history-core.jar" com.emerge.app.analysis.StructuredSemanticsCases
