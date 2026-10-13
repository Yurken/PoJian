"""Frozen, controlled synthetic experiments; never participant or natural-media evidence.

Python 3.10+, stdlib only. Generation never imports the evaluated Android algorithm.
Inputs, authored expected labels, and measured predictions live in separate directories.
"""
import argparse
from collections import Counter, defaultdict
from contextlib import closing
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import random
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
SEED = 20261012
BASE = 1791158400000  # 2026-10-05T00:00:00Z
SPLITS = ("train", "validation", "test")
DOMAINS = ("就业与教育", "性别与家庭", "科技与产品", "经济与消费", "健康与生活", "公共议题")
MEDIA = ("小红书图文", "小红书短视频", "抖音短视频", "快手短视频", "微博", "微信视频号", "微信公众号")
KINDS = ("new_evidence", "paraphrase", "conditions", "explanation", "action", "rebuttal", "same_source", "insufficient")
DIMENSIONS = ("fact", "evidence", "condition", "explanation", "action")
# Twelve topic families per domain. Families are assigned before concrete cases are expanded.
FAMILIES = [
    ["应届生需要结合岗位要求准备项目", "转岗者需要重新匹配已有经验", "求职者面临招聘回复缓慢", "研究生需要比较升学与就业成本",
     "实习生需要明确留用条件", "职业教育需要增加实际操作", "毕业生需要了解行业差异", "在职学习需要安排可持续的时间",
     "面试准备需要关注真实任务", "异地求职需要核算迁移成本", "初次就业需要核对劳动条件", "技能更新需要持续练习"],
    ["家庭照护需要共同分担", "女性需要保留独立经济安排", "伴侣需要讨论家务时间", "育儿决策需要考虑照护资源",
     "成年子女需要协调养老责任", "婚前需要讨论共同支出", "家庭成员需要尊重职业选择", "照护者需要获得休息支持",
     "异地家庭需要明确沟通安排", "夫妻需要协商搬迁决定", "单亲家庭需要可获得的支持", "代际分歧需要具体协商"],
    ["旧手机够用时可以推迟换新", "人工智能工具需要核对输出", "产品选购需要匹配实际任务", "软件订阅需要定期核算用途",
     "设备维修需要比较使用成本", "智能家居需要考虑维护难度", "学习工具需要减少无关通知", "算法推荐需要提供调整入口",
     "便携设备需要考虑续航条件", "云端备份需要核对恢复能力", "辅助工具需要支持不同使用者", "产品测评需要说明测试条件"],
    ["收入波动时需要保留应急储蓄", "租房支出需要结合稳定收入", "分期消费需要核算全部成本", "家庭预算需要预留意外支出",
     "促销购买需要先核对真实需求", "通勤方案需要比较时间成本", "大额购买需要比较替代方案", "合租需要提前明确费用分担",
     "教育消费需要核对服务内容", "搬家决定需要计算重复支出", "小额订阅需要定期整理", "维修支出需要比较长期使用"],
    ["连续加班后需要安排休息", "夜间工作者需要稳定作息安排", "运动安排需要适应个人能力", "健康信息需要核对适用人群",
     "照护压力需要及时寻求支持", "生活安排需要保留自由时间", "饮食计划需要考虑现实条件", "体检信息需要结合专业解释",
     "长期久坐需要安排活动间隔", "休息不足时需要调整任务量", "恢复期需要尊重个体差异", "睡前活动需要减少持续打扰"],
    ["公交调整需要听取通勤者意见", "社区改造需要保留无障碍通道", "公共服务需要回应不同工作时段", "信息公开需要说明统计范围",
     "环境议题需要比较长期记录", "公共讨论需要区分事实与推测", "社区活动需要考虑参与门槛", "服务评价需要保留少数意见",
     "城市更新需要了解原住居民需求", "公共设施需要持续维护", "意见征集需要反馈处理结果", "公共资源安排需要说明取舍"],
]
CONDITIONS = ["在岗位要求相近时", "在照护责任相近时", "在任务需求相近时", "在可支配收入相近时", "在个人处境相近时", "在服务需求相近时"]
CAUSES = ["等待回复时无法安排接下来的生活", "个人计划与照护责任反复冲突", "担心自己跟不上新工具的变化", "不确定能否负担下一笔必要支出", "连续任务挤占了休息时间", "担心自己的实际需求被忽略"]
MEASURES = ["完成岗位要求的核对", "完成双方协商的安排", "完成实际任务的试用", "完成必要支出的核算", "保留原先计划的休息", "收到针对具体意见的回复"]
STYLE = [
    ("这份记录讨论：{claim}。", "观察部分写道：{evidence}", "作者的解释是{explanation}；建议{action}。"),
    ("作者先提出“{claim}”。", "支撑这一说法的材料为：{evidence}", "对于原因，作者认为{explanation}。可采取的步骤是{action}。"),
    ("本段的判断为：{claim}。", "回查资料可见：{evidence}", "对此的理解是{explanation}，后续可以{action}。"),
    ("讨论从这一点开始：{claim}。", "文中保留了以下依据：{evidence}", "文末把原因表述为{explanation}，把应对方式表述为{action}。"),
]


def digest(value):
    return hashlib.sha256(value.encode("utf8")).hexdigest()


def identifier(kind, *parts):
    return "syn-" + kind + "-" + digest("|".join(map(str, (SEED,) + parts)))[:16]


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf8", newline="\n")


def write_rows(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def emotion_snippets(domain, case, mode):
    cause = CAUSES[domain]
    other_cause = "得到具体反馈后能重新安排下一步"
    if mode == 0:
        one = (f"我因为{cause}感到焦虑。", "焦虑", cause, "author", "affirmed", "直接自述")
    elif mode == 1:
        one = (f"朋友告诉我，他因{cause}觉得焦虑；我只是在转述。", "焦虑", cause, "quoted_person", "reported", "转述")
    elif mode == 2:
        one = (f"虽然{cause}，我现在并不焦虑。", "并不焦虑", cause, "author", "negated", "否定")
    else:
        one = (f"{other_cause}，我的心情逐渐平静。", "平静", other_cause, "author", "affirmed", "直接自述")
    two = (f"评论者说：“我因{other_cause}感到安心。”这不代表正文作者的感受。", "安心", other_cause,
           "commenter", "reported", "评论引述")
    return one, two


def build_works():
    works, versions, annotations, emotions, groups = [], [], [], [], []
    for domain in range(6):
        for group in range(140):
            split = SPLITS[0 if group < 98 else 1 if group < 119 else 2]
            family = group % 8 if split == "train" else 8 + (group - 98) % 2 if split == "validation" else 10 + (group - 119) % 2
            group_id = identifier("case", domain, group)
            family_id = identifier("family", domain, family)
            phrase = FAMILIES[domain][family]
            condition = CONDITIONS[domain]
            # Distinct fictional source cases; numbers are controlled parameters, not measurements.
            rng = random.Random(SEED + domain * 140 + group)
            n, count = rng.randint(35, 150), rng.randint(4, 19)
            origin_a, origin_b, origin_c = [identifier("source", domain, group, s) for s in range(3)]
            fact_a = f"虚构档案{origin_a}记录了{n}次尝试，其中{count}次{MEASURES[domain]}。"
            fact_b = f"独立的虚构访谈{origin_b}补充了另外{n + 11}次经历，其中{count + 5}次{MEASURES[domain]}。"
            fact_c = f"另一份虚构日志{origin_c}记录了{n + 23}次尝试，其中{count + 3}次{MEASURES[domain]}。"
            explanation_a = "安排与实际需求的匹配程度可能影响结果"
            explanation_b = "资源获得渠道的差异也可能影响结果"
            action_a, action_b = "先核对具体条件，再制定分步计划", "先联系相关人员获取反馈，再调整原先方案"
            members = []
            for member in range(10):
                kind = "anchor" if member < 2 else KINDS[member - 2]
                key = identifier("work", domain, group, member)
                author = identifier("author", domain, group, member % 3)
                ordinal = domain * 1400 + group * 10 + member
                media = MEDIA[ordinal % 7]
                observed = BASE + (group * 10 + member) * 600_000
                evidence = fact_b if member == 1 else fact_c if kind == "new_evidence" else fact_a
                origin = origin_b if member == 1 else origin_c if kind == "new_evidence" else origin_a
                current_condition = condition + "以及资源不足时" if kind == "conditions" else condition
                explanation = explanation_b if kind in ("explanation", "rebuttal") else explanation_a
                action = action_b if kind == "action" else action_a
                stance = "反对无条件采用" if kind == "rebuttal" else "支持有条件采用"
                quote = f"{current_condition}，{phrase}"
                if kind == "rebuttal":
                    quote = f"有人主张“{phrase}”，但我反对在这些条件下直接采用这一建议"
                if kind == "paraphrase":
                    quote = f"{current_condition}，可以把“{phrase}”理解为一项需要具体落实的安排"
                    if group % 2 == 0:  # multi-history joint coverage, no single history suffices
                        evidence = fact_a + fact_b
                if kind == "insufficient":
                    quote, evidence, explanation, action = "有人对此提出看法，但当前片段没有说明是谁、持何种立场", "", "", ""
                template = STYLE[family % len(STYLE)]
                claim_line = template[0].format(claim=quote)
                # Keep all argument evidence contiguous, permitting the existing strict codec.
                argument_evidence = evidence + (f"{explanation}；{action}。" if explanation else "")
                body = claim_line + "\n" + argument_evidence
                snippets = emotion_snippets(domain, group, (member + group) % 4)
                title = phrase + "：一段虚构观察"
                texts = [title + "\n" + claim_line, title + "\n" + body,
                         title + "\n" + body + "\n" + snippets[0][0] + "\n" + snippets[1][0]]
                version_ids = []
                for version, text in enumerate(texts, 1):
                    version_id = identifier("version", key, version)
                    available = observed + (version - 1) * 30_000 + (group % 3) * 1000
                    version_ids.append(version_id)
                    channels = [{"type": "visible_text", "text": text, "provenance": "synthetic_text"}]
                    if version >= 2:
                        channels.append({"type": "ocr_proxy" if media in (MEDIA[0], MEDIA[4], MEDIA[6]) else "asr_proxy", "text": evidence,
                                         "provenance": "authored_transcript_without_media", "work_id": key})
                    versions.append(dict(id=version_id, work_id=key, version=version, split=split,
                        observed_at=observed + (version - 1) * 30_000, available_at=available, text=text,
                        modalities=channels, media_files=[], coverage=["claim_prefix", "argument", "context_and_comment"][version - 1]))
                    annotations.append(dict(version_id=version_id, work_id=key, split=split,
                        quote=quote, topic=DOMAINS[domain], subject=phrase.split("需要")[0], dimension=phrase,
                        stance="" if kind == "insufficient" else stance, conditions=current_condition,
                        evidence=argument_evidence if version >= 2 else "", uncertain=kind == "insufficient" or version == 1,
                        source_origin=origin if version >= 2 and evidence else None,
                        source_origins=([origin_a, origin_b] if kind == "paraphrase" and group % 2 == 0 else [origin]) if version >= 2 and evidence else [],
                        relation="refutes_quoted_speaker" if kind == "rebuttal" else "author_claim",
                        quote_span=[text.index(quote), text.index(quote) + len(quote)],
                        evidence_span=[text.index(argument_evidence), text.index(argument_evidence) + len(argument_evidence)] if version >= 2 and argument_evidence else None))
                works.append(dict(id=key, split=split, domain=DOMAINS[domain], media_type=media, author_id=author,
                                  identity_group=group_id, near_duplicate_group=family_id, semantic_family=family_id,
                                  source_origin=origin, platform_native_id=None, version_ids=version_ids,
                                  synthetic=True, provenance="AI_authored_controlled_generator", authorization="not_applicable_fictional"))
                # One annotation per work, with the additional third chosen below, by partition.
                members.append(dict(work=works[-1], annotation=annotations[-1], kind=kind, snippets=snippets,
                                    full_text=texts[-1], observed=observed, ordinal=ordinal))
            groups.append(dict(id=group_id, split=split, domain=domain, group=group, members=members))
    # Select additional snippets by fixed within-partition order, never using any model result.
    additional = {"train": 1960, "validation": 420, "test": 420}
    for split in SPLITS:
        ordered = [m for g in groups if g["split"] == split for m in g["members"]]
        chosen = {m["work"]["id"] for m in sorted(ordered, key=lambda m: digest(m["work"]["id"]))[:additional[split]]}
        for member in ordered:
            for index, snippet in enumerate(member["snippets"][:2 if member["work"]["id"] in chosen else 1]):
                context, expression, cause, holder, attribution, style = snippet
                text = member["full_text"]
                context_start = text.index(context)
                expression_start = context_start + context.index(expression)
                cause_start = context_start + context.index(cause)
                emotions.append(dict(id=identifier("emotion", member["work"]["id"], index), work_id=member["work"]["id"],
                    version_id=member["work"]["version_ids"][2], split=split, context_span=[context_start, context_start + len(context)],
                    expression=expression, expression_span=[expression_start, expression_start + len(expression)],
                    cause=cause, cause_span=[cause_start, cause_start + len(cause)], holder=holder,
                    target=member["annotation"]["subject"], attribution=attribution, expression_style=style,
                    situation=CAUSES[DOMAINS.index(member["work"]["domain"])] if attribution != "negated" else "否定焦虑的当前陈述",
                    appraisal={name: {"applicable": expression == "焦虑" and present,
                                      "value": 3 if expression == "焦虑" and present else None}
                               for name, present in [("goal_obstruction", any(s in cause for s in ["无法", "冲突", "挤占"])),
                                                     ("threat_or_loss", "担心" in cause), ("uncertainty", "不确定" in cause),
                                                     ("control", False), ("coping_resources", False)]},
                    viewer_emotion=None, label_status="authored_unreviewed"))
    return works, versions, annotations, emotions, groups


def build_increments(groups, versions):
    inputs, labels = [], []
    version_index = {v["id"]: v for v in versions}
    for group in groups:
        anchors = group["members"][:2]
        for case, member in enumerate(group["members"][2:]):
            kind = KINDS[case]
            for variant in range(3 if (case + group["group"] + group["domain"]) % 2 == 0 else 2):
                current = member["work"]["version_ids"][1 if variant == 0 else 2]
                query_at = version_index[current]["available_at"] + 1000 + variant
                history = []
                # All histories are previously available versions, in the same isolated source group.
                union = kind == "paraphrase" and group["group"] % 2 == 0
                history_anchors = anchors[:1] if variant == 2 and not union else anchors
                for index, anchor in enumerate(history_anchors):
                    version = anchor["work"]["version_ids"][2]
                    history.append(dict(work_id=anchor["work"]["id"], version_id=version,
                                        observed_at=version_index[version]["observed_at"], available_at=version_index[version]["available_at"]))
                key = identifier("pair", member["work"]["id"], variant)
                inputs.append(dict(id=key, split=group["split"], current_version_id=current,
                                   query_at=query_at, history=history, identity_group=group["id"]))
                dims = {name: "repeated" for name in DIMENSIONS}
                if kind == "new_evidence":
                    dims.update(fact="new", evidence="new")
                elif kind == "conditions":
                    dims["condition"] = "new"
                elif kind in ("explanation", "rebuttal"):
                    dims["explanation"] = "new"
                elif kind == "action":
                    dims["action"] = "new"
                elif kind == "insufficient":
                    dims = {name: "unknown" for name in DIMENSIONS}
                scalar = {"new_evidence": "NEW", "paraphrase": "REPEATED", "conditions": "CONDITIONS",
                          "explanation": "NEW", "action": "NEW", "rebuttal": "FIRST", "same_source": "REPEATED", "insufficient": "UNKNOWN"}[kind]
                labels.append(dict(id=key, split=group["split"], stratum=kind, dimensions=dims,
                    expected_legacy_increment=scalar, same_source=None if kind == "insufficient" else kind != "new_evidence",
                    union_coverage=union,
                    relation="refutation" if kind == "rebuttal" else "insufficient" if kind == "insufficient" else "comparable",
                    supporting_history=[h["version_id"] for h in history], label_status="authored_unreviewed",
                    rationale="预设语义变化维度；标签在运行分析器之前冻结，不能按预测修订。"))
    return inputs, labels


def build_longitudinal(works, versions, emotions):
    profiles, days, contacts, feedback = [], [], [], []
    version_index = {v["id"]: v for v in versions}
    by_work = defaultdict(list)
    for row in emotions:
        by_work[row["work_id"]].append(row)
    anxious_work = {key: any(e["expression"] == "焦虑" and e["attribution"] in ("affirmed", "reported") for e in values)
                    for key, values in by_work.items()}
    candidate_cache = {}
    for arm in range(4):  # factorial: concentration and content anxiety vary independently
        for member in range(50):
            user = identifier("profile", arm, member)
            split = "train" if member < 35 else "validation" if member < (43 if arm < 2 else 42) else "test"
            concentration, anxiety = arm // 2 == 1, arm % 2 == 1
            pool = [w for w in works if w["split"] == split]
            primary = sorted({w["semantic_family"] for w in pool if w["domain"] == DOMAINS[0]})[member % (8 if split == "train" else 2)]
            for focus in (False, True):
                for anxious in (False, True):
                    cache_key = (split, primary, focus, anxious)
                    if cache_key not in candidate_cache:
                        candidate_cache[cache_key] = [w for w in pool if (w["semantic_family"] == primary) == focus and anxious_work[w["id"]] == anxious]
            profiles.append(dict(id=user, user_split=split, synthetic=True, participant=False,
                                 concentration_condition="high" if concentration else "low", anxiety_condition="high" if anxiety else "low"))
            for day in range(14):
                start = BASE + (day + 20) * 86_400_000 + 3_600_000
                missing = member % 10 == 0 and day == 5
                time_split = SPLITS[0 if day < 8 else 1 if day < 11 else 2]
                day_id = identifier("day", user, day)
                day_contacts = []
                if not missing:
                    rng = random.Random(int(digest(day_id)[:12], 16))
                    flags = [(i < (10 if concentration else 3), i < (9 if anxiety else 2)) for i in range(12)]
                    # Rotate anxiety independently to avoid confounding it with the primary topic.
                    flags = [(focus, flags[(i + day + member) % 12][1]) for i, (focus, _) in enumerate(flags)]
                    for slot, (focus, anxious) in enumerate(flags):
                        candidates = candidate_cache[(split, primary, focus, anxious)]
                        work = rng.choice(candidates)
                        at = start + slot * 3_600_000
                        duration = rng.randint(30, 150) * 1000
                        entry = dict(id=identifier("contact", user, day, slot), profile_id=user, day_id=day_id,
                                     work_id=work["id"], version_id=work["version_ids"][2], timestamp=at,
                                     visible_intervals=[[at, at + duration]], user_split=split, time_split=time_split)
                        assert version_index[entry["version_id"]]["available_at"] < at
                        contacts.append(entry)
                        day_contacts.append(entry)
                gaps = [[start, start + 86_400_000]] if missing else []
                if not missing:
                    cursor = start
                    for entry in day_contacts:
                        if cursor < entry["timestamp"]:
                            gaps.append([cursor, entry["timestamp"]])
                        cursor = entry["visible_intervals"][0][1]
                    gaps.append([cursor, start + 86_400_000])
                days.append(dict(id=day_id, profile_id=user, scheduled_day=day, user_split=split, time_split=time_split,
                                 status="missing" if missing else "partially_observed", start=start, end=start + 86_400_000,
                                 gaps=[dict(start=a, end=b, reason="simulation_missing_day" if missing else "not_observed") for a, b in gaps],
                                 contact_ids=[e["id"] for e in day_contacts], reading_purpose="比较信息并回顾依据"))
                if not missing:
                    feedback.append(dict(id=identifier("feedback", user, day), profile_id=user, day_id=day_id,
                        value="平静" if (member + day) % 2 == 0 else "有些担忧", source="simulated_self_report",
                        available_at=start + 12 * 3_600_000, user_protocol_training_allowed=split == "train",
                        time_protocol_training_allowed=day < 8, not_a_content_inference=True))
    return profiles, days, contacts, feedback


def build_traces():
    inputs, labels, safety_inputs, safety_labels = [], [], [], []
    names = ["gallery_pages", "caption_expand", "comments", "video_frames", "pause", "same_author_new_work",
             "same_view_different_work", "return_to_work", "app_return", "cross_hour", "long_sampling_gap", "lockscreen"]
    for platform in range(2):
        package = ("com.xingin.xhs", "com.ss.android.ugc.aweme")[platform]
        for repeat in range(10):
            for scenario, name in enumerate(names):
                key = identifier("trace", platform, repeat, scenario)
                native_a, native_b = str(100000000 + platform * 1000 + repeat * 2), str(100000001 + platform * 1000 + repeat * 2)
                title = "职业能力需要持续提升，具体准备需要结合岗位要求"
                steps = []
                expected = []
                for index in range(4):
                    work = "b" if name in ("same_author_new_work", "same_view_different_work") and index >= 2 or name == "return_to_work" and index == 2 else "a"
                    reset = name in ("app_return", "lockscreen") and index == 2
                    pause = name == "pause" and index == 2
                    elapsed = index * 2000 + (20000 if name == "long_sampling_gap" and index >= 2 else 0)
                    steps.append(dict(offset_ms=elapsed, text=f"观察片段{index}：{title}", native_id=native_b if work == "b" else native_a,
                                      title=title[:12] if name == "caption_expand" and index == 0 else title,
                                      truncated=name == "caption_expand" and index == 0, author="虚构作者", reset=reset, pause=pause,
                                      page="COMMENTS" if name == "comments" and index == 2 else "CAPTION" if name == "caption_expand" and index == 1 else "VIDEO",
                                      gallery_index=index + 1 if name == "gallery_pages" else 0,
                                      gallery_total=4 if name == "gallery_pages" else 0))
                    expected.append(dict(work=work, new_visit=index == 0 or reset or index > 0 and work != expected[index - 1]["work"]))
                expected_ms = 0
                for i in range(1, 4):
                    if not expected[i]["new_visit"] and not steps[i]["pause"] and steps[i]["offset_ms"] - steps[i - 1]["offset_ms"] <= 6000:
                        expected_ms += steps[i]["offset_ms"] - steps[i - 1]["offset_ms"]
                inputs.append(dict(id=key, platform=package, scenario=name, started_at=BASE + 3_596_000 if name == "cross_hour" else BASE + repeat * 10_000,
                                   steps=steps, synthetic=True))
                labels.append(dict(id=key, boundaries=expected, distinct_works=len({e["work"] for e in expected}),
                                   visit_count=sum(e["new_visit"] for e in expected), visible_ms=expected_ms, label_status="authored_boundary_fixture"))
    scenarios = ["consent_revoked", "secure_window", "wrong_app", "late_screenshot", "keyboard_visible",
                 "unrelated_window", "no_permission", "storage_full", "deletion", "valid_context"]
    for variant in range(12):
        for name in scenarios:
            key = identifier("safety", variant, name)
            request = dict(package="synthetic.app", window_id=100 + variant, work_id=identifier("safety-work", variant),
                           consent_epoch=variant, local_save_consent=True, screenshot_permission=True)
            callback = dict(request)
            if name == "consent_revoked":
                callback.update(local_save_consent=False, consent_epoch=variant + 1)
            if name == "wrong_app":
                callback["package"] = "synthetic.other"
            if name == "unrelated_window":
                callback["window_id"] += 1
            if name == "late_screenshot":
                callback["work_id"] = identifier("safety-next-work", variant)
            if name == "no_permission":
                callback["screenshot_permission"] = False
            safety_inputs.append(dict(id=key, scenario=name, generation=variant, synthetic=True,
                request_context=request, callback_context=callback, secure_window=name == "secure_window",
                keyboard_or_private_input=name == "keyboard_visible", work_exists=name != "deletion",
                free_bytes=1024 if name == "storage_full" else 100 * 1024 * 1024,
                upload_consent=variant % 2 == 0, operation="delete_record" if name == "deletion" else "save_local_thumbnail"))
            safety_labels.append(dict(id=key, expected="allow" if name == "valid_context" else "deny_or_remove",
                                      execution_status="not_executed_on_device"))
    return inputs, labels, safety_inputs, safety_labels


def tsv_export(folder, pairs, labels, versions, arguments):
    """Use existing real analyzer; no expected label is present in its structured input."""
    folder.mkdir(parents=True, exist_ok=True)
    vi, ai, li = ({r["id"]: r for r in versions}, {r["version_id"]: r for r in arguments}, {r["id"]: r for r in labels})
    input_header = ["sample_id", "sequence_id", "event_id", "observed_seconds", "visible_text", "quote", "topic", "subject", "dimension", "stance", "evidence", "conditions", "uncertain", "mode"]
    def cell(value):
        return str(value).replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
    with (folder / "inputs.tsv").open("w", encoding="utf8", newline="\n") as inputs, (folder / "labels.tsv").open("w", encoding="utf8", newline="\n") as gold:
        inputs.write("\t".join(input_header) + "\n")
        gold.write("sample_id\texpected_increment\tchallenge\trationale\n")
        for pair in pairs:
            if pair["split"] != "test":
                continue
            ids = [h["version_id"] for h in pair["history"]] + [pair["current_version_id"]]
            for index, version_id in enumerate(ids):
                a, v = ai[version_id], vi[version_id]
                current = index == len(ids) - 1
                sample_id = pair["id"] + (".target" if current else f".history{index}")
                row = [sample_id, pair["id"], index + 1, index * 600, v["text"], a["quote"], a["topic"], a["subject"], a["dimension"],
                       a["stance"], a["evidence"], a["conditions"], str(a["uncertain"]).lower(), "structured"]
                inputs.write("\t".join(map(cell, row)) + "\n")
                label = li[pair["id"]]
                expected = label["expected_legacy_increment"] if current else "FIRST" if index == 0 else "NEW"
                gold.write("\t".join(map(cell, [sample_id, expected, label["stratum"] if current else "context_only",
                                                  label["rationale"] if current else "仅提供历史上下文，不纳入目标项指标"])) + "\n")
    (folder / "windows.tsv").write_text("sequence_id\texpected_alert\tchallenge\trationale\n", encoding="utf8")
    (folder / "metadata.properties").write_text("id=research-controlled-v2\nkind=synthetic\nannotation_status=AI_authored_unreviewed\nannotation_version=2\nscope=downstream_history_supplied_fields_target_only\n", encoding="utf8")


def database(output, tables):
    path = output / "research-synthetic-v2.sqlite"
    # Research schema only, deliberately incompatible with zhiheng.db and the App.
    temporary = output / "research-synthetic-v2.building"
    if temporary.exists():
        temporary.unlink()
    with closing(sqlite3.connect(temporary)) as db, db:
        db.execute("PRAGMA user_version=1")
        db.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execute("INSERT INTO metadata VALUES ('kind','controlled_synthetic_research')")
        for name, rows in tables.items():
            db.execute(f"CREATE TABLE {name}(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            db.executemany(f"INSERT INTO {name} VALUES (?,?)", ((r.get("id", r.get("version_id")), json.dumps(r, ensure_ascii=False)) for r in rows))
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/research-synthetic-v2")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    marker = output / "manifest.json"
    if any(output.iterdir()) and (not marker.exists() or json.loads(marker.read_text(encoding="utf8")).get("dataset_id") != "research-controlled-v2"):
        raise SystemExit("Refusing to overwrite a directory not owned by this generator")
    works, versions, arguments, emotions, groups = build_works()
    pairs, pair_labels = build_increments(groups, versions)
    profiles, days, contacts, feedback = build_longitudinal(works, versions, emotions)
    traces, trace_labels, safety, safety_labels = build_traces()
    tables = dict(works=works, versions=versions, arguments=arguments, emotions=emotions, increments=pairs,
                  increment_labels=pair_labels, profiles=profiles, user_days=days, contacts=contacts, feedback=feedback,
                  traces=traces, trace_labels=trace_labels, safety=safety, safety_labels=safety_labels)
    labels = {"arguments", "emotions", "increment_labels", "profiles", "feedback", "trace_labels", "safety_labels"}
    for name, rows in tables.items():
        write_rows(output / ("labels" if name in labels else "inputs") / f"{name}.jsonl", rows)
    tsv_export(output / "legacy-test", pairs, pair_labels, versions, arguments)
    database(output, tables)
    manifest = dict(dataset_id="research-controlled-v2", seed=SEED, synthetic=True, real_participants=0,
        annotation_status="AI_authored_unreviewed", independent_human_annotators=0, model_runs=0,
        design="controlled_structured_semantics_and_temporal_simulation", counts={k: len(v) for k, v in tables.items()},
        platform_policy="User-approved seven combinations, expanded to 8400 works: 1200 per combination, 1400 per domain",
        semantic_families=72, shared_render_scaffolds=4, source_cases=840, observation_versions_per_work=3,
        independence_unit="source_case_or_semantic_family_not_each_expanded_row",
        limitations=["Shared generation scaffolds; synthetic-only generalization", "OCR/ASR are authored text proxies without image/audio files",
                     "Five-dimensional labels are not outputs of the existing scalar classifier", "Virtual profiles are not volunteers",
                     "Safety fixtures are specifications, not executed device tests", "No human agreement, psychological diagnosis or causal effect is measured"],
        history_policy="history.observed_at < query_at AND history.available_at < query_at; same split and source case",
        hashes={str(p.relative_to(output)).replace('\\','/'): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in sorted(output.rglob("*")) if p.is_file() and p.name != "manifest.json"},
        generator_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    write_json(marker, manifest)
    print(json.dumps(manifest["counts"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
