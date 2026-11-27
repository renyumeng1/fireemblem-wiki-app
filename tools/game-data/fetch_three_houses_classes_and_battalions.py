#!/usr/bin/env python3
"""Fetch complete Three Houses class data (Fire Emblem Wiki), battalion stats (Serenes Forest),
and 12 official FE16 battalion icons (Fire Emblem Wiki)."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import html
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request

WIKI_API = "https://fireemblemwiki.org/w/api.php"
SF_BATTALIONS_URL = "https://serenesforest.net/three-houses/miscellaneous/battalions/"
UA = "Mozilla/5.0 (FECompanionDataFetcher/1.0)"

ROOT = Path("data/games/three-houses")
CLASSES_OUT = ROOT / "sources/extracts/three-houses-classes-complete.json"
BATTALIONS_OUT = ROOT / "sources/extracts/three-houses-battalions-complete.json"
ICONS_DIR = ROOT / "assets/original/icons"
BATTALION_MANIFEST_OUT = ROOT / "assets/battalion-icons-manifest.json"

SKILL_COLS = (
    "Sword", "Lance", "Axe", "Bow", "Brawling",
    "Reason", "Faith", "Authority", "Heavy Armor", "Riding", "Flying",
)
BASE_STAT_COLS = ("HP", "Str", "Mag", "Dex", "Spd", "Lck", "Def", "Res")
GROWTH_COLS = ("HP", "Str", "Mag", "Dex", "Spd", "Lck", "Def", "Res", "Cha")
BONUS_COLS = ("HP", "Str", "Mag", "Dex", "Spd", "Lck", "Def", "Res", "Cha", "Mov")
DISMOUNT_COLS = ("Str", "Spd", "Def", "Mov")

BATTALION_ICON_FILES = [
    "Is_fe16_battalion_infantry.png",
    "Is_fe16_battalion_armored.png",
    "Is_fe16_battalion_cavalry.png",
    "Is_fe16_battalion_flying.png",
    "Is_fe16_battalion_infantry_silver.png",
    "Is_fe16_battalion_armored_silver.png",
    "Is_fe16_battalion_cavalry_silver.png",
    "Is_fe16_battalion_flying_silver.png",
    "Is_fe16_battalion_infantry_gold.png",
    "Is_fe16_battalion_armored_gold.png",
    "Is_fe16_battalion_cavalry_gold.png",
    "Is_fe16_battalion_flying_gold.png",
]


def http_get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=25) as resp:
        return resp.read()


def wiki_api(params: dict[str, str]) -> dict:
    url = WIKI_API + "?" + urllib.parse.urlencode({**params, "format": "json"})
    return json.loads(http_get(url).decode("utf-8"))


def clean_wiki_cell(raw: str) -> str:
    s = raw.strip()
    # Strip cell attribute prefix like `data-sort-value="3" | D` or `class="pixel-art" | ...`
    if "|" in s and not s.startswith("[[") and not s.startswith("{{"):
        left, right = s.split("|", 1)
        if "=" in left or "style" in left or "class" in left or "colspan" in left or "rowspan" in left:
            s = right.strip()
    s = re.sub(r"<!--.*?-->", "", s, flags=re.S)
    s = re.sub(r"<ref[^>]*>.*?</ref>", "", s, flags=re.S)
    s = re.sub(r"<ref[^/]*/>", "", s)
    s = re.sub(r"<br\s*/?>", ", ", s, flags=re.I)
    s = re.sub(r"\{\{nwh\}\}", "", s, flags=re.I)
    s = re.sub(r"\{\{hover\|([^|}]*)\|[^}]*\}\}", r"\1", s, flags=re.I)
    s = re.sub(r"\[\[File:[^\]]+\]\]", "", s, flags=re.I)
    s = re.sub(r"\[\[[^|\]]+\|([^\]]+)\]\]", r"\1", s)
    s = re.sub(r"\[\[([^\]]+)\]\]", r"\1", s)
    s = re.sub(r"\{\{[^}]+\}\}", "", s)
    s = re.sub(r"<[^>]+>", "", s)
    s = html.unescape(s).replace("\xa0", " ").replace("−", "-")
    s = re.sub(r"\s+", " ", s).strip()
    return s


def parse_signed_int(raw: str) -> int | None:
    s = raw.strip().replace("%", "").replace("+", "").replace("−", "-")
    if not s or s in ("-", "--"):
        return None
    try:
        return int(s)
    except ValueError:
        return None


def fetch_classes() -> list[dict]:
    data = wiki_api({
        "action": "parse",
        "page": "List of classes in Fire Emblem: Three Houses",
        "prop": "wikitext",
    })
    wt = data["parse"]["wikitext"]["*"]
    rows = wt.split("\n|-")
    classes: list[dict] = []

    for row in rows[2:]:
        # Extract individual `| ...` lines (each cell is on its own line in this table)
        lines = [ln for ln in row.splitlines() if ln.startswith("|") and not ln.startswith("|}")]
        if len(lines) < 70:
            continue
        cells = [clean_wiki_cell(ln[1:]) for ln in lines]
        name = cells[0]
        if not name or name.lower() == "class":
            continue
        tier = cells[2]
        unit_type = cells[3]
        terrain = cells[4]

        base_stats = {}
        for i, col in enumerate(BASE_STAT_COLS):
            v = parse_signed_int(cells[6 + i])
            if v is not None:
                base_stats[col] = v

        player_growths = {}
        for i, col in enumerate(GROWTH_COLS):
            v = parse_signed_int(cells[14 + i])
            if v is not None:
                player_growths[col] = v

        stat_bonuses = {}
        for i, col in enumerate(BONUS_COLS):
            v = parse_signed_int(cells[32 + i])
            if v is not None and v != 0:
                stat_bonuses[col] = v

        # FE16 base movement is 4 + class Mov bonus
        mov_bonus = parse_signed_int(cells[41]) or 0
        movement = 4 + mov_bonus

        dismount = {}
        for i, col in enumerate(DISMOUNT_COLS):
            v = parse_signed_int(cells[42 + i])
            if v is not None and v != 0:
                dismount[col] = v

        skill_bonuses = {}
        for i, col in enumerate(SKILL_COLS):
            v = parse_signed_int(cells[46 + i])
            if v is not None and v != 0:
                skill_bonuses[col] = v

        exam_reqs = {}
        for i, col in enumerate(SKILL_COLS):
            rk = cells[57 + i].strip()
            if rk and rk not in ("-", "--", "0"):
                exam_reqs[col] = rk

        innate = [x.strip() for x in cells[68].split(",") if x.strip()] if len(cells) > 68 else []
        mastery = [x.strip() for x in cells[69].split(",") if x.strip()] if len(cells) > 69 else []
        mastery_exp = parse_signed_int(cells[70]) if len(cells) > 70 else None
        notes = cells[-1] if len(cells) > 75 else ""

        classes.append({
            "name": name,
            "tier": tier,
            "unit_type": unit_type,
            "terrain": terrain,
            "movement": movement,
            "base_stats": base_stats,
            "player_growths": player_growths,
            "stat_bonuses": stat_bonuses,
            "dismount_modifiers": dismount,
            "skill_bonuses": skill_bonuses,
            "exam_requirements": exam_reqs,
            "innate_abilities": innate,
            "mastery_abilities": mastery,
            "mastery_exp": mastery_exp,
            "notes": notes,
        })
    return classes


def fetch_battalions() -> list[dict]:
    page = http_get(SF_BATTALIONS_URL).decode("utf-8", errors="replace")
    tables = re.findall(r"<table.*?</table>", page, flags=re.S)
    if len(tables) < 4:
        raise RuntimeError(f"Expected >= 4 tables on Serenes Forest battalions page, got {len(tables)}")

    def parse_table(tbl_html: str) -> list[list[str]]:
        out = []
        for tr in re.findall(r"<tr.*?</tr>", tbl_html, flags=re.S):
            cells = [
                re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", c))).replace("\xa0", "").strip()
                for c in re.findall(r"<t[hd][^>]*>(.*?)</t[hd]>", tr, flags=re.S)
            ]
            if cells:
                out.append(cells)
        return out

    init_rows = parse_table(tables[1])
    max_rows = parse_table(tables[3])

    stat_keys = ("Phys", "Mag", "Hit", "Crit", "Avo", "Prot", "Res", "Cha")
    max_by_name: dict[str, dict[str, int]] = {}
    for r in max_rows[1:]:
        if len(r) < 9:
            continue
        name = r[0].strip()
        if not name or name.lower() == "name":
            continue
        st = {}
        for i, k in enumerate(stat_keys):
            v = parse_signed_int(r[1 + i])
            if v is not None:
                st[k] = v
        max_by_name[name.lower()] = st

    battalions: list[dict] = []
    for r in init_rows[1:]:
        if len(r) < 12:
            continue
        name = r[0].strip()
        if not name or name.lower() == "name":
            continue
        init_st = {}
        for i, k in enumerate(stat_keys):
            v = parse_signed_int(r[1 + i])
            if v is not None:
                init_st[k] = v
        gambit = r[9].strip()
        auth_rank = r[10].strip()
        endurance = parse_signed_int(r[11])
        battalions.append({
            "name": name,
            "authority_rank": auth_rank,
            "gambit": gambit,
            "endurance": endurance,
            "lv1_stats": init_st,
            "lv5_stats": max_by_name.get(name.lower(), init_st),
        })
    return battalions


def fetch_battalion_icons() -> dict:
    ICONS_DIR.mkdir(parents=True, exist_ok=True)
    titles = "|".join(f"File:{f}" for f in BATTALION_ICON_FILES)
    res = wiki_api({
        "action": "query",
        "titles": titles,
        "prop": "imageinfo",
        "iiprop": "url|size",
    })
    pages = res.get("query", {}).get("pages", {})
    url_map: dict[str, tuple[str, int | None, int | None]] = {}
    for p in pages.values():
        t = (p.get("title") or "").removeprefix("File:").replace(" ", "_")
        ii = (p.get("imageinfo") or [{}])[0]
        if ii.get("url"):
            url_map[t] = (ii["url"], ii.get("width"), ii.get("height"))

    icons_meta: dict[str, dict] = {}

    def download_one(fname: str) -> tuple[str, dict | None]:
        info = url_map.get(fname)
        if not info:
            return (fname, None)
        url, w, h = info
        dst = ICONS_DIR / fname
        if not dst.is_file():
            raw = http_get(url)
            dst.write_bytes(raw)
        raw = dst.read_bytes()
        return (fname, {
            "name": fname,
            "local_path": f"original/icons/{fname}",
            "source_url": url,
            "sha256": hashlib.sha256(raw).hexdigest(),
            "bytes": len(raw),
            "width": w,
            "height": h,
        })

    with ThreadPoolExecutor(max_workers=8) as ex:
        futs = [ex.submit(download_one, fn) for fn in BATTALION_ICON_FILES]
        for fut in as_completed(futs):
            fn, meta = fut.result()
            if meta:
                icons_meta[fn] = meta

    return {
        "source": "https://fireemblemwiki.org",
        "retrieved_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "icons": icons_meta,
    }


def main() -> int:
    classes = fetch_classes()
    CLASSES_OUT.parent.mkdir(parents=True, exist_ok=True)
    CLASSES_OUT.write_text(
        json.dumps({
            "source": "https://fireemblemwiki.org/wiki/List_of_classes_in_Fire_Emblem:_Three_Houses",
            "retrieved_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "count": len(classes),
            "classes": classes,
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Fetched {len(classes)} classes -> {CLASSES_OUT}")

    battalions = fetch_battalions()
    BATTALIONS_OUT.write_text(
        json.dumps({
            "source": SF_BATTALIONS_URL,
            "retrieved_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "count": len(battalions),
            "battalions": battalions,
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Fetched {len(battalions)} battalions -> {BATTALIONS_OUT}")

    b_icons = fetch_battalion_icons()
    BATTALION_MANIFEST_OUT.write_text(
        json.dumps(b_icons, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Fetched {len(b_icons['icons'])}/{len(BATTALION_ICON_FILES)} battalion icons -> {BATTALION_MANIFEST_OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
