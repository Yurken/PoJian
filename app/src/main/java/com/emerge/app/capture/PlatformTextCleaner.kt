package com.emerge.app.capture

import java.text.Normalizer

/** Remove complete control labels, never occurrences of a word inside a content sentence. */
object PlatformTextCleaner {
    // Classification depends only on the literal line. Keep a small bounded cache
    // for repeated controls/captions across report, timeline and capture readers.
    private val controlLines = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 1024
    }
    private val labels = setOf("分享", "转发", "点赞", "未点赞", "喜欢", "赞", "已赞", "已点赞", "取消点赞", "收藏", "已收藏", "取消收藏",
        "关注", "已关注", "互相关注", "回关", "评论", "评论区", "全部评论", "查看评论", "写评论", "评论框",
        "直播", "直播中", "进入直播间", "去直播间", "推荐", "首页", "同城", "朋友", "消息", "我", "发现", "商城",
        "搜索", "返回", "关闭", "更多", "更多内容", "展开", "展开全文", "收起", "收起全文", "播放", "暂停",
        "举报", "不感兴趣", "复制链接", "按钮", "标签", "选项卡", "已选中", "未选中", "可点击",
        "双击激活", "双击以激活", "点按两次即可激活")
    private val actions = "未点赞|点赞|已点赞|喜欢|赞|收藏|已收藏|评论|全部评论|评论区|分享|转发|粉丝|获赞|关注"
    private val number = "[0-9]+(?:[.,][0-9]+)*(?:万|千|亿|[kKwW])?\\+?"
    private val counted = Regex("^(?:(?:$actions)\\s*[:：]?\\s*[(（]?\\s*$number\\s*[)）]?(?:次|个|条|人)?|" +
        "(?:共|共有)?\\s*$number\\s*(?:次|个|条|人)?\\s*(?:$actions))$")
    private val inputHint = Regex("^(?:说点什么|写评论|添加评论)[.。…]*$")
    private val gallery = Regex("^图片[，,、\\s]*第\\s*\\d+\\s*张[，,、\\s]*共\\s*\\d+\\s*张$")
    private val separators = Regex("[\\s,，、|｜·:：()（）]+")
    private val counter = Regex("$number(?:次|个|条|人)?")
    private val actionWord = Regex(actions)
    private val countValue = Regex(number)
    // Known action IDs only. A clickable text/description node may contain the actual article.
    private val actionIds = setOf("like_button", "btn_like", "digg_button", "comment_button", "btn_comment",
        "share_button", "btn_share", "follow_button", "btn_follow", "collect_button", "btn_collect",
        "live_entry", "recommend_tab", "tab_recommend", "back_btn", "btn_back")

    private fun label(value: String): Boolean {
        val name = value.removeSuffix("按钮").trim()
        return value in labels || name in labels || counted.matches(value) || inputHint.matches(value)
    }
    fun isControlLine(text: String): Boolean {
        if (text.length > 512) return classifyControlLine(text)
        synchronized(controlLines) { controlLines[text]?.let { return it } }
        val result = classifyControlLine(text)
        synchronized(controlLines) { controlLines[text] = result }
        return result
    }

    private fun classifyControlLine(text: String): Boolean {
        val line = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        if (label(line) || gallery.matches(line)) return true
        val parts = line.split(separators).filter(String::isNotBlank)
        // Numeric prose survives; a count is removable only alongside a recognized control.
        return parts.size > 1 && parts.any(::label) && parts.all { label(it) || counter.matches(it) }
    }

    internal val controlSuffix = Regex("\\s+(?:广告\\s+)?(?:展开全文|展开|收起全文|收起)(?:\\s+按钮)?\\s*$")
    private val explicitControlSuffix = Regex("\\s+(?:广告\\s+(?:展开全文|展开)|(?:展开全文|展开|收起全文|收起)\\s+按钮)\\s*$")

    fun clean(text: String): String {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        val drop = mutableSetOf<Int>()
        val controls = lines.indices.filter { isControlLine(lines[it]) }
        controls.forEach { index ->
            drop.add(index)
            // Only a contiguous counter block immediately following an explicit counted action.
            if (actionWord.containsMatchIn(lines[index]) && countValue.containsMatchIn(lines[index])) {
                val end = (index + 1 until lines.size).firstOrNull { !counter.matches(lines[it]) } ?: lines.size
                if (end - index > 2) drop.addAll(index + 1 until end)
            }
        }
        lines.forEachIndexed { index, line ->
            if (line == "相关搜索" && controls.isNotEmpty()) {
                drop.add(index)
                // Conservative offline fallback: one trailing, short search suggestion with UI context.
                if (index == lines.lastIndex - 1 && lines.last().length <= 40 &&
                    lines.last().none { it in "。！？!?；;" }) drop.add(index + 1)
            }
        }
        return lines.filterIndexed { index, _ -> index !in drop }
            .map { line ->
                // Every supported suffix contains one of these words. Ordinary
                // prose need not allocate a regex matcher just to stay unchanged.
                if (line.contains("展开") || line.contains("收起"))
                    line.replace(explicitControlSuffix, "").trimEnd() else line
            }.filter(String::isNotEmpty).joinToString("\n")
    }

    /** Keep neighboring control context until the complete window is filtered. */
    fun captureNodeText(text: String, description: String, resourceId: String): String =
        if (resourceId.substringAfterLast('/') in actionIds) ""
        else listOf(text, description).filter(String::isNotBlank).distinct().joinToString("\n")

    fun nodeText(text: String, description: String, resourceId: String): String =
        clean(captureNodeText(text, description, resourceId))

    fun forSource(text: String, sourceType: String): String =
        if (sourceType in setOf("accessibility", "screen_ocr", "visual_model")) clean(text) else text
}
