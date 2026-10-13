package com.emerge.app.capture

import java.security.MessageDigest

enum class WorkPage { GENERIC, VIDEO, CAPTION, COMMENTS, NON_CONTENT }

data class WorkHint(val title: String = "", val author: String = "", val nativeId: String = "",
    val galleryIndex: Int = 0, val galleryTotal: Int = 0,
    val page: WorkPage = WorkPage.GENERIC, val truncated: Boolean = false,
    val canonicalKey: String? = null) {
    fun matchesCaption(old: WorkHint): Boolean =
        page in setOf(WorkPage.VIDEO, WorkPage.CAPTION, WorkPage.GENERIC) && old.page in setOf(WorkPage.VIDEO, WorkPage.CAPTION, WorkPage.GENERIC) &&
        (nativeId.isBlank() || old.nativeId.isBlank() || nativeId == old.nativeId) &&
        author.isNotBlank() && author == old.author && title.length >= 16 && old.title.length >= 16 &&
        (title == old.title || (truncated && old.title.startsWith(title)) || (old.truncated && title.startsWith(old.title)))
    fun key(packageName: String): String? {
        val material = when {
            nativeId.isNotBlank() -> "id:$nativeId"
            canonicalKey != null -> return canonicalKey
            !truncated && title.length >= 8 && author.isNotBlank() -> "title:$author|$title"
            else -> return null
        }
        return MessageDigest.getInstance("SHA-256").digest("$packageName|$material".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    val method get() = if (nativeId.isNotBlank()) "platform_id" else "author_title"
    val evidence get() = when {
        canonicalKey != null -> if (truncated) "unique_observed_caption_prefix" else "unique_observed_author_caption"
        nativeId.isNotBlank() -> "visible_platform_id"
        !truncated && title.length >= 8 && author.isNotBlank() -> "visible_author_and_content_field"
        else -> "adjacent_content_only"
    }

    /** Only an adjacent forward image change with the same visible author is a gallery continuation.
     * A new title or a reset to image one is never merged on author alone. */
    fun followsGallery(old: WorkHint): Boolean = author.isNotBlank() && author == old.author &&
        galleryTotal > 1 && galleryTotal == old.galleryTotal && galleryIndex == old.galleryIndex + 1 &&
        galleryIndex <= galleryTotal && (title.isBlank() || old.title.isBlank() || title == old.title)
}

data class IdentityNode(val resourceId: String, val text: String, val description: String = "")

object WorkIdentityExtractor {
    private val feedCard = Regex("(?m)^(?:笔记|视频)\\s+.+?\\s+来自\\s*.+?\\s+[0-9.万]+赞")
    fun isRecommendationList(packageName: String, text: String, nodes: List<IdentityNode> = emptyList()): Boolean =
        (packageName == "com.xingin.xhs" && feedCard.findAll(text).take(2).count() >= 2) ||
            (packageName in setOf("com.xingin.xhs", "com.ss.android.ugc.aweme") && nodes.filter {
                it.resourceId.substringAfterLast('/') in setOf("aweme_id", "video_id", "item_id", "note_id", "noteId")
            }.map { it.text }.filter { it.isNotBlank() }.distinct().size > 1)

    private val chrome = Regex("^(?:关注|已关注|返回|更多内容|评论框|说点什么.*|共.*条评论|点赞.*|收藏.*|评论\\s*\\d*|\\d+|图片[，,].*|\\d+分钟前|昨天.*|今天.*)$")
    fun body(text: String): List<String> = text.lines().map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotBlank() && !chrome.matches(it) }

    fun extract(packageName: String, text: String, nodes: List<IdentityNode> = emptyList()): WorkHint {
        if (isRecommendationList(packageName, text, nodes)) return WorkHint(page = WorkPage.NON_CONTENT)
        val nativeId = nativeId(packageName, nodes)
        if (packageName == "com.ss.android.ugc.aweme" && nodes.isNotEmpty()) return douyin(nodes).copy(nativeId = nativeId)
        // Only known content fields; arbitrary UI labels must not become a post identity.
        val supported = packageName == "com.xingin.xhs" || packageName == "com.ss.android.ugc.aweme"
        val title = if (supported) nodes.firstOrNull {
            it.resourceId.substringAfterLast('/') in setOf("note_title", "note_content_title", "tv_title", "desc") &&
                it.text.length in 8..500
        }?.text.orEmpty().trim() else ""
        val nodeAuthor = if (supported) nodes.firstOrNull {
            it.resourceId.substringAfterLast('/') in setOf("nickname", "nick_name", "user_name", "tv_nickname", "author_name")
        }?.text.orEmpty().trim() else ""
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val follow = lines.indexOfFirst { it == "关注" || it == "已关注" }
        val author = nodeAuthor.ifBlank {
            if (supported && follow > 0) lines[follow - 1].takeIf {
                it.length in 2..40 && !it.contains("图片") && it !in setOf("推荐", "首页", "返回")
            }.orEmpty() else ""
        }
        val gallery = Regex("图片[，,、\\s]*第\\s*(\\d+)\\s*张[，,、\\s]*共\\s*(\\d+)\\s*张").find(text)
        // Obfuscated resource IDs are common on real phones. A detail page with one
        // visible author and a gallery may still expose its title in the text tree.
        val detailTitle = if (packageName == "com.xingin.xhs" && gallery != null && gallery.groupValues[2] == "1" && author.isNotBlank() &&
            !isRecommendationList(packageName, text)) {
            val tail = text.substring(gallery.range.last + 1).substringAfter('\n', "")
            body(tail).firstOrNull { it.length in 8..240 && it != author }.orEmpty()
        } else ""
        val comments = nodes.any { it.resourceId.substringAfterLast('/') in setOf("comment_list", "comments_panel") }
        val rawTitle = title.ifBlank { detailTitle }
        val truncated = rawTitle.endsWith("…展开") || rawTitle.endsWith("...展开")
        val stableTitle = if (truncated) rawTitle.removeSuffix("展开").removeSuffix("...").removeSuffix("…").trim() else rawTitle
        return WorkHint(stableTitle, author, nativeId, truncated = truncated, galleryIndex = gallery?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            galleryTotal = gallery?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            page = if (comments) WorkPage.COMMENTS else WorkPage.GENERIC)
    }

    private fun nativeId(packageName: String, nodes: List<IdentityNode>): String {
        val fields = when (packageName) {
            "com.ss.android.ugc.aweme" -> setOf("aweme_id", "video_id", "item_id")
            "com.xingin.xhs" -> setOf("note_id", "noteId")
            else -> return ""
        }
        val ids = nodes.filter { it.resourceId.substringAfterLast('/') in fields }
            .map { it.text.trim() }.filter { it.matches(Regex("[A-Za-z0-9_-]{5,80}")) }.distinct()
        // Multiple visible platform IDs can be a feed. Never pick an arbitrary card.
        return ids.singleOrNull().orEmpty()
    }

    private fun douyin(nodes: List<IdentityNode>): WorkHint {
        fun field(id: String) = nodes.firstOrNull { it.resourceId.substringAfterLast('/') == id }
        // The same title resource is reused by comment authors. Classify the page first.
        if (field("e4u")?.text?.matches(Regex("评论\\s*\\d+")) == true && field("back_btn") != null)
            return WorkHint(page = WorkPage.COMMENTS)
        val expanded = field("g8t")
        val caption = expanded?.text ?: field("desc")?.text.orEmpty()
        val author = if (expanded != null) field("g9q")?.text.orEmpty()
            else field("user_avatar")?.description?.ifBlank { field("title")?.text.orEmpty() }
                ?: field("title")?.text.orEmpty()
        val cleanAuthor = author.trim().removePrefix("@").trim()
        if (caption.isBlank() || cleanAuthor.isBlank()) return WorkHint(page = WorkPage.NON_CONTENT)
        val truncated = Regex("(?:\\.\\.\\.|…)\\s*展开[>〉]?\\s*$").containsMatchIn(caption)
        val cleanCaption = caption.replace(Regex("(?:\\.\\.\\.|…)\\s*展开[>〉]?\\s*$"), "")
            .replace(Regex("\\s+\\d{2}月\\d{2}日\\s*$"), "").trim().replace(Regex("\\s+"), " ")
        return WorkHint(cleanCaption, cleanAuthor, page = if (expanded != null) WorkPage.CAPTION else WorkPage.VIDEO,
            truncated = truncated)
    }
}
