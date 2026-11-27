from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sqlite3
import time
from typing import Any
import urllib.parse
import urllib.request


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DB = REPO_ROOT / "data" / "games" / "three-houses" / "database" / "content.db"
DEFAULT_OUTPUT = REPO_ROOT / "data" / "games" / "three-houses" / "sources" / "extracts" / "character-stats.json"

WIKI_PAGE_OVERRIDES = {
    "Anna": "Anna (Three Houses)",
    "Lorenz": "Lorenz (Three Houses)",
    "Seteth": "Seteth",
    "Flayn": "Flayn",
}

RANK_KEYS = {
    "sw": "Sword",
    "la": "Lance",
    "ax": "Axe",
    "bo": "Bow",
    "br": "Brawling",
    "re": "Reason",
    "fa": "Faith",
    "au": "Authority",
    "ar": "Heavy Armor",
    "ri": "Riding",
    "fl": "Flying",
}


def _fetch_pages(titles: list[str]) -> dict[str, tuple[str, str]]:
    url = "https://fireemblemwiki.org/w/api.php?" + urllib.parse.urlencode(
        {
            "action": "query",
            "prop": "revisions",
            "rvprop": "content",
            "rvslots": "main",
            "redirects": "1",
            "titles": "|".join(titles),
            "format": "json",
            "formatversion": "2",
        }
    )
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "FireEmblemCompanionResearch/1.0 (local offline study)"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read().decode("utf-8"))

    query = payload.get("query", {})
    redirects = {r["from"]: r["to"] for r in query.get("redirects", [])}
    normalized = {n["from"]: n["to"] for n in query.get("normalized", [])}
    by_title: dict[str, str] = {}
    for page in query.get("pages", []):
        if page.get("missing"):
            continue
        revs = page.get("revisions", [])
        if not revs:
            continue
        text = revs[0].get("slots", {}).get("main", {}).get("content", "")
        if text:
            by_title[page["title"]] = text

    resolved: dict[str, tuple[str, str]] = {}
    for orig in titles:
        cur = normalized.get(orig, orig)
        cur = redirects.get(cur, cur)
        if cur in by_title:
            resolved[orig] = (cur, by_title[cur])
    return resolved


def _extract_balanced_template(text: str, marker: str, start_idx: int = 0) -> str | None:
    idx = text.find(marker, start_idx)
    if idx < 0:
        return None
    depth = 0
    i = idx
    n = len(text)
    while i < n:
        if text[i : i + 2] == "{{":
            depth += 1
            i += 2
            continue
        if text[i : i + 2] == "}}":
            depth -= 1
            i += 2
            if depth == 0:
                return text[idx:i]
            continue
        i += 1
    return None


def _find_all_templates(text: str, prefix: str) -> list[str]:
    out: list[str] = []
    pos = 0
    while True:
        idx = text.find(prefix, pos)
        if idx < 0:
            break
        block = _extract_balanced_template(text, prefix, idx)
        if not block:
            break
        out.append(block)
        pos = idx + len(block)
    return out


def _split_template_params(block: str) -> dict[str, str]:
    inner = block.strip()
    if inner.startswith("{{") and inner.endswith("}}"):
        inner = inner[2:-2]
    parts: list[str] = []
    cur: list[str] = []
    brace_depth = 0
    bracket_depth = 0
    i = 0
    n = len(inner)
    while i < n:
        ch2 = inner[i : i + 2]
        if ch2 == "{{":
            brace_depth += 1
            cur.append(ch2)
            i += 2
            continue
        if ch2 == "}}":
            brace_depth = max(0, brace_depth - 1)
            cur.append(ch2)
            i += 2
            continue
        if ch2 == "[[":
            bracket_depth += 1
            cur.append(ch2)
            i += 2
            continue
        if ch2 == "]]":
            bracket_depth = max(0, bracket_depth - 1)
            cur.append(ch2)
            i += 2
            continue
        if inner[i] == "|" and brace_depth == 0 and bracket_depth == 0:
            parts.append("".join(cur))
            cur = []
            i += 1
            continue
        cur.append(inner[i])
        i += 1
    if cur:
        parts.append("".join(cur))

    params: dict[str, str] = {}
    for part in parts[1:]:
        if "=" in part:
            k, v = part.split("=", 1)
            params[k.strip()] = v.strip()
    return params


def _strip_wiki_markup(val: str) -> str:
    val = re.sub(r"<ref[^>]*>.*?</ref>", "", val, flags=re.DOTALL)
    val = re.sub(r"<ref[^/]*/>", "", val)
    val = re.sub(r"<!--.*?-->", "", val, flags=re.DOTALL)
    # {{h|display|tooltip}} -> display
    val = re.sub(r"\{\{h\|([^|}]+)(?:\|[^}]*)?\}\}", r"\1", val)
    # {{Personal|29|0}} or {{Personal|40|0|%}} -> 29 or 40%
    def repl_personal(m: re.Match[str]) -> str:
        args = m.group(1).split("|")
        base = args[0].strip() if args else ""
        pct = "%" if len(args) >= 3 and "%" in args[2] else ""
        return base + pct

    val = re.sub(r"\{\{Personal\|([^}]+)\}\}", repl_personal, val)
    val = re.sub(r"\{\{Crest\|([^|}]+)(?:\|([^}]+))?\}\}", lambda m: f"{m.group(1).strip()}" + (f" ({m.group(2).strip()})" if m.group(2) else ""), val)
    # [[Link|Label]] -> Label, [[Link]] -> Link
    val = re.sub(r"\[\[[^|\]]+\|([^\]]+)\]\]", r"\1", val)
    val = re.sub(r"\[\[([^\]]+)\]\]", r"\1", val)
    return val.strip()


def _parse_int(val: str | None) -> int | None:
    if not val:
        return None
    cleaned = _strip_wiki_markup(val).replace("%", "").strip()
    m = re.search(r"-?\d+", cleaned)
    return int(m.group(0)) if m else None


def _parse_item_list(raw: str | None) -> list[str]:
    if not raw:
        return []
    cleaned = re.sub(r"\{\{h\|([^|}]+)(?:\|[^}]*)?\}\}", r"\1", raw)
    cleaned = re.sub(r"<ref[^>]*>.*?</ref>", "", cleaned, flags=re.DOTALL)
    items: list[str] = []
    # Match {{Item|16|Name|...}} or {{Item|Name|...}}
    for m in re.finditer(r"\{\{Item\|([^}]+)\}\}", cleaned):
        parts = [p.strip() for p in m.group(1).split("|") if "=" not in p]
        name = parts[1] if len(parts) >= 2 and parts[0].isdigit() else (parts[0] if parts else "")
        name = _strip_wiki_markup(name)
        if name and name not in items:
            items.append(name)
    if not items:
        for seg in re.split(r"<br\s*/?>", cleaned):
            s = _strip_wiki_markup(re.sub(r"\{\{[^}]+\}\}", "", seg)).strip()
            if s:
                items.append(s)
    return items


def _choose_playable_charstats(wikitext: str) -> dict[str, str]:
    blocks = _find_all_templates(wikitext, "{{CharStats FE16")
    if not blocks:
        return {}
    # Prefer playable Part I/Chapter 1 or lowest lv row; ignore Cindered Shadows / Part II spoiler overrides when Chapter 1/2 exists
    parsed_candidates: list[tuple[int, dict[str, str]]] = []
    for block in blocks:
        params = _split_template_params(block)
        lv = _parse_int(params.get("lv")) or 99
        cls = _strip_wiki_markup(params.get("class", "")).lower()
        score = lv * 10
        if cls in ("noble", "commoner", "myrmidon", "soldier", "fighter", "monk", "priest", "mage", "swordmaster", "warrior", "fortress knight", "death knight"):
            score -= 50
        if "enlightened" in cls or "armored lord" in cls or "emperor" in cls or "high lord" in cls:
            score += 200
        parsed_candidates.append((score, params))
    parsed_candidates.sort(key=lambda pair: pair[0])
    return parsed_candidates[0][1]


def _extract_character(title_en: str, wiki_title: str, wikitext: str) -> dict[str, Any]:
    stats_params = _choose_playable_charstats(wikitext)
    # Some pages put growths only on one template or split across templates; merge missing keys
    all_stats = _find_all_templates(wikitext, "{{CharStats FE16")
    for block in all_stats:
        p = _split_template_params(block)
        for k, v in p.items():
            if k.endswith("1") and k not in stats_params and v:
                stats_params[k] = v

    ability_blocks = _find_all_templates(wikitext, "{{AbilityListFE16")
    ability_params = _split_template_params(ability_blocks[0]) if ability_blocks else {}

    stat_map = {
        "HP": "HP",
        "str": "Str",
        "mag": "Mag",
        "dex": "Dex",
        "spd": "Spd",
        "luck": "Lck",
        "def": "Def",
        "res": "Res",
        "cha": "Cha",
    }
    base_stats: dict[str, int] = {}
    for wk, label in stat_map.items():
        val = _parse_int(stats_params.get(wk))
        if val is not None:
            base_stats[label] = val

    growth_rates: dict[str, int] = {}
    for wk, label in stat_map.items():
        val = _parse_int(stats_params.get(f"{wk}1"))
        if val is not None:
            growth_rates[label] = val

    ranks: dict[str, str] = {}
    for code, skill_name in RANK_KEYS.items():
        raw_rank = _strip_wiki_markup(stats_params.get(code, ""))
        if raw_rank:
            ranks[skill_name] = raw_rank

    strengths: list[str] = []
    weaknesses: list[str] = []
    budding_from_icons: list[str] = []
    for code, skill_name in RANK_KEYS.items():
        affinity_raw = stats_params.get(f"{code}1", "")
        if "Strength16" in affinity_raw:
            strengths.append(skill_name)
        elif "Weakness16" in affinity_raw:
            weaknesses.append(skill_name)
        elif "Budding16" in affinity_raw:
            budding_from_icons.append(skill_name)

    budding_talent = _strip_wiki_markup(ability_params.get("BuddingTalent", "")) or (", ".join(budding_from_icons) if budding_from_icons else "")
    budding_skill = _strip_wiki_markup(ability_params.get("BuddingSkill", ""))

    # Personal & starting skills
    personal_skills: list[str] = []
    for pk in ("Personal", "Personal2"):
        pv = _strip_wiki_markup(ability_params.get(pk, ""))
        if pv and pv not in personal_skills:
            personal_skills.append(pv)
    starting_skills = _parse_item_list(stats_params.get("skills"))
    if not personal_skills and starting_skills:
        personal_skills.append(starting_skills[0])

    # Learnable skills & spells from AbilityListFE16
    learnable_entries: list[str] = []
    ignore_keys = {"SwordRank", "LanceRank", "AxeRank", "BowRank", "BrawlRank", "ReasonRank", "FaithRank", "AuthRank", "ArmorRank", "RidingRank", "FlyingRank", "Personal", "PersonalNote", "Personal2", "Personal2Note", "BuddingTalent", "BuddingSkill", "Dark"}
    for k, v in ability_params.items():
        if k in ignore_keys or k.endswith("Image") or k.endswith("Note") or not v:
            continue
        cleaned_v = _strip_wiki_markup(v)
        if cleaned_v:
            learnable_entries.append(f"{k}: {cleaned_v}")

    return {
        "title_en": title_en,
        "wiki_title": wiki_title,
        "source_url": f"https://fireemblemwiki.org/wiki/{urllib.parse.quote(wiki_title.replace(' ', '_'))}",
        "starting_class": _strip_wiki_markup(stats_params.get("class", "")),
        "starting_level": _parse_int(stats_params.get("lv")),
        "move": _parse_int(stats_params.get("move")),
        "crest": _strip_wiki_markup(stats_params.get("crest", "")),
        "base_stats": base_stats,
        "growth_rates": growth_rates,
        "starting_inventory": _parse_item_list(stats_params.get("inventory")),
        "personal_skills": personal_skills,
        "starting_skills": starting_skills,
        "starting_combat_arts": _parse_item_list(stats_params.get("combatarts")),
        "starting_ranks": ranks,
        "strengths": strengths,
        "weaknesses": weaknesses,
        "budding_talent": budding_talent,
        "budding_skill": budding_skill,
        "learnable_skills": learnable_entries,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="从 Fire Emblem Wiki 抓取风花雪月全部 40 名角色初始属性、成长率与武器技能")
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    with closing(sqlite3.connect(args.db)) as conn:
        chars = [r[0] for r in conn.execute("SELECT title_en FROM content_entries WHERE category_id='characters' ORDER BY title_en")]

    wiki_queries = [WIKI_PAGE_OVERRIDES.get(name, name) for name in chars]
    pages: dict[str, tuple[str, str]] = {}
    for start in range(0, len(wiki_queries), 20):
        batch = wiki_queries[start : start + 20]
        pages.update(_fetch_pages(batch))
        if start + 20 < len(wiki_queries):
            time.sleep(1.5)

    records: dict[str, Any] = {}
    missing_stats: list[str] = []
    for name_en in chars:
        query_title = WIKI_PAGE_OVERRIDES.get(name_en, name_en)
        resolved = pages.get(query_title)
        if not resolved:
            missing_stats.append(f"{name_en} (page not found)")
            continue
        wiki_title, content = resolved
        rec = _extract_character(name_en, wiki_title, content)
        if not rec["base_stats"] or not rec["growth_rates"]:
            missing_stats.append(f"{name_en} (incomplete: base={len(rec['base_stats'])}, growth={len(rec['growth_rates'])})")
        records[name_en] = rec

    payload = {
        "schema_version": 1,
        "source": "Fire Emblem Wiki (MediaWiki API)",
        "license": "GFDL 1.3",
        "retrieved_at": datetime.now(timezone.utc).isoformat(),
        "character_count": len(records),
        "characters": records,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Fetched {len(records)}/{len(chars)} characters -> {args.output}")
    if missing_stats:
        print("Missing/incomplete:", missing_stats)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
