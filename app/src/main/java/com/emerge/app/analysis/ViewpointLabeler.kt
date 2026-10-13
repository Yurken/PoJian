package com.emerge.app.analysis

/** Explicit offline phrase rules. Unmatched sentences are never used as labels. */
object ViewpointLabeler {
    fun compact(quote: String): String {
        if (Regex("如果|只有|除非|条件").containsMatchIn(quote)) {
            val faithful = quote.trim().replace(Regex("\\s+"), " ")
            return if (faithful.length <= 28) faithful else faithful.take(27) + "…"
        }
        if (quote.contains("女性") && quote.contains("经济独立") && !Regex("不应该|反对|不支持|不必|无需|不需要|不要|不能").containsMatchIn(quote)) return "女性经济独立"
        if (Regex("职业|工作|专业").containsMatchIn(quote) && Regex("能力|技能").containsMatchIn(quote) &&
            Regex("提升|提高|学习").containsMatchIn(quote) && !Regex("不必|无需|不能|不要").containsMatchIn(quote)) return "职业能力提升"
        val known = if (Regex("如果|只有|除非|条件|反对|不支持|不应该|不需要").containsMatchIn(quote)) "未识别" else label(quote)
        if (known != "未识别") return known
        val text = quote.trim().replace(Regex("\\s+"), " ")
        return if (text.length <= 28) text else text.take(27) + "…"
    }
    private fun String.has(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(this)

    fun label(text: String): String {
        val q = text.trim()
        return when {
            q.has("女性|女人|女生") && q.has("不应该[^，。；]{0,6}独立|不支持[^，。；]{0,6}独立|反对[^，。；]{0,6}独立") -> "反对女性独立"
            q.has("女性|女人|女生") && q.has("独立|自主|不依赖") -> "女性独立"
            q.has("就业|求职|招聘|裁员|找工作") && q.has("不必[^，。；]{0,6}焦虑|不用[^，。；]{0,6}焦虑|无需[^，。；]{0,6}焦虑|别[^，。；]{0,6}焦虑|不要[^，。；]{0,6}焦虑") -> "缓解就业焦虑"
            q.has("就业|求职|招聘|裁员|找工作") && q.has("焦虑|困难|很难|难找|失业") -> "就业焦虑"
            q.has("翻译") && q.has("API|费用|消耗|成本|收费") -> "翻译成本"
            q.has("Zotero|软件|工具") && q.has("UI|界面|使用|易用") && q.has("丑|不方便|难用|不好用|繁琐") -> "工具难用"
            q.has("Zotero|软件|工具") && q.has("方便|好用|易用") -> "工具好用"
            q.has("双语") && q.has("翻译|阅读") -> "双语阅读"
            q.has("人生|生活") && q.has("焦虑") -> "人生焦虑"
            q.has("坚持") && q.has("收获|成长|进步|努力") -> "坚持成长"
            q.has("前途|事业") && q.has("不会[^，。；]{0,20}放弃|不放弃|不愿[^，。；]{0,10}放弃") -> "前途优先"
            q.has("赚|收入|挣钱") && q.has("多少|一天|每天|今天|目标") -> "收入期待"
            q.has("质量|短板") && q.has("改善|改进|解决") -> "质量改善期待"
            q.has("质量") && q.has("问题|差|担忧|不好") -> "质量担忧"
            q.has("变量|时序") && q.has("相关") && q.has("频率|尺度|条件") -> "相关条件"
            q.has("机器人|智能体") && q.has("记忆|历史") -> "机器人记忆"
            q.has("人工智能|AI|模型") && q.has("可靠|证据|可信") -> "AI可靠性"
            else -> "未识别"
        }
    }
}
