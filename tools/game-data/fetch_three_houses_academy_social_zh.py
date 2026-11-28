#!/usr/bin/env python3
"""Fetch and verify Fire Emblem: Three Houses academy Q&A, advice box, lost items, and tea party guides (Simplified Chinese + DLC).

Sources:
- Fedatamine (https://houses.fedatamine.com/zh-cn/ & /en-us/):
  Official Simplified Chinese classroom lecture questions/reactions, advice box Q&A,
  lost items & clues, favorite teas, interested & blush tea topics, and 4th-round dialogue lines.
- Serenes Forest (https://serenesforest.net/three-houses/monastery/tea-party/):
  Valid 4th-round Tea Party reaction answers (including Constance's Type 1 indoor / Type 2 outdoor split)
  and cross-check counts for favorite teas & interested topics.
"""

from __future__ import annotations

import json
import re
import threading
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import requests
from bs4 import BeautifulSoup, Tag

REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_PATH = (
    REPO_ROOT
    / "data"
    / "games"
    / "three-houses"
    / "sources"
    / "extracts"
    / "three-houses-academy-social-zh.json"
)

USER_AGENT = "FireEmblemCompanionDataBuilder/1.0 (+offline-companion-app)"

# Official Simplified Chinese Tea Party final-comment reactions
TEA_REACTION_ZH: dict[str, str] = {
    "nod": "点头",
    "disagree": "没那种事",
    "admonish": "责备",
    "commend": "表示佩服",
    "praise": "加油打气",
    "chat": "说出自己的想法",
    "laugh": "露出笑容",
    "blush": "害羞",
    "sip tea": "喝茶",
    "sigh": "沮丧",
}

# All 40 characters inviteable to Tea Party (39 playable non-Byleth + Rhea)
# Tuple: (fedatamine_id, fedatamine_slug, serenes_slug, short_zh, db_title_zh, affiliation_tag, is_dlc_or_patch)
CHARACTER_SPECS: list[tuple[int, str, str, str, str, str, bool]] = [
    (2, "Edelgard", "edelgard", "艾黛尔贾特", "艾黛尔贾特·冯·弗雷斯贝尔古", "黑鹫学级", False),
    (3, "Dimitri", "dimitri", "帝弥托利", "帝弥托利·亚历山大·布雷达德", "青狮子学级", False),
    (4, "Claude", "claude", "库罗德", "库罗德·冯·里刚", "金鹿学级", False),
    (5, "Hubert", "hubert", "修伯特", "修伯特·冯·贝斯特拉", "黑鹫学级", False),
    (6, "Ferdinand", "ferdinand", "菲尔迪南特", "菲尔迪南特·冯·艾吉尔", "黑鹫学级", False),
    (7, "Linhardt", "linhardt", "林哈尔特", "林哈尔特·冯·海弗林格", "黑鹫学级", False),
    (8, "Caspar", "caspar", "卡斯帕尔", "卡斯帕尔·冯·贝尔谷里斯", "黑鹫学级", False),
    (9, "Bernadetta", "bernadetta", "贝尔娜提塔", "贝尔娜提塔·冯·瓦立", "黑鹫学级", False),
    (10, "Dorothea", "dorothea", "多洛缇雅", "多洛缇雅·雅尔诺尔德", "黑鹫学级", False),
    (11, "Petra", "petra", "佩托拉", "佩托拉·马克聂阿利", "黑鹫学级", False),
    (12, "Dedue", "dedue", "杜笃", "杜笃·莫利纳洛", "青狮子学级", False),
    (13, "Felix", "felix", "菲力克斯", "菲力克斯·尤果·伏拉鲁达力乌斯", "青狮子学级", False),
    (14, "Ashe", "ashe", "亚修", "亚修·多蓝", "青狮子学级", False),
    (15, "Sylvain", "sylvain", "希尔凡", "希尔凡·乔瑟·戈迪耶", "青狮子学级", False),
    (16, "Mercedes", "mercedes", "梅尔赛德司", "梅尔赛德司·冯·马尔特利兹", "青狮子学级", False),
    (17, "Annette", "annette", "雅妮特", "雅妮特·方提努·多米尼克", "青狮子学级", False),
    (18, "Ingrid", "ingrid", "英谷莉特", "英谷莉特·布兰多尔·贾拉提雅", "青狮子学级", False),
    (19, "Lorenz", "lorenz", "洛廉兹", "洛廉兹·海尔曼·古罗斯塔尔", "金鹿学级", False),
    (20, "Raphael", "raphael", "拉斐尔", "拉斐尔·克里斯汀", "金鹿学级", False),
    (21, "Ignatz", "ignatz", "伊古纳兹", "伊古纳兹·维克特", "金鹿学级", False),
    (22, "Lysithea", "lysithea", "莉丝缇亚", "莉丝缇亚·冯·科迪利亚", "金鹿学级", False),
    (23, "Marianne", "marianne", "玛莉安奴", "玛莉安奴·冯·艾德蒙", "金鹿学级", False),
    (24, "Hilda", "hilda", "希尔妲", "希尔妲·凡伦汀·哥纳利尔", "金鹿学级", False),
    (25, "Leonie", "leonie", "雷欧妮", "雷欧妮·皮聂利", "金鹿学级", False),
    (26, "Seteth", "seteth", "西提司", "西提司", "赛罗司圣教会", False),
    (27, "Flayn", "flayn", "芙莲", "芙莲", "赛罗司圣教会", False),
    (28, "Hanneman", "hanneman", "汉尼曼", "汉尼曼·冯·艾萨", "赛罗司圣教会", False),
    (29, "Manuela", "manuela", "玛努艾拉", "玛努艾拉·卡扎古蓝达", "赛罗司圣教会", False),
    (30, "Gilbert", "gilbert", "吉尔伯特", "吉尔伯特·普罗尼斯拉夫", "赛罗司骑士团", False),
    (31, "Alois", "alois", "阿罗伊斯", "阿罗伊斯·兰贾尔特", "赛罗司骑士团", False),
    (32, "Catherine", "catherine", "卡多莉奴", "卡多莉奴", "赛罗司骑士团", False),
    (33, "Shamir", "shamir", "萨米亚", "萨米亚·聂芙兰特", "赛罗司骑士团", False),
    (34, "Cyril", "cyril", "锥里尔", "锥里尔", "赛罗司圣教会", False),
    (36, "Rhea", "rhea", "蕾雅", "蕾雅", "赛罗司圣教会", True),
    (1040, "Yuri", "yuri", "尤里斯", "尤里斯·路克雷", "灰狼学级（DLC）", True),
    (1041, "Balthus", "balthus", "巴鲁塔札尔", "巴鲁塔札尔·冯·亚达尔布雷希特", "灰狼学级（DLC）", True),
    (1042, "Constance", "constance", "康丝坦洁", "康丝坦洁·冯·努维尔", "灰狼学级（DLC）", True),
    (1043, "Hapi", "hapi", "哈琵", "哈琵", "灰狼学级（DLC）", True),
    (1045, "Jeritza", "jeritza", "伊艾里扎", "伊艾里扎·冯·弗琉慕", "帝国（免费更新）", True),
    (1046, "Anna", "anna", "安娜", "安娜", "阿比斯（DLC）", True),
]


def clean_ws(text: str) -> str:
    text = text.replace("\u205f", " ").replace("\xa0", " ").replace("\u200b", "")
    return re.sub(r"\s+", " ", text).strip()


def norm_en_for_match(text: str) -> str:
    nfkd = unicodedata.normalize("NFKD", text)
    ascii_ish = "".join(ch for ch in nfkd if not unicodedata.combining(ch))
    s = clean_ws(ascii_ish).lower()
    s = (
        s.replace("’", "'")
        .replace("‘", "'")
        .replace("“", '"')
        .replace("”", '"')
        .replace("…", "...")
        .replace("—", "-")
        .replace("–", "-")
    )
    # Remove spaces around ellipses and punctuation differences
    s = re.sub(r"\s*\.\.\.\s*", "...", s)
    s = re.sub(r"[^a-z0-9'\s]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


_HOST_LOCKS: dict[str, threading.Lock] = {}
_HOST_LAST_CALL: dict[str, float] = {}
_THREAD_LOCAL = threading.local()


def _session() -> requests.Session:
    sess = getattr(_THREAD_LOCAL, "session", None)
    if sess is None:
        sess = requests.Session()
        sess.headers.update({"User-Agent": USER_AGENT})
        _THREAD_LOCAL.session = sess
    return sess


def http_get(
    url: str,
    retries: int = 5,
    timeout: int = 25,
    min_interval: float = 0.0,
) -> str:
    host = urlparse(url).netloc
    lock = _HOST_LOCKS.setdefault(host, threading.Lock())
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            if min_interval > 0:
                with lock:
                    now = time.monotonic()
                    wait = min_interval - (now - _HOST_LAST_CALL.get(host, 0.0))
                    if wait > 0:
                        time.sleep(wait)
                    _HOST_LAST_CALL[host] = time.monotonic()
            resp = _session().get(url, timeout=timeout)
            if resp.status_code == 429 or resp.status_code >= 500:
                retry_after = resp.headers.get("Retry-After")
                delay = (
                    min(15.0, float(retry_after) + 1.0)
                    if retry_after and retry_after.isdigit()
                    else min(15.0, 2.5 * (2**attempt))
                )
                last_err = requests.HTTPError(f"{resp.status_code} for {url}", response=resp)
                if attempt + 1 < retries:
                    time.sleep(delay)
                    continue
            resp.raise_for_status()
            resp.encoding = resp.apparent_encoding or "utf-8"
            return resp.text
        except Exception as exc:  # noqa: BLE001
            last_err = exc
            if attempt + 1 < retries:
                time.sleep(min(15.0, 1.5 * (2**attempt)))
    raise RuntimeError(f"Failed to fetch {url}: {last_err}")


def portrait_key(src: str) -> str:
    """Normalize portrait asset URL path e.g. '/3h/face_school/30/0.png' -> '/3h/face_school/30/'."""
    parsed = urlparse(src)
    path = parsed.path
    # Strip trailing '/0.png' or filename to match expression variants if any
    if "/" in path:
        return path.rsplit("/", 1)[0] + "/"
    return path


def active_pane(soup: BeautifulSoup) -> Tag:
    panes = soup.select("div.tab-pane.active")
    if panes:
        return panes[-1]
    main = soup.select_one("main")
    return main or soup.body


def parse_classroom(
    soup: BeautifulSoup,
    asker_cid: int,
    portrait_map: dict[str, tuple[int, str]],
) -> tuple[list[dict[str, Any]], list[str]]:
    pane = active_pane(soup)
    cols = pane.select("div.col-md-6")
    target: Tag | None = None
    for col in cols:
        h2 = col.find("h2")
        if h2 and "问答" in h2.get_text():
            target = col
            break
    if target is None:
        return [], []

    questions: list[dict[str, Any]] = []
    unresolved_portraits: list[str] = []
    current_part = "第一部"
    pending_companions: list[str] = []

    children = [c for c in target.children if isinstance(c, Tag) or (isinstance(c, str) and c.strip())]
    for node in children:
        if not isinstance(node, Tag):
            continue
        if node.name == "h3":
            txt = clean_ws(node.get_text())
            if txt in ("第一部", "第二部"):
                current_part = txt
                pending_companions = []
        elif node.name == "a" and "/characters/" in (node.get("href") or ""):
            comp_name = clean_ws(node.get_text())
            if comp_name:
                pending_companions.append(comp_name)
        elif node.name == "ul":
            q_li = node.find("li", recursive=False)
            opt_ul = node.find("ul", recursive=False)
            if not q_li or not opt_ul:
                # Some pages put options ul as immediate sibling, check if so
                continue
            q_text = clean_ws(q_li.get_text(" ", strip=True))
            if not q_text:
                continue
            options: list[dict[str, Any]] = []
            for opt_li in opt_ul.find_all("li", recursive=False):
                # Extract option label before opinion divs
                text_parts: list[str] = []
                for child in opt_li.children:
                    if isinstance(child, Tag) and "opinion" in (child.get("class") or []):
                        continue
                    if isinstance(child, Tag):
                        text_parts.append(child.get_text(" ", strip=True))
                    else:
                        text_parts.append(str(child))
                opt_text = clean_ws(" ".join(text_parts))
                reactions: list[dict[str, str]] = []
                asker_likes = False
                for op in opt_li.select("div.opinion"):
                    img = op.select_one("img")
                    span = op.select_one("span.opinion-text")
                    src = img.get("src", "") if img else ""
                    pkey = portrait_key(src) if src else ""
                    if pkey not in portrait_map:
                        unresolved_portraits.append(src)
                        actor_cid, actor_name = -1, f"未知角色({src})"
                    else:
                        actor_cid, actor_name = portrait_map[pkey]
                    sentiment_raw = clean_ws(span.get_text()) if span else ""
                    is_like = "like" in (span.get("class") or []) if span else ("like" in sentiment_raw.lower() and "dislike" not in sentiment_raw.lower())
                    sentiment = "Like" if is_like else "Dislike"
                    sentiment_zh = "喜欢" if is_like else "不喜欢"
                    if actor_cid == asker_cid and is_like:
                        asker_likes = True
                    reactions.append({
                        "actor": actor_name,
                        "sentiment": sentiment,
                        "sentiment_zh": sentiment_zh,
                    })
                options.append({
                    "text_zh": opt_text,
                    "recommended": asker_likes,
                    "asker_likes": asker_likes,
                    "reactions": reactions,
                })
            questions.append({
                "part": current_part,
                "companions": list(pending_companions),
                "question_zh": q_text,
                "options": options,
            })
            pending_companions = []

    return questions, unresolved_portraits


def parse_advice(soup: BeautifulSoup) -> list[dict[str, Any]]:
    pane = active_pane(soup)
    questions: list[dict[str, Any]] = []
    current_part = "第一部"
    for tag in pane.find_all(["h3", "ul"], recursive=False):
        if tag.name == "h3":
            txt = clean_ws(tag.get_text())
            if txt:
                current_part = txt
        elif tag.name == "ul":
            pending_q: str | None = None
            for child in tag.children:
                if not isinstance(child, Tag):
                    continue
                if child.name == "li":
                    pending_q = clean_ws(child.get_text(" ", strip=True))
                elif child.name == "ul" and pending_q:
                    opts: list[dict[str, Any]] = []
                    for opt_li in child.find_all("li", recursive=False):
                        is_best = "correct-answer" in (opt_li.get("class") or [])
                        opts.append({
                            "text_zh": clean_ws(opt_li.get_text(" ", strip=True)),
                            "is_best": is_best,
                        })
                    questions.append({
                        "part": current_part,
                        "question_zh": pending_q,
                        "options": opts,
                    })
                    pending_q = None
    return questions


def parse_lost_items(soup: BeautifulSoup) -> list[dict[str, str]]:
    pane = active_pane(soup)
    for col in pane.select("div.col-md-6"):
        h2 = col.find("h2")
        if h2 and "失物" in h2.get_text():
            h3 = col.find("h3", string=lambda t: t and "失物" in t)
            if not h3:
                continue
            ul = h3.find_next_sibling("ul")
            if not ul:
                continue
            items: list[dict[str, str]] = []
            for li in ul.find_all("li", recursive=False):
                a = li.find("a")
                italic = li.find("i")
                name_zh = clean_ws(a.get_text()) if a else ""
                clue_zh = clean_ws(italic.get_text(" ", strip=True)) if italic else clean_ws(li.get_text(" ", strip=True))
                if name_zh:
                    items.append({"name_zh": name_zh, "clue_zh": clue_zh})
            return items
    return []


def parse_fedatamine_tea(soup: BeautifulSoup, is_zh: bool) -> dict[str, Any]:
    pane = active_pane(soup)
    fav_label = "我的最爱" if is_zh else "Favorite"
    talk_label = "对话" if is_zh else "Talk"
    likes_label = "喜欢的东西" if is_zh else "Likes"
    dislikes_label = "讨厌的东西" if is_zh else "Dislikes"

    favorites: list[str] = []
    fav_h3 = pane.find("h3", string=lambda t: t and clean_ws(t) == fav_label)
    if fav_h3:
        ul = fav_h3.find_next_sibling("ul")
        if ul:
            favorites = [clean_ws(li.get_text(" ", strip=True)) for li in ul.find_all("li", recursive=False) if clean_ws(li.get_text())]

    talks: list[str] = []
    talk_h5 = pane.find("h5", string=lambda t: t and clean_ws(t) == talk_label)
    if talk_h5:
        sib = talk_h5.find_next_sibling()
        while sib is not None:
            if sib.name in ("h3", "h4", "h5"):
                break
            if sib.name == "ul":
                for li in sib.find_all("li", recursive=False):
                    parts: list[str] = []
                    for child in li.children:
                        if isinstance(child, Tag) and "listen" in (child.get("class") or []):
                            continue
                        parts.append(child.get_text(" ", strip=True) if isinstance(child, Tag) else str(child))
                    txt = clean_ws(" ".join(parts))
                    if txt:
                        talks.append(txt)
            sib = sib.find_next_sibling()

    interested_topics: list[str] = []
    likes_h3 = pane.find("h3", string=lambda t: t and clean_ws(t) == likes_label)
    if likes_h3:
        ul = likes_h3.find_next_sibling("ul")
        if ul:
            interested_topics = [clean_ws(li.get_text(" ", strip=True)) for li in ul.find_all("li", recursive=False) if clean_ws(li.get_text())]

    blush_liked: list[str] = []
    blush_disliked: list[str] = []
    blush_h3 = pane.find("h3", string=lambda t: t and "blush" in clean_ws(t).lower())
    if blush_h3:
        sib = blush_h3.find_next_sibling()
        while sib is not None:
            if sib.name in ("h2", "h3"):
                break
            if sib.name == "h5":
                lbl = clean_ws(sib.get_text())
                next_ul = sib.find_next_sibling("ul")
                vals = [clean_ws(li.get_text(" ", strip=True)) for li in next_ul.find_all("li", recursive=False) if clean_ws(li.get_text())] if next_ul else []
                if lbl == likes_label:
                    blush_liked = vals
                elif lbl == dislikes_label:
                    blush_disliked = vals
            sib = sib.find_next_sibling()

    return {
        "favorites": favorites,
        "talks": talks,
        "interested_topics": interested_topics,
        "blush_liked_topics": blush_liked,
        "blush_disliked_topics": blush_disliked,
    }


def parse_samurai_tea(soup: BeautifulSoup, slug: str) -> list[dict[str, Any]]:
    final_tables: list[tuple[str | None, Tag]] = []
    for table in soup.select("table"):
        first_tr = table.find("tr")
        if not first_tr:
            continue
        header_txt = clean_ws(first_tr.get_text(" ", strip=True))
        low = header_txt.lower()
        if "possible final conversation" not in low:
            continue
        if "indoors" in low:
            variant: str | None = "室内（日光外）"
        elif "outdoors" in low:
            variant = "室外（日光下）"
        else:
            variant = None
        final_tables.append((variant, table))

    if not final_tables:
        raise ValueError(f"{slug}: No 'Possible Final Conversation' table found on Samurai Gamers page")
    if slug == "Constance" and len(final_tables) < 2:
        raise ValueError(f"Constance: expected Indoors & Outdoors tables, got {len(final_tables)}")

    rows_out: list[dict[str, Any]] = []
    for variant, table in final_tables:
        trs = table.select("tr")[1:]  # skip header row
        idx = 0
        while idx < len(trs):
            quote_raw = clean_ws(trs[idx].get_text(" ", strip=True))
            # Skip any stray blank rows
            if not quote_raw:
                idx += 1
                continue
            # Defensive: strip leading/trailing quotation marks
            quote_clean = quote_raw.strip("“”\"' ")
            if idx + 1 >= len(trs):
                raise ValueError(f"{slug}: Missing answers row after quote {quote_clean!r}")
            ans_tr = trs[idx + 1]
            valid_en: list[str] = []
            valid_zh: list[str] = []
            for cell in ans_tr.find_all(["td", "th"], recursive=False):
                cell_txt = clean_ws(cell.get_text(" ", strip=True))
                if not cell_txt or cell_txt in ("–", "-", "—"):
                    continue
                cell_txt = re.sub(r"^answer\s*:\s*", "", cell_txt, flags=re.IGNORECASE).strip()
                for part in re.split(r"\s*/\s*", cell_txt):
                    ans = clean_ws(part)
                    if not ans or ans in ("–", "-", "—"):
                        continue
                    key = ans.lower()
                    if key not in TEA_REACTION_ZH:
                        raise ValueError(f"{slug}: Unknown tea reaction {ans!r} in cell {cell_txt!r}")
                    valid_en.append(ans)
                    valid_zh.append(TEA_REACTION_ZH[key])
            if not valid_zh:
                raise ValueError(f"{slug}: Empty valid answers for quote {quote_clean!r}")
            rows_out.append({
                "comment_en": quote_clean,
                "valid_answers_en": valid_en,
                "valid_answers": valid_zh,
                "variant": variant,
            })
            idx += 2
    return rows_out


def align_final_comments(
    sg_rows: list[dict[str, Any]],
    fed_en_talks: list[str],
    fed_zh_talks: list[str],
    slug: str,
) -> list[dict[str, Any]]:
    if len(fed_en_talks) != len(fed_zh_talks):
        raise ValueError(f"{slug}: Fedatamine en/zh talks length mismatch: {len(fed_en_talks)} vs {len(fed_zh_talks)}")

    en_norm_to_idx: dict[str, list[int]] = {}
    for idx, line in enumerate(fed_en_talks):
        en_norm_to_idx.setdefault(norm_en_for_match(line), []).append(idx)

    # Track consumed indices so duplicates (if any) pair cleanly in order
    used_indices: set[int] = set()
    aligned: list[dict[str, Any]] = []

    for row in sg_rows:
        sg_norm = norm_en_for_match(row["comment_en"])
        matched_idx: int | None = None

        # 1. Exact normalized match
        for idx in en_norm_to_idx.get(sg_norm, []):
            if idx not in used_indices:
                matched_idx = idx
                break

        # 2. Substring / token-Jaccard match if punctuation or contraction wording slightly differs
        if matched_idx is None:
            sg_tokens = set(sg_norm.split())
            best_score = 0.0
            for idx, fed_line in enumerate(fed_en_talks):
                if idx in used_indices:
                    continue
                fed_norm = norm_en_for_match(fed_line)
                if sg_norm in fed_norm or fed_norm in sg_norm:
                    matched_idx = idx
                    break
                fed_tokens = set(fed_norm.split())
                if sg_tokens and fed_tokens:
                    jaccard = len(sg_tokens & fed_tokens) / len(sg_tokens | fed_tokens)
                    if jaccard > best_score:
                        best_score = jaccard
                        if jaccard >= 0.55:
                            matched_idx = idx
            if matched_idx is not None:
                print(
                    f"[fuzzy match] {slug}: {row['comment_en']!r} -> {fed_en_talks[matched_idx]!r}",
                    flush=True,
                )

        if matched_idx is None:
            raise ValueError(
                f"{slug}: Failed to match Samurai Gamers final comment {row['comment_en']!r} against Fedatamine en_talks={fed_en_talks!r}"
            )

        used_indices.add(matched_idx)
        aligned.append({
            "comment_zh": fed_zh_talks[matched_idx],
            "comment_en": row["comment_en"],
            "valid_answers": row["valid_answers"],
            "valid_answers_en": row["valid_answers_en"],
            **({"variant": row["variant"]} if row["variant"] else {}),
        })

    return aligned


def fetch_fedatamine_raw(spec: tuple[int, str, str, str, str, str, bool]) -> dict[str, Any]:
    cid, slug, sf_slug, short_zh, db_title, aff, is_dlc = spec
    base_zh = f"https://houses.fedatamine.com/zh-cn/characters/{cid}/{slug}"
    base_en = f"https://houses.fedatamine.com/en-us/characters/{cid}/{slug}"

    basics_html = http_get(f"{base_zh}/basics", min_interval=0.05)
    classroom_html = http_get(f"{base_zh}/activities/classroom", min_interval=0.05)
    advice_html = http_get(f"{base_zh}/activities/advice", min_interval=0.05)
    gifts_html = http_get(f"{base_zh}/activities/gifts", min_interval=0.05)
    tea_zh_html = http_get(f"{base_zh}/activities/tea", min_interval=0.05)
    tea_en_html = http_get(f"{base_en}/activities/tea", min_interval=0.05)
    sg_html = http_get(f"https://samurai-gamers.com/fire-emblem-three-houses/{sf_slug}-tea-party-guide/", min_interval=0.25)

    basics_soup = BeautifulSoup(basics_html, "lxml")
    portrait_keys = [
        portrait_key(img["src"])
        for img in basics_soup.select("div.portrait-picker img[src]")
        if img.get("src")
    ]

    return {
        "cid": cid,
        "slug": slug,
        "sf_slug": sf_slug,
        "short_name_zh": short_zh,
        "db_title": db_title,
        "affiliation": aff,
        "is_dlc_or_patch": is_dlc,
        "portrait_keys": portrait_keys,
        "classroom_soup": BeautifulSoup(classroom_html, "lxml"),
        "advice_soup": BeautifulSoup(advice_html, "lxml"),
        "gifts_soup": BeautifulSoup(gifts_html, "lxml"),
        "tea_zh_soup": BeautifulSoup(tea_zh_html, "lxml"),
        "tea_en_soup": BeautifulSoup(tea_en_html, "lxml"),
        "sg_soup": BeautifulSoup(sg_html, "lxml"),
    }


def main() -> None:
    print(f"Fetching Fedatamine + Samurai Gamers data for {len(CHARACTER_SPECS)} characters...", flush=True)
    with ThreadPoolExecutor(max_workers=4) as executor:
        raw_results = list(executor.map(fetch_fedatamine_raw, CHARACTER_SPECS))

    # Build global portrait -> (cid, short_name_zh) lookup across all 40 characters (+ Byleth if ever present)
    portrait_map: dict[str, tuple[int, str]] = {}
    for item in raw_results:
        for pk in item["portrait_keys"]:
            portrait_map[pk] = (item["cid"], item["short_name_zh"])

    # Also register Byleth male/female portraits (cid 0, 1) just in case classroom opinions include them
    for b_cid, b_slug in [(0, "Byleth"), (1, "Byleth")]:
        try:
            b_soup = BeautifulSoup(http_get(f"https://houses.fedatamine.com/zh-cn/characters/{b_cid}/{b_slug}/basics"), "lxml")
            for img in b_soup.select("div.portrait-picker img[src]"):
                portrait_map[portrait_key(img["src"])] = (b_cid, "贝雷特/贝雷丝")
        except Exception:  # noqa: BLE001
            pass

    characters_out: list[dict[str, Any]] = []
    all_unresolved: list[tuple[str, str]] = []
    total_classroom = 0
    total_advice = 0
    total_final_comments = 0
    total_lost_items = 0

    for item in raw_results:
        cid = item["cid"]
        slug = item["slug"]
        short_zh = item["short_name_zh"]

        classroom_qs, unresolved = parse_classroom(item["classroom_soup"], cid, portrait_map)
        for src in unresolved:
            all_unresolved.append((slug, src))

        advice_qs = parse_advice(item["advice_soup"])
        lost_items = parse_lost_items(item["gifts_soup"])

        tea_zh = parse_fedatamine_tea(item["tea_zh_soup"], is_zh=True)
        tea_en = parse_fedatamine_tea(item["tea_en_soup"], is_zh=False)
        sg_rows = parse_samurai_tea(item["sg_soup"], slug)

        # Validate counts & map favorite teas cleanly
        fav_zh = tea_zh["favorites"]
        fav_en = tea_en["favorites"]
        if len(fav_zh) != len(fav_en):
            raise ValueError(f"{slug}: favorite tea count mismatch zh={fav_zh} en={fav_en}")
        favorite_teas = [{"name_zh": z, "name_en": e} for z, e in zip(fav_zh, fav_en)]

        final_comments = align_final_comments(
            sg_rows,
            tea_en["talks"],
            tea_zh["talks"],
            slug,
        )

        # Assertions per character
        expected_class_count = 0 if slug in ("Jeritza", "Rhea") else 2
        if len(classroom_qs) != expected_class_count:
            raise AssertionError(f"{slug}: expected {expected_class_count} classroom questions, got {len(classroom_qs)}")
        min_comments = 18 if slug == "Constance" else (8 if slug == "Mercedes" else 9)
        if len(final_comments) < min_comments:
            raise AssertionError(f"{slug}: expected >= {min_comments} final comments, got {len(final_comments)}")
        if not favorite_teas or not tea_zh["interested_topics"]:
            raise AssertionError(f"{slug}: missing favorite teas or interested topics")

        total_classroom += len(classroom_qs)
        total_advice += len(advice_qs)
        total_final_comments += len(final_comments)
        total_lost_items += len(lost_items)

        characters_out.append({
            "cid": cid,
            "slug": slug,
            "sf_slug": item["sf_slug"],
            "short_name_zh": short_zh,
            "db_title": item["db_title"],
            "affiliation": item["affiliation"],
            "is_dlc_or_patch": item["is_dlc_or_patch"],
            "classroom_questions": classroom_qs,
            "advice_questions": advice_qs,
            "lost_items": lost_items,
            "tea_party": {
                "favorite_teas": favorite_teas,
                "interested_topics": tea_zh["interested_topics"],
                "blush_liked_topics": tea_zh["blush_liked_topics"],
                "blush_disliked_topics": tea_zh["blush_disliked_topics"],
                "final_comments": final_comments,
            },
        })

    if all_unresolved:
        raise AssertionError(f"Unresolved classroom opinion portraits: {all_unresolved}")

    payload = {
        "schema_version": 1,
        "game": "three-houses",
        "sources": [
            {
                "name": "Fedatamine",
                "url": "https://houses.fedatamine.com/zh-cn/",
                "notes": "Official Simplified Chinese classroom lecture questions, advice box Q&A, lost items, tea favorites, tea topics, and 4th-round dialogue text",
            },
            {
                "name": "Samurai Gamers",
                "url": "https://samurai-gamers.com/fire-emblem-three-houses/",
                "notes": "4th-round Tea Party valid reaction answers (including Constance Indoors/Outdoors split)",
            },
        ],
        "character_count": len(characters_out),
        "characters": characters_out,
    }

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"Saved {OUT_PATH.relative_to(REPO_ROOT)}: "
        f"characters={len(characters_out)}, "
        f"classroom_questions={total_classroom}, "
        f"advice_questions={total_advice}, "
        f"lost_items={total_lost_items}, "
        f"final_comments={total_final_comments}, "
        f"unresolved_portraits={len(all_unresolved)}"
    )


if __name__ == "__main__":
    main()
