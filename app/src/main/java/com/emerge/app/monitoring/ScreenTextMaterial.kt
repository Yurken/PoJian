package com.emerge.app.monitoring

import com.emerge.app.data.BrowseEvent
import com.emerge.app.capture.PlatformTextCleaner

/** Keep OCR as observed material; it is neither another watched work nor a measured duration. */
internal object ScreenTextMaterial {
    const val SOURCE = "screen_ocr"

    fun additionalText(recognized: String, accessible: String): String {
        fun key(text: String) = text.filterNot(Char::isWhitespace)
        val existing = PlatformTextCleaner.clean(accessible).lineSequence().map(::key).toSet()
        return PlatformTextCleaner.clean(recognized).lineSequence().filter { it.isNotBlank() && key(it) !in existing }
            .distinct().joinToString("\n").take(6000)
    }

    fun event(packageName: String, text: String, at: Long, parent: BrowseEvent?): BrowseEvent {
        require(parent == null || parent.sourceType == "accessibility" && parent.packageName == packageName &&
            parent.id > 0 && parent.timestamp <= at && at - MonitorInputs.observationTime(parent) in 0..6000)
        return BrowseEvent(packageName = packageName, timestamp = at, eventType = 0, visibleText = PlatformTextCleaner.clean(text).take(6000),
            sourceType = SOURCE, parentEventId = parent?.id, workKey = parent?.workKey,
            identityMethod = parent?.identityMethod ?: "unknown")
    }

    fun inputText(accessible: String, material: BrowseEvent?): String =
        // Keep OCR content first; earlier page text must not crowd new evidence out of the request.
        listOfNotNull(material?.visibleText, PlatformTextCleaner.clean(accessible).takeIf(String::isNotBlank)).joinToString("\n").take(6000)
}
