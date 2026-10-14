package com.emerge.app.data

import java.math.BigDecimal
import java.math.RoundingMode

const val DEFAULT_STORAGE_LIMIT_BYTES = 5_000_000_000L
const val MIN_STORAGE_LIMIT_BYTES = 100_000_000L
private val gigabyte = BigDecimal("1000000000")

/** Decimal GB throughout; never round a submitted quota down or overflow a Long. */
fun parseStorageLimitGb(text: String): Long? {
    val value = text.trim()
    if (value.length !in 1..40 || !value.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) return null
    return runCatching { BigDecimal(value).multiply(gigabyte).longValueExact() }.getOrNull()
        ?.takeIf { it >= MIN_STORAGE_LIMIT_BYTES }
}

fun storageLimitGbText(bytes: Long): String = BigDecimal.valueOf(bytes)
    .divide(gigabyte).stripTrailingZeros().toPlainString()

fun formatStorageBytes(bytes: Long): String {
    val amount = bytes.coerceAtLeast(0)
    val units = listOf(1_000_000_000_000L to "TB", 1_000_000_000L to "GB", 1_000_000L to "MB", 1000L to "KB")
    val (divisor, unit) = units.firstOrNull { amount >= it.first } ?: return "$amount B"
    val value = BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(divisor), 1, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
    return "$value $unit"
}

/** Free SQLite pages are reusable allocation, not retained records to delete again. */
internal fun retainedStorageBytes(pageCount: Long, freePages: Long, pageSize: Long, thumbnailBytes: Long): Long {
    val livePages = pageCount.coerceAtLeast(0) - freePages.coerceIn(0, pageCount.coerceAtLeast(0))
    val size = pageSize.coerceAtLeast(0)
    val databaseBytes = if (size > 0 && livePages > Long.MAX_VALUE / size) Long.MAX_VALUE else livePages * size
    return saturatedStorageSum(databaseBytes, thumbnailBytes.coerceAtLeast(0))
}

internal fun saturatedStorageSum(first: Long, second: Long): Long =
    if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
