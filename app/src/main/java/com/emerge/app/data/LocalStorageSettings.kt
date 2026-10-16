package com.emerge.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalStorageSettings(context: Context) {
    private val preferences = context.getSharedPreferences("local_storage", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(runCatching { preferences.getLong("limit_bytes", DEFAULT_STORAGE_LIMIT_BYTES) }
        .getOrDefault(DEFAULT_STORAGE_LIMIT_BYTES).takeIf { it >= MIN_STORAGE_LIMIT_BYTES } ?: DEFAULT_STORAGE_LIMIT_BYTES)
    val limitBytes: StateFlow<Long> = state.asStateFlow()
    private val retentionState = MutableStateFlow(runCatching { preferences.getInt("retention_days", 0) }
        .getOrDefault(0).takeIf { it in STORAGE_RETENTION_DAYS } ?: 0)
    val retentionDays: StateFlow<Int> = retentionState.asStateFlow()

    @Synchronized fun setLimitBytes(value: Long) {
        require(value >= MIN_STORAGE_LIMIT_BYTES)
        if (state.value == value) return
        check(preferences.edit().putLong("limit_bytes", value).commit()) { "存储上限保存失败" }
        state.value = value
    }

    @Synchronized fun setRetentionDays(value: Int) {
        require(value in STORAGE_RETENTION_DAYS)
        if (retentionState.value == value) return
        check(preferences.edit().putInt("retention_days", value).commit()) { "保留周期保存失败" }
        retentionState.value = value
    }
}
