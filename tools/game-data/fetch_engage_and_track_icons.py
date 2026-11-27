from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import time
from typing import Any
import urllib.parse
import urllib.request


REPO_ROOT = Path(__file__).resolve().parents[2]
ENGAGE_ASSETS = REPO_ROOT / "data" / "games" / "engage" / "assets"
ENGAGE_ICONS_DIR = ENGAGE_ASSETS / "wiki-icons"
ENGAGE_MANIFEST = ENGAGE_ASSETS / "wiki-icons-manifest.json"
TH_ICONS_DIR = REPO_ROOT / "data" / "games" / "three-houses" / "assets" / "original" / "icons"
TH_RANK_MANIFEST = REPO_ROOT / "data" / "games" / "three-houses" / "assets" / "rank-icons-manifest.json"

API_URL = "https://fireemblemwiki.org/w/api.php"
USER_AGENT = "FireEmblemCompanionResearch/1.0 (local offline study)"

ENGAGE_CLASS_EN_TO_ZH: dict[str, str] = {
    "Dragon Child": "神龙之子",
    "Divine Dragon": "神龙之王",
    "Fell Child": "邪龙之子",
    "Noble": "贵族",
    "Avenir": "阿温尼尔",
    "Vidame": "维达姆",
    "Lord": "领主",
    "Successeur": "继承者",
    "Tireur d'élite": "神射手",
    "Wing Tamer": "驯翼师",
    "Lindwurm": "林德虫",
    "Sleipnir Rider": "八足天马骑士",
    "Sentinel": "哨兵",
    "Picket": "义警队",
    "Cupido": "丘比特",
    "Dancer": "舞蹈家",
    "Melusine": "美露莘",
    "Enchanter": "附魔师",
    "Mage Cannoneer": "魔法炮手",
    "Sword Fighter": "剑士",
    "Swordmaster": "刀剑将领",
    "Hero": "勇者",
    "Lance Fighter": "枪兵",
    "Halberdier": "枪术大师",
    "Royal Knight": "皇家骑士",
    "Axe Fighter": "斧斗士",
    "Berserker": "狂战士",
    "Warrior": "勇士",
    "Archer": "弓箭手",
    "Sniper": "狙击手",
    "Bow Knight": "弓骑士",
    "Sword Armor": "剑重甲",
    "Lance Armor": "枪重甲",
    "Axe Armor": "斧重甲",
    "General": "将领",
    "Great Knight": "重装骑士",
    "Sword Cavalier": "剑骑兵",
    "Lance Cavalier": "枪骑兵",
    "Axe Cavalier": "斧骑兵",
    "Paladin": "圣骑士",
    "Wolf Knight": "狼骑士",
    "Mage": "魔道士",
    "Sage": "贤者",
    "Mage Knight": "魔法骑士",
    "Martial Monk": "武僧",
    "Martial Master": "僧侣大师",
    "High Priest": "主教",
    "Sword Flier": "剑天马",
    "Lance Flier": "枪天马",
    "Axe Flier": "斧天马",
    "Griffin Knight": "鹰狮骑士",
    "Wyvern Knight": "飞龙骑士",
    "Thief": "盗贼",
}

FE17_RANK_FILES: dict[str, str] = {
    "sword": "FE17RankSword.png",
    "lance": "FE17RankLance.png",
    "axe": "FE17RankAxe.png",
    "bow": "FE17RankBow.png",
    "dagger": "FE17RankKnife.png",
    "reason": "FE17RankTome.png",
    "faith": "FE17RankStaff.png",
    "brawl": "FE17RankArt.png",
}

FE16_RANK_FILES: dict[str, str] = {
    "sword": "FE16RankSword.png",
    "lance": "FE16RankLance.png",
    "axe": "FE16RankAxe.png",
    "bow": "FE16RankBow.png",
    "brawl": "FE16RankBrawling.png",
    "reason": "FE16RankReason.png",
    "faith": "FE16RankFaith.png",
    "authority": "FE16RankAuthority.png",
    "armor": "Is_fe16_armored.png",
    "riding": "Is_fe16_cavalry.png",
    "flying": "Is_fe16_flying.png",
}


def api_get(params: dict[str, str]) -> dict[str, Any]:
    url = API_URL + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_image_urls(filenames: list[str]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    unique = list(dict.fromkeys(f"File:{f}" for f in filenames if f))
    for i in range(0, len(unique), 45):
        chunk = unique[i : i + 45]
        payload = api_get(
            {
                "action": "query",
                "prop": "imageinfo",
                "iiprop": "url|size",
                "titles": "|".join(chunk),
                "format": "json",
                "formatversion": "2",
            }
        )
        for p in payload.get("query", {}).get("pages", []):
            ii = p.get("imageinfo")
            if not ii:
                continue
            name = p["title"].removeprefix("File:").replace(" ", "_")
            result[name] = {
                "name": name,
                "url": ii[0]["url"],
                "width": ii[0].get("width"),
                "height": ii[0].get("height"),
            }
        if i + 45 < len(unique):
            time.sleep(0.2)
    return result


def parse_engage_class_sprites(wikitext: str) -> dict[str, list[str]]:
    """Return {class_en: [Ma_fe17_*.png, ...]}."""
    out: dict[str, list[str]] = {}
    for row_raw in re.split(r"\n\|-[^\n]*\n", wikitext):
        lines = [ln.strip() for ln in row_raw.splitlines() if ln.strip() and not ln.startswith(("!", "{|", "|}"))]
        cells: list[str] = []
        for ln in lines:
            if ln.startswith("|"):
                cells.append(ln[1:].strip())
            elif cells:
                cells[-1] += " " + ln
        if len(cells) < 2:
            continue
        name_cell = cells[0]
        m_link = re.search(r"\[\[([^|\]]+)(?:\|([^\]]+))?\]\]", name_cell)
        cls_name = (m_link.group(2) or m_link.group(1)).strip() if m_link else re.sub(r"<[^>]+>", "", name_cell).strip()
        cls_name = re.sub(r"\s*\([^)]*\)\s*", "", cls_name).strip()
        files = [f.strip().replace(" ", "_") for f in re.findall(r"\[\[File:([^|\]]+)", cells[1], flags=re.IGNORECASE)]
        files = [f[0].upper() + f[1:] for f in files if f]
        if cls_name and files:
            out.setdefault(cls_name, []).extend(files)
    return out


def download_batch(url_map: dict[str, dict[str, Any]], dest_dir: Path, rel_prefix: str) -> dict[str, dict[str, Any]]:
    dest_dir.mkdir(parents=True, exist_ok=True)
    out: dict[str, dict[str, Any]] = {}

    def _one(fname: str, info: dict[str, Any]) -> tuple[str, dict[str, Any]]:
        safe = re.sub(r"[^A-Za-z0-9._+-]+", "_", fname)
        dst = dest_dir / safe
        if not (dst.is_file() and dst.stat().st_size > 0):
            req = urllib.request.Request(info["url"], headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=25) as resp:
                dst.write_bytes(resp.read())
        raw = dst.read_bytes()
        return fname, {
            "name": fname,
            "local_path": f"{rel_prefix}/{safe}",
            "source_url": info["url"],
            "sha256": hashlib.sha256(raw).hexdigest(),
            "bytes": len(raw),
            "width": info.get("width"),
            "height": info.get("height"),
        }

    with ThreadPoolExecutor(max_workers=4) as pool:
        futs = [pool.submit(_one, k, v) for k, v in sorted(url_map.items())]
        for fut in as_completed(futs):
            k, rec = fut.result()
            out[k] = rec
    return out


def main() -> int:
    print("1. Fetching List of classes in Fire Emblem Engage...")
    pg = api_get(
        {
            "action": "query",
            "prop": "revisions",
            "rvprop": "content",
            "rvslots": "main",
            "titles": "List of classes in Fire Emblem Engage",
            "format": "json",
            "formatversion": "2",
        }
    )
    wikitext = pg["query"]["pages"][0]["revisions"][0]["slots"]["main"]["content"]
    cls_sprites = parse_engage_class_sprites(wikitext)
    print(f"   Found sprites for {len(cls_sprites)} Engage classes.")

    # Pick primary sprite per class
    engage_class_primary: dict[str, str] = {}
    needed_engage: list[str] = ["Is_fe17_lunar_brace+.png", "Is_fe17_reprisal+.png"]
    needed_engage.extend(FE17_RANK_FILES.values())
    for cls_en, files in cls_sprites.items():
        primary = files[0]
        engage_class_primary[cls_en] = primary
        needed_engage.append(primary)
        # Also include unique character variants for Noble / Lord / Wing Tamer / Sentinel / Picket / Fell Child
        if len(files) > 1 and cls_en in ("Noble", "Lord", "Wing Tamer", "Sentinel", "Picket", "Fell Child", "Dragon Child"):
            needed_engage.extend(files[:4])

    print(f"2. Resolving {len(set(needed_engage))} Engage icon URLs + {len(FE16_RANK_FILES)} Three Houses rank icon URLs...")
    engage_urls = fetch_image_urls(needed_engage)
    th_rank_urls = fetch_image_urls(list(FE16_RANK_FILES.values()))
    print(f"   Resolved Engage={len(engage_urls)}, 3H ranks={len(th_rank_urls)}")

    print("3. Downloading Engage icons...")
    eng_dl = download_batch(engage_urls, ENGAGE_ICONS_DIR, "wiki-icons")
    print(f"   Downloaded/verified {len(eng_dl)} Engage icons.")

    print("4. Downloading Three Houses rank icons...")
    th_dl = download_batch(th_rank_urls, TH_ICONS_DIR, "original/icons")
    print(f"   Downloaded/verified {len(th_dl)} Three Houses rank icons.")

    ENGAGE_MANIFEST.write_text(
        json.dumps(
            {
                "source": "https://fireemblemwiki.org",
                "retrieved_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "class_sprites_en": cls_sprites,
                "class_en_to_zh": ENGAGE_CLASS_EN_TO_ZH,
                "rank_icons": FE17_RANK_FILES,
                "missing_skill_icons": {
                    "月之手镯+": "Is_fe17_lunar_brace+.png",
                    "血仇+": "Is_fe17_reprisal+.png",
                },
                "icons": eng_dl,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    TH_RANK_MANIFEST.write_text(
        json.dumps(
            {
                "source": "https://fireemblemwiki.org",
                "retrieved_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "rank_icons": FE16_RANK_FILES,
                "icons": th_dl,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print("DONE!")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
