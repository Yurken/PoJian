package com.emerge.app.capture

import java.text.Normalizer

/** Remove complete control labels, never occurrences of a word inside a content sentence. */
object PlatformTextCleaner {
    private val labels = setOf("分享", "转发", "点赞", "赞", "已赞", "已点赞", "取消点赞", "收藏", "已收藏", "取消收藏",
        "关注", "已关注", "互相关注", "回关", "评论", "评论区", "全部评论", "查看评论", "写评论", "评论框",
        "直播", "直播中", "进入直播间", "去直播间", "推荐", "首页", "同城", "朋友", "消息", "我", "发现", "商城",
        "搜索", "返回", "关闭", "更多", "更多内容", "展开", "展开全文", "收起", "收起全文", "播放", "暂停",
        "举报", "不感兴趣", "复制链接", "按钮", "标签", "选项卡", "已选中", "未选中", "可点击",
        "双击激活", "双击以激活", "点按两次即可激活")
    private val actions = "点赞|已点赞|赞|收藏|已收藏|评论|全部评论|评论区|分享|转发|粉丝|获赞|关注"
    private val number = "[0-9]+(?:[.,][0-9]+)*(?:万|千|亿|[kKwW])?\\+?"
    private val counted = Regex("^(?:(?:$actions)\\s*[:：]?\\s*[(（]?\\s*$number\\s*[)）]?(?:次|个|条|人)?|" +
        "(?:共|共有)?\\s*$number\\s*(?:次|个|条|人)?\\s*(?:$actions))$")
    private val inputHint = Regex("^(?:说点什么|写评论|添加评论)[.。…]*$")
    private val gallery = Regex("^图片[，,、\\s]*第\\s*\\d+\\s*张[，,、\\s]*共\\s*\\d+\\s*张$")
    private val separators = Regex("[\\s,，、|｜·:：()（）]+")
    private val counter = Regex("$number(?:次|个|条|人)?")
    // Known action IDs only. A clickable text/description node may contain the actual article.
    private val actionIds = setOf("like_button", "btn_like", "digg_button", "comment_button", "btn_comment",
        "share_button", "btn_share", "follow_button", "btn_follow", "collect_button", "btn_collect",
        "live_entry", "recommend_tab", "tab_recommend", "back_btn", "btn_back")

    private fun label(value: String): Boolean {
        val name = value.removeSuffix("按钮").trim()
        return value in labels || name in labels || counted.matches(value) || inputHint.matches(value)
    }
    fun isControlLine(text: String): Boolean {
        val line = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        if (label(line) || gallery.matches(line)) return true
        val parts = line.split(separators).filter(String::isNotBlank)
        // Numeric prose survives; a count is removable only alongside a recognized control.
        return parts.size > 1 && parts.any(::label) && parts.all { label(it) || counter.matches(it) }
    }

    fun clean(text: String): String = text.lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && !isControlLine(it) }.joinToString("\n")

    fun nodeText(text: String, description: String, resourceId: String): String =
        if (resourceId.substringAfterLast('/') in actionIds) ""
        else clean(listOf(text, description).filter(String::isNotBlank).distinct().joinToString("\n"))

    fun forSource(text: String, sourceType: String): String =
        if (sourceType in setOf("accessibility", "screen_ocr", "visual_model")) clean(text) else text
}
