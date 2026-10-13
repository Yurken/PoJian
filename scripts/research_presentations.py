"""Authored UI annotations derived only from an encountered synthetic version, never experiment gold labels.

These deterministic annotations are not responses from a deployed model and are not evaluation targets.
"""
import re

PROVENANCE = "AI_authored_rules_unreviewed"


def category(text):
    for label, pattern in [("求职", "求职|应聘|简历|面试|找工作"), ("就业", "就业|招聘|失业|裁员|职场|工资|职业"),
                           ("教育", "教育|学校|考试|升学|学习|考研|学生"), ("体育", "体育|运动|健身|足球|篮球|跑步|比赛"),
                           ("健康", "健康|医疗|睡眠|疾病|就医|饮食"), ("家庭", "家庭|性别|女性|婚姻|照护|育儿"),
                           ("科技", "科技|产品|软件|人工智能|模型|手机|机器人"), ("消费", "经济|消费|储蓄|收入|支出|购物|价格"),
                           ("公共议题", "公共|政策|社会|社区|交通"), ("生活", "生活|人生|日常|旅行|收纳")]:
        if re.search(pattern, text):
            return label
    return "其他"


def make_presentation(version, argument, emotions):
    text, quote = version["text"], argument["quote"]
    assert argument["version_id"] == version["id"] and argument["work_id"] == version["work_id"] and quote in text
    assert all(e["version_id"] == version["id"] and e["work_id"] == version["work_id"] for e in emotions)
    phrase = quote.replace("可以把“", "").replace("”理解为一项需要具体落实的安排", "")
    if "但我反对" in quote:
        phrase = "反对在当前条件下直接采用：" + argument["dimension"]
    if argument["uncertain"]:
        phrase = "当前片段的观点归属与立场不明"
    if len(phrase) > 48:
        phrase = phrase[:47] + "…"
    # Classify the actual statement, not a broad corpus sampling domain or a creator's identity.
    topic = category(quote)
    if topic == "其他":
        topic = category(argument["topic"])
    def signal(level, reason, evidence=""):
        assert not evidence or evidence in text
        return dict(level=level, reason=reason, evidence=evidence)
    unknown = signal("unknown", "当前片段不足以评估该项影响。")
    anxiety = unknown
    observed = []
    for emotion in emotions:
        start, end = emotion["context_span"]
        observed.append((emotion, text[start:end]))
    anxious = next(((e, q) for e, q in observed if e["expression"] == "焦虑" and e["attribution"] in ("affirmed", "reported")), None)
    calm = next(((e, q) for e, q in observed if e["expression"] in ("平静", "并不焦虑", "安心")), None)
    if anxious:
        emotion, evidence = anxious
        anxiety = signal("medium", f"片段将担忧归因于{emotion['cause']}。反复接触可能放大担忧，不代表观看者已经焦虑。", evidence)
    elif calm:
        anxiety = signal("low", "片段表达平静、否定焦虑或描述安心，未呈现升级恐惧的表达；不据此推断观看者情绪。", calm[1])
    if argument["uncertain"]:
        cocoon = cognition = unknown
        suggestions = ["先补全观点归属和上下文，再比较不同立场的依据。"]
    else:
        conditional = bool(argument["conditions"] and argument["conditions"] in quote)
        cocoon = signal("low" if conditional or "但我反对" in quote else "medium",
                        "观点保留了具体条件或反对意见。若长期排除其他条件和反例，仍可能收窄视角；单条作品不能证明形成茧房。"
                        if conditional or "但我反对" in quote else
                        "该片段只呈现一项主张；只有长期忽略其他解释与反例时，才可能收窄视角，不能据此判断已形成茧房。", quote)
        cognition = signal("low" if conditional else "medium",
                           "观点限定了适用处境，应核对条件再应用；其中的虚构数值不能作为现实证据。" if conditional else
                           "缺少可直接应用于其他处境的边界，直接照搬可能忽略差异；虚构材料不能当作现实事实。", quote)
        suggestions = [f"围绕“{argument['subject'][:32]}”比较不同来源的解释和反例，再决定是否采用。",
                       "重复刷到相似说法时先暂停，核对适用条件，再补充不同处境的材料。"]
    return dict(category=topic, viewpoint=phrase, anxiety=anxiety, cocoon=cocoon, cognition=cognition, suggestions=suggestions)
