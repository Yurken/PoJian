"""Reproducible fictional fixtures, never collected participant data. Python stdlib only.

Writes an isolated Room database and JSON for developer inspection and regression tests.
No access to a device, production database, platform API, media or cloud service.
"""
import argparse
from collections import Counter
from contextlib import closing
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
ZONE = timezone(timedelta(hours=8))
ANCHOR = datetime(2026, 10, 12, 23, tzinfo=ZONE)
DOMAINS = ["就业与教育", "性别与家庭", "科技与产品", "经济与消费", "健康与生活", "公共议题"]
FORMATS = [
    ("小红书图文", "com.xingin.xhs"), ("小红书短视频", "com.xingin.xhs"),
    ("抖音短视频", "com.ss.android.ugc.aweme"), ("快手短视频", "com.smile.gifmaker"),
    ("微博", "com.sina.weibo"), ("微信视频号", "com.tencent.mm"), ("微信公众号", "com.tencent.mm"),
]
JOBS = ["应届生", "转岗者", "产品助理", "设计毕业生", "测试工程师", "文科毕业生", "运营专员", "返岗者"]
CAUSES = ["投递后迟迟没有回复", "面试被连续取消", "岗位要求反复变动", "等待录用时房租即将到期",
          "看到同伴陆续收到录用通知", "实习结束却没有留用名额", "求职准备挤占了休息时间", "存款只能支撑两个月"]
COUNTER = ["整理作品集后获得两次面试", "调整投递范围后收到回复", "通过公开招聘找到匹配岗位",
           "补充项目经历后进入复试", "把准备任务拆成每天的小步骤", "与同行讨论后修正了岗位预期"]
# Domain is a sampling stratum; it is never used as a viewpoint conclusion.
OTHER = [
    ("家庭分工", "家庭成员", "照护分工", "家务应该共同分担", "一次家庭周记记录了双方轮流接送和做饭", "担心照护责任影响工作"),
    ("产品选择", "手机使用者", "换机必要性", "手机够用时可以推迟换新", "一周使用记录显示旧手机仍能完成通勤和沟通任务", "担心落后于新品宣传"),
    ("消费安排", "租房者", "支出安排", "不稳定收入下需要保留应急储蓄", "一份虚构账本逐项列出了房租和交通支出", "担心下个月付不起房租"),
    ("休息安排", "夜间工作者", "休息条件", "连续加班后需要安排休息", "一份虚构作息日志记录了加班后减少的休息时间", "担心工作占满休息时间"),
    ("公共服务", "通勤居民", "反馈渠道", "公交调整应该听取通勤者意见", "虚构社区讨论分别记录了早班和晚班居民的路线需求", "担心末班车调整影响返家"),
]
# Entire evidence templates are reserved for one partition before expanding works.
# Shared viewpoint phrases are labels, not a license to split revisions of one source.
CORPUS_SCENES = [
    ["虚构校招记录里，第{case}位申请者复盘了简历筛选、笔试和面试三个环节，记录的实际回复与预期存在差距",
     "虚构转岗访谈关注作品集是否匹配职责；第{case}份访谈分别整理了招聘方反馈和当事人的调整计划",
     "虚构招聘会的第{case}页手记对照了现场咨询与后续通知，并把行业差异作为解释边界"],
    ["虚构家庭周记的第{case}次协商列出了接送、备餐和夜间照护，由两位家庭成员各自确认可以承担的时段",
     "虚构双职工访谈的第{case}份材料对照了通勤距离和工作班次，记录双方重新安排家务的过程",
     "虚构照护日志的第{case}周同时记录临时加班与孩子生病，讨论把紧急安排交给谁才可持续"],
    ["虚构耐用性记录的第{case}台旧设备仍可完成导航、沟通和扫码，使用者将电池维护纳入换新比较",
     "虚构产品访谈的第{case}位使用者把相机需求与存储空间分开评估，决定先整理文件再观察运行情况",
     "虚构出差手记的第{case}组任务同时测试续航与离线资料读取，作者据实际任务判断更换设备的必要性"],
    ["虚构租房账本的第{case}页分别记录固定房租、交通费与备用金，作者先保留基本生活开支再安排弹性消费",
     "虚构自由职业访谈的第{case}份材料显示收入到账日并不固定，当事人把必要支出与可推迟购买分开",
     "虚构家庭预算的第{case}轮讨论列出维修与搬迁两项不定期费用，并商量在收入变化时保留周转资金"],
    ["虚构轮班日记的第{case}次交班之后留出了完整休息段，作者比较了任务安排与休息被打断的原因",
     "虚构居家工作访谈的第{case}位叙述者设置了停止接单的时间，并记录减少熬夜之后的主观体验",
     "虚构值班日志的第{case}组记录区分临时任务与固定排班，团队讨论如何给连续加班的人补足休息机会"],
    ["虚构社区座谈的第{case}组居民分别来自早班和晚班岗位，讨论路线变更时希望同时保留不同出行时段的意见",
     "虚构公共服务问卷的第{case}份整理保留赞成与反对的理由，报告将换乘次数和步行距离分列说明",
     "虚构车站观察的第{case}次讨论关注雨天接驳和末班安排，组织者邀请受影响的通勤居民逐项回应"],
]


def millis(value):
    return int(value.timestamp() * 1000)


def dumps(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def material(number, domain, difficult, emotion_mode):
    """Annotations are authored alongside the text; they are not model evaluation results."""
    role = JOBS[number % len(JOBS)]
    if domain == 0:
        topic, subject, dimension, condition = "就业", "求职者", "求职难度", "同等岗位要求下"
        stance = "招聘困难" if difficult else "仍有机会"
        phrase = "当前招聘困难" if difficult else "匹配岗位仍有求职机会"
        evidence = (f"虚构求职日志中，一位{role}投递{12 + number % 19}份申请，只获得{number % 2}次面试"
                    if difficult else f"虚构求职日志中，一位{role}{COUNTER[number % len(COUNTER)]}")
        cause = CAUSES[number % len(CAUSES)]
        title = f"{role}的求职记录：{['等待回复', '调整投递', '复盘面试', '补充项目'][number % 4]}"
    else:
        topic, subject, dimension, phrase, evidence, cause = OTHER[domain - 1]
        condition, stance = "在文中记录的处境下", phrase
        title = ["照护与工作的安排", "这次没有更换手机", "房租之外的预算", "把休息排进日程", "末班车调整的讨论"][domain - 1]
    quote = f"{condition}，{phrase}。"
    evidence += "。"
    if emotion_mode == "self":
        feeling = f"我因为{cause}感到焦虑，但这只是我的处境，不能代表所有人。"
        holder, expression, polarity = "虚构叙述者", "焦虑", "affirmed"
    elif emotion_mode == "reported":
        feeling = f"朋友说：“我因为{cause}感到焦虑。”我记录的是朋友的表达。"
        holder, expression, polarity = "虚构朋友", "焦虑", "reported"
    elif emotion_mode == "negated":
        feeling = f"虽然{cause}，我现在并不焦虑，已经给自己安排了休息。"
        holder, expression, polarity = "虚构叙述者", "并不焦虑", "negated"
    else:
        feeling = "我把能完成的事逐项写下来，今天的心情比昨天平静。"
        holder, expression, polarity, cause = "虚构叙述者", "平静", "affirmed", "把能完成的事逐项写下来"
    secondary = "职业能力需要持续提升。" if domain == 0 and number % 5 == 0 else ""
    text = f"【AI合成】{title}\n{quote}{evidence}\n{feeling}" + (f"\n{secondary}" if secondary else "")
    claim = dict(quote=quote, topic=topic, subject=subject, dimension=dimension, stance=stance,
                 evidence=evidence, conditions=condition, uncertain=False, evidence_actors=[], evidence_origins=[])
    claims = [claim]
    if secondary:
        claims.append(dict(quote=secondary, topic=topic, subject=subject, dimension="能力准备", stance="持续提升能力",
                           evidence="", conditions="求职准备期间", uncertain=False, evidence_actors=[], evidence_origins=[]))
    span = lambda token: [text.index(token), text.index(token) + len(token)]
    emotion = dict(holder=holder, target=subject, expression=expression, expressionSpan=span(expression),
                   cause=cause, causeSpan=span(cause), attribution=polarity, context=feeling,
                   appraisal="不确定性" if expression == "焦虑" else "可控性", viewerState="not_inferred")
    return title, text, claims, emotion


def build_demo():
    works, events, semantics, samples, emotions = [], [], [], [], []
    event_id = 0
    for day in range(14):
        for slot in range(42):
            number = day * 42 + slot
            domain = slot % 6 if day < 7 else (0 if slot < 36 else 1 + (slot - 36 + day) % 5)
            difficult = (day + slot // 6) % 7 < 4 if day < 7 else (slot + day) % 9 != 0
            mode = ("self" if (number % 3 != 0 if day >= 7 and domain == 0 else number % 9 == 0)
                    else ["calm", "negated", "reported"][number % 3])
            # Keep platform exposure identical across weeks; the observed content changes.
            media, package = FORMATS[slot % 7]
            title, text, claims, emotion = material(number, domain, difficult, mode)
            start = millis(ANCHOR.replace(hour=9) - timedelta(days=13 - day) + timedelta(minutes=slot * 17))
            key = f"synthetic:demo:{number:04d}"
            works.append(dict(workKey=key, packageName=package, title=title, author=f"虚构作者{number // 3:03d}",
                              identityMethod="synthetic_fixture", firstSeenAt=start,
                              identityEvidence="synthetic-authored-boundary", nativeId=""))
            emotions.append(dict(workKey=key, timestamp=start, mediaType=media, domain=DOMAINS[domain], **emotion))
            for visit in range(2 if slot % 7 == 0 else 1):
                event_id += 1
                begin = start + visit * 240_000
                duration = (45 + number % 90) * 1000
                finish = begin + duration
                event = dict(id=event_id, packageName=package, timestamp=begin, eventType=32,
                             visibleText=text, endTimestamp=finish, durationMillis=duration, sampleCount=3,
                             sourceType="accessibility", audioSessionId=None, mediaStartMillis=0, mediaEndMillis=0,
                             workKey=key, identityMethod="synthetic_fixture",
                             visibilityIntervals=f"v1;{begin},{finish},{duration},m", parentEventId=None)
                events.append(event)
                semantics.append(dict(eventId=event_id, inputText=text, analyzedAt=finish + 1000,
                                      inputKind="synthetic_annotation", claims=claims))
                for version, observed in enumerate([text.split("\n")[0], text.split("\n")[1], text]):
                    samples.append(dict(id=len(samples) + 1, visitId=event_id, capturedAt=begin + duration * version // 2,
                                        eventType=32, visibleText=observed))
                if visit == 0 and slot % 6 == 0:
                    parent = event_id
                    event_id += 1
                    # OCR is attached material, not another visited work or duration.
                    events.append(dict(event, id=event_id, sourceType="screen_ocr", parentEventId=parent,
                                       visibleText=claims[0]["quote"], durationMillis=0, sampleCount=0,
                                       visibilityIntervals=None, endTimestamp=begin))
                    semantics.append(dict(eventId=event_id, inputText=claims[0]["quote"], analyzedAt=finish + 1000,
                                          inputKind="synthetic_annotation", claims=[dict(claims[0], evidence="")]))
    names = {package: name for name, package in [("小红书", "com.xingin.xhs"), ("抖音", "com.ss.android.ugc.aweme"),
              ("快手", "com.smile.gifmaker"), ("微博", "com.sina.weibo"), ("微信", "com.tencent.mm")]}
    return dict(formatVersion=1, synthetic=True, source="AI-authored deterministic templates",
                annotationSource="synthetic-authored; not cloud inference or human ground truth",
                now=millis(ANCHOR), timezone="Asia/Shanghai", appNames=names,
                works=works, events=events, semantics=semantics, samples=samples, emotions=emotions,
                missingIntervals=[dict(start=millis(ANCHOR.replace(hour=22) - timedelta(days=d + 1)),
                                       end=millis(ANCHOR.replace(hour=9) - timedelta(days=d)), reason="not_observed") for d in range(13)],
                media=[], participantCount=0, consent="not_applicable_synthetic_no_personal_data")


def write_database(path, demo):
    # Never accept a production DB as an output, even when the caller passes it explicitly.
    if path.name != "pojian-synthetic-demo.db":
        raise ValueError("Only the reserved synthetic database filename is allowed")
    if path.exists():
        try:
            with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as previous:
                total, foreign = previous.execute("""SELECT COUNT(*), SUM(CASE WHEN workKey NOT LIKE
                    'synthetic:demo:%' THEN 1 ELSE 0 END) FROM observed_works""").fetchone()
                if not total or foreign:
                    raise ValueError("Existing file is not this generator's synthetic database")
        except sqlite3.DatabaseError as error:
            raise ValueError("Refusing to replace an unrecognized existing database") from error
    schema = json.loads((ROOT / "app/schemas/com.emerge.app.data.BrowseDatabase/8.json").read_text(encoding="utf-8"))["database"]
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".building")
    if temporary.exists():
        temporary.unlink()
    try:
        with closing(sqlite3.connect(temporary)) as db, db:
            db.execute("PRAGMA foreign_keys=ON")
            for entity in schema["entities"]:
                db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
                for index in entity["indices"]:
                    db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for query in schema["setupQueries"]:
                db.execute(query)
            db.execute("PRAGMA user_version=8")
            for table, rows in [("observed_works", demo["works"]), ("browse_events", demo["events"]),
                                ("browse_samples", demo["samples"])]:
                for row in rows:
                    keys = list(row)
                    db.execute(f"INSERT INTO {table} ({','.join(keys)}) VALUES ({','.join('?' for _ in keys)})", list(row.values()))
            for row in demo["semantics"]:
                db.execute("INSERT INTO browse_semantics VALUES (?,?,?,?,?)", (row["eventId"], row["inputText"],
                           row["analyzedAt"], dumps(row["claims"]), row["inputKind"]))
            assert not db.execute("PRAGMA foreign_key_check").fetchall()
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def research_corpus(output):
    """6k template examples / 18k partial versions. No fabricated volunteer study."""
    counts = Counter()
    with (output / "works.jsonl").open("w", encoding="utf-8", newline="\n") as works, \
         (output / "observations.jsonl").open("w", encoding="utf-8", newline="\n") as versions:
        for domain in range(6):
            for group in range(100):
                split = "train" if group < 70 else "validation" if group < 85 else "test"
                author_id = f"synthetic:{domain}:{group:03d}"
                family = f"synthetic:template:{domain}:{split}"
                for member in range(10):
                    number = domain * 1000 + group * 10 + member
                    media, package = FORMATS[number % 3]
                    title, text, claims, emotion = material(number, domain, member < 8,
                                                            ["self", "reported", "negated", "calm"][member % 4])
                    # Distinct evidence families across partitions, expanded only after assignment.
                    scene = CORPUS_SCENES[domain][["train", "validation", "test"].index(split)].format(case=group * 10 + member + 1) + "。"
                    # A job claim also needs its direction-specific evidence retained.
                    if domain == 0:
                        scene += ("申请者至今没有收到面试邀请。" if member < 8 else "申请者收到了匹配岗位的面试邀请。")
                    claims[0]["evidence"] = scene
                    text = f"【AI合成】{title}\n{claims[0]['quote']}{scene}\n{emotion['context']}"
                    if len(claims) > 1:
                        text += "\n" + claims[1]["quote"]
                    for field in ["expression", "cause"]:
                        begin = text.index(emotion[field])
                        emotion[field + "Span"] = [begin, begin + len(emotion[field])]
                    key = f"synthetic:corpus:{number:05d}"
                    at = millis(ANCHOR - timedelta(days=30) + timedelta(minutes=number * 3))
                    row = dict(workKey=key, synthetic=True, split=split, authorId=author_id,
                               nearDuplicateGroup=family, templateFamily=family, domain=DOMAINS[domain], mediaType=media,
                               packageName=package, platformNativeId=None, title=title, text=text,
                               claims=claims, emotion=emotion, media=[], consent="not_applicable_synthetic")
                    works.write(dumps(row) + "\n")
                    for version in range(3):
                        visible = "\n".join(text.split("\n")[:version + 1]) if version < 2 else text
                        versions.write(dumps(dict(workKey=key, split=split, version=version + 1,
                            observedAt=at + version * 30_000, availableAt=at + version * 30_000,
                            text=visible, coverage=["title", "argument", "retained_text"][version],
                            evidenceIndices=[dict(start=visible.index(c["quote"]), end=visible.index(c["quote"]) + len(c["quote"]))
                                             for c in claims if c["quote"] in visible], synthetic=True)) + "\n")
                    counts[split] += 1
    return dict(works=6000, observations=18000, split=dict(counts), domainQuota=1000,
                mediaQuota={name: 2000 for name, _ in FORMATS[:3]},
                participants=0, templateFamilies=18,
                limitation="Only 18 authored evidence families expanded to 6,000 fictional entries. Not 6,000 independently collected works or a validated generalization benchmark; no measured accuracy.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/synthetic-data", help="Generated output directory inside app/build")
    parser.add_argument("--write-test-fixture", action="store_true", help="Update the synthetic JVM test fixture; never packaged in the App")
    parser.add_argument("--research", action="store_true", help="Also generate 6,000 fictional works / 18,000 observations")
    args = parser.parse_args()
    args.output = args.output.resolve()
    if not args.output.is_relative_to((ROOT / "app/build").resolve()):
        parser.error("--output must be inside app/build")
    args.output.mkdir(parents=True, exist_ok=True)
    demo = build_demo()
    payload = dumps(demo) + "\n"
    (args.output / "demo.json").write_text(payload, encoding="utf-8", newline="\n")
    write_database(args.output / "pojian-synthetic-demo.db", demo)
    if args.write_test_fixture:
        fixture = ROOT / "app/src/test/resources/synthetic/demo-v1.json"
        fixture.parent.mkdir(parents=True, exist_ok=True)
        fixture.write_text(payload, encoding="utf-8", newline="\n")
    manifest = dict(synthetic=True, generatedBy="AI-authored deterministic templates v1", realParticipants=0,
                    timezone="Asia/Shanghai", simulatedNow=ANCHOR.isoformat(),
                    demo={k: len(demo[k]) for k in ["works", "events", "samples", "semantics", "emotions"]},
                    sha256=hashlib.sha256(payload.encode()).hexdigest(),
                    media="none; no synthetic image presented as an original cover",
                    quotaDecision="Research follows original LaTeX: 3 media combinations x 2,000. Demo includes all 7 requested combinations.",
                    exclusions=["No real participant study", "No 12,000 incremental gold labels or 8,000 emotion gold labels claimed",
                                "No fabricated 240 human operation traces or 120 executed safety tests"],
                    scenario="First 7 days broad exposure; last 7 days concentrated employment content with attributed anxiety and counterexamples.")
    if args.research:
        manifest["research"] = research_corpus(args.output)
    (args.output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
