package com.emerge.app.monitoring

/** Immutable request binding; every observed window/content change revokes outstanding requests. */
internal data class ThumbnailContext(val eventId: Long, val workKey: String, val packageName: String,
    val windowId: Int, val revision: Long, val permission: Long, val consent: Long) {
    fun matches(current: ThumbnailContext?, revision: Long, permission: Long?, consent: Long,
        enabled: Boolean): Boolean = enabled && this == current && this.revision == revision &&
        this.permission == permission && this.consent == consent
}
