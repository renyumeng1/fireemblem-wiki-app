from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import time
from typing import Any
import urllib.parse
import urllib.request


REPO_ROOT = Path(__file__).resolve().parents[2]
TH_ROOT = REPO_ROOT / "data" / "games" / "three-houses"
DB_PATH = TH_ROOT / "database" / "content.db"
CHAR_STATS_PATH = TH_ROOT / "sources" / "extracts" / "character-stats.json"
OUT_JSON = TH_ROOT / "sources" / "extracts" / "three-houses-skills-complete.json"
ICONS_DIR = TH_ROOT / "assets" / "original" / "icons"
ICONS_MANIFEST = TH_ROOT / "assets" / "icons-manifest.json"

API_URL = "https://fireemblemwiki.org/w/api.php"
USER_AGENT = "FireEmblemCompanionResearch/1.0 (local offline study)"


def api_get(params: dict[str, str]) -> dict[str, Any]:
    url = API_URL + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_pages_batch(titles: list[str]) -> dict[str, tuple[str, str]]:
    resolved: dict[str, tuple[str, str]] = {}
    unique_titles = list(dict.fromkeys(t.strip() for t in titles if t and t.strip()))
    for i in range(0, len(unique_titles), 45):
        chunk = unique_titles[i : i + 45]
        payload = api_get(
            {
                "action": "query",
                "prop": "revisions",
                "rvprop": "content",
                "rvslots": "main",
                "redirects": "1",
                "titles": "|".join(chunk),
                "format": "json",
                "formatversion": "2",
            }
        )
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
            txt = revs[0].get("slots", {}).get("main", {}).get("content", "")
            if txt:
                by_title[page["title"]] = txt
        for orig in chunk:
            cur = normalized.get(orig, orig)
            cur = redirects.get(cur, cur)
            if cur in by_title:
                resolved[orig] = (cur, by_title[cur])
        if i + 45 < len(unique_titles):
            time.sleep(0.5)
    return resolved


def strip_wiki_markup(raw: str) -> str:
    s = raw or ""
    s = re.sub(r"<ref[^>]*>.*?</ref>", "", s, flags=re.DOTALL)
    s = re.sub(r"<ref[^/]*/>", "", s)
    s = re.sub(r"<!--.*?-->", "", s, flags=re.DOTALL)
    s = re.sub(r"\[\[File:[^\]]+\]\]", "", s, flags=re.IGNORECASE)
    s = re.sub(r"\[\[Image:[^\]]+\]\]", "", s, flags=re.IGNORECASE)
    # {{h|display|tooltip}} -> display
    s = re.sub(r"\{\{h\|([^|}]+)(?:\|[^}]*)?\}\}", r"\1", s)
    # {{hover|display|tooltip}} -> display
    s = re.sub(r"\{\{hover\|([^|}]+)(?:\|[^}]*)?\}\}", r"\1", s, flags=re.IGNORECASE)
    # {{title|Three Houses}} -> Three Houses
    s = re.sub(r"\{\{title\|([^|}]+)(?:\|[^}]*)?\}\}", r"\1", s, flags=re.IGNORECASE)
    # {{color|...|text}} or {{hl|jp|romaji}}
    s = re.sub(r"\{\{hl\|([^|}]+)\|[^}]*\}\}", r"\1", s, flags=re.IGNORECASE)
    # {{Sword16}}, {{Lance16}}, etc.
    s = re.sub(r"\{\{(Sword|Lance|Axe|Bow|Brawling|Reason|Faith|Authority|Armor|Riding|Flying)16\}\}", r"\1", s, flags=re.IGNORECASE)
    # {{nwh}} or {{dash}} -> --
    s = re.sub(r"\{\{(?:nwh|dash)\}\}", "--", s, flags=re.IGNORECASE)
    # Remove any remaining simple templates like {{FE16}}
    s = re.sub(r"\{\{FE16\}\}", "Three Houses", s)
    s = re.sub(r"\{\{[^|{}]+\}\}", "", s)
    # [[Link|Label]] -> Label, [[Link]] -> Link
    s = re.sub(r"\[\[[^|\]]+\|([^\]]+)\]\]", r"\1", s)
    s = re.sub(r"\[\[([^\]]+)\]\]", r"\1", s)
    s = re.sub(r"<br\s*/?>", " ", s, flags=re.IGNORECASE)
    s = re.sub(r"</?(?:small|span|b|i|u|sup|sub|center|div)[^>]*>", "", s, flags=re.IGNORECASE)
    s = s.replace("&nbsp;", " ").replace("&minus;", "−").replace("'''", "").replace("''", "")
    s = re.sub(r"\s+", " ", s).strip()
    return s


def extract_first_file(raw: str) -> str | None:
    m = re.search(r"\[\[File:([^|\]]+)", raw or "", flags=re.IGNORECASE)
    if not m:
        return None
    name = m.group(1).replace("&nbsp;", " ").strip().replace(" ", "_")
    if name:
        name = name[0].upper() + name[1:]
    return name


def extract_balanced_template(text: str, marker: str, start_idx: int = 0) -> str | None:
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


def find_all_templates(text: str, prefix: str) -> list[str]:
    out: list[str] = []
    pos = 0
    while True:
        idx = text.find(prefix, pos)
        if idx < 0:
            break
        blk = extract_balanced_template(text, prefix, idx)
        if not blk:
            break
        out.append(blk)
        pos = idx + len(blk)
    return out


def split_template_params(block: str) -> dict[str, str]:
    inner = block.strip()
    if inner.startswith("{{") and inner.endswith("}}"):
        inner = inner[2:-2]
    parts: list[str] = []
    cur: list[str] = []
    bd = 0
    kd = 0
    i = 0
    n = len(inner)
    while i < n:
        c2 = inner[i : i + 2]
        if c2 == "{{":
            bd += 1
            cur.append(c2)
            i += 2
            continue
        if c2 == "}}":
            bd = max(0, bd - 1)
            cur.append(c2)
            i += 2
            continue
        if c2 == "[[":
            kd += 1
            cur.append(c2)
            i += 2
            continue
        if c2 == "]]":
            kd = max(0, kd - 1)
            cur.append(c2)
            i += 2
            continue
        if inner[i] == "|" and bd == 0 and kd == 0:
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


def parse_abilities_page(wikitext: str) -> list[dict[str, Any]]:
    abilities: list[dict[str, Any]] = []
    current_section = "Other abilities"
    sections = re.split(r"(^==[^=]+==\s*$)", wikitext, flags=re.MULTILINE)
    for chunk in sections:
        m = re.match(r"^==\s*([^=]+?)\s*==$", chunk.strip())
        if m:
            current_section = m.group(1).strip()
            continue
        # Parse wikitable rows inside this section
        for row_raw in re.split(r"\n\|-[^\n]*\n", chunk):
            lines = [ln.strip() for ln in row_raw.splitlines() if ln.strip()]
            cells: list[str] = []
            for ln in lines:
                if ln.startswith("!"):
                    continue
                if ln.startswith("{|") or ln.startswith("|}"):
                    continue
                if ln.startswith("|"):
                    # Could contain || cell separators on same line
                    body = ln[1:]
                    for seg in body.split("||"):
                        # Strip cell attributes like `style="..." | value` if single pipe before content
                        seg_s = seg.strip()
                        if "|" in seg_s and not seg_s.startswith(("[[", "{{")):
                            left, right = seg_s.split("|", 1)
                            if any(attr in left for attr in ("style=", "colspan=", "rowspan=", "class=", "data-sort-value=")):
                                seg_s = right.strip()
                        cells.append(seg_s)
                elif cells:
                    cells[-1] += " " + ln
            if len(cells) < 3:
                continue
            icon_file = extract_first_file(cells[0])
            name_cell = cells[1] if icon_file else cells[0]
            # Extract link target if any
            link_m = re.search(r"\[\[([^|\]]+)(?:\|([^\]]+))?\]\]", name_cell)
            name_en = (link_m.group(2) or link_m.group(1)).strip() if link_m else strip_wiki_markup(name_cell)
            name_en = name_en.replace("&nbsp;", " ").replace("&minus;", "−")
            name_en = re.sub(r"\s+", " ", name_en).strip()
            if not name_en or name_en.lower() in ("ability", "effect", "notes"):
                continue
            effect_idx = 2 if icon_file else 1
            effect_en = strip_wiki_markup(cells[effect_idx]) if effect_idx < len(cells) else ""
            source_en = strip_wiki_markup(cells[effect_idx + 1]) if (effect_idx + 1) < len(cells) else ""
            if not effect_en:
                continue
            abilities.append(
                {
                    "name": name_en,
                    "section": current_section,
                    "icon_file": icon_file,
                    "effect_en": effect_en,
                    "source_en": source_en,
                }
            )
    return abilities


def parse_weapons_and_items_icons(wikitext: str) -> dict[str, str]:
    """Return {lowercase_name: icon_filename} from List of weapons / List of items wikitables."""
    icons: dict[str, str] = {}
    for row_raw in re.split(r"\n\|-[^\n]*\n", wikitext):
        lines = [ln.strip() for ln in row_raw.splitlines() if ln.strip()]
        cells: list[str] = []
        for ln in lines:
            if ln.startswith(("!", "{|", "|}")):
                continue
            if ln.startswith("|"):
                for seg in ln[1:].split("||"):
                    seg_s = seg.strip()
                    if "|" in seg_s and not seg_s.startswith(("[[", "{{")):
                        left, right = seg_s.split("|", 1)
                        if any(attr in left for attr in ("style=", "colspan=", "rowspan=", "class=", "data-sort-value=")):
                            seg_s = right.strip()
                    cells.append(seg_s)
            elif cells:
                cells[-1] += " " + ln
        if len(cells) < 2:
            continue
        icon_file = extract_first_file(cells[0]) or extract_first_file(cells[1])
        if not icon_file:
            continue
        name_cell = cells[1] if extract_first_file(cells[0]) else cells[0]
        name = strip_wiki_markup(name_cell)
        if name:
            icons[name.lower()] = icon_file
    return icons


def parse_combat_art_page(name_en: str, wikitext: str) -> dict[str, Any] | None:
    """Extract Three Houses {{CombatArtStats}} or {{SkillData}} from a combat art wiki page."""
    # 1. Check {{CombatArtStats ...}}
    for blk in find_all_templates(wikitext, "{{CombatArtStats"):
        params = split_template_params(blk)
        # Find which numeric suffix N corresponds to Three Houses (or single unnumbered if FE16-only)
        chosen_suffix: str | None = None
        for k, v in params.items():
            if k.startswith("game") and ("Three Houses" in v or "FE16" in v):
                chosen_suffix = k[len("game") :]
                break
        if chosen_suffix is None:
            continue
        suf = chosen_suffix
        sprite = extract_first_file(params.get(f"sprite{suf}", ""))
        mt = strip_wiki_markup(params.get(f"mt{suf}", ""))
        hit = strip_wiki_markup(params.get(f"hit{suf}", ""))
        crt = strip_wiki_markup(params.get(f"crt{suf}", ""))
        avo = strip_wiki_markup(params.get(f"avo{suf}", ""))
        rng = strip_wiki_markup(params.get(f"rng{suf}", ""))
        cost = strip_wiki_markup(params.get(f"cost{suf}", ""))
        notes = strip_wiki_markup(params.get(f"notes{suf}", ""))
        # Also pull infobox help_text / icon fallback
        help_text = ""
        for ib in find_all_templates(wikitext, "{{Skill Infobox"):
            ip = split_template_params(ib)
            if not sprite:
                sp = extract_first_file(ip.get("image", ""))
                if sp and "fe16" in sp.lower():
                    sprite = sp
            help_text = strip_wiki_markup(ip.get("help_text", ""))
        return {
            "name": name_en,
            "kind": "combat_art",
            "icon_file": sprite,
            "might": mt or None,
            "hit": hit or None,
            "crit": crt or None,
            "avoid": avo or None,
            "range": rng or None,
            "cost": cost or None,
            "notes_en": notes or None,
            "help_text_en": help_text or None,
        }

    # 2. Check {{CombatArtData ...}} or {{SkillData ...}} (for non-offensive combat arts like Shove/Swap/Reposition/Draw Back/Healing Focus/Triangle Attack)
    for tpl_prefix in ("{{CombatArtData", "{{SkillData"):
        for blk in find_all_templates(wikitext, tpl_prefix):
            params = split_template_params(blk)
            chosen_suffix = None
            for k, v in params.items():
                if k.startswith("game") and ("Three Houses" in v or "FE16" in v):
                    chosen_suffix = k[len("game") :]
                    break
            if chosen_suffix is None:
                continue
            suf = chosen_suffix
            sprite = extract_first_file(params.get(f"sprite{suf}", ""))
            eff = strip_wiki_markup(params.get(f"effect{suf}", ""))
            notes = strip_wiki_markup(params.get(f"notes{suf}", ""))
            rng = strip_wiki_markup(params.get(f"rng{suf}", ""))
            cost = strip_wiki_markup(params.get(f"cost{suf}", ""))
            return {
                "name": name_en,
                "kind": "combat_art",
                "icon_file": sprite,
                "might": None,
                "hit": None,
                "crit": None,
                "avoid": None,
                "range": rng or None,
                "cost": cost or None,
                "notes_en": " ".join(p for p in (eff, notes) if p) or None,
                "help_text_en": None,
            }
    return None


def list_all_fe16_images() -> dict[str, dict[str, Any]]:
    images: dict[str, dict[str, Any]] = {}
    cont: dict[str, str] = {}
    while True:
        params = {
            "action": "query",
            "list": "allimages",
            "aiprefix": "Is fe16",
            "ailimit": "500",
            "format": "json",
        }
        params.update(cont)
        res = api_get(params)
        for item in res.get("query", {}).get("allimages", []):
            images[item["name"]] = item
        cont = res.get("continue", {})
        if not cont:
            break
        time.sleep(0.3)
    return images


def download_icons(needed_files: set[str], all_fe16: dict[str, dict[str, Any]]) -> dict[str, dict[str, Any]]:
    ICONS_DIR.mkdir(parents=True, exist_ok=True)
    # Build case-insensitive lookup over all_fe16
    by_lower = {k.lower(): v for k, v in all_fe16.items()}
    # Also fetch imageinfo for any requested file not starting with Is_fe16 (just in case)
    manifest: dict[str, dict[str, Any]] = {}
    if ICONS_MANIFEST.is_file():
        try:
            manifest = json.loads(ICONS_MANIFEST.read_text(encoding="utf-8")).get("icons", {})
        except Exception:
            manifest = {}

    downloaded = 0
    reused = 0
    missing: list[str] = []
    for fname in sorted(needed_files):
        info = all_fe16.get(fname) or by_lower.get(fname.lower())
        if not info:
            # Try normalizing apostrophe / spaces
            alt = fname.replace("'", "_").replace(" ", "_").lower()
            info = by_lower.get(alt)
        if not info:
            missing.append(fname)
            continue
        canonical_name = info["name"]
        url = info.get("url")
        if not url:
            missing.append(fname)
            continue
        safe_name = re.sub(r"[^A-Za-z0-9._+-]+", "_", canonical_name)
        dst = ICONS_DIR / safe_name
        rel_path = f"original/icons/{safe_name}"
        if dst.is_file() and dst.stat().st_size > 0:
            reused += 1
        else:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=25) as resp:
                data = resp.read()
            dst.write_bytes(data)
            downloaded += 1
            time.sleep(0.08)
        manifest[canonical_name] = {
            "name": canonical_name,
            "local_path": rel_path,
            "source_url": url,
            "sha256": hashlib.sha256(dst.read_bytes()).hexdigest(),
            "bytes": dst.stat().st_size,
            "width": info.get("width"),
            "height": info.get("height"),
        }

    ICONS_MANIFEST.write_text(
        json.dumps(
            {
                "source": "https://fireemblemwiki.org",
                "retrieved_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "count": len(manifest),
                "icons": manifest,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Icons: downloaded={downloaded}, reused={reused}, missing={len(missing)}, total_manifest={len(manifest)}")
    if missing:
        print("  missing sample:", missing[:15])
    return manifest


def collect_combat_art_candidates() -> list[str]:
    names: set[str] = set()
    # 1. From three-houses/database/content.db skills (all combat arts)
    with closing(sqlite3.connect(DB_PATH)) as conn:
        for title_en, sub in conn.execute(
            "SELECT COALESCE(title_en, title), subtitle FROM content_entries WHERE category_id='skills'"
        ).fetchall():
            if sub and "combat art" in sub.lower():
                names.add(title_en.strip())
    # 2. From character-stats.json (starting_combat_arts + learnable Sword/Lance/Axe/Bow/Brawl arts + budding skills)
    if CHAR_STATS_PATH.is_file():
        chars = json.loads(CHAR_STATS_PATH.read_text(encoding="utf-8")).get("characters", {})
        for st in chars.values():
            for a in st.get("starting_combat_arts", []):
                names.add(a.strip())
            for item in st.get("learnable_skills", []):
                if ":" not in item:
                    continue
                k, v = [p.strip() for p in item.split(":", 1)]
                if any(k.startswith(pref) for pref in ("Sword", "Lance", "Axe", "Bow", "Brawl")) and not k.endswith("Link"):
                    if v:
                        names.add(v)
            bs = (st.get("budding_skill") or "").strip()
            if bs:
                names.add(bs)
    # 3. Notable class mastery / relic combat arts
    names.update(
        {
            "Assassinate", "Smash (combat art)",
            "Hunter's Volley", "Fierce Iron Fist", "Astra", "Paraselene", "Flickering Flower",
            "Wind God", "Raging Storm", "Atrocity", "Fallen Star", "Ruptured Heaven",
            "Sublime Heaven", "Burning Quake", "Dust", "Apocalyptic Flame", "Foudroyant Strike",
            "Beast Fang", "Sunder", "Shove", "Swap", "Reposition", "Draw Back", "Smite",
            "Healing Focus", "Triangle Attack", "Subdue", "Foul Play", "Pneuma Gale",
            "Schism Shot", "Frozen Lance", "Soulblade", "Hexblade", "Lightning Axe",
            "Vengeance", "Swift Strikes", "Point-Blank Volley", "Deadeye", "Encloser",
            "Windsweep", "Bane of Monsters", "Monster Piercer", "Monster Breaker",
            "Monster Crusher", "Monster Blast", "Exhaustive Strike", "Armored Strike",
            "Glowing Ember", "Helm Splitter", "Spike", "Knightkneeler", "Shatter Slash",
            "Tempest Lance", "Lance Jab", "Wrath Strike", "Grounder", "Haze Slice",
            "Finesse Blade", "Focused Strike", "Smash", "Wild Abandon", "Diamond Axe",
            "War Master's Strike", "Curved Shot", "Heavy Draw", "Break Shot", "Waning Shot",
            "Ward Arrow", "Fading Blow", "Rushing Blow", "Draining Blow", "Mystic Blow",
            "Mighty Blow", "Bombard", "One-Two Punch", "Nimble Combo",
        }
    )
    return sorted(names)


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch complete Fire Emblem: Three Houses abilities, combat arts, and FE16 icons from Fire Emblem Wiki.")
    parser.add_argument("--skip-icons", action="store_true")
    args = parser.parse_args()

    print("1. Fetching Three Houses Abilities, Weapons, and Items list pages...")
    core_pages = fetch_pages_batch(
        [
            "List of abilities in Fire Emblem: Three Houses",
            "List of weapons in Fire Emblem: Three Houses",
            "List of items in Fire Emblem: Three Houses",
        ]
    )
    abilities = parse_abilities_page(core_pages["List of abilities in Fire Emblem: Three Houses"][1])
    weapon_icons = parse_weapons_and_items_icons(core_pages["List of weapons in Fire Emblem: Three Houses"][1])
    item_icons = parse_weapons_and_items_icons(core_pages["List of items in Fire Emblem: Three Houses"][1])
    equip_icons = {**item_icons, **weapon_icons}
    print(f"   Parsed {len(abilities)} abilities, {len(equip_icons)} equipment/magic icons.")

    print("2. Fetching all Three Houses Combat Arts in batches...")
    ca_candidates = collect_combat_art_candidates()
    ability_names_lower = {a["name"].lower() for a in abilities}
    ca_to_fetch = [c for c in ca_candidates if c.lower() not in ability_names_lower]
    print(f"   Querying {len(ca_to_fetch)} combat art pages (filtered from {len(ca_candidates)} candidates)...")
    ca_pages = fetch_pages_batch(ca_to_fetch)
    combat_arts: list[dict[str, Any]] = []
    for cand in ca_to_fetch:
        hit = ca_pages.get(cand)
        if not hit:
            continue
        canonical = "Smash" if cand == "Smash (combat art)" else cand
        parsed = parse_combat_art_page(canonical, hit[1])
        if parsed:
            combat_arts.append(parsed)
    print(f"   Parsed {len(combat_arts)} Three Houses combat arts with stats/range/effects.")

    print("3. Listing all Is_fe16_* icons on Fire Emblem Wiki...")
    all_fe16 = list_all_fe16_images()
    print(f"   Found {len(all_fe16)} total Is_fe16_* icon files.")

    # Match crest icons Is_fe16_crest_of_*.png
    crest_icons: dict[str, str] = {}
    for fname in all_fe16:
        m = re.match(r"^Is_fe16_(crest_of_.+)\.png$", fname, flags=re.IGNORECASE)
        if m:
            slug = m.group(1).replace("_", " ").lower()
            crest_icons[slug] = fname

    needed_icons: set[str] = set()
    for a in abilities:
        if a.get("icon_file"):
            needed_icons.add(a["icon_file"])
    for ca in combat_arts:
        if ca.get("icon_file"):
            needed_icons.add(ca["icon_file"])
    for ic in equip_icons.values():
        needed_icons.add(ic)
    for ic in crest_icons.values():
        needed_icons.add(ic)
    # Also include generic combat art icons and Lv 1..5 prowess / reason / faith / authority icons
    for generic in (
        "Is_fe16_combat_art.png",
        "Is_fe16_combat_art_sword.png",
        "Is_fe16_combat_art_lance.png",
        "Is_fe16_combat_art_axe.png",
        "Is_fe16_combat_art_bow.png",
        "Is_fe16_combat_art_brawling.png",
        "Is_fe16_weight_-5.png",
    ):
        if generic in all_fe16:
            needed_icons.add(generic)
    for fname in all_fe16:
        fl = fname.lower()
        if any(k in fl for k in ("_prowess_lv_", "_reason_lv_", "_faith_lv_", "_authority_lv_")):
            needed_icons.add(fname)

    print(f"4. Saving {OUT_JSON.name} ({len(abilities)} abilities, {len(combat_arts)} combat arts, {len(equip_icons)} equip icons, {len(crest_icons)} crest icons)...")
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(
        json.dumps(
            {
                "source": "https://fireemblemwiki.org",
                "retrieved_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "abilities": abilities,
                "combat_arts": combat_arts,
                "equipment_icons": equip_icons,
                "crest_icons": crest_icons,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    if not args.skip_icons:
        print(f"5. Downloading {len(needed_icons)} real in-game FE16 icon files...")
        download_icons(needed_icons, all_fe16)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
