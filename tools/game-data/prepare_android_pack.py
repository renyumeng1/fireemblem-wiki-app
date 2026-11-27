from __future__ import annotations

import argparse
from contextlib import closing
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import tempfile
from typing import Any
import uuid

try:
    from PIL import Image, ImageDraw
except ImportError:
    Image = None
    ImageDraw = None

from th_skill_zh import (
    ENGAGE_CATEGORY_DESCRIPTIONS_ZH,
    TH_BATTALION_STAT_ZH,
    TH_CATEGORY_DESCRIPTIONS_ZH,
    TH_CLASS_TIER_MIN_LEVEL,
    TH_EXTRA_SKILL_NAMES_ZH,
    TH_NON_FE16_LEAKED_SKILLS,
    TH_PROWESS_BASE_WEAPON_ZH,
    TH_PROWESS_ICON_PREFIX,
    TH_PROWESS_LEVELS,
    TH_PROWESS_RANK_REQ,
    TH_RECRUITMENT_GUIDE,
    TH_ROUTE_GUIDES,
    TH_SECTION_ZH,
    TH_SUPPORT_DISCOUNT_RULE,
    clean_combat_art_cost,
    combat_art_weapon_zh,
    localize_th_ability_source,
    translate_th_ability_effect,
    translate_th_class_notes,
    translate_th_combat_art_notes,
)
from zh_localization import (
    STAT_KEY_ZH,
    TH_CHARACTERS,
    TH_CRESTS,
    TH_FACTIONS_AND_ROUTES,
    TH_WEAPON_CATEGORIES,
    _translate_th_gambit,
    localize_pack_db,
    localize_stat_dict,
    translate_class_title,
    translate_equipment_title,
    translate_join_condition,
    translate_skill_title,
)


APPLICATION_ID = 0x46454744
SCHEMA_VERSION = 1
STRIP_TABLES = (
    "workbook_cells",
    "workbook_image_placements",
    "workbook_sheets",
    "image_ocr_results",
)
INDEX_STATEMENTS = (
    "CREATE INDEX IF NOT EXISTS idx_entries_category_title ON content_entries(category_id, title COLLATE NOCASE, id)",
    "CREATE INDEX IF NOT EXISTS idx_relations_source ON content_relations(source_entry_id, relation_type)",
    "CREATE INDEX IF NOT EXISTS idx_relations_target ON content_relations(target_entry_id, relation_type)",
    "CREATE INDEX IF NOT EXISTS idx_entry_assets_entry ON entry_assets(entry_id, role)",
    "CREATE INDEX IF NOT EXISTS idx_entry_sources_entry ON entry_sources(entry_id)",
)

_THREE_HOUSES_CHAR_ALIASES: dict[str, tuple[str, ...]] = {
    "lorenz": ("lorenz", "lorentz"),
    "ignatz": ("ignatz", "ignace"),
    "bernadetta": ("bernadetta", "bernadette"),
}

_THREE_HOUSES_MONSTER_PORTRAIT_KEYS: dict[str, tuple[str, ...]] = {
    "the immaculate one": ("immaculate one",),
    "black beast": ("black beast",),
    "giant bird": ("giant bird portrait",),
    "giant crawler": ("giant crawler portrait",),
    "giant demonic beast": ("giant demonic beast portrait",),
    "giant wolf": ("giant wolf portrait",),
    "golem": ("golem portrait",),
    "altered golem": ("golem portrait",),
    "guardian golem": ("golem portrait",),
    "titanus": ("titanus portrait",),
    "white beast": ("white beast portrait",),
    "wild demonic beast": ("wild demonic beast portrait",),
}


def _norm_tokens(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (text or "").lower()).strip()


def _three_houses_portrait_rank(title: str) -> tuple[int, str, str]:
    lower = title.lower()
    if any(k in lower for k in ("5 year", "5year")):
        return (1, "character_portrait_timeskip", title)
    if any(k in lower for k in ("young", "without glasses", "with glasses", "sothis", "trickster", "byleth-f", "constance 02", "5 years2", "5years alt", "5years 2", "portrait 2")):
        return (2, "character_portrait_variant", title)
    return (0, "character_portrait_primary", title)


def _three_houses_sprite_rank(title: str) -> tuple[int, str]:
    lower = title.lower()
    is_enemy = "enemy" in lower or "unused" in lower
    is_part2 = "part 2" in lower or "timeskip" in lower
    is_ally = "ally" in lower or "other" in lower
    tier = (1 if is_enemy else 0) * 4 + (1 if is_part2 else 0) * 2 + (1 if is_ally else 0)
    return (tier, title)


def _convert_image_to_webp(
    src: Path,
    dst: Path,
    max_dim: int,
    *,
    dark_badge_bg: bool = False,
    pixel_upscale_min: int = 0,
    lossless_small: bool = False,
) -> tuple[int, int, int, str]:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if Image is None:
        shutil.copy2(src, dst)
        return (0, 0, dst.stat().st_size, _sha256(dst))
    with Image.open(src) as img:
        try:
            img.seek(0)
        except EOFError:
            pass
        rgba = img.convert("RGBA") if img.mode not in ("RGB", "RGBA") else img.copy()
        w0, h0 = rgba.size
        if dark_badge_bg and ImageDraw is not None:
            canvas_size = 96
            bg = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
            draw = ImageDraw.Draw(bg)
            draw.rounded_rectangle(
                (2, 2, canvas_size - 3, canvas_size - 3),
                radius=20,
                fill=(43, 54, 75, 255),
                outline=(84, 102, 134, 255),
                width=2,
            )
            icon = rgba.copy()
            target = 64
            scale = min(target / max(w0, 1), target / max(h0, 1))
            nw = max(1, int(round(w0 * scale)))
            nh = max(1, int(round(h0 * scale)))
            resampler = Image.Resampling.LANCZOS if hasattr(Image, "Resampling") else Image.LANCZOS
            icon = icon.resize((nw, nh), resampler)
            bg.alpha_composite(icon, ((canvas_size - nw) // 2, (canvas_size - nh) // 2))
            rgba = bg
        elif pixel_upscale_min > 0 and max(w0, h0) < pixel_upscale_min and max(w0, h0) > 0:
            factor = max(2, pixel_upscale_min // max(w0, h0))
            nearest = Image.Resampling.NEAREST if hasattr(Image, "Resampling") else Image.NEAREST
            rgba = rgba.resize((w0 * factor, h0 * factor), nearest)
        elif max(rgba.size) > max_dim:
            rgba.thumbnail((max_dim, max_dim))
        if lossless_small and max(rgba.size) <= 128:
            rgba.save(dst, format="WEBP", lossless=True, method=4)
        else:
            rgba.save(dst, format="WEBP", quality=82, method=4)
        w, h = rgba.size
    return (w, h, dst.stat().st_size, _sha256(dst))


def _cleanup_engage_bond_rings(conn: sqlite3.Connection) -> int:
    rows = conn.execute(
        """
        SELECT e.id, e.title, COALESCE(e.description, '')
        FROM content_entries e
        WHERE e.category_id = 'bond-rings'
          AND NOT EXISTS (SELECT 1 FROM entry_assets ea WHERE ea.entry_id = e.id)
        """
    ).fetchall()
    bad_titles = {"A", "B", "C", "D", "输出型", "增益型", "辅助型", "防御型", "妨碍型"}
    removed: list[str] = []
    for eid, title, desc in rows:
        if (
            (title or "").strip() in bad_titles
            or "在正常佩戴纹章士的玩法下" in desc
            or "特技类型特殊说明" in desc
            or "【输出 型】" in desc
        ):
            removed.append(eid)
    if removed:
        _delete_entries_cascade(conn, removed)
        _refresh_category_count(conn, "bond-rings")
    return len(removed)


_ENGAGE_CLASS_ZH_TO_SPRITE: dict[str, str] = {
    "主教": "Ma_fe17_high_priest_firene_art.png",
    "义警队": "Ma_fe17_sentinel_fogado.png",
    "义警队（佛贾特）": "Ma_fe17_sentinel_fogado.png",
    "义警队（蜜丝提拉）": "Ma_fe17_sentinel_timerra.png",
    "僧侣": "Ma_fe17_martial_monk_firene.png",
    "僧侣大师": "Ma_fe17_martial_master_firene_art.png",
    "刀剑将领": "Ma_fe17_swordmaster_firene.png",
    "刀剑战士": "Ma_fe17_sword_fighter_firene.png",
    "剑飞马骑士": "Ma_fe17_sword_flier_firene.png",
    "剑骑士": "Ma_fe17_sword_cavalier_firene.png",
    "勇士": "Ma_fe17_warrior_firene_axe.png",
    "圣者": "Ma_fe17_sage_firene.png",
    "圣骑士": "Ma_fe17_paladin_firene_sword.png",
    "将军": "Ma_fe17_general_firene_sword.png",
    "小偷": "Ma_fe17_thief_firene.png",
    "弓箭手": "Ma_fe17_archer_firene.png",
    "弓骑士": "Ma_fe17_bow_knight_firene.png",
    "弗罗刺祝": "Ma_fe17_vidame_céline_tome.png",
    "弗罗剌祝": "Ma_fe17_vidame_céline_tome.png",
    "斧战士": "Ma_fe17_axe_fighter_firene.png",
    "斧飞马骑士": "Ma_fe17_axe_flier_firene.png",
    "斧骑士": "Ma_fe17_axe_cavalier_firene.png",
    "斯雷普尼尔": "Ma_fe17_sleipnir_rider_hortensia_staff.png",
    "无畏英雄": "Ma_fe17_hero_firene_sword.png",
    "林德普尔姆": "Ma_fe17_lindwurm_ivy.png",
    "枪战士": "Ma_fe17_lance_fighter_firene.png",
    "枪斗士": "Ma_fe17_halberdier_firene.png",
    "枪飞马骑士": "Ma_fe17_lance_flier_firene.png",
    "枪骑士": "Ma_fe17_lance_cavalier_firene.png",
    "格里芬骑士": "Ma_fe17_griffin_knight_firene_sword.png",
    "狂斗士": "Ma_fe17_berserker_firene.png",
    "狙击兵": "Ma_fe17_tireur_d'élite_alcryst.png",
    "狙击手": "Ma_fe17_sniper_firene.png",
    "狼骑士": "Ma_fe17_wolf_knight_firene_knife.png",
    "王室贵族女": "Ma_fe17_noble_céline_tome.png",
    "王室贵族男": "Ma_fe17_noble_alfred.png",
    "王室贵族（锡莉奴）": "Ma_fe17_noble_céline_tome.png",
    "王室贵族（阿尔弗雷德）": "Ma_fe17_noble_alfred.png",
    "皇家骑士": "Ma_fe17_royal_knight_firene_lance.png",
    "神龙之子": "Ma_fe17_dragon_child_alear_m.png",
    "神龙之王": "Ma_fe17_divine_dragon_alear_m.png",
    "继承者": "Ma_fe17_successeur_diamant.png",
    "美露莘": "Ma_fe17_melusine_zelestia_sword.png",
    "舞蹈家": "Ma_fe17_dancer_seadall.png",
    "英勇骑士": "Ma_fe17_great_knight_firene_sword.png",
    "草叉枪兵": "Ma_fe17_picket_timerra.png",
    "邪龙之女": "Ma_fe17_fell_child_nel_lance.png",
    "邪龙之女（爱尔）": "Ma_fe17_fell_child_nel_lance.png",
    "邪龙之女（贝珥）": "Ma_fe17_fell_child_veyle_tome.png",
    "邪龙之子": "Ma_fe17_fell_child_rafal_axe.png",
    "邱比特": "Ma_fe17_cupido_fogado.png",
    "重剑兵": "Ma_fe17_sword_armor_firene.png",
    "重斧兵": "Ma_fe17_axe_armor_firene.png",
    "重枪兵": "Ma_fe17_lance_armor_firene.png",
    "阿巴尼尔": "Ma_fe17_avenir_alfred.png",
    "附魔师": "Ma_fe17_enchanter_firene_art.png",
    "领主剑": "Ma_fe17_lord_diamant.png",
    "领主弓": "Ma_fe17_lord_alcryst.png",
    "领主（史塔卢克）": "Ma_fe17_lord_alcryst.png",
    "领主（帝亚曼德）": "Ma_fe17_lord_diamant.png",
    "飞马骑士": "Ma_fe17_axe_flier_firene.png",
    "飞龙骑士": "Ma_fe17_wyvern_knight_firene_lance.png",
    "驯兽师": "Ma_fe17_wing_tamer_ivy.png",
    "驯兽师（奥尔坦希亚）": "Ma_fe17_wing_tamer_hortensia_staff.png",
    "驯兽师（艾比）": "Ma_fe17_wing_tamer_ivy.png",
    "魔法加农炮兵": "Ma_fe17_mage_cannoneer_firene.png",
    "魔法师": "Ma_fe17_mage_firene.png",
    "魔法师骑士": "Ma_fe17_mage_knight_firene_tome.png",
}

_ENGAGE_PROF_TITLE_TO_ICON: dict[str, str] = {
    "剑术资质": "FE17RankSword.png",
    "枪术资质": "FE17RankLance.png",
    "斧术资质": "FE17RankAxe.png",
    "弓术资质": "FE17RankBow.png",
    "短剑资质": "FE17RankKnife.png",
    "魔法资质": "FE17RankTome.png",
    "杖术资质": "FE17RankStaff.png",
    "体术资质": "FE17RankArt.png",
}


def _upsert_content_asset_row(
    conn: sqlite3.Connection,
    asset_cols: set[str],
    asset_id: str,
    original_rel: str,
    app_rel: str,
    src_file: Path,
    classification: str,
    source_meta: dict[str, Any],
    w: int | None = None,
    h: int | None = None,
    app_bytes: int | None = None,
    app_sha: str | None = None,
) -> None:
    if "original_file" in asset_cols and "source_sha256" in asset_cols:
        src_sha = source_meta.get("sha256") or _sha256(src_file)
        src_bytes = int(source_meta.get("bytes") or src_file.stat().st_size)
        proc_json = json.dumps(
            {
                "manifest_source_id": "fireemblemwiki-org-icons",
                "source_url": source_meta.get("source_url"),
            },
            ensure_ascii=False,
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO content_assets(
                asset_id, original_file, app_file, source_sha256, app_sha256,
                mime_type, width, height, source_bytes, app_bytes,
                classification, classification_status, rights_status, redistribution_allowed, processing_json
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                asset_id,
                original_rel,
                app_rel,
                src_sha,
                app_sha or src_sha,
                "image/webp",
                w or source_meta.get("width"),
                h or source_meta.get("height"),
                src_bytes,
                app_bytes or src_bytes,
                classification,
                "reviewed",
                "personal_use_only",
                0,
                proc_json,
            ),
        )
    else:
        conn.execute(
            "INSERT OR REPLACE INTO content_assets(asset_id, app_file) VALUES(?,?)",
            (asset_id, app_rel),
        )


def _register_engage_wiki_icons(
    conn: sqlite3.Connection,
    engage_root: Path,
) -> tuple[list[tuple[Path, str, bool, int]], dict[str, Path]]:
    manifest_path = engage_root / "assets" / "wiki-icons-manifest.json"
    if not manifest_path.is_file():
        return ([], {})
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    icons_meta: dict[str, dict[str, Any]] = manifest.get("icons", {})
    asset_cols = {row[1] for row in conn.execute("PRAGMA table_info(content_assets)")}
    assets_base = engage_root / "assets"

    pending_conversions: dict[str, tuple[Path, str, bool, int]] = {}
    registered_ids: set[str] = set()

    def register_icon(
        entry_id: str,
        icon_filename: str,
        role: str,
        classification: str,
        *,
        dark_badge_bg: bool = False,
        pixel_upscale_min: int = 0,
    ) -> None:
        meta = icons_meta.get(icon_filename)
        if not meta:
            return
        src_file = assets_base / meta["local_path"]
        if not src_file.is_file():
            return
        asset_id = f"fe17_wiki_icon:{icon_filename}"
        out_name = f"fe17_{hashlib.sha256(asset_id.encode('utf-8')).hexdigest()[:24]}.webp"
        app_rel = f"assets/app/{out_name}"
        if asset_id not in registered_ids:
            registered_ids.add(asset_id)
            _upsert_content_asset_row(
                conn,
                asset_cols,
                asset_id,
                meta["local_path"],
                app_rel,
                src_file,
                classification,
                meta,
            )
            pending_conversions[app_rel] = (src_file, app_rel, dark_badge_bg, pixel_upscale_min)
        conn.execute(
            "INSERT OR IGNORE INTO entry_assets(entry_id, asset_id, role, locator) VALUES(?,?,?,?)",
            (entry_id, asset_id, role, icon_filename),
        )

    # 1. All 68 Engage classes -> real FE17 pixel class sprites
    for eid, title in conn.execute("SELECT id, title FROM content_entries WHERE category_id='classes'").fetchall():
        sprite = _ENGAGE_CLASS_ZH_TO_SPRITE.get((title or "").strip())
        if sprite:
            register_icon(eid, sprite, "class_sprite_primary", "engage_class_sprite", pixel_upscale_min=96)

    # 2. Missing 2 Engage skills (月之手镯+, 血仇+) -> 437/437 = 100%
    missing_skills: dict[str, str] = manifest.get("missing_skill_icons", {})
    for eid, title in conn.execute("SELECT id, title FROM content_entries WHERE category_id='skills'").fetchall():
        icon_fn = missing_skills.get((title or "").strip())
        if icon_fn:
            register_icon(eid, icon_fn, "skill_icon_candidate", "engage_skill_icon")

    # 3. All 8 Engage weapon-proficiency entries -> real FE17 weapon-rank icons on royal-slate badge
    for eid, title in conn.execute("SELECT id, title FROM content_entries WHERE category_id='weapon-proficiency'").fetchall():
        icon_fn = _ENGAGE_PROF_TITLE_TO_ICON.get((title or "").strip())
        if icon_fn:
            register_icon(eid, icon_fn, "weapon_proficiency_icon", "engage_weapon_rank_icon", dark_badge_bg=True)

    # 4. Track header rank icons (copied to assets/app/rank_<trackKey>.webp)
    rank_files: dict[str, Path] = {}
    for track_key, icon_fn in manifest.get("rank_icons", {}).items():
        meta = icons_meta.get(icon_fn)
        if meta:
            p = assets_base / meta["local_path"]
            if p.is_file():
                rank_files[track_key] = p

    return (list(pending_conversions.values()), rank_files)


def _propagate_engage_bond_ring_portraits(conn: sqlite3.Connection) -> int:
    rows = conn.execute(
        """
        SELECT e.title, ea.asset_id, ea.role, ea.locator
        FROM content_entries e
        JOIN entry_assets ea ON ea.entry_id = e.id
        WHERE e.category_id = 'bond-rings'
          AND ea.role = 'bond_ring_portrait_candidate'
        ORDER BY e.id, ea.asset_id
        """
    ).fetchall()
    by_title: dict[str, tuple[str, str, str]] = {}
    for title, asset_id, role, locator in rows:
        by_title.setdefault(title, (asset_id, role, locator or ""))
    if not by_title:
        return 0
    missing = conn.execute(
        """
        SELECT e.id, e.title
        FROM content_entries e
        WHERE e.category_id = 'bond-rings'
          AND NOT EXISTS (SELECT 1 FROM entry_assets ea WHERE ea.entry_id = e.id)
        """
    ).fetchall()
    inserted = 0
    for entry_id, title in missing:
        match = by_title.get(title)
        if not match:
            continue
        asset_id, role, locator = match
        conn.execute(
            "INSERT OR IGNORE INTO entry_assets(entry_id, asset_id, role, locator) VALUES(?,?,?,?)",
            (entry_id, asset_id, role, locator),
        )
        inserted += 1
    return inserted


def _ingest_three_houses_manifest_assets(
    conn: sqlite3.Connection,
    assets_base: Path,
    manifest_path: Path,
    output_game_dir: Path,
) -> tuple[int, int]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    raw_assets = [a for a in manifest.get("assets", []) if isinstance(a, dict)]
    portraits = [a for a in raw_assets if a.get("local_category") == "portraits"]
    sprites = [a for a in raw_assets if a.get("local_category") == "sprites"]

    # Map normalized portrait titles once
    portrait_norm = [(a, _norm_tokens(a["title"].removeprefix("File:").rsplit(".", 1)[0])) for a in portraits]
    sprite_norm = [(a, _norm_tokens(a["title"].removeprefix("File:").rsplit(".", 1)[0])) for a in sprites]

    pending_links: list[tuple[str, dict[str, Any], str]] = []
    primary_by_entry: dict[str, dict[str, Any]] = {}

    # 1. Characters -> Portraits
    chars = conn.execute(
        "SELECT id, COALESCE(title_en, title) FROM content_entries WHERE category_id='characters'"
    ).fetchall()
    for entry_id, name_en in chars:
        key = _norm_tokens(name_en)
        aliases = _THREE_HOUSES_CHAR_ALIASES.get(key, (key,))
        matched = [
            a for a, norm_title in portrait_norm
            if any(f" {alias} " in f" {norm_title} " for alias in aliases)
        ]
        matched.sort(key=lambda item: _three_houses_portrait_rank(item["title"]))
        if matched:
            primary_by_entry[entry_id] = matched[0]
        for idx, asset in enumerate(matched):
            _, role, _ = _three_houses_portrait_rank(asset["title"])
            if idx == 0 and role != "character_portrait_primary":
                role = "character_portrait_primary"
            pending_links.append((entry_id, asset, role))

    # 2. Classes -> Sprites & Monster/Boss Portraits
    classes = conn.execute(
        "SELECT id, COALESCE(title_en, title) FROM content_entries WHERE category_id='classes'"
    ).fetchall()
    for entry_id, name_en in classes:
        stripped = re.sub(r"\([^)]*\)", "", name_en).strip()
        key = _norm_tokens(stripped)
        if not key:
            continue
        matched_sprites = [
            a for a, norm_title in sprite_norm
            if (f"fe16 {key} " in f"{norm_title} " or f" {key} fe16" in f" {norm_title}")
        ]
        matched_sprites.sort(key=lambda item: _three_houses_sprite_rank(item["title"]))
        if matched_sprites:
            primary_by_entry[entry_id] = matched_sprites[0]
            pending_links.append((entry_id, matched_sprites[0], "class_sprite_primary"))
            for variant in matched_sprites[1:4]:
                pending_links.append((entry_id, variant, "character_portrait_variant"))
            continue

        monster_keys = _THREE_HOUSES_MONSTER_PORTRAIT_KEYS.get(key, ())
        if monster_keys:
            matched_portraits = [
                a for a, norm_title in portrait_norm
                if any(mk in norm_title for mk in monster_keys)
            ]
            matched_portraits.sort(key=lambda item: item["title"])
            if matched_portraits:
                primary_by_entry[entry_id] = matched_portraits[0]
                pending_links.append((entry_id, matched_portraits[0], "class_portrait_primary"))

    # 3. Propagate across content_relations (recruitment, growth, supports)
    rel_rows = conn.execute(
        "SELECT source_entry_id, target_entry_id, relation_type FROM content_relations"
    ).fetchall()
    for src_id, dst_id, rel_type in rel_rows:
        if rel_type in ("recruitment_condition_for", "character_recruitment") and dst_id in primary_by_entry:
            pending_links.append((src_id, primary_by_entry[dst_id], "character_portrait_primary"))
        elif rel_type in ("class_growth_profile", "class_growth") and dst_id in primary_by_entry:
            role = "class_sprite_primary" if primary_by_entry[dst_id].get("local_category") == "sprites" else "class_portrait_primary"
            pending_links.append((src_id, primary_by_entry[dst_id], role))
        elif rel_type in ("support_pair_character", "support_member") and dst_id in primary_by_entry:
            pending_links.append((src_id, primary_by_entry[dst_id], "support_character_portrait"))

    if not pending_links:
        return (0, 0)

    asset_cols = {row[1] for row in conn.execute("PRAGMA table_info(content_assets)")}
    has_full_cols = "original_file" in asset_cols and "source_sha256" in asset_cols

    referenced_assets: dict[str, dict[str, Any]] = {}
    for _, asset, _ in pending_links:
        referenced_assets[asset["asset_id"]] = asset

    dest_app_dir = output_game_dir / "assets" / "app"
    if dest_app_dir.exists():
        shutil.rmtree(dest_app_dir)

    copied_count = 0
    total_bytes = 0
    inserted_asset_ids: set[str] = set()
    for asset_id, asset in sorted(referenced_assets.items()):
        rel_src = Path(asset["local_path"])
        src_file = assets_base / rel_src
        if not src_file.is_file():
            continue
        is_sprite = asset.get("local_category") == "sprites"
        max_dim = 128 if is_sprite else 256
        out_name = f"{hashlib.sha256(asset_id.encode('utf-8')).hexdigest()[:32]}.webp"
        dst_file = dest_app_dir / out_name
        app_rel = f"assets/app/{out_name}"
        w, h, app_bytes, app_sha = _convert_image_to_webp(src_file, dst_file, max_dim=max_dim)
        total_bytes += app_bytes
        copied_count += 1
        inserted_asset_ids.add(asset_id)

        if has_full_cols:
            proc_json = json.dumps(
                {
                    "manifest_source_id": "fire-emblem-fandom-images",
                    "manifest_rights_status": asset.get("rights_status", "no_reuse_grant_verified"),
                    "local_category": asset.get("local_category"),
                    "source_page_url": asset.get("source_page_url"),
                },
                ensure_ascii=False,
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO content_assets(
                    asset_id, original_file, app_file, source_sha256, app_sha256,
                    mime_type, width, height, source_bytes, app_bytes,
                    classification, classification_status, rights_status, redistribution_allowed, processing_json
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    asset_id,
                    asset["local_path"],
                    app_rel,
                    asset.get("sha256") or _sha256(src_file),
                    app_sha,
                    "image/webp",
                    w or asset.get("width"),
                    h or asset.get("height"),
                    int(asset.get("downloaded_bytes") or src_file.stat().st_size),
                    app_bytes,
                    f"three_houses_{asset.get('local_category', 'image')}",
                    "reviewed",
                    "personal_use_only",
                    0,
                    proc_json,
                ),
            )
        else:
            conn.execute(
                "INSERT OR REPLACE INTO content_assets(asset_id, app_file) VALUES(?,?)",
                (asset_id, app_rel),
            )

    for entry_id, asset, role in pending_links:
        asset_id = asset["asset_id"]
        if asset_id not in inserted_asset_ids:
            continue
        conn.execute(
            "INSERT OR IGNORE INTO entry_assets(entry_id, asset_id, role, locator) VALUES(?,?,?,?)",
            (entry_id, asset_id, role, asset.get("title", "")),
        )

    return (copied_count, total_bytes)


def _clean_num_text(val: Any) -> str:
    if val is None:
        return ""
    s = str(val).strip()
    if s.endswith(".0") and s[:-2].lstrip("-").isdigit():
        return s[:-2]
    return s


def _format_range_zh(raw: Any) -> str | None:
    if raw is None:
        return None
    s = _clean_num_text(raw).strip()
    if not s or s in ("-", "--", "null", "None"):
        return None
    s = re.sub(r"\(\s*magic\s*/\s*(\d+)\s*\)", r"魔力/\1", s, flags=re.IGNORECASE)
    s = re.sub(r"\bmagic\s*/\s*(\d+)", r"魔力/\1", s, flags=re.IGNORECASE)
    s = re.sub(r"\s*[–—~-]\s*", "～", s)
    return s.strip() or None


def _split_top_level_semicolon(raw: str) -> list[str]:
    parts: list[str] = []
    buf: list[str] = []
    depth = 0
    for ch in raw:
        if ch in ("（", "("):
            depth += 1
            buf.append(ch)
        elif ch in ("）", ")"):
            depth = max(0, depth - 1)
            buf.append(ch)
        elif ch in ("；", ";") and depth == 0:
            item = "".join(buf).strip()
            if item:
                parts.append(item)
            buf = []
        else:
            buf.append(ch)
    tail = "".join(buf).strip()
    if tail:
        parts.append(tail)
    return parts


def _has_table(conn: sqlite3.Connection, name: str) -> bool:
    return conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone() is not None


_THREE_HOUSES_HOUSE_MAP: dict[str, str] = {
    "Edelgard": "黑鹫学级",
    "Hubert": "黑鹫学级",
    "Ferdinand": "黑鹫学级",
    "Linhardt": "黑鹫学级",
    "Caspar": "黑鹫学级",
    "Bernadetta": "黑鹫学级",
    "Dorothea": "黑鹫学级",
    "Petra": "黑鹫学级",
    "Dimitri": "青狮子学级",
    "Dedue": "青狮子学级",
    "Felix": "青狮子学级",
    "Ashe": "青狮子学级",
    "Sylvain": "青狮子学级",
    "Mercedes": "青狮子学级",
    "Annette": "青狮子学级",
    "Ingrid": "青狮子学级",
    "Claude": "金鹿学级",
    "Lorenz": "金鹿学级",
    "Raphael": "金鹿学级",
    "Ignatz": "金鹿学级",
    "Lysithea": "金鹿学级",
    "Marianne": "金鹿学级",
    "Hilda": "金鹿学级",
    "Leonie": "金鹿学级",
    "Yuri": "灰狼学级",
    "Balthus": "灰狼学级",
    "Constance": "灰狼学级",
    "Hapi": "灰狼学级",
    "Seteth": "赛罗司圣教会",
    "Flayn": "赛罗司圣教会",
    "Hanneman": "赛罗司圣教会",
    "Manuela": "赛罗司圣教会",
    "Gilbert": "赛罗司圣教会",
    "Alois": "赛罗司圣教会",
    "Catherine": "赛罗司圣教会",
    "Shamir": "赛罗司圣教会",
    "Cyril": "赛罗司圣教会",
    "Byleth": "主角与独立",
    "Jeritza": "主角与独立",
    "Anna": "主角与独立",
}

_TH_TRACK_SPECS: tuple[tuple[str, str, str, str], ...] = (
    # (track_key, learnable_prefix, stat_key, label_zh)
    ("sword", "Sword", "Sword", "剑术"),
    ("lance", "Lance", "Lance", "枪术"),
    ("axe", "Axe", "Axe", "斧术"),
    ("bow", "Bow", "Bow", "弓术"),
    ("brawl", "Brawl", "Brawling", "格斗术"),
    ("reason", "Reason", "Reason", "理学"),
    ("faith", "Faith", "Faith", "信仰"),
    ("authority", "Auth", "Authority", "指挥"),
    ("armor", "Heavy Armor", "Heavy Armor", "重装"),
    ("riding", "Riding", "Riding", "马术"),
    ("flying", "Flying", "Flying", "飞行"),
)

_TH_UNIVERSAL_TRACK_SKILLS: dict[str, tuple[tuple[str, str, str], ...]] = {
    "sword": (
        ("E+", "剑术 Lv.1", "Sword Prowess Lv 1"),
        ("D+", "剑术 Lv.2", "Sword Prowess Lv 2"),
        ("C+", "剑术 Lv.3", "Sword Prowess Lv 3"),
        ("B+", "剑术 Lv.4", "Sword Prowess Lv 4"),
        ("A+", "剑术 Lv.5", "Sword Prowess Lv 5"),
        ("S", "剑必杀+10", "Sword Crit +10"),
        ("S+", "剑达人", "Swordfaire"),
    ),
    "lance": (
        ("E+", "枪术 Lv.1", "Lance Prowess Lv 1"),
        ("D+", "枪术 Lv.2", "Lance Prowess Lv 2"),
        ("C+", "枪术 Lv.3", "Lance Prowess Lv 3"),
        ("B+", "枪术 Lv.4", "Lance Prowess Lv 4"),
        ("A+", "枪术 Lv.5", "Lance Prowess Lv 5"),
        ("S", "枪必杀+10", "Lance Crit +10"),
        ("S+", "枪达人", "Lancefaire"),
    ),
    "axe": (
        ("E+", "斧术 Lv.1", "Axe Prowess Lv 1"),
        ("D+", "斧术 Lv.2", "Axe Prowess Lv 2"),
        ("C+", "斧术 Lv.3", "Axe Prowess Lv 3"),
        ("B+", "斧术 Lv.4", "Axe Prowess Lv 4"),
        ("A+", "斧术 Lv.5", "Axe Prowess Lv 5"),
        ("S", "斧必杀+10", "Axe Crit +10"),
        ("S+", "斧达人", "Axefaire"),
    ),
    "bow": (
        ("E+", "弓术 Lv.1", "Bow Prowess Lv 1"),
        ("D+", "弓术 Lv.2", "Bow Prowess Lv 2"),
        ("C+", "弓术 Lv.3", "Bow Prowess Lv 3"),
        ("B+", "弓术 Lv.4", "Bow Prowess Lv 4"),
        ("A+", "弓术 Lv.5", "Bow Prowess Lv 5"),
        ("S", "弓必杀+10", "Bow Crit +10"),
        ("S+", "弓达人", "Bowfaire"),
    ),
    "brawl": (
        ("E+", "格斗术 Lv.1", "Brawling Prowess Lv 1"),
        ("D+", "格斗术 Lv.2", "Brawling Prowess Lv 2"),
        ("C+", "格斗术 Lv.3", "Brawling Prowess Lv 3"),
        ("B+", "格斗术 Lv.4", "Brawling Prowess Lv 4"),
        ("A+", "格斗术 Lv.5", "Brawling Prowess Lv 5"),
        ("S", "格斗必杀+10", "Brawl Crit +10"),
        ("S+", "格斗达人", "Fistfaire"),
    ),
    "reason": (
        ("E+", "理学 Lv.1", "Reason Lv 1"),
        ("D+", "理学 Lv.2", "Reason Lv 2"),
        ("C+", "理学 Lv.3", "Reason Lv 3"),
        ("B+", "理学 Lv.4", "Reason Lv 4"),
        ("A+", "理学 Lv.5", "Reason Lv 5"),
        ("S", "黑魔法射程+1", "Black Magic Range +1"),
        ("S+", "黑魔法达人", "Black Tomefaire"),
    ),
    "faith": (
        ("E+", "信仰 Lv.1", "Faith Lv 1"),
        ("D+", "信仰 Lv.2", "Faith Lv 2"),
        ("C+", "信仰 Lv.3", "Faith Lv 3"),
        ("B+", "信仰 Lv.4", "Faith Lv 4"),
        ("A+", "信仰 Lv.5", "Faith Lv 5"),
        ("S", "白魔法射程+1", "White Magic Range +1"),
        ("S+", "白魔法达人", "White Tomefaire"),
    ),
    "authority": (
        ("E+", "指挥 Lv.1", "Authority Lv 1"),
        ("D+", "指挥 Lv.2", "Authority Lv 2"),
        ("C+", "指挥 Lv.3", "Authority Lv 3"),
        ("B+", "指挥 Lv.4", "Authority Lv 4"),
        ("A+", "指挥 Lv.5", "Authority Lv 5"),
    ),
    "armor": (
        ("C", "减轻重量 -3", "Weight -3"),
        ("A+", "减轻重量 -5", "Weight -5"),
        ("S+", "重甲特效无效", "Armored Effect Null"),
    ),
    "riding": (
        ("C", "技巧 +4", "Dexterity +4"),
        ("A+", "移动力 +1", "Movement +1"),
        ("S+", "骑兵特效无效", "Cavalry Effect Null"),
    ),
    "flying": (
        ("B", "警戒姿态", "Alert Stance"),
        ("A+", "警戒姿态+", "Alert Stance+"),
        ("S+", "飞行特效无效", "Flying Effect Null"),
    ),
}

_TH_STARTING_ART_TRACK: dict[str, str] = {
    "Wrath Strike": "sword",
    "Tempest Lance": "lance",
    "Smash": "axe",
    "Curved Shot": "bow",
    "Healing Focus": "brawl",
    "Fade Back": "brawl",
    "Swap": "sword",
    "Shove": "axe",
    "Reposition": "lance",
    "Draw Back": "bow",
}

_ENGAGE_REGION_NORMALIZE: dict[str, str] = {
    "其它": "邪龙之章·其他",
    "其他": "邪龙之章·其他",
}

_ENGAGE_WEAPON_TRACKS: tuple[tuple[str, str, tuple[str, ...], str], ...] = (
    # (track_key, label_zh, token_aliases, prof_title)
    ("sword", "剑术", ("剑", "剑术"), "【剑术资质】"),
    ("lance", "枪术", ("枪", "枪术"), "【枪术资质】"),
    ("axe", "斧术", ("斧", "斧术"), "【斧术资质】"),
    ("bow", "弓术", ("弓", "弓术"), "【弓术资质】"),
    ("dagger", "短剑", ("短剑", "短剑术"), "【短剑资质】"),
    ("reason", "魔法", ("魔道书", "魔法", "魔导书"), "【魔法资质】"),
    ("faith", "杖", ("杖", "杖术"), "【杖术资质】"),
    ("brawl", "体术", ("体术", "格斗"), "【体术资质】"),
)

_RANK_ORDER: dict[str, int] = {
    "固有": 0, "初始": 1, "E": 2, "E+": 3, "D": 4, "D+": 5,
    "C": 6, "C+": 7, "B": 8, "B+": 9, "A": 10, "A+": 11,
    "S": 12, "S+": 13, "绽放": 14,
}


_CHILD_ENTRY_TABLES: tuple[str, ...] = (
    "entry_assets",
    "entry_sources",
    "character_details",
    "class_details",
    "skill_details",
    "equipment_details",
    "emblem_details",
    "bond_ring_details",
    "chapter_details",
    "support_details",
    "activity_details",
    "mechanic_details",
    "achievement_details",
)


def _delete_entries_cascade(conn: sqlite3.Connection, entry_ids: list[str]) -> None:
    if not entry_ids:
        return
    existing_tables = {
        r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
    }
    for eid in entry_ids:
        for tbl in _CHILD_ENTRY_TABLES:
            if tbl in existing_tables:
                conn.execute(f'DELETE FROM "{tbl}" WHERE entry_id=?', (eid,))
        if "content_relations" in existing_tables:
            conn.execute(
                "DELETE FROM content_relations WHERE source_entry_id=? OR target_entry_id=?",
                (eid, eid),
            )
        conn.execute("DELETE FROM content_entries WHERE id=?", (eid,))


def _migrate_entry_links(conn: sqlite3.Connection, src_id: str, dst_id: str) -> None:
    if src_id == dst_id:
        return
    for asset_id, role, locator in conn.execute(
        "SELECT asset_id, role, locator FROM entry_assets WHERE entry_id=?", (src_id,)
    ).fetchall():
        conn.execute(
            "INSERT OR IGNORE INTO entry_assets(entry_id, asset_id, role, locator) VALUES(?,?,?,?)",
            (dst_id, asset_id, role, locator),
        )
    if _has_table(conn, "entry_sources"):
        cols = [r[1] for r in conn.execute("PRAGMA table_info(entry_sources)").fetchall()]
        other_cols = [c for c in cols if c != "entry_id"]
        if other_cols:
            col_sql = ", ".join(other_cols)
            q_sql = ", ".join("?" for _ in cols)
            for row in conn.execute(f"SELECT {col_sql} FROM entry_sources WHERE entry_id=?", (src_id,)).fetchall():
                conn.execute(
                    f"INSERT OR IGNORE INTO entry_sources(entry_id, {col_sql}) VALUES({q_sql})",
                    (dst_id, *row),
                )


def _refresh_category_count(conn: sqlite3.Connection, category_id: str) -> None:
    conn.execute(
        "UPDATE categories SET record_count=(SELECT COUNT(*) FROM content_entries WHERE category_id=?) WHERE id=?",
        (category_id, category_id),
    )


def _consolidate_engage_weapon_proficiency(conn: sqlite3.Connection) -> dict[str, list[dict[str, Any]]]:
    rows = conn.execute(
        "SELECT id, title, description FROM content_entries WHERE category_id='weapon-proficiency' ORDER BY id"
    ).fetchall()
    if not rows:
        return {}

    groups: dict[str, list[tuple[str, str, dict[str, Any]]]] = {}
    for eid, title, raw_desc in rows:
        try:
            fields = json.loads(raw_desc or "{}").get("fields", {})
        except Exception:
            fields = {}
        groups.setdefault(title.strip(), []).append((eid, title.strip(), fields))

    emblem_prof_unlocks: dict[str, list[dict[str, Any]]] = {}
    removed_ids: list[str] = []

    for raw_title, grp in groups.items():
        main_id = grp[0][0]
        clean_title = raw_title.strip("【】").strip()
        mentors: list[tuple[int, str, str, str]] = []
        for eid, _, fields in grp:
            emblem = str(fields.get("对应纹章士 / 马尔斯", "")).strip()
            bond_str = _clean_num_text(fields.get("解锁所需牵绊等级 / 8.0", ""))
            chapters = str(
                fields.get("可习得章节 / 第1章结束到第10章开始\n第22章结束到终章前", "")
            ).replace("\n", "；").strip()
            if emblem and emblem != "--":
                bond_num = int(bond_str) if bond_str.isdigit() else 99
                mentors.append((bond_num, emblem, bond_str, chapters))
            if eid != main_id:
                _migrate_entry_links(conn, eid, main_id)
                removed_ids.append(eid)

        mentors.sort(key=lambda x: (x[0], x[1]))
        clean_fields: dict[str, str] = {}
        unlock_items: list[dict[str, Any]] = []
        for _, emblem, bond_str, chapters in mentors:
            req = f"牵绊 Lv.{bond_str}" if bond_str else "纹章士传授"
            val = f"{req} · {chapters}" if chapters and chapters != "--" else req
            clean_fields[f"纹章士 {emblem}"] = val
            unlock_items.append(
                {
                    "name": f"{emblem} 传授",
                    "requirement": f"牵绊{bond_str}" if bond_str else "纹章士",
                    "target_id": main_id,
                    "is_universal": True,
                    "kind": "纹章士资质",
                }
            )
        emblem_prof_unlocks[raw_title] = unlock_items

        earliest = f"最早牵绊 Lv.{mentors[0][2]}（{mentors[0][1]}）" if mentors and mentors[0][2] else ""
        subtitle = f"共 {len(mentors)} 位纹章士可传授" + (f" · {earliest}" if earliest else "")
        search_text = " ".join(p for p in (clean_title, raw_title, subtitle, *(m[1] for m in mentors)) if p)
        conn.execute(
            "UPDATE content_entries SET title=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (clean_title, subtitle, json.dumps({"fields": clean_fields}, ensure_ascii=False), search_text, main_id),
        )

    _delete_entries_cascade(conn, removed_ids)
    _refresh_category_count(conn, "weapon-proficiency")
    return emblem_prof_unlocks


def _consolidate_engage_emblems(conn: sqlite3.Connection) -> int:
    if not _has_table(conn, "emblem_details"):
        return 0
    rows = conn.execute(
        """
        SELECT e.id, e.title, e.description,
               (SELECT COUNT(*) FROM entry_assets ea WHERE ea.entry_id=e.id) AS img_cnt
        FROM content_entries e
        WHERE e.category_id='emblems'
        """
    ).fetchall()
    if not rows:
        return 0

    parsed: list[tuple[int, str, str, dict[str, Any], int]] = []
    for eid, title, raw_desc, img_cnt in rows:
        try:
            obj = json.loads(raw_desc or "{}")
        except Exception:
            obj = {}
        parsed.append((int(obj.get("source_row", 999999)), eid, title, obj.get("fields", {}) or {}, img_cnt))
    parsed.sort(key=lambda x: x[0])

    # Split into Emblem blocks on image-bearing rows (exactly 20 Emblems in workbook)
    blocks: list[list[tuple[int, str, str, dict[str, Any], int]]] = []
    cur: list[tuple[int, str, str, dict[str, Any], int]] = []
    for item in parsed:
        if item[4] > 0 and len(cur) >= 10:
            blocks.append(cur)
            cur = [item]
        else:
            cur.append(item)
    if cur:
        blocks.append(cur)

    # Guard: if already consolidated (<= 25 rows), don't re-group
    if len(rows) <= 25:
        return len(rows)

    removed_ids: list[str] = []
    label_tokens = {"声优", "简介", "加入章节", "加入条件", "登场作品"}

    for block in blocks:
        main_id = block[0][1]
        # Extract ordered unique column-A metadata tokens
        col_a_seq: list[str] = []
        for _, _, _, fields, _ in block:
            v = str(fields.get("纹章士 / 图片", "")).strip()
            if v and v != "图片" and (not col_a_seq or col_a_seq[-1] != v):
                col_a_seq.append(v)

        names: list[str] = []
        voice_actors: list[str] = []
        bios: list[str] = []
        join_chapter = ""
        join_condition = ""
        origin_game = ""
        max_bond_bonus = ""

        state = "name"
        for tok in col_a_seq:
            if tok in label_tokens:
                state = tok
                continue
            if tok.startswith("牵绊度满级时的能力加成"):
                max_bond_bonus = " · ".join(
                    line.strip() for line in tok.split("：", 1)[-1].splitlines() if line.strip()
                )
                state = "done"
                continue
            if state == "name":
                names.append(tok)
            elif state == "声优":
                voice_actors.append(tok)
            elif state == "简介":
                # Handle dual-character Chrom / Robin block where second character name follows first bio
                if bios and len(tok) <= 6 and "。" not in tok and "“" not in tok:
                    names.append(tok)
                    state = "name"
                else:
                    bios.append(tok.replace("\n", ""))
            elif state == "加入章节":
                join_chapter = tok.replace("\n", " ").strip()
                state = "after_join_ch"
            elif state == "加入条件":
                join_condition = (join_condition + "\n" + tok).strip() if join_condition else tok
            elif state == "登场作品":
                origin_game = tok.replace("\n", " ").strip()
                state = "after_origin"

        emblem_title = " / ".join(dict.fromkeys(names)) if names else block[0][2]
        va_text = "；".join(dict.fromkeys(voice_actors)) if voice_actors else None
        bio_text = "\n".join(dict.fromkeys(bios)) if bios else None

        # Categorize every bond-level unlock row inside this Emblem block
        sync_skills: dict[str, str] = {}
        engage_skills: dict[str, str] = {}
        inherited_skills: dict[str, str] = {}
        bond_effects: dict[str, str] = {}

        for _, eid, row_title, fields, _ in block:
            if eid != main_id:
                _migrate_entry_links(conn, eid, main_id)
                removed_ids.append(eid)

            ability_label = str(
                fields.get("提升牵绊等级后可获得的能力 / Lv.1同步特技【识破】", row_title)
            ).strip()
            detail_desc = str(
                fields.get("详细说明 / 自身发动攻击时，回避+15。回避会随速度提升而提高。", "")
            ).strip()
            sp_val = _clean_num_text(fields.get("继承SP / 250.0", ""))
            note = str(fields.get("备注", "")).strip()

            if not ability_label or ability_label == "--":
                continue

            val_parts: list[str] = []
            if detail_desc and detail_desc != "--":
                val_parts.append(detail_desc)
            if sp_val and sp_val != "--":
                val_parts.append(f"继承消耗：{sp_val} SP")
            if note and note != "--":
                val_parts.append("战斗风格特化：" + note.replace("\n", "；"))
            formatted_val = "\n".join(val_parts) if val_parts else "解锁对应牵绊能力"

            if "同步特技" in ability_label:
                sync_skills[ability_label] = formatted_val
            elif any(k in ability_label for k in ("结合特技", "结合指令技", "结合武器", "结合技")):
                engage_skills[ability_label] = formatted_val
            elif "继承特技" in ability_label:
                inherited_skills[ability_label] = formatted_val
            else:
                bond_effects[ability_label] = formatted_val

        if max_bond_bonus:
            bond_effects["满级牵绊能力加成"] = max_bond_bonus

        is_dlc = "DLC" in join_condition or "神龙之章" in join_condition
        sub_parts = [p for p in ("DLC 纹章士" if is_dlc else "主线纹章士", join_chapter, origin_game) if p]
        subtitle = " · ".join(sub_parts)

        clean_fields: dict[str, str] = {}
        if join_chapter:
            clean_fields["加入章节"] = join_chapter
        if join_condition:
            clean_fields["加入条件"] = join_condition
        if origin_game:
            clean_fields["登场作品"] = origin_game
        if max_bond_bonus:
            clean_fields["满级牵绊加成"] = max_bond_bonus

        search_terms = [
            emblem_title,
            subtitle,
            va_text or "",
            bio_text or "",
            *sync_skills.keys(),
            *engage_skills.keys(),
            *inherited_skills.keys(),
        ]
        conn.execute(
            "UPDATE content_entries SET title=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (
                emblem_title,
                subtitle,
                json.dumps({"fields": clean_fields}, ensure_ascii=False),
                " ".join(t for t in search_terms if t),
                main_id,
            ),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO emblem_details(
                entry_id, bond_level_effects_json, sync_skills_json,
                engage_skills_json, inherited_skills_json, voice_actor, profile
            ) VALUES(?,?,?,?,?,?,?)
            """,
            (
                main_id,
                json.dumps(bond_effects, ensure_ascii=False) if bond_effects else None,
                json.dumps(sync_skills, ensure_ascii=False) if sync_skills else None,
                json.dumps(engage_skills, ensure_ascii=False) if engage_skills else None,
                json.dumps(inherited_skills, ensure_ascii=False) if inherited_skills else None,
                va_text,
                bio_text,
            ),
        )

    _delete_entries_cascade(conn, removed_ids)
    _refresh_category_count(conn, "emblems")
    return len(blocks)


_ENGAGE_CLASS_TITLE_ALIASES: dict[str, str] = {
    "神龙之子（主角琉尔）": "神龙之子",
    "神龙之王（主角琉尔）": "神龙之王",
    "美露莘（赛勒斯提雅）": "美露莘",
    "舞蹈家（赛安达斯）": "舞蹈家",
    "邪龙之子（拉法尔）": "邪龙之子",
}


def _consolidate_engage_classes(conn: sqlite3.Connection) -> int:
    if not _has_table(conn, "class_details"):
        return 0
    rows = conn.execute(
        """
        SELECT e.id, e.title, e.subtitle, e.description, e.search_text,
               cd.tier, cd.movement, cd.movement_type, cd.weapon_ranks_json,
               cd.base_stats_json, cd.growths_json, cd.caps_json,
               cd.promotion_requirement, cd.class_skill
        FROM content_entries e
        LEFT JOIN class_details cd ON cd.entry_id=e.id
        WHERE e.category_id='classes'
        ORDER BY e.id
        """
    ).fetchall()
    if len(rows) <= 80:
        return len(rows)

    groups: dict[str, list[tuple[Any, ...]]] = {}
    removed_ids: list[str] = []

    for row in rows:
        raw_title = (row[1] or "").strip()
        if raw_title == "转职机制说明":
            removed_ids.append(row[0])
            continue
        canonical = _ENGAGE_CLASS_TITLE_ALIASES.get(raw_title, raw_title)
        groups.setdefault(canonical, []).append(row)

    for canonical, grp in groups.items():
        # Prefer row from 06-兵种信息 as primary, else 附录-职业数据表
        def sheet_priority(r: tuple[Any, ...]) -> tuple[int, str]:
            try:
                sh = json.loads(r[3] or "{}").get("source_sheet", "")
            except Exception:
                sh = ""
            order = {"06-兵种信息": 0, "附录-职业数据表": 1, "17-兵种基础能力修正表": 2}
            return (order.get(sh, 3), r[0])

        grp_sorted = sorted(grp, key=sheet_priority)
        main_id = grp_sorted[0][0]

        tier = ""
        movement: int | None = None
        movement_type = ""
        weapon_ranks_text = ""
        class_skill_name = ""
        class_skill_desc = ""
        style_effect = ""
        remark = ""
        promo_req = ""
        base_stats_raw: str | None = None
        growths_raw: str | None = None
        caps_raw: str | None = None

        for r in grp_sorted:
            eid, _, _, raw_desc, _, r_tier, r_mov, r_mtype, r_wr, r_base, r_gr, r_cap, r_promo, r_sk = r
            if eid != main_id:
                _migrate_entry_links(conn, eid, main_id)
                removed_ids.append(eid)
            try:
                fields = json.loads(raw_desc or "{}").get("fields", {}) or {}
            except Exception:
                fields = {}

            if not tier and r_tier and r_tier != "--":
                tier = str(r_tier).strip()
            if not tier:
                t_val = str(fields.get("兵种级别", "")).strip()
                if t_val and t_val != "--":
                    tier = t_val

            if movement is None:
                if r_mov is not None:
                    movement = int(r_mov)
                else:
                    mv_s = _clean_num_text(fields.get("基础移动", ""))
                    if mv_s.isdigit():
                        movement = int(mv_s)

            if not movement_type:
                raw_mt = str(r_mtype or fields.get("战斗类型", "")).strip().strip("【】")
                if raw_mt and raw_mt != "--":
                    movement_type = raw_mt

            if not weapon_ranks_text:
                if r_wr:
                    try:
                        wr_obj = json.loads(r_wr)
                        wt = str(wr_obj.get("source_text") or wr_obj.get("武器资质") or "").strip()
                        if wt and wt != "--":
                            weapon_ranks_text = wt
                    except Exception:
                        pass
                if not weapon_ranks_text:
                    for k in ("基础武器熟练度\n/必要资质", "基础武器熟练度/必要资质", "武器资质"):
                        wt = str(fields.get(k, "")).strip()
                        if wt and wt != "--":
                            weapon_ranks_text = wt
                            break

            if not class_skill_name:
                sk_raw = str(r_sk or "").strip()
                if not sk_raw or sk_raw == "--":
                    for k, v in fields.items():
                        if "兵种可习得特技" in k:
                            sk_raw = str(v or "").strip()
                            break
                if sk_raw and sk_raw != "--":
                    class_skill_name = sk_raw.strip("【】")

            if not class_skill_desc:
                sd = str(fields.get("详细说明 [G]", "")).strip()
                if sd and sd != "--":
                    class_skill_desc = sd

            if not style_effect:
                se = str(fields.get("详细说明 [AC]", "")).strip()
                if se and se != "--":
                    style_effect = se

            if not remark:
                rm = str(fields.get("备注", "")).strip()
                if rm and rm != "--":
                    remark = rm

            if not promo_req and r_promo and str(r_promo).strip() != "--":
                promo_req = str(r_promo).strip()

            if not base_stats_raw and r_base and r_base.strip() not in ("", "{}"):
                base_stats_raw = r_base
            if not growths_raw and r_gr and r_gr.strip() not in ("", "{}"):
                growths_raw = r_gr
            if not caps_raw and r_cap and r_cap.strip() not in ("", "{}"):
                caps_raw = r_cap

        # Fallback: extract growth/caps from 06-兵种信息 fields if typed JSON missing
        def extract_prefixed_stats(prefix: str) -> str | None:
            out: dict[str, int] = {}
            for r in grp_sorted:
                try:
                    fields = json.loads(r[3] or "{}").get("fields", {}) or {}
                except Exception:
                    continue
                for k, v in fields.items():
                    if k.startswith(prefix):
                        stat_k = k[len(prefix) :].strip()
                        if stat_k == "总和":
                            continue
                        clean_v = _clean_num_text(str(v or "").replace("\n", "").strip())
                        if clean_v.lstrip("-").isdigit():
                            out[stat_k] = int(clean_v)
                if out:
                    return json.dumps(out, ensure_ascii=False)
            return None

        if not growths_raw:
            growths_raw = extract_prefixed_stats("兵种成长率补正 / ")
        if not caps_raw:
            caps_raw = extract_prefixed_stats("兵种上限数值 / ")
        if not base_stats_raw:
            base_stats_raw = extract_prefixed_stats("兵种基础能力修正 / ")

        full_skill = (
            f"{class_skill_name}：{class_skill_desc}"
            if class_skill_name and class_skill_desc
            else (class_skill_name or None)
        )
        wr_json = json.dumps({"武器资质": weapon_ranks_text}, ensure_ascii=False) if weapon_ranks_text else None
        clean_fields: dict[str, str] = {}
        if style_effect:
            clean_fields["战斗类型特性"] = style_effect
        if remark:
            clean_fields["限定与备注"] = remark

        sub_parts = [p for p in (tier, movement_type, f"移动 {movement}" if movement is not None else "", weapon_ranks_text) if p]
        subtitle = " · ".join(sub_parts)
        search_text = " ".join(p for p in (canonical, subtitle, class_skill_name, remark) if p)

        conn.execute(
            "UPDATE content_entries SET title=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (
                canonical,
                subtitle,
                json.dumps({"fields": clean_fields}, ensure_ascii=False) if clean_fields else "",
                search_text,
                main_id,
            ),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO class_details(
                entry_id, tier, movement, movement_type, weapon_ranks_json,
                base_stats_json, growths_json, caps_json, promotion_requirement, class_skill
            ) VALUES(?,?,?,?,?,?,?,?,?,?)
            """,
            (
                main_id,
                tier or None,
                movement,
                movement_type or None,
                wr_json,
                localize_stat_dict(base_stats_raw) if base_stats_raw else None,
                localize_stat_dict(growths_raw, is_growth=True) if growths_raw else None,
                localize_stat_dict(caps_raw) if caps_raw else None,
                promo_req or None,
                full_skill,
            ),
        )

    _delete_entries_cascade(conn, removed_ids)
    _refresh_category_count(conn, "classes")
    return len(groups)


def _consolidate_engage_country_investment(conn: sqlite3.Connection) -> int:
    rows = conn.execute(
        "SELECT id, title, description FROM content_entries WHERE category_id='country-investment' ORDER BY id"
    ).fetchall()
    if not rows:
        return 0

    # Strip all 32 raw Nintendo Switch workbook screenshots from country-investment!
    conn.execute(
        """
        DELETE FROM entry_assets
        WHERE entry_id IN (SELECT id FROM content_entries WHERE category_id='country-investment')
          AND role = 'workbook_image'
        """
    )

    if len(rows) <= 4:
        return len(rows)

    groups: dict[str, list[tuple[int, str, dict[str, Any]]]] = {}
    for eid, title, raw_desc in rows:
        try:
            fields = json.loads(raw_desc or "{}").get("fields", {}) or {}
        except Exception:
            fields = {}
        lv_raw = str(fields.get("等级", "")).lower().replace("lv", "").strip()
        lv_num = int(lv_raw) if lv_raw.isdigit() else 99
        groups.setdefault(title.strip(), []).append((lv_num, eid, fields))

    removed_ids: list[str] = []
    for nation, grp in groups.items():
        grp.sort(key=lambda x: x[0])
        main_id = grp[0][1]
        clean_fields: dict[str, str] = {
            "投资总览": f"{nation}共有 5 个投资等级，满级需累计投入 90,000G，可提升遭遇战金/银异形兵出现几率、散步拾取晶石与食材倍率，并解锁国家限定服饰与道具。",
        }
        for lv_num, eid, fields in grp:
            if eid != main_id:
                _migrate_entry_links(conn, eid, main_id)
                removed_ids.append(eid)
            cost = _clean_num_text(fields.get("金额", ""))
            total = _clean_num_text(fields.get("累计金额", ""))
            reward = str(fields.get("升级效果 / 升级奖励 [J]", "")).strip()
            stroll = str(fields.get("升级效果 / 散步", "")).strip()
            skirmish = str(fields.get("升级效果 / 遭遇战 [G]", "")).strip()
            if lv_num == 1:
                clean_fields["Lv.1（初始状态）"] = "无需投资，默认解锁该国基础散步采集与遭遇战。"
                continue
            cost_tag = f"投入 {cost}G" if cost and cost != "-" else ""
            total_tag = f"累计 {total}G" if total and total != "-" else ""
            meta = " · ".join(p for p in (cost_tag, total_tag) if p)
            label = f"Lv.{lv_num}（{meta}）" if meta else f"Lv.{lv_num}"
            parts: list[str] = []
            if reward and reward != "-":
                parts.append("升级奖励：" + "、".join(l.strip() for l in reward.splitlines() if l.strip()))
            if stroll and stroll != "-":
                parts.append("散步拾取：" + "；".join(l.strip() for l in stroll.splitlines() if l.strip()))
            if skirmish and skirmish != "-":
                parts.append("遭遇战变化：" + "；".join(l.strip() for l in skirmish.splitlines() if l.strip()))
            if parts:
                clean_fields[label] = "\n".join(parts)

        subtitle = "国家投资 Lv.1–Lv.5 · 满级累计 90,000G"
        search_text = f"{nation} 国家投资 " + " ".join(clean_fields.values())
        conn.execute(
            "UPDATE content_entries SET title=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (nation, subtitle, json.dumps({"fields": clean_fields}, ensure_ascii=False), search_text, main_id),
        )

    _delete_entries_cascade(conn, removed_ids)
    _refresh_category_count(conn, "country-investment")
    return len(groups)


def _consolidate_engage_growth_mechanics(conn: sqlite3.Connection) -> int:
    rows = conn.execute(
        "SELECT id, title, description FROM content_entries WHERE category_id='growth-mechanics' ORDER BY id"
    ).fetchall()
    if len(rows) <= 3:
        return len(rows)

    # Read actual source rows faithfully (row 3 personal, row 4 class, rows 10/12/14/16/18 Lv.1..5)
    by_row: dict[int, dict[str, Any]] = {}
    for eid, title, raw_desc in rows:
        try:
            obj = json.loads(raw_desc or "{}")
            by_row[int(obj.get("source_row", -1))] = obj.get("fields", {}) or {}
        except Exception:
            pass

    stats_order = ("HP", "力量", "魔力", "技巧", "速度", "幸运", "防守", "魔防", "体格")

    def read_stat_row(row_num: int) -> dict[str, int]:
        f = by_row.get(row_num, {})
        out: dict[str, int] = {}
        for st in stats_order:
            v = _clean_num_text(f.get(f"琉尔总成长率 / {st}", ""))
            if v.lstrip("-").isdigit():
                out[st] = int(v)
        return out

    pers = read_stat_row(3)
    cls = read_stat_row(4)
    total = {st: pers.get(st, 0) + cls.get(st, 0) for st in stats_order if st in pers or st in cls}

    def fmt_stats(d: dict[str, int], suffix: str = "%") -> str:
        return " · ".join(f"{k} {v}{suffix}" for k, v in d.items())

    keep_ids = [r[0] for r in rows[:3]]
    removed_ids = [r[0] for r in rows[3:]]
    for rid in removed_ids:
        _migrate_entry_links(conn, rid, keep_ids[0])

    # Build 3 crystal-clear Chinese Wiki guide entries
    guide_specs = [
        (
            keep_ids[0],
            "固定成长核心规则",
            "超难默认成长模式 · 满 100 进 1 无随机波动",
            {
                "适用模式": "首次游玩「超难」难度默认启用固定成长；通关一次后可在所有难度自由切换「固定成长」与「随机成长」。",
                "总成长率公式": "总成长率 = 角色个人成长率 + 当前职业成长率补正（若佩戴琪姬「星玉的加护」则全属性再 +15%）。",
                "积分槽累加机制": "每项属性各自拥有一个隐藏积分槽，每升 1 级便将对应总成长率累加进积分槽；每满 100 点该属性 +1，并扣除 100 保留余数带入下一级。",
                "1 级初始槽值": "角色处于 1 级初始状态时，隐藏积分槽以「个人成长率」作为初始值（不计入职业成长率）。",
            },
            "核心规则",
            "每升 1 级累加（个人成长率 + 职业成长率），每满 100 点对应属性 +1 并保留余数。",
            "新积分 = 旧积分 + (个人成长率 + 职业成长率)；跳点 = floor(新积分 / 100)；余数 = 新积分 mod 100",
        ),
        (
            keep_ids[1],
            "成长率叠加基准（以神龙之子琉尔为例）",
            "个人成长率 + 职业成长率 = 升级累加值",
            {
                "示例角色与职业": "琉尔（初始职业：神龙之子）",
                "个人成长率": fmt_stats(pers) if pers else "HP 60% · 力量 35% · 魔力 20% · 技巧 45% · 速度 50% · 幸运 25% · 防守 40% · 魔防 25% · 体格 5%",
                "神龙之子职业成长补正": fmt_stats(cls) if cls else "HP 10% · 力量 10% · 魔力 0% · 技巧 10% · 速度 15% · 幸运 5% · 防守 10% · 魔防 10% · 体格 5%",
                "最终总成长率": fmt_stats(total) if total else "HP 70% · 力量 45% · 魔力 20% · 技巧 55% · 速度 65% · 幸运 30% · 防守 50% · 魔防 35% · 体格 10%",
            },
            "基准参数",
            "展示琉尔在「神龙之子」职业下个人成长率与职业补正相加后的总成长率。",
            "总成长率 = 个人成长率 + 职业成长率",
        ),
        (
            keep_ids[2],
            "固定成长逐级演算示例（琉尔 Lv.1 → Lv.5）",
            "展示每一级实际跳点与满 100 扣除后的剩余积分",
            {
                "Lv.1 初始积分（仅个人成长）": fmt_stats(read_stat_row(10), suffix=""),
                "升至 Lv.2 跳点": f"{by_row.get(12, {}).get('列T', 'HP+1、技巧+1、速度+1')}\n结算后剩余积分：HP 30 · 力量 80 · 魔力 40 · 技巧 0 · 速度 15 · 幸运 55 · 防守 90 · 魔防 60 · 体格 15",
                "升至 Lv.3 跳点": f"{by_row.get(14, {}).get('列T', 'HP+1、力量+1、防守+1')}\n结算后剩余积分：HP 0 · 力量 25 · 魔力 60 · 技巧 55 · 速度 80 · 幸运 85 · 防守 40 · 魔防 95 · 体格 25",
                "升至 Lv.4 跳点": f"{by_row.get(16, {}).get('列T', '技巧+1、速度+1、幸运+1、魔防+1')}\n结算后剩余积分：HP 70 · 力量 70 · 魔力 80 · 技巧 10 · 速度 45 · 幸运 15 · 防守 90 · 魔防 30 · 体格 35",
                "升至 Lv.5 跳点": f"{by_row.get(18, {}).get('列T', 'HP+1、力量+1、魔力+1、速度+1、防守+1')}\n结算后剩余积分：HP 40 · 力量 15 · 魔力 0 · 技巧 65 · 速度 10 · 幸运 45 · 防守 40 · 魔防 65 · 体格 45",
            },
            "逐级演算",
            "以神龙之子琉尔 Lv.1 至 Lv.5 为例，直观演示每次升级满 100 进 1 后的跳点结果。",
            None,
        ),
    ]

    for eid, title, subtitle, fields, mtype, rule_txt, formula_txt in guide_specs:
        search_text = " ".join(p for p in (title, subtitle, *fields.values()) if p)
        conn.execute(
            "UPDATE content_entries SET title=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (title, subtitle, json.dumps({"fields": fields}, ensure_ascii=False), search_text, eid),
        )
        if _has_table(conn, "mechanic_details"):
            conn.execute(
                """
                INSERT OR REPLACE INTO mechanic_details(
                    entry_id, mechanic_type, game_version, rule_description, formula_text, parameters_json
                ) VALUES(?,?,?,?,?,?)
                """,
                (eid, mtype, None, rule_txt, formula_txt, None),
            )

    _delete_entries_cascade(conn, removed_ids)
    _refresh_category_count(conn, "growth-mechanics")
    return 3


def _consolidate_engage_paralogues(conn: sqlite3.Connection) -> int:
    rows = conn.execute(
        "SELECT id, title, subtitle, description FROM content_entries WHERE category_id='paralogues' ORDER BY id"
    ).fetchall()
    if not rows:
        return 0

    groups: dict[str, list[tuple[str, str, str, dict[str, Any]]]] = {}
    for eid, title, sub, raw_desc in rows:
        try:
            fields = json.loads(raw_desc or "{}").get("fields", {}) or {}
        except Exception:
            fields = {}
        groups.setdefault(title.strip(), []).append((eid, title.strip(), (sub or "").strip(), fields))

    if len(groups) == len(rows):
        return len(rows)

    removed_ids: list[str] = []
    for title, grp in groups.items():
        main_id = grp[0][0]
        code = ""
        location = ""
        related = ""
        rewards: list[str] = []
        extra_notes: list[str] = []

        for eid, _, _, fields in grp:
            if eid != main_id:
                _migrate_entry_links(conn, eid, main_id)
                removed_ids.append(eid)
            for k, v in fields.items():
                val = str(v or "").strip()
                if not val or val == "--":
                    continue
                if k.startswith("编号"):
                    code = code or val
                elif k.startswith("触发地点"):
                    location = location or val.replace("\n", " · ")
                elif k.startswith("相关角色"):
                    related = related or val.replace("\n", "、")
                elif k.startswith("报酬奖励"):
                    # Pair label-like lines cleanly
                    rewards.append(val.replace("\n", " "))
                elif not k.startswith("外传名称"):
                    extra_notes.append(val.replace("\n", " "))

        # Group rewards pairs like "可从敌方单位获取：" + "【长斧】"
        merged_rewards: list[str] = []
        idx = 0
        dedup_rewards = list(dict.fromkeys(rewards))
        while idx < len(dedup_rewards):
            cur_r = dedup_rewards[idx]
            if cur_r.endswith(("：", ":")) and idx + 1 < len(dedup_rewards):
                merged_rewards.append(f"{cur_r}{dedup_rewards[idx + 1]}")
                idx += 2
            else:
                merged_rewards.append(cur_r)
                idx += 1

        clean_fields: dict[str, str] = {}
        if code:
            clean_fields["外传编号"] = code
        if location:
            clean_fields["触发地点"] = location
        if related:
            clean_fields["关联角色 / 纹章士"] = related
        if merged_rewards:
            clean_fields["报酬与掉落奖励"] = "\n".join(merged_rewards)
        if extra_notes:
            clean_fields["补充说明"] = "\n".join(dict.fromkeys(extra_notes))

        sub_parts = [p for p in (code, location, related) if p]
        subtitle = " · ".join(sub_parts)
        search_text = " ".join(p for p in (title, subtitle, *clean_fields.values()) if p)
        conn.execute(
            "UPDATE content_entries SET subtitle=?, description=?, search_text=? WHERE id=?",
            (subtitle, json.dumps({"fields": clean_fields}, ensure_ascii=False), search_text, main_id),
        )

    _delete_entries_cascade(conn, removed_ids)
    _refresh_category_count(conn, "paralogues")
    return len(groups)


_ENGAGE_BOSS_ZH: dict[str, str] = {
    "Lumera": "琉弥艾尔",
    "Corrupted Lumera": "异形兵琉弥艾尔",
    "Rodine": "罗迪内",
    "Nelucce": "内卢切",
    "Teronda": "特龙达",
    "Hortensia": "奥尔坦希亚",
    "Ivy": "艾比",
    "Hyacinth": "海亚辛斯",
    "Corrupted Morion": "异形兵莫利翁",
    "Alear": "琉尔",
    "the past Alear": "过去的琉尔",
    "Tetchie": "泰奇",
    "Totchie": "托奇",
    "Zephia": "赛琵雅",
    "Marni": "玛伦",
    "Mauvier": "莫布",
    "Seadall": "赛安达斯",
    "Abyme": "阿比姆",
    "Griss": "古利",
    "Veyle": "贝珥",
    "Sombron": "尚卜尔",
    "Emblem Lucina": "纹章士露琪娜",
    "Emblem Lyn": "纹章士琳",
    "Emblem Ike": "纹章士艾克",
    "Emblem Byleth": "纹章士贝雷特",
    "Emblem Corrin": "纹章士神威",
    "Emblem Eirika": "纹章士艾莉可",
    "Emblem Sigurd": "纹章士西格尔特",
    "Emblem Leif": "纹章士里弗",
    "Emblem Micaiah": "纹章士米卡雅",
    "Emblem Roy": "纹章士罗伊",
    "Emblem Celica": "纹章士赛莉卡",
    "Emblem Marth": "纹章士马尔斯",
    "Emblem Tiki": "纹章士琪姬",
    "Emblem Hector": "纹章士海克托尔",
    "Emblem Soren": "纹章士塞涅里欧",
    "Emblem Camilla": "纹章士卡美拉",
    "Emblem Chrom": "纹章士库洛武",
    "Emblem Veronica": "纹章士维洛妮卡",
    "Nel": "爱尔",
    "Nil": "伊尔",
    "Rafal": "拉法尔",
}


def _translate_engage_objective(raw: str | None) -> str | None:
    if not raw:
        return None
    s = raw.strip()
    if not s or s.startswith("{") or "请点击此链接" in s:
        return None
    s_clean = s.rstrip(".").replace(" .", "").strip()
    exact = {
        "Rout the enemy": "全灭敌军",
        "Alear escapes": "琉尔脱离战场",
        "Seadall escapes": "赛安达斯脱离战场",
        "Defeat all six enemy commanders": "击败全部 6 名敌军将领",
        "Recover all of the Emblem Rings, then rout the enemy": "夺回全部纹章士戒指并全灭敌军",
        "Survive": "坚守至规定回合结束",
        "Protect the crystal": "保护水晶",
    }
    if s_clean in exact:
        return exact[s_clean]
    if s_clean.startswith("Defeat "):
        who = s_clean[len("Defeat ") :].strip()
        for en, zh in sorted(_ENGAGE_BOSS_ZH.items(), key=lambda x: -len(x[0])):
            who = re.sub(rf"\b{re.escape(en)}\b", zh, who)
        who = who.replace(", and ", "、").replace(" and ", "与").replace(", ", "、")
        return f"击败{who}"
    return s


_RAW_SCREENSHOT_ASSET_ROLES: tuple[str, ...] = (
    "workbook_image",
    "community_skill_rating_chart",
    "support_matrix_visual_candidate",
    "bond_ring_rating_visual_candidate",
    "character_rating_visual_candidate",
)

_TABLE_PREFIX_USE_RIGHT: tuple[str, ...] = (
    "持有物品信息表",
    "角色信息",
    "角色持有特技",
    "角色能力值面板",
    "额外附加效果",
    "升级效果",
    "战斗相关数值",
    "战斗相关词条解释",
    "游戏系统相关说明",
    "地形效果",
    "兵种基础能力修正",
    "兵种成长率补正",
    "兵种上限数值",
    "各兵种属性上限",
)


def _clean_engage_field_key(raw_key: str) -> str:
    k = re.sub(r"\s*\[[A-Z]{1,3}\]\s*$", "", (raw_key or "").strip()).strip()
    k = re.sub(r"\s*\n\s*", " ", k)
    if " / " in k:
        left, right = [p.strip() for p in k.split(" / ", 1)]
        if any(left.startswith(pref) for pref in _TABLE_PREFIX_USE_RIGHT) and right and not right.startswith("--"):
            k = right
        else:
            k = left
    # Strip verbose parenthetical notes in Excel headers
    k = re.sub(r"（除绿色底[^）]*）", "", k).strip()
    k = re.sub(r"（颜色标记[^）]*）", "", k).strip()
    k = re.sub(r"（消耗道具[^）]*）", "", k).strip()
    k = re.sub(r"（指定并强化[^）]*）", "", k).strip()
    return k.strip() or raw_key.strip()


def _flatten_json_obj_to_text(raw: str | None) -> str | None:
    if not raw:
        return None
    s = raw.strip()
    if not s.startswith("{"):
        return s
    try:
        obj = json.loads(s)
    except Exception:
        return s
    if not isinstance(obj, dict):
        return s
    parts: list[str] = []
    seen_vals: set[str] = set()
    for k, v in obj.items():
        val = _clean_num_text(v).strip()
        if not val or val in ("--", "-") or val in seen_vals:
            continue
        seen_vals.add(val)
        label = _clean_engage_field_key(k)
        if label in ("相关项目", "详细说明", "计算公式", "备注"):
            parts.append(val)
        else:
            parts.append(f"{label}：{val}")
    return "\n".join(parts) if parts else None


def _sanitize_engage_entries(conn: sqlite3.Connection) -> None:
    # 1. Remove raw spreadsheet/Switch screenshot assets from entry_assets
    placeholders = ",".join("?" for _ in _RAW_SCREENSHOT_ASSET_ROLES)
    conn.execute(f"DELETE FROM entry_assets WHERE role IN ({placeholders})", _RAW_SCREENSHOT_ASSET_ROLES)

    # 2. Clean Engage chapter_details objective hyperlink junk & English objectives
    if _has_table(conn, "chapter_details"):
        for entry_id, route, ch_num, obj in conn.execute(
            "SELECT entry_id, route, chapter_number, objective FROM chapter_details"
        ).fetchall():
            new_route = {
                "main_or_paralogue": "主线 / 外传",
                "Main story": "主线章节",
                "Paralogues": "外传",
                "Divine Paralogues": "神龙之章外传",
                "Fell Xenologue": "邪龙之章 DLC",
            }.get(route or "", route)
            new_ch = ch_num
            if ch_num == "Prologue":
                new_ch = "序章"
            elif ch_num and ch_num.startswith("Chapter "):
                new_ch = f"第 {ch_num[8:].strip()} 章"
            elif ch_num and ch_num.startswith("Paralogue "):
                new_ch = f"外传 {ch_num[10:].strip()}"
            new_obj = _translate_engage_objective(obj)
            conn.execute(
                "UPDATE chapter_details SET route=?, chapter_number=?, objective=? WHERE entry_id=?",
                (new_route, new_ch, new_obj, entry_id),
            )

    # 3. Clean Engage mechanic_details JSON blobs in rule_description & formula_text
    if _has_table(conn, "mechanic_details"):
        for entry_id, mtype, gver, rule_desc, formula_txt in conn.execute(
            "SELECT entry_id, mechanic_type, game_version, rule_description, formula_text FROM mechanic_details"
        ).fetchall():
            new_rule = _flatten_json_obj_to_text(rule_desc)
            new_formula = _flatten_json_obj_to_text(formula_txt)
            new_gver = None if (gver and "workbook" in gver.lower()) else gver
            conn.execute(
                "UPDATE mechanic_details SET game_version=?, rule_description=?, formula_text=? WHERE entry_id=?",
                (new_gver, new_rule, new_formula, entry_id),
            )

    # 4. Global sweep of content_entries across ALL Engage categories
    skip_cats = {"characters", "emblems", "classes", "country-investment", "growth-mechanics", "weapon-proficiency", "paralogues"}
    rows = conn.execute(
        "SELECT id, category_id, title, subtitle, description, search_text FROM content_entries"
    ).fetchall()

    for eid, cat, title, subtitle, raw_desc, search_text in rows:
        if cat in skip_cats:
            continue
        new_title = title.replace("\n", " · ").strip() if "\n" in (title or "") else title
        new_sub = (subtitle or "").replace("\n", " · ").strip()
        if not raw_desc or not raw_desc.strip().startswith("{"):
            if new_title != title or new_sub != (subtitle or ""):
                conn.execute("UPDATE content_entries SET title=?, subtitle=? WHERE id=?", (new_title, new_sub, eid))
            continue

        try:
            obj = json.loads(raw_desc)
        except Exception:
            continue
        if not isinstance(obj, dict):
            continue

        fields = obj.get("fields")
        if not isinstance(fields, dict):
            # Strip workbook metadata even if no fields
            for junk_k in ("source_sheet", "source_row", "source_cells", "formula_cache", "normalization_status"):
                obj.pop(junk_k, None)
            conn.execute(
                "UPDATE content_entries SET title=?, subtitle=?, description=? WHERE id=?",
                (new_title, new_sub, json.dumps(obj, ensure_ascii=False), eid),
            )
            continue

        clean_fields: dict[str, str] = {}

        if cat == "gifts" and isinstance(fields.get("赠礼偏好"), dict):
            prefs = fields["赠礼偏好"]
            liked = [g for g, p in prefs.items() if str(p).strip() == "喜欢"]
            disliked = [g for g, p in prefs.items() if str(p).strip() == "讨厌"]
            if liked:
                clean_fields[f"喜欢的赠礼（{len(liked)} 件）"] = "、".join(liked)
            if disliked:
                clean_fields[f"讨厌的赠礼（{len(disliked)} 件）"] = "、".join(disliked)
            new_sub = f"喜欢 {len(liked)} 件 · 讨厌 {len(disliked)} 件"
        elif cat == "supports" and isinstance(fields.get("支援关系与状态"), dict):
            matrix = fields["支援关系与状态"]
            partners: list[str] = []
            bonus = ""
            for k, v in matrix.items():
                val = str(v or "").strip()
                if "支援属性" in val or "本作支援" in k:
                    bonus = val
                elif val == "有":
                    partners.append(k)
            if bonus:
                clean_fields["支援属性加成"] = bonus
            if partners:
                clean_fields[f"可支援对象（{len(partners)} 位）"] = "、".join(partners)
            new_sub = f"可支援 {len(partners)} 位伙伴"
        elif cat == "meals":
            if "特性" in fields and "前缀词条" in fields and len(new_title) <= 2:
                tier_code = str(fields.get("特性", new_title)).strip()
                prefix = str(fields.get("前缀词条", "")).strip()
                new_title = f"{tier_code} 级料理特性：{prefix}" if prefix else f"{tier_code} 级料理特性"
                new_sub = "料理品质前缀与附加效果"
                clean_fields["品质等级"] = tier_code
                if prefix:
                    clean_fields["前缀词条"] = prefix
                for k, v in fields.items():
                    if k in ("特性", "前缀词条"):
                        continue
                    val = _clean_num_text(v).strip()
                    if val and val != "--":
                        clean_fields[_clean_engage_field_key(k)] = val
            elif "厨师" in fields:
                prefix = str(fields.get("前缀词条", "")).strip()
                liked_dishes = [k for k, v in fields.items() if k not in ("厨师", "前缀词条") and str(v).strip() in ("喜欢", "拿手", "擅长")]
                disliked_dishes = [k for k, v in fields.items() if k not in ("厨师", "前缀词条") and str(v).strip() in ("讨厌", "不擅长")]
                if prefix and prefix != "--":
                    clean_fields["专属料理前缀"] = prefix
                if liked_dishes:
                    clean_fields[f"擅长 / 喜欢料理（{len(liked_dishes)} 道）"] = "、".join(liked_dishes)
                if disliked_dishes:
                    clean_fields[f"不擅长 / 讨厌料理（{len(disliked_dishes)} 道）"] = "、".join(disliked_dishes)
                new_sub = f"主厨 · 专属前缀「{prefix}」" if prefix and prefix != "--" else f"主厨 · 擅长 {len(liked_dishes)} 道料理"
            else:
                for k, v in fields.items():
                    val = _clean_num_text(v).strip()
                    if val and val != "--":
                        clean_fields[_clean_engage_field_key(k)] = val
        elif cat == "weapons":
            w_type = str(fields.get("武器类型", "")).replace("\n", " ").strip()
            if w_type and w_type not in ("-", "--"):
                clean_fields["武器类型"] = w_type
            w_range = _format_range_zh(fields.get("武器基础信息 / 射程"))
            for src_k, dst_k in (
                ("武器基础信息 / 所需武器等级", "所需武器等级"),
                ("武器基础信息 / 特效", "特效"),
                ("详细说明", "详细说明"),
                ("武器基础信息 / 获取途径", "获取途径"),
            ):
                raw_v = _clean_num_text(fields.get(src_k, "")).strip()
                if not raw_v or raw_v in ("-", "--"):
                    continue
                if dst_k == "获取途径":
                    lines = [ln.strip() for ln in raw_v.splitlines() if ln.strip() and not ln.strip().endswith(("：", ":")) and not ln.strip().endswith(":-")]
                    raw_v = "\n".join(lines)
                if raw_v:
                    clean_fields[dst_k] = raw_v
            # Keep compact subtitle with weapon type, rank and range
            sub_bits = [
                clean_fields.get("武器类型", ""),
                clean_fields.get("所需武器等级", ""),
                f"射程 {w_range}" if w_range else "",
            ]
            sub_clean = " · ".join(p for p in sub_bits if p)
            if sub_clean:
                new_sub = sub_clean
        else:
            for k, v in fields.items():
                if isinstance(v, dict):
                    flat_v = _flatten_json_obj_to_text(json.dumps(v, ensure_ascii=False))
                    if flat_v:
                        clean_fields[_clean_engage_field_key(k)] = flat_v
                    continue
                if isinstance(v, list):
                    joined = "、".join(_clean_num_text(x) for x in v if str(x).strip())
                    if joined:
                        clean_fields[_clean_engage_field_key(k)] = joined
                    continue
                val = _clean_num_text(v).strip()
                if not val or val in ("--", "-"):
                    continue
                ck = _clean_engage_field_key(k)
                # Drop redundant title echo or hyperlink junk
                if "请点击此链接" in ck or "请点击此链接" in val or ck == "图标":
                    continue
                if ck in clean_fields and clean_fields[ck] != val:
                    clean_fields[ck] = f"{clean_fields[ck]}；{val}"
                else:
                    clean_fields[ck] = val
            if cat == "skills":
                sk_type = clean_fields.get("特技类型", "").strip()
                if sk_type and not new_sub:
                    new_sub = sk_type
                eff_txt = clean_fields.get("详细效果说明", "")
                rng_m = re.search(r"射程\s*(\d+\s*[~～-]\s*\d+)", eff_txt)
                if rng_m and "射程" not in clean_fields:
                    fmt_r = _format_range_zh(rng_m.group(1))
                    if fmt_r:
                        clean_fields["射程"] = fmt_r

        # Clean subtitle trailing .0 (e.g. "艾黛尔贾特 / 1.0")
        if new_sub:
            sub_tokens = [_clean_num_text(p.strip()) for p in new_sub.split(" / ") if p.strip()]
            new_sub = " · ".join(sub_tokens)

        clean_payload: dict[str, Any] = {}
        if obj.get("claim_type"):
            clean_payload["claim_type"] = obj["claim_type"]
        if clean_fields:
            clean_payload["fields"] = clean_fields
        if obj.get("notes"):
            clean_payload["notes"] = obj["notes"]

        new_search = " ".join(p for p in (new_title, new_sub, search_text or "") if p)
        conn.execute(
            "UPDATE content_entries SET title=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (
                new_title,
                new_sub,
                json.dumps(clean_payload, ensure_ascii=False) if clean_payload else "",
                new_search,
                eid,
            ),
        )


def _build_engage_character_wiki_data(
    conn: sqlite3.Connection,
    emblem_prof_unlocks_override: dict[str, list[dict[str, Any]]] | None = None,
) -> None:
    char_id_by_title: dict[str, str] = {}
    for cid, title in conn.execute("SELECT id, title FROM content_entries WHERE category_id='characters'").fetchall():
        char_id_by_title[title] = cid

    skill_id_by_title: dict[str, str] = {}
    for sid, title in conn.execute("SELECT id, title FROM content_entries WHERE category_id='skills'").fetchall():
        skill_id_by_title.setdefault(title.strip(), sid)

    # 1. Weapon-proficiency emblem unlock matrix
    emblem_prof_unlocks: dict[str, list[dict[str, Any]]] = emblem_prof_unlocks_override or {}
    if not emblem_prof_unlocks:
        for eid, title, raw_desc in conn.execute(
            "SELECT id, title, description FROM content_entries WHERE category_id='weapon-proficiency'"
        ).fetchall():
            try:
                fields = json.loads(raw_desc or "{}").get("fields", {})
            except Exception:
                continue
            emblem = str(fields.get("对应纹章士 / 马尔斯", "")).strip()
            bond = _clean_num_text(fields.get("解锁所需牵绊等级 / 8.0", ""))
            if not emblem or emblem == "--":
                continue
            emblem_prof_unlocks.setdefault(title.strip(), []).append(
                {
                    "name": f"{emblem} 传授",
                    "requirement": f"牵绊{bond}" if bond else "纹章士",
                    "target_id": eid,
                    "is_universal": True,
                    "kind": "纹章士资质",
                }
            )

    # 2. Support partners & support bonus notes by character name
    supports_by_char: dict[str, tuple[str, list[dict[str, Any]]]] = {}
    for _, title, raw_desc in conn.execute(
        "SELECT id, title, description FROM content_entries WHERE category_id='supports'"
    ).fetchall():
        try:
            fields = json.loads(raw_desc or "{}").get("fields", {})
        except Exception:
            continue
        matrix = fields.get("支援关系与状态")
        if not isinstance(matrix, dict):
            continue
        bonus_note = ""
        partners: list[dict[str, Any]] = []
        for k, v in matrix.items():
            val = str(v or "").strip()
            if "支援属性" in val or "本作支援" in k:
                bonus_note = val.replace("\n", " · ").strip()
            elif val == "有":
                target_id = char_id_by_title.get(k)
                if k == "琉尔":
                    target_id = char_id_by_title.get("琉尔（男）") or char_id_by_title.get("琉尔（女）")
                partners.append({"name": k, "target_id": target_id})
        supports_by_char[title.strip()] = (bonus_note, partners)

    # 3. Gift preferences by character name
    gifts_by_char: dict[str, tuple[list[dict[str, Any]], list[dict[str, Any]]]] = {}
    for _, title, raw_desc in conn.execute(
        "SELECT id, title, description FROM content_entries WHERE category_id='gifts'"
    ).fetchall():
        try:
            fields = json.loads(raw_desc or "{}").get("fields", {})
        except Exception:
            continue
        prefs = fields.get("赠礼偏好")
        if not isinstance(prefs, dict):
            continue
        liked = [{"name": g, "target_id": None} for g, pref in prefs.items() if str(pref).strip() == "喜欢"]
        disliked = [{"name": g, "target_id": None} for g, pref in prefs.items() if str(pref).strip() == "讨厌"]
        gifts_by_char[title.strip()] = (liked, disliked)

    # 4. Attach wiki_profile to every Engage character
    for cid, title, raw_desc in conn.execute(
        "SELECT id, title, description FROM content_entries WHERE category_id='characters'"
    ).fetchall():
        try:
            payload = json.loads(raw_desc or "{}")
        except Exception:
            payload = {}
        fields = payload.get("fields", {}) if isinstance(payload, dict) else {}
        lookup_name = "琉尔" if title.startswith("琉尔") else title

        pers_name = str(fields.get("个人特技", "")).strip()
        pers_desc = str(fields.get("个人特技说明", "")).strip()
        class_skill = str(fields.get("兵种特技", "")).strip()
        init_prof = str(fields.get("初始武器资质", "")).strip()
        init_ranks_str = str(fields.get("初始武器等级", "")).strip()
        held_items_str = str(fields.get("初始持有武器与道具", "")).strip()
        starting_weapons = _split_top_level_semicolon(held_items_str) if held_items_str else []

        # Parse initial weapon ranks e.g. "魔道书B、杖B" or "弓B" or "剑A / 体术B"
        rank_by_track: dict[str, str] = {}
        for token in re.split(r"[、/，,\s]+", init_ranks_str):
            m = re.match(r"^([^\sA-ES]+)([A-ES][+-]?)$", token.strip())
            if not m:
                continue
            w_name, r_val = m.group(1), m.group(2)
            for tkey, _, aliases, _ in _ENGAGE_WEAPON_TRACKS:
                if any(a in w_name for a in aliases):
                    rank_by_track[tkey] = r_val
                    break

        tracks: list[dict[str, Any]] = []
        personal_items: list[dict[str, Any]] = []
        if pers_name:
            personal_items.append(
                {
                    "name": pers_name,
                    "requirement": "固有",
                    "target_id": skill_id_by_title.get(pers_name),
                    "is_universal": False,
                    "kind": "个人特技",
                }
            )
        if class_skill:
            cs_short = class_skill.split("（", 1)[0].strip()
            personal_items.append(
                {
                    "name": cs_short,
                    "requirement": "兵种",
                    "target_id": skill_id_by_title.get(cs_short),
                    "is_universal": False,
                    "kind": "兵种特技",
                }
            )
        if personal_items:
            tracks.append(
                {
                    "track_key": "personal",
                    "track_label": "个人与兵种特技",
                    "initial_rank": "—",
                    "proficiency": "neutral",
                    "items": personal_items,
                }
            )

        prof_tokens = [p.strip() for p in re.split(r"[、/，,\s]+", init_prof) if p.strip()]
        for tkey, label_zh, aliases, prof_title in _ENGAGE_WEAPON_TRACKS:
            is_innate = any(any(a == pt or a in pt for a in aliases) for pt in prof_tokens)
            init_r = rank_by_track.get(tkey, "—")
            items: list[dict[str, Any]] = []
            if is_innate:
                items.append(
                    {
                        "name": f"{label_zh}天性擅长",
                        "requirement": "天生",
                        "target_id": None,
                        "is_universal": False,
                        "kind": "天性资质",
                    }
                )
            if init_r != "—":
                items.append(
                    {
                        "name": f"初始{label_zh}熟练",
                        "requirement": init_r,
                        "target_id": None,
                        "is_universal": False,
                        "kind": "初始武器",
                    }
                )
            items.extend(emblem_prof_unlocks.get(prof_title, []))
            if is_innate or init_r != "—" or items:
                tracks.append(
                    {
                        "track_key": tkey,
                        "track_label": label_zh,
                        "initial_rank": init_r,
                        "proficiency": "strength" if is_innate else "neutral",
                        "items": items,
                    }
                )

        support_note, support_partners = supports_by_char.get(lookup_name, ("", []))
        liked_gifts, disliked_gifts = gifts_by_char.get(lookup_name, ([], []))

        payload["wiki_profile"] = {
            "starting_class": str(fields.get("初始职业", "")).strip() or None,
            "personal_skill_name": pers_name or None,
            "personal_skill_description": pers_desc or None,
            "starting_weapons": starting_weapons,
            "support_bonus_note": support_note or None,
            "support_partners": support_partners,
            "liked_gifts": liked_gifts,
            "disliked_gifts": disliked_gifts,
            "skill_tracks": tracks,
        }
        conn.execute(
            "UPDATE content_entries SET description=? WHERE id=?",
            (json.dumps(payload, ensure_ascii=False), cid),
        )


def _consolidate_engage_characters(
    conn: sqlite3.Connection,
    prof_unlocks: dict[str, list[dict[str, Any]]] | None = None,
) -> int:
    if not _has_table(conn, "character_details"):
        return 0
    rows = conn.execute(
        """
        SELECT e.id, e.title, e.title_en, e.subtitle, e.description, e.search_text,
               cd.affiliation, cd.join_condition, cd.route_availability,
               cd.base_stats_json, cd.growths_json, cd.caps_json
        FROM content_entries e
        LEFT JOIN character_details cd ON cd.entry_id = e.id
        WHERE e.category_id = 'characters'
        ORDER BY e.id
        """
    ).fetchall()
    if not rows:
        return 0

    groups: dict[str, list[tuple[Any, ...]]] = {}
    for row in rows:
        _, title, title_en, _, desc, *_ = row
        canonical = title
        if title == "琉尔":
            if title_en == "Alear (M)" or "Alear (M)" in (desc or ""):
                canonical = "琉尔（男）"
            elif title_en == "Alear (F)" or "Alear (F)" in (desc or ""):
                canonical = "琉尔（女）"
            else:
                canonical = "琉尔（男）"
        elif title == "罗萨德（男）":
            canonical = "罗萨德"
        groups.setdefault(canonical, []).append(row)

    # Shared Fell Xenologue row for Alear (if present) should also enrich Alear (F)
    alear_fx_rows = [
        r for r in rows if r[1] == "琉尔" and (r[8] == "邪龙之章" or "持有物品信息表" in (r[4] or ""))
    ]

    removed_ids: list[str] = []
    for canonical, grp in groups.items():
        # Pick primary main-story row (has growths_json and join_condition)
        main_row = max(
            grp,
            key=lambda r: (
                1 if r[10] else 0,
                1 if r[7] else 0,
                1 if r[9] else 0,
                1 if r[2] else 0,
            ),
        )
        main_id = main_row[0]
        siblings = [r for r in grp if r[0] != main_id]
        extra_fx = alear_fx_rows if canonical == "琉尔（女）" and all((r[8] != "邪龙之章" and "持有物品信息表" not in (r[4] or "")) for r in grp) else []

        def parse_fields(raw_desc: str | None) -> dict[str, Any]:
            if not raw_desc or not raw_desc.startswith("{"):
                return {}
            try:
                return json.loads(raw_desc).get("fields", {}) or {}
            except Exception:
                return {}

        main_fields = parse_fields(main_row[4])
        raw_affiliation = main_row[6] or next((r[6] for r in siblings if r[6] and not str(r[6]).startswith("#")), None)
        affiliation = _ENGAGE_REGION_NORMALIZE.get((raw_affiliation or "").strip(), raw_affiliation)
        join_cond = main_row[7] or next((r[7] for r in siblings if r[7]), None)
        route_avail = main_row[8] or next((r[8] for r in siblings if r[8]), None)

        # Base stats + starting Lv/Move/SP
        base_dict: dict[str, Any] = {}
        if main_fields.get("初始能力值 / LV"):
            base_dict["初始等级"] = _clean_num_text(main_fields["初始能力值 / LV"])
        if main_fields.get("初始能力值 / 内置等级"):
            base_dict["内置等级"] = _clean_num_text(main_fields["初始能力值 / 内置等级"])
        if main_fields.get("初始能力值 / 移动"):
            base_dict["移动力"] = _clean_num_text(main_fields["初始能力值 / 移动"])
        if main_fields.get("初始能力值 / 初始SP"):
            base_dict["初始SP"] = _clean_num_text(main_fields["初始能力值 / 初始SP"])
        if main_row[9]:
            base_dict.update(json.loads(localize_stat_dict(main_row[9]) or "{}"))
        base_stats_json = json.dumps(base_dict, ensure_ascii=False) if base_dict else main_row[9]

        # Growths + total
        growth_dict: dict[str, Any] = {}
        if main_row[10]:
            growth_dict.update(json.loads(localize_stat_dict(main_row[10], is_growth=True) or "{}"))
        if main_fields.get("个人成长率（颜色标记为各项前三） / 合计"):
            growth_dict["成长合计"] = f"{_clean_num_text(main_fields['个人成长率（颜色标记为各项前三） / 合计'])}%"
        growths_json = json.dumps(growth_dict, ensure_ascii=False) if growth_dict else main_row[10]

        # Cap modifiers from cap sibling row
        cap_raw = main_row[11] or next((r[11] for r in siblings if r[11]), None)
        caps_json = localize_stat_dict(cap_raw, signed=True) if cap_raw else None

        # Structured Chinese fields
        init_class = str(main_fields.get("初始职业", "")).strip()
        clean_fields: dict[str, str] = {}
        for src_k, dst_k in (
            ("初始职业", "初始职业"),
            ("初始武器资质", "初始武器资质"),
            ("初始能力值 / 初始武器等级", "初始武器等级"),
            ("个人特技", "个人特技"),
            ("详细说明", "个人特技说明"),
        ):
            v = str(main_fields.get(src_k, "")).strip()
            if v and v != "--":
                clean_fields[dst_k] = v

        # Merge FX / held items sibling rows
        fx_sources = [r for r in siblings if r[8] == "邪龙之章" or "持有物品信息表" in (r[4] or "")] + extra_fx
        for fx in fx_sources:
            fx_fields = parse_fields(fx[4])
            fx_cls = str(fx_fields.get("角色信息（绿色底为章节强制登场角色） / 兵种职业", "")).strip()
            fx_wr = str(fx_fields.get("角色信息（绿色底为章节强制登场角色） / 武器等级", "")).strip()
            fx_sk = str(fx_fields.get("角色持有特技 / 兵种特技", "")).strip()
            fx_sk_desc = str(fx_fields.get("角色持有特技 / 详细说明 [M]", "")).strip()
            fx_ch = str(fx_fields.get("角色信息（绿色底为章节强制登场角色） / 主线加入章节", "") or fx[8] or "").strip()
            if fx_cls and fx_cls != "--":
                label = "主线持有武器参考职业" if fx_ch and fx_ch != "邪龙之章" else "邪龙之章兵种职业"
                clean_fields.setdefault(label, fx_cls)
            if fx_wr and fx_wr != "--":
                clean_fields.setdefault("章内初始武器等级", fx_wr)
            if fx_sk and fx_sk != "--":
                clean_fields.setdefault("兵种特技", f"{fx_sk}（{fx_sk_desc}）" if fx_sk_desc else fx_sk)

            items_list: list[str] = []
            item_keys = sorted(k for k in fx_fields if "持有物品信息表" in k and "/ 物品" in k)
            desc_keys = sorted(k for k in fx_fields if "持有物品信息表" in k and "/ 详细说明" in k)
            for idx, ik in enumerate(item_keys):
                item_name = str(fx_fields.get(ik, "")).strip()
                if not item_name or item_name == "--":
                    continue
                item_desc = str(fx_fields.get(desc_keys[idx], "")).strip() if idx < len(desc_keys) else ""
                items_list.append(f"{item_name}（{item_desc}）" if item_desc and item_desc != "--" else item_name)
            if items_list:
                clean_fields["初始持有武器与道具"] = "；".join(items_list)

            if fx[9]:
                fx_stats = json.loads(localize_stat_dict(fx[9]) or "{}")
                fx_lv = _clean_num_text(fx_fields.get("角色能力值面板（颜色标记为各项前三） / Lv"))
                fx_mov = _clean_num_text(fx_fields.get("角色能力值面板（颜色标记为各项前三） / 移动"))
                prefix = f"Lv.{fx_lv} · 移动 {fx_mov} · " if fx_lv else ""
                stat_str = " / ".join(f"{k} {v}" for k, v in fx_stats.items())
                label = "邪龙之章固定面板" if fx[8] == "邪龙之章" else "加入章节实机面板"
                clean_fields.setdefault(label, prefix + stat_str)

        subtitle = " · ".join(p for p in (affiliation, init_class, route_avail) if p)
        title_en = main_row[2] or next((r[2] for r in siblings if r[2]), None)
        merged_desc = json.dumps({"fields": clean_fields}, ensure_ascii=False)
        merged_search = " ".join(p for p in (canonical, title_en or "", subtitle, main_row[5] or "") if p)

        conn.execute(
            "UPDATE content_entries SET title=?, title_en=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (canonical, title_en, subtitle, merged_desc, merged_search, main_id),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO character_details(
                entry_id, affiliation, join_condition, route_availability,
                base_stats_json, growths_json, caps_json
            ) VALUES(?,?,?,?,?,?,?)
            """,
            (main_id, affiliation, join_cond, route_avail, base_stats_json, growths_json, caps_json),
        )

        for sib in siblings:
            sib_id = sib[0]
            # Migrate assets and sources cleanly without primary-key collisions
            for asset_id, role, locator in conn.execute(
                "SELECT asset_id, role, locator FROM entry_assets WHERE entry_id=?", (sib_id,)
            ).fetchall():
                conn.execute(
                    "INSERT OR IGNORE INTO entry_assets(entry_id, asset_id, role, locator) VALUES(?,?,?,?)",
                    (main_id, asset_id, role, locator),
                )
            for src_id, loc, sha, lang, trans, status in conn.execute(
                "SELECT source_id, locator, source_sha256, source_language, translation_status, assertion_status FROM entry_sources WHERE entry_id=?",
                (sib_id,),
            ).fetchall():
                conn.execute(
                    "INSERT OR IGNORE INTO entry_sources(entry_id, source_id, locator, source_sha256, source_language, translation_status, assertion_status) VALUES(?,?,?,?,?,?,?)",
                    (main_id, src_id, loc, sha, lang, trans, status),
                )
            conn.execute("DELETE FROM entry_assets WHERE entry_id=?", (sib_id,))
            conn.execute("DELETE FROM entry_sources WHERE entry_id=?", (sib_id,))
            removed_ids.append(sib_id)

    for rid in removed_ids:
        conn.execute("DELETE FROM character_details WHERE entry_id=?", (rid,))
        conn.execute("DELETE FROM content_entries WHERE id=?", (rid,))

    conn.execute(
        "UPDATE categories SET record_count=(SELECT COUNT(*) FROM content_entries WHERE category_id='characters') WHERE id='characters'"
    )
    _build_engage_character_wiki_data(conn, prof_unlocks)
    return len(groups)


def _format_three_houses_crest(raw_crest: str) -> str:
    s = (raw_crest or "").strip()
    if not s:
        return "无"
    m = re.match(r"^(.+?)\s*\((Minor|Major)\)$", s)
    if m:
        c_zh = TH_CRESTS.get(m.group(1).strip(), m.group(1).strip())
        grade = "小纹章" if m.group(2) == "Minor" else "大纹章"
        return f"{c_zh}（{grade}）"
    return TH_CRESTS.get(s, s)


def _format_learnable_skill(raw_entry: str) -> str:
    if ":" not in raw_entry:
        return translate_skill_title(raw_entry)
    k, v = [p.strip() for p in raw_entry.split(":", 1)]
    sk_zh = translate_skill_title(v)
    m = re.match(r"^(Sword|Lance|Axe|Bow|Brawl|Reason|Faith|Auth)([A-ES][+-]?)$", k)
    if m:
        cat_map = {
            "Sword": "剑术", "Lance": "枪术", "Axe": "斧术", "Bow": "弓术",
            "Brawl": "格斗术", "Reason": "理学", "Faith": "信仰", "Auth": "指挥",
        }
        return f"{cat_map[m.group(1)]} {m.group(2)}：{sk_zh}"
    return f"{k}：{sk_zh}"


def _th_skill_zh_title(name_en: str) -> str:
    if name_en in TH_EXTRA_SKILL_NAMES_ZH:
        return TH_EXTRA_SKILL_NAMES_ZH[name_en]
    return translate_skill_title(name_en)


def _prepare_three_houses_skills_catalog(
    conn: sqlite3.Connection,
    skills_json_path: Path,
) -> dict[str, dict[str, Any]]:
    """Purge leaked non-FE16 stubs and ensure every FE16 ability, prowess level, and combat art exists in `skills`."""
    if not skills_json_path.is_file():
        return {}
    data = json.loads(skills_json_path.read_text(encoding="utf-8"))

    # 1. Remove leaked FE15 Echoes / non-FE16 entries from `skills` (and bare aliases when Greek-letter spell exists)
    leaked_lower = {n.lower() for n in TH_NON_FE16_LEAKED_SKILLS} | {"--", "-", "miasma", "mire", "death"}
    leaked_ids: list[str] = []
    for eid, title, title_en in conn.execute(
        "SELECT id, title, title_en FROM content_entries WHERE category_id='skills'"
    ).fetchall():
        key = (title_en or title or "").strip().lower()
        if key in leaked_lower:
            leaked_ids.append(eid)
    if leaked_ids:
        _delete_entries_cascade(conn, leaked_ids)

    # 1b. Deduplicate any pre-existing `skills` rows sharing the same normalized English name
    # (e.g. Death Blow, Aegis, Pavise, Swap, Shove, Subdue, Triangle Attack, Hunter's Volley, Fire)
    by_norm_groups: dict[str, list[tuple[str, str | None]]] = {}
    for eid, title, title_en, eff_raw in conn.execute(
        """
        SELECT e.id, e.title, e.title_en, sd.effect_json
        FROM content_entries e
        LEFT JOIN skill_details sd ON sd.entry_id = e.id
        WHERE e.category_id = 'skills'
        ORDER BY e.id
        """
    ).fetchall():
        k = (title_en or title or "").strip().lower()
        if k:
            by_norm_groups.setdefault(k, []).append((eid, eff_raw))

    dup_removed: list[str] = []
    for k, grp in by_norm_groups.items():
        if len(grp) <= 1:
            continue
        # Prefer row that already has class source_variants or relations
        grp_sorted = sorted(
            grp,
            key=lambda item: (
                0 if (item[1] and "source_variants" in item[1]) else 1,
                item[0],
            ),
        )
        keep_id = grp_sorted[0][0]
        for sib_id, _ in grp_sorted[1:]:
            _migrate_entry_links(conn, sib_id, keep_id)
            if _has_table(conn, "content_relations"):
                conn.execute(
                    "UPDATE OR IGNORE content_relations SET source_entry_id=? WHERE source_entry_id=?",
                    (keep_id, sib_id),
                )
                conn.execute(
                    "UPDATE OR IGNORE content_relations SET target_entry_id=? WHERE target_entry_id=?",
                    (keep_id, sib_id),
                )
            dup_removed.append(sib_id)
    if dup_removed:
        _delete_entries_cascade(conn, dup_removed)

    # 2. Index remaining existing `skills` entries by normalized English title
    existing_by_en: dict[str, str] = {}
    for eid, title, title_en in conn.execute(
        "SELECT id, title, title_en FROM content_entries WHERE category_id='skills'"
    ).fetchall():
        for raw in (title_en, title):
            k = (raw or "").strip().lower()
            if k:
                existing_by_en.setdefault(k, eid)

    def ensure_skill_entry(name_en: str, title_zh: str, subtitle: str) -> str:
        k = name_en.strip().lower()
        eid = existing_by_en.get(k)
        if not eid:
            alt = k.replace(" lv ", " lv.")
            eid = existing_by_en.get(alt)
        if not eid:
            eid = f"skills:{uuid.uuid5(uuid.NAMESPACE_DNS, f'fe16:skill:{k}')}"
            slug = re.sub(r"[^a-z0-9]+", "-", k).strip("-") or "skill"
            conn.execute(
                """
                INSERT INTO content_entries(
                    id, category_id, stable_key, title, title_ja, title_en,
                    subtitle, description, search_text, verification_status
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    eid,
                    "skills",
                    f"fe16-skill:{slug}:{eid[-8:]}",
                    title_zh,
                    None,
                    name_en,
                    subtitle,
                    "",
                    f"{title_zh} {name_en} {subtitle}",
                    "confirmed",
                ),
            )
            existing_by_en[k] = eid
        if _has_table(conn, "skill_details"):
            conn.execute("INSERT OR IGNORE INTO skill_details(entry_id) VALUES(?)", (eid,))
        return eid

    meta_by_en: dict[str, dict[str, Any]] = {}

    # 3. Upsert all 77 Three Houses combat arts
    for ca in data.get("combat_arts", []):
        name_en = (ca.get("name") or "").strip()
        if not name_en:
            continue
        title_zh = _th_skill_zh_title(name_en)
        rng = _format_range_zh(ca.get("range"))
        eff_zh = translate_th_combat_art_notes(name_en, ca.get("notes_en") or ca.get("help_text_en") or "")
        eid = ensure_skill_entry(name_en, title_zh, "战技")
        entry_meta = {
            "id": eid,
            "kind": "combat_art",
            "title_zh": title_zh,
            "range": rng,
            "effect_zh": eff_zh,
            "icon_file": ca.get("icon_file"),
        }
        meta_by_en[name_en.lower()] = entry_meta
        if name_en == "Foudroyant Strike":
            meta_by_en.setdefault("foudroyant", entry_meta)

    # 4. Upsert all 234 Three Houses abilities
    for ab in data.get("abilities", []):
        name_en = (ab.get("name") or "").strip()
        if not name_en or name_en in ("-", "--"):
            continue
        title_zh = _th_skill_zh_title(name_en)
        sec_zh = TH_SECTION_ZH.get(ab.get("section") or "", "技能等级特技")
        eff_zh = translate_th_ability_effect(name_en, ab.get("effect_en") or "")
        eid = ensure_skill_entry(name_en, title_zh, sec_zh)
        meta_by_en[name_en.lower()] = {
            "id": eid,
            "kind": "ability",
            "title_zh": title_zh,
            "range": None,
            "effect_zh": eff_zh,
            "icon_file": ab.get("icon_file"),
        }

    # 5. Upsert all 40 Prowess / Reason / Faith / Authority Lv 1-5 abilities
    for base_en, levels in TH_PROWESS_LEVELS.items():
        icon_tpl = TH_PROWESS_ICON_PREFIX.get(base_en, "")
        for lv, en_lv, zh_lv, eff_zh in levels:
            eid = ensure_skill_entry(en_lv, zh_lv, "技能等级特技")
            info = {
                "id": eid,
                "kind": "ability",
                "title_zh": zh_lv,
                "range": None,
                "effect_zh": eff_zh,
                "icon_file": icon_tpl.format(lv=lv) if icon_tpl else None,
            }
            meta_by_en[en_lv.lower()] = info
            meta_by_en[en_lv.lower().replace(" lv ", " lv.")] = info

    _refresh_category_count(conn, "skills")
    return meta_by_en


def _enrich_three_houses_characters(
    conn: sqlite3.Connection,
    stats_path: Path,
    th_skill_meta: dict[str, dict[str, Any]] | None = None,
) -> int:
    if not _has_table(conn, "character_details") or not stats_path.is_file():
        return 0
    payload = json.loads(stats_path.read_text(encoding="utf-8"))
    chars_data = payload.get("characters", {})
    skill_meta = th_skill_meta or {}

    # Build lookup index for skills + spells (equipment) so learnable chips jump straight to their Wiki entry!
    # For magic spells, prefer full `equipment` records (which have range, might, hit, crit, weight, uses, effect)
    # over sparse `skills` presence stubs.
    entry_lookup_en: dict[str, str] = {}
    magic_equip_lookup: dict[str, tuple[str, str | None]] = {}

    if _has_table(conn, "equipment_details"):
        for eid, title, title_en, extra_raw in conn.execute(
            """
            SELECT e.id, e.title, e.title_en, ed.extra_stats_json
            FROM content_entries e
            JOIN equipment_details ed ON ed.entry_id = e.id
            WHERE e.category_id = 'equipment'
              AND lower(ed.subtype) IN ('黑魔法', '暗魔法', '白魔法', 'black magic', 'dark magic', 'white magic', 'reason', 'faith')
            """
        ).fetchall():
            rng = None
            if extra_raw:
                try:
                    ex = json.loads(extra_raw)
                    rng = _format_range_zh(ex.get("range"))
                except Exception:
                    pass
            for key_raw in (title_en, title):
                k = (key_raw or "").strip().lower()
                if not k:
                    continue
                magic_equip_lookup[k] = (eid, rng)
                parts = k.split()
                if len(parts) == 2 and len(parts[1]) == 1:
                    magic_equip_lookup.setdefault(parts[0], (eid, rng))

    for eid, cat, title, title_en in conn.execute(
        "SELECT id, category_id, title, title_en FROM content_entries WHERE category_id IN ('skills', 'equipment')"
    ).fetchall():
        prio = 0 if cat == "skills" else 1
        for key_raw in (title_en, title):
            k = (key_raw or "").strip().lower()
            if not k:
                continue
            if k not in entry_lookup_en or prio == 0:
                entry_lookup_en[k] = eid

    def find_skill_target(en_name: str, prefer_magic_equip: bool = False) -> tuple[str | None, str | None]:
        key = en_name.strip().lower()
        if prefer_magic_equip and key in magic_equip_lookup:
            return magic_equip_lookup[key]
        alt = key.replace(" lv ", " lv.")
        meta = skill_meta.get(key) or skill_meta.get(alt)
        if meta:
            return (meta["id"], meta.get("range") or magic_equip_lookup.get(key, (None, None))[1])
        if key in entry_lookup_en:
            return (entry_lookup_en[key], magic_equip_lookup.get(key, (None, None))[1])
        if alt in entry_lookup_en:
            return (entry_lookup_en[alt], magic_equip_lookup.get(alt, (None, None))[1])
        if key in magic_equip_lookup:
            return magic_equip_lookup[key]
        return (None, None)

    rows = conn.execute(
        """
        SELECT e.id, e.title, e.title_en, e.description, e.search_text,
               cd.affiliation, cd.join_condition, cd.route_availability
        FROM content_entries e
        LEFT JOIN character_details cd ON cd.entry_id = e.id
        WHERE e.category_id = 'characters'
        """
    ).fetchall()

    updated = 0
    for entry_id, title, title_en, raw_desc, search_text, aff, join_cond, route_avail in rows:
        st = chars_data.get(title_en or "")
        if not st:
            continue

        title_zh = TH_CHARACTERS.get(title, TH_CHARACTERS.get(title_en or "", title))
        cls_zh = translate_class_title(st.get("starting_class") or "")
        aff_zh = _THREE_HOUSES_HOUSE_MAP.get(
            title_en or "",
            TH_FACTIONS_AND_ROUTES.get((aff or "").strip(), aff or ""),
        )
        join_zh = translate_join_condition(join_cond or "") if join_cond else None

        routes_zh = route_avail
        if raw_desc and "Roster-section records:" in raw_desc:
            head = raw_desc.split(".", 1)[0].replace("Roster-section records:", "").strip()
            parts = [TH_FACTIONS_AND_ROUTES.get(p.strip(), p.strip()) for p in head.split(";") if p.strip()]
            routes_zh = "、".join(dict.fromkeys(parts))
            if not aff_zh and parts:
                aff_zh = parts[0]

        base_stats = st.get("base_stats", {})
        base_dict: dict[str, Any] = {}
        if st.get("starting_level") is not None:
            base_dict["初始等级"] = st["starting_level"]
        if st.get("move") is not None:
            base_dict["移动力"] = st["move"]
        for k, v in base_stats.items():
            base_dict[STAT_KEY_ZH.get(k.lower(), k)] = v

        growths = st.get("growth_rates", {})
        growth_dict: dict[str, Any] = {}
        total_growth = 0
        for k, v in growths.items():
            growth_dict[STAT_KEY_ZH.get(k.lower(), k)] = f"{v}%"
            total_growth += int(v)
        if growths:
            growth_dict["成长合计"] = f"{total_growth}%"

        inv_zh = [translate_equipment_title(it) for it in st.get("starting_inventory", [])]
        pers_en = st.get("personal_skills", [])
        pers_zh = [_th_skill_zh_title(sk) for sk in pers_en]
        pers_desc_parts: list[str] = []
        for sk_en, sk_zh in zip(pers_en, pers_zh):
            m_info = skill_meta.get(sk_en.strip().lower())
            eff_txt = (m_info or {}).get("effect_zh") or translate_th_ability_effect(sk_en, "")
            if eff_txt:
                pers_desc_parts.append(f"{sk_zh}：{eff_txt}" if len(pers_en) > 1 else eff_txt)
        personal_skill_desc = "\n".join(pers_desc_parts) if pers_desc_parts else None
        start_sk_zh = [_th_skill_zh_title(sk) for sk in st.get("starting_skills", []) if sk not in pers_en]
        arts_en = st.get("starting_combat_arts", [])
        arts_zh = [_th_skill_zh_title(a) for a in arts_en]
        starting_ranks_raw = st.get("starting_ranks", {})
        ranks_zh = [f"{TH_WEAPON_CATEGORIES.get(k, k)} {v}" for k, v in starting_ranks_raw.items()]
        strengths_raw = set(st.get("strengths", []))
        weaknesses_raw = set(st.get("weaknesses", []))
        strengths_zh = [TH_WEAPON_CATEGORIES.get(k, k) for k in st.get("strengths", [])]
        weaknesses_zh = [TH_WEAPON_CATEGORIES.get(k, k) for k in st.get("weaknesses", [])]
        budding_talent_en = (st.get("budding_talent") or "").strip()
        budding_skill_en = (st.get("budding_skill") or "").strip()
        budding_skill_zh = _th_skill_zh_title(budding_skill_en) if budding_skill_en else ""
        budding_zh = TH_WEAPON_CATEGORIES.get(budding_talent_en, budding_talent_en)
        if budding_zh and budding_skill_zh:
            budding_zh = f"{budding_zh}（解锁：{budding_skill_zh}）"

        # Parse learnable_skills by track prefix cleanly (strictly dropping *Link / Dark / Byleth noise)
        learnable_by_prefix: dict[str, list[tuple[str, str, str]]] = {}
        learnable_search_terms: list[str] = []
        for item in st.get("learnable_skills", []):
            if ":" not in item:
                continue
            raw_k, raw_v = [p.strip() for p in item.split(":", 1)]
            if raw_k.endswith("Link") or raw_k in ("Dark", "Byleth") or not raw_v:
                continue
            m = re.match(r"^(Sword|Lance|Axe|Bow|Brawl|Reason|Faith|Auth)([A-ES][+-]?)$", raw_k)
            if not m:
                continue
            prefix, rank = m.group(1), m.group(2)
            sk_zh = _th_skill_zh_title(raw_v)
            learnable_by_prefix.setdefault(prefix, []).append((rank, sk_zh, raw_v))
            learnable_search_terms.append(sk_zh)

        # Build structured skill tracks matching Wiki matrix layout
        skill_tracks: list[dict[str, Any]] = []
        if pers_en:
            skill_tracks.append(
                {
                    "track_key": "personal",
                    "track_label": "个人特技",
                    "initial_rank": "—",
                    "proficiency": "neutral",
                    "items": [
                        {
                            "name": _th_skill_zh_title(sk),
                            "requirement": "固有",
                            "target_id": find_skill_target(sk)[0],
                            "is_universal": False,
                            "kind": "个人特技",
                        }
                        for sk in pers_en
                    ],
                }
            )

        for track_key, prefix, stat_key, label_zh in _TH_TRACK_SPECS:
            init_rank = starting_ranks_raw.get(stat_key, "E")
            if budding_talent_en == stat_key:
                prof = "budding"
            elif stat_key in strengths_raw:
                prof = "strength"
            elif stat_key in weaknesses_raw:
                prof = "weakness"
            else:
                prof = "neutral"

            items: list[dict[str, Any]] = []
            seen_names: set[str] = set()

            # Starting combat arts mapped to their weapon track
            for art_en in arts_en:
                if _TH_STARTING_ART_TRACK.get(art_en) == track_key:
                    art_zh = _th_skill_zh_title(art_en)
                    if art_zh not in seen_names:
                        seen_names.add(art_zh)
                        t_id, t_rng = find_skill_target(art_en)
                        item_dict: dict[str, Any] = {
                            "name": art_zh,
                            "requirement": "初始",
                            "target_id": t_id,
                            "is_universal": False,
                            "kind": "初始战技",
                        }
                        if t_rng:
                            item_dict["range"] = t_rng
                        items.append(item_dict)

            # Character-specific rank learnables
            char_learned = sorted(
                learnable_by_prefix.get(prefix, []),
                key=lambda x: (_RANK_ORDER.get(x[0], 99), x[1]),
            )
            for rank, sk_zh, sk_en in char_learned:
                if sk_zh in seen_names:
                    continue
                seen_names.add(sk_zh)
                is_magic = track_key in ("reason", "faith")
                kind = "魔法" if is_magic else ("指挥特技" if track_key == "authority" else "战技")
                t_id, t_rng = find_skill_target(sk_en, prefer_magic_equip=is_magic)
                item_dict = {
                    "name": sk_zh,
                    "requirement": rank,
                    "target_id": t_id,
                    "is_universal": False,
                    "kind": kind,
                }
                if t_rng:
                    item_dict["range"] = t_rng
                items.append(item_dict)

            # Budding talent unlock
            if prof == "budding" and budding_skill_zh and budding_skill_zh not in seen_names:
                seen_names.add(budding_skill_zh)
                t_id, t_rng = find_skill_target(budding_skill_en, prefer_magic_equip=track_key in ("reason", "faith"))
                item_dict = {
                    "name": budding_skill_zh,
                    "requirement": "绽放",
                    "target_id": t_id,
                    "is_universal": False,
                    "kind": "才能绽放",
                }
                if t_rng:
                    item_dict["range"] = t_rng
                items.append(item_dict)

            # Universal Three Houses rank unlocks (togglable via 角色专属 / 通用 filter)
            for u_rank, u_zh, u_en in _TH_UNIVERSAL_TRACK_SKILLS.get(track_key, ()):
                items.append(
                    {
                        "name": u_zh,
                        "requirement": u_rank,
                        "target_id": find_skill_target(u_en)[0],
                        "is_universal": True,
                        "kind": "通用特技",
                    }
                )

            skill_tracks.append(
                {
                    "track_key": track_key,
                    "track_label": label_zh,
                    "initial_rank": init_rank,
                    "proficiency": prof,
                    "budding_skill": budding_skill_zh if prof == "budding" else None,
                    "items": items,
                }
            )

        crest_zh = _format_three_houses_crest(st.get("crest") or "")
        fields: dict[str, str] = {
            "初始职业": cls_zh,
            "纹章": crest_zh,
            "个人特技": " / ".join(pers_zh) if pers_zh else "无",
            "初始武器与道具": "、".join(inv_zh) if inv_zh else "无",
            "初始武器与技能等级": " · ".join(ranks_zh) if ranks_zh else "无",
            "擅长项目": "、".join(strengths_zh) if strengths_zh else "无",
            "弱项项目": "、".join(weaknesses_zh) if weaknesses_zh else "无",
            "才能绽放": budding_zh or "无",
            "初始持有特技": "、".join(start_sk_zh) if start_sk_zh else "无",
            "初始战技": "、".join(arts_zh) if arts_zh else "无",
        }
        wiki_profile = {
            "starting_class": cls_zh,
            "crest": crest_zh if crest_zh != "无" else None,
            "personal_skill_name": " / ".join(pers_zh) if pers_zh else None,
            "personal_skill_description": personal_skill_desc,
            "starting_weapons": inv_zh,
            "skill_tracks": skill_tracks,
        }
        subtitle = " · ".join(p for p in (aff_zh, f"初始职业：{cls_zh}") if p)
        merged_search = " ".join(
            p for p in (title_zh, title_en or "", subtitle, *inv_zh, *pers_zh, *learnable_search_terms, search_text or "") if p
        )

        conn.execute(
            "UPDATE content_entries SET title=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (
                title_zh,
                subtitle,
                json.dumps({"fields": fields, "wiki_profile": wiki_profile}, ensure_ascii=False),
                merged_search,
                entry_id,
            ),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO character_details(
                entry_id, affiliation, join_condition, route_availability,
                base_stats_json, growths_json
            ) VALUES(?,?,?,?,?,?)
            """,
            (
                entry_id,
                aff_zh or None,
                join_zh,
                routes_zh,
                json.dumps(base_dict, ensure_ascii=False) if base_dict else None,
                json.dumps(growth_dict, ensure_ascii=False) if growth_dict else None,
            ),
        )
        updated += 1
    return updated


def _finalize_three_houses_magic_entries(conn: sqlite3.Connection) -> None:
    """Run AFTER localize_pack_db so magic effects, subtitles, and overlapping skills stubs stay intact."""
    if not _has_table(conn, "equipment_details"):
        return

    magic_stats_by_key: dict[str, tuple[str, dict[str, str]]] = {}

    for eid, title, title_en, sub, rank, might, hit, crit, weight, dur, eff, extra_raw in conn.execute(
        """
        SELECT e.id, e.title, e.title_en, ed.subtype, ed.rank, ed.might, ed.hit,
               ed.critical, ed.weight, ed.durability, ed.effect, ed.extra_stats_json
        FROM content_entries e
        JOIN equipment_details ed ON ed.entry_id = e.id
        WHERE e.category_id = 'equipment'
          AND ed.subtype IN ('黑魔法', '暗魔法', '白魔法')
        """
    ).fetchall():
        rng = None
        uses_txt = None
        if extra_raw:
            try:
                ex = json.loads(extra_raw)
                rng = _format_range_zh(ex.get("range"))
                uses_txt = _clean_num_text(ex.get("uses_source_text")) or None
            except Exception:
                pass

        eff_zh = (eff or "").strip()
        if not eff_zh:
            # Generate a factual Chinese combat summary strictly from this spell's verified subtype & stats
            rng_phrase = f"射程 {rng} 格" if rng else "标准射程"
            traits: list[str] = []
            if crit is not None and crit >= 15:
                traits.append(f"必杀率高（{int(crit)}）")
            if might is not None and might >= 14:
                traits.append(f"高威力（{int(might)}）")
            if hit is not None and hit >= 95:
                traits.append(f"高命中（{int(hit)}）")
            trait_suffix = f"，{'、'.join(traits)}" if traits else ""
            eff_zh = f"{sub}攻击魔法，{rng_phrase}，对目标造成魔法伤害{trait_suffix}（无额外附带状态效果）。"
            conn.execute("UPDATE equipment_details SET effect=? WHERE entry_id=?", (eff_zh, eid))

        sub_parts = [sub]
        if rank:
            sub_parts.append(f"{rank} 级")
        if rng:
            sub_parts.append(f"射程 {rng}")
        new_subtitle = " · ".join(sub_parts)
        conn.execute(
            "UPDATE content_entries SET subtitle=?, description=? WHERE id=?",
            (new_subtitle, "", eid),
        )

        stat_map: dict[str, str] = {"魔法分类": sub}
        if rank:
            stat_map["等级"] = str(rank)
        if rng:
            stat_map["射程"] = rng
        if might is not None:
            stat_map["威力"] = _clean_num_text(might)
        if hit is not None:
            stat_map["命中"] = _clean_num_text(hit)
        if crit is not None:
            stat_map["必杀"] = _clean_num_text(crit)
        if weight is not None:
            stat_map["重量"] = _clean_num_text(weight)
        if dur is not None:
            stat_map["使用次数"] = _clean_num_text(dur)
        elif uses_txt:
            stat_map["使用次数"] = uses_txt
        stat_map["魔法效果"] = eff_zh

        for key_raw in (title_en, title):
            k = (key_raw or "").strip().lower()
            if not k:
                continue
            magic_stats_by_key[k] = (new_subtitle, stat_map)
            parts = k.split()
            if len(parts) == 2 and len(parts[1]) == 1:
                magic_stats_by_key.setdefault(parts[0], (new_subtitle, stat_map))
            # Also match Chinese title stripped of trailing Greek letter (e.g. 瘴气Δ -> 瘴气)
            if len(k) >= 2 and k[-1] in "αβγδεζηθικλμνξοπρστυφχψω":
                magic_stats_by_key.setdefault(k[:-1], (new_subtitle, stat_map))

    # Backfill ALL 39 playable Three Houses magic spells into `skills` so browsing 技能 -> 黑魔法/暗魔法/白魔法 shows every spell with full stats, range & effect!
    if _has_table(conn, "skill_details"):
        existing_skill_by_key: dict[str, tuple[str, str | None]] = {}
        for sid, stitle, stitle_en, s_eff in conn.execute(
            """
            SELECT e.id, e.title, e.title_en, sd.effect_json
            FROM content_entries e
            LEFT JOIN skill_details sd ON sd.entry_id = e.id
            WHERE e.category_id = 'skills'
            """
        ).fetchall():
            for key_raw in (stitle_en, stitle):
                k = (key_raw or "").strip().lower()
                if k:
                    existing_skill_by_key.setdefault(k, (sid, s_eff))

        # Ensure every magic entry in `equipment` has a full corresponding entry in `skills`
        seen_spell_keys: set[str] = set()
        for eid, title, title_en, sub in conn.execute(
            """
            SELECT e.id, e.title, e.title_en, ed.subtype
            FROM content_entries e
            JOIN equipment_details ed ON ed.entry_id = e.id
            WHERE e.category_id = 'equipment'
              AND ed.subtype IN ('黑魔法', '暗魔法', '白魔法')
            """
        ).fetchall():
            k = (title_en or title or "").strip().lower()
            if not k or k in seen_spell_keys:
                continue
            seen_spell_keys.add(k)
            matched = magic_stats_by_key.get(k)
            if not matched:
                continue
            new_sub, stat_map = matched
            existing = existing_skill_by_key.get(k) or existing_skill_by_key.get((title or "").strip().lower())
            if existing:
                sid, s_eff = existing
            else:
                sid = f"skills:{uuid.uuid5(uuid.NAMESPACE_DNS, f'fe16:magic:{k}')}"
                slug = re.sub(r"[^a-z0-9]+", "-", k).strip("-") or "magic"
                search_txt = " ".join(p for p in (title, title_en or "", new_sub, stat_map.get("魔法效果", "")) if p)
                conn.execute(
                    """
                    INSERT INTO content_entries(
                        id, category_id, stable_key, title, title_ja, title_en,
                        subtitle, description, search_text, verification_status
                    ) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,
                    (
                        sid,
                        "skills",
                        f"fe16-magic:{slug}:{sid[-8:]}",
                        title,
                        None,
                        title_en,
                        new_sub,
                        "",
                        search_txt,
                        "confirmed",
                    ),
                )
                conn.execute("INSERT OR IGNORE INTO skill_details(entry_id) VALUES(?)", (sid,))
                s_eff = None

            eff_obj: dict[str, Any] = {}
            if s_eff:
                try:
                    parsed = json.loads(s_eff)
                    if isinstance(parsed, dict):
                        eff_obj.update(parsed)
                except Exception:
                    pass
            if "in the Fire Emblem series" in str(eff_obj.get("技能分类", "")):
                eff_obj.pop("技能分类", None)
            eff_obj.update(stat_map)
            search_txt = " ".join(p for p in (title, title_en or "", new_sub, stat_map.get("魔法效果", "")) if p)
            conn.execute(
                "UPDATE skill_details SET skill_type=?, effect_json=? WHERE entry_id=?",
                (stat_map.get("魔法分类"), json.dumps(eff_obj, ensure_ascii=False), sid),
            )
            conn.execute(
                "UPDATE content_entries SET title=?, title_en=?, subtitle=?, description=?, search_text=? WHERE id=?",
                (title, title_en, new_sub, "", search_txt, sid),
            )


def _finalize_three_houses_skills_and_icons(
    conn: sqlite3.Connection,
    th_root: Path,
    output_game_dir: Path,
) -> tuple[int, int]:
    """Run AFTER localize_pack_db so all 3H magic, combat arts, abilities, prowess, and 318 real FE16 icons stay intact."""
    _finalize_three_houses_magic_entries(conn)

    skills_json = th_root / "sources" / "extracts" / "three-houses-skills-complete.json"
    icons_manifest_path = th_root / "assets" / "icons-manifest.json"
    rank_manifest_path = th_root / "assets" / "rank-icons-manifest.json"

    if not skills_json.is_file():
        return (0, 0)
    data = json.loads(skills_json.read_text(encoding="utf-8"))

    # Map existing `skills` entries by normalized English/Chinese keys
    skill_row_by_key: dict[str, tuple[str, str | None]] = {}
    if _has_table(conn, "skill_details"):
        for sid, title, title_en, eff_raw in conn.execute(
            """
            SELECT e.id, e.title, e.title_en, sd.effect_json
            FROM content_entries e
            LEFT JOIN skill_details sd ON sd.entry_id = e.id
            WHERE e.category_id = 'skills'
            """
        ).fetchall():
            for raw in (title_en, title):
                k = (raw or "").strip().lower()
                if k:
                    skill_row_by_key.setdefault(k, (sid, eff_raw))

    skill_icon_by_entry_id: dict[str, str] = {}

    # 1. Enrich all 77 Three Houses combat arts with exact stats, range, durability cost & Chinese effect
    for ca in data.get("combat_arts", []):
        name_en = (ca.get("name") or "").strip()
        if not name_en:
            continue
        row_info = skill_row_by_key.get(name_en.lower())
        if not row_info:
            continue
        sid, _ = row_info
        title_zh = _th_skill_zh_title(name_en)
        weapon_zh = combat_art_weapon_zh(ca.get("icon_file"))
        rng = _format_range_zh(ca.get("range"))
        cost_raw = (ca.get("cost") or "").replace("durability", "").strip()
        cost_clean = clean_combat_art_cost(ca.get("cost"))
        eff_zh = translate_th_combat_art_notes(name_en, ca.get("notes_en") or ca.get("help_text_en") or "")

        effect_map: dict[str, str] = {}
        if rng:
            effect_map["射程"] = rng
        for src_k, dst_k in (("might", "威力"), ("hit", "命中"), ("crit", "必杀"), ("avoid", "回避")):
            val = (ca.get(src_k) or "").strip()
            if val and val not in ("-", "--"):
                effect_map[dst_k] = val
        if cost_raw and cost_raw not in ("-", "--"):
            effect_map["耐久消耗"] = cost_raw
        if eff_zh:
            effect_map["战技效果"] = eff_zh

        is_assist = (weapon_zh == "通用 / 辅助" and "威力" not in effect_map)
        sub_parts = ["辅助战技" if is_assist else "战技"]
        if weapon_zh and weapon_zh != "通用 / 辅助":
            sub_parts.append(weapon_zh)
        if rng:
            sub_parts.append(f"射程 {rng}")
        subtitle = " · ".join(sub_parts)
        search_txt = " ".join(p for p in (title_zh, name_en, subtitle, weapon_zh, eff_zh) if p)

        conn.execute(
            "UPDATE content_entries SET title=?, title_en=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (title_zh, name_en, subtitle, "", search_txt, sid),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO skill_details(
                entry_id, skill_type, weapon_type, requirement, cost, activation_condition, effect_json
            ) VALUES(?,?,?,?,?,?,?)
            """,
            (
                sid,
                "战技",
                weapon_zh,
                None,
                None if "耐久消耗" in effect_map else cost_clean,
                None,
                json.dumps(effect_map, ensure_ascii=False),
            ),
        )
        if ca.get("icon_file"):
            skill_icon_by_entry_id[sid] = ca["icon_file"]

    # 2. Enrich all 234 Three Houses abilities (personal, class, skill-level, mastery, other)
    for ab in data.get("abilities", []):
        name_en = (ab.get("name") or "").strip()
        if not name_en:
            continue
        row_info = skill_row_by_key.get(name_en.lower())
        if not row_info:
            continue
        sid, prev_eff_raw = row_info
        title_zh = _th_skill_zh_title(name_en)
        sec_zh = TH_SECTION_ZH.get(ab.get("section") or "", "技能等级特技")
        eff_zh = translate_th_ability_effect(name_en, ab.get("effect_en") or "")
        req_zh = localize_th_ability_source(ab.get("source_en"), TH_CHARACTERS, TH_WEAPON_CATEGORIES, translate_class_title)

        effect_map: dict[str, str] = {}
        if eff_zh:
            effect_map["特技效果"] = eff_zh
        if prev_eff_raw:
            try:
                prev_obj = json.loads(prev_eff_raw)
                if isinstance(prev_obj, dict) and prev_obj.get("习得职业"):
                    effect_map["习得职业"] = str(prev_obj["习得职业"])
            except Exception:
                pass

        search_txt = " ".join(p for p in (title_zh, name_en, sec_zh, req_zh or "", eff_zh) if p)
        conn.execute(
            "UPDATE content_entries SET title=?, title_en=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (title_zh, name_en, sec_zh, "", search_txt, sid),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO skill_details(
                entry_id, skill_type, weapon_type, requirement, cost, activation_condition, effect_json
            ) VALUES(?,?,?,?,?,?,?)
            """,
            (
                sid,
                sec_zh,
                None,
                req_zh,
                None,
                None,
                json.dumps(effect_map, ensure_ascii=False) if effect_map else None,
            ),
        )
        if ab.get("icon_file"):
            skill_icon_by_entry_id[sid] = ab["icon_file"]

    # 3. Enrich all 40 Prowess / Reason / Faith / Authority Lv 1-5 abilities
    for base_en, levels in TH_PROWESS_LEVELS.items():
        weapon_zh = TH_PROWESS_BASE_WEAPON_ZH.get(base_en)
        icon_tpl = TH_PROWESS_ICON_PREFIX.get(base_en, "")
        for lv, en_lv, zh_lv, eff_zh in levels:
            row_info = skill_row_by_key.get(en_lv.lower()) or skill_row_by_key.get(en_lv.lower().replace(" lv ", " lv."))
            if not row_info:
                continue
            sid, _ = row_info
            req_rank = TH_PROWESS_RANK_REQ[lv - 1] if 1 <= lv <= len(TH_PROWESS_RANK_REQ) else ""
            req_zh = f"{weapon_zh} {req_rank}".strip() if weapon_zh else None
            search_txt = " ".join(p for p in (zh_lv, en_lv, "技能等级特技", req_zh or "", eff_zh) if p)
            conn.execute(
                "UPDATE content_entries SET title=?, title_en=?, subtitle=?, description=?, search_text=? WHERE id=?",
                (zh_lv, en_lv, "技能等级特技", "", search_txt, sid),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO skill_details(
                    entry_id, skill_type, weapon_type, requirement, cost, activation_condition, effect_json
                ) VALUES(?,?,?,?,?,?,?)
                """,
                (
                    sid,
                    "技能等级特技",
                    weapon_zh,
                    req_zh,
                    None,
                    None,
                    json.dumps({"特技效果": eff_zh}, ensure_ascii=False),
                ),
            )
            if icon_tpl:
                skill_icon_by_entry_id[sid] = icon_tpl.format(lv=lv)

    _refresh_category_count(conn, "skills")

    # 4. Ingest ALL 307 real FE16 icons (skills, equipment, crests) + 11 rank track icons!
    if not icons_manifest_path.is_file():
        return (0, 0)
    icons_manifest = json.loads(icons_manifest_path.read_text(encoding="utf-8"))
    icons_meta: dict[str, dict[str, Any]] = icons_manifest.get("icons", {})
    assets_base = th_root / "assets"
    dest_app_dir = output_game_dir / "assets" / "app"
    dest_app_dir.mkdir(parents=True, exist_ok=True)
    asset_cols = {row[1] for row in conn.execute("PRAGMA table_info(content_assets)")}

    converted_cache: dict[str, str] = {}  # icon_filename -> asset_id
    copied_count = 0
    total_bytes = 0

    def attach_fe16_icon(entry_id: str, icon_filename: str, role: str, classification: str) -> None:
        nonlocal copied_count, total_bytes
        meta = icons_meta.get(icon_filename)
        if not meta:
            return
        src_file = assets_base / meta["local_path"]
        if not src_file.is_file():
            return
        asset_id = converted_cache.get(icon_filename)
        if not asset_id:
            asset_id = f"fe16_wiki_icon:{icon_filename}"
            out_name = f"fe16_{hashlib.sha256(asset_id.encode('utf-8')).hexdigest()[:24]}.webp"
            dst_file = dest_app_dir / out_name
            app_rel = f"assets/app/{out_name}"
            w, h, app_bytes, app_sha = _convert_image_to_webp(
                src_file, dst_file, max_dim=128, lossless_small=True,
            )
            _upsert_content_asset_row(
                conn,
                asset_cols,
                asset_id,
                meta["local_path"],
                app_rel,
                src_file,
                classification,
                meta,
                w=w,
                h=h,
                app_bytes=app_bytes,
                app_sha=app_sha,
            )
            converted_cache[icon_filename] = asset_id
            copied_count += 1
            total_bytes += app_bytes
        conn.execute(
            "INSERT OR IGNORE INTO entry_assets(entry_id, asset_id, role, locator) VALUES(?,?,?,?)",
            (entry_id, asset_id, role, icon_filename),
        )

    # 4a. Normalize equipment_icons map with html.unescape (fixes failnaught τ, mire β, swarm ζ, dark spikes τ, bohr χ!)
    eq_icons_norm: dict[str, str] = {}
    for raw_k, icon_fn in data.get("equipment_icons", {}).items():
        k_unesc = html.unescape(raw_k).strip().lower()
        eq_icons_norm[k_unesc] = icon_fn
        parts = k_unesc.split()
        if len(parts) == 2 and len(parts[1]) == 1:
            eq_icons_norm.setdefault(parts[0], icon_fn)

    # Attach icons to `skills` (combat arts, abilities, prowess, and magic entries)
    for sid, icon_fn in skill_icon_by_entry_id.items():
        attach_fe16_icon(sid, icon_fn, "skill_icon_candidate", "three_houses_skill_icon")

    for sid, title, title_en, stype in conn.execute(
        """
        SELECT e.id, e.title, e.title_en, sd.skill_type
        FROM content_entries e
        LEFT JOIN skill_details sd ON sd.entry_id = e.id
        WHERE e.category_id = 'skills'
          AND NOT EXISTS (SELECT 1 FROM entry_assets ea WHERE ea.entry_id = e.id)
        """
    ).fetchall():
        key = (title_en or title or "").strip().lower()
        icon_fn = eq_icons_norm.get(key)
        if not icon_fn:
            if stype == "黑魔法":
                icon_fn = "Is_fe16_black_magic.png"
            elif stype == "暗魔法":
                icon_fn = "Is_fe16_dark_magic.png"
            elif stype == "白魔法":
                icon_fn = "Is_fe16_white_magic.png"
        if icon_fn:
            attach_fe16_icon(sid, icon_fn, "skill_icon_candidate", "three_houses_skill_icon")

    # 4b. Attach icons to ALL `equipment` (weapons, black/dark/white magic, shields, rings, items -> 353/353!)
    subtype_fallback_icon = {
        "黑魔法": "Is_fe16_black_magic.png",
        "暗魔法": "Is_fe16_dark_magic.png",
        "白魔法": "Is_fe16_white_magic.png",
        "剑术": "Is_fe16_sword.png",
        "枪术": "Is_fe16_lance.png",
        "斧术": "Is_fe16_axe.png",
        "弓术": "Is_fe16_bow.png",
        "格斗": "Is_fe16_brawling.png",
    }
    for eid, title, title_en, eq_type, subtype in conn.execute(
        """
        SELECT e.id, e.title, e.title_en, ed.equipment_type, ed.subtype
        FROM content_entries e
        LEFT JOIN equipment_details ed ON ed.entry_id = e.id
        WHERE e.category_id = 'equipment'
        """
    ).fetchall():
        key = (title_en or title or "").strip().lower()
        icon_fn = eq_icons_norm.get(key)
        if not icon_fn and " " in key:
            parts = key.split()
            if len(parts) == 2 and len(parts[1]) == 1:
                icon_fn = eq_icons_norm.get(parts[0])
        if not icon_fn:
            icon_fn = subtype_fallback_icon.get((subtype or "").strip())
        if icon_fn:
            role = "item_icon_candidate" if (eq_type or "") == "消耗品 / 道具" else "weapon_icon_candidate"
            attach_fe16_icon(eid, icon_fn, role, "three_houses_equipment_icon")

    # 4c. Attach icons to ALL 22/22 `crests`
    crest_icons_norm: dict[str, str] = {
        html.unescape(k).strip().lower(): v for k, v in data.get("crest_icons", {}).items()
    }
    for eid, title, title_en in conn.execute(
        "SELECT id, title, title_en FROM content_entries WHERE category_id='crests'"
    ).fetchall():
        key = (title_en or title or "").strip().lower()
        icon_fn = crest_icons_norm.get(key) or crest_icons_norm.get(f"crest of {key}")
        if icon_fn:
            attach_fe16_icon(eid, icon_fn, "crest_icon_candidate", "three_houses_crest_icon")

    # 4d. Copy 11 real FE16 skill-track rank icons -> assets/app/rank_<trackKey>.webp
    if rank_manifest_path.is_file():
        rank_manifest = json.loads(rank_manifest_path.read_text(encoding="utf-8"))
        r_icons_meta = rank_manifest.get("icons", {})
        for track_key, icon_fn in rank_manifest.get("rank_icons", {}).items():
            meta = r_icons_meta.get(icon_fn)
            if not meta:
                continue
            src_file = assets_base / meta["local_path"]
            if not src_file.is_file():
                continue
            dst_file = dest_app_dir / f"rank_{track_key}.webp"
            _, _, b, _ = _convert_image_to_webp(src_file, dst_file, max_dim=128, lossless_small=True)
            copied_count += 1
            total_bytes += b

    # 4e. Convert Rhea's official FE16 portrait for the `recruitment` category icon
    rhea_src = assets_base / "original" / "portraits" / "80396_Rhea Portrait.png"
    if rhea_src.is_file():
        _, _, b, _ = _convert_image_to_webp(rhea_src, dest_app_dir / "category_recruitment_rhea.webp", max_dim=256)
        copied_count += 1
        total_bytes += b

    return (copied_count, total_bytes)


def _format_signed_stat_map(raw_map: dict[str, int], key_map: dict[str, str]) -> dict[str, str]:
    out: dict[str, str] = {}
    for k, v in raw_map.items():
        if v is None:
            continue
        k_zh = key_map.get(k, key_map.get(k.lower(), k))
        out[k_zh] = f"+{v}" if v > 0 else str(v)
    return out


def _enrich_three_houses_classes_battalions_supports(
    conn: sqlite3.Connection,
    th_root: Path,
    output_game_dir: Path,
) -> tuple[int, int]:
    """Unify redundant 3H categories (growth->classes, battles->chapters), enrich all 92 classes,
    all 186 battalions (with 12 real FE16 icons), all 304 real Chinese support conversations,
    route/DLC guides, and clean player-facing category descriptions."""
    copied_count = 0
    total_bytes = 0

    # 1. Remove redundant duplicate categories `growth` (duplicated `classes`) & `battles` (duplicated `chapters`)
    redundant_ids = [
        r[0] for r in conn.execute(
            "SELECT id FROM content_entries WHERE category_id IN ('growth', 'battles')"
        ).fetchall()
    ]
    if redundant_ids:
        _delete_entries_cascade(conn, redundant_ids)
    conn.execute("DELETE FROM categories WHERE id IN ('growth', 'battles')")

    # 2. Enrich ALL 92/92 Three Houses `classes` with movement, exam requirements, skill EXP bonuses,
    #    class stat bonuses, dismount modifiers, innate/mastery skills, and clean Chinese notes
    classes_path = th_root / "sources" / "extracts" / "three-houses-classes-complete.json"
    if classes_path.is_file() and _has_table(conn, "class_details"):
        cls_data = json.loads(classes_path.read_text(encoding="utf-8")).get("classes", [])

        def norm_cls_key(name: str) -> str:
            s = re.sub(r"\s*,\s*", ", ", (name or "").strip())
            s = re.sub(r"\(\s*(?:CF|SS)\s*", "(", s, flags=re.IGNORECASE)
            s = re.sub(r"\s+\)", ")", s)
            s = re.sub(r"\(\s+", "(", s)
            return re.sub(r"\s+", " ", s).strip().lower()

        cls_by_norm = {norm_cls_key(c["name"]): c for c in cls_data if c.get("name")}
        tier_zh_map = {
            "Unique": "专属", "Beginner": "初级", "Intermediate": "中级",
            "Advanced": "高级", "Master": "最高级", "Special": "特殊", "--": "特殊 / 魔物",
        }
        move_zh_map = {
            "infantry": "步兵", "armored": "重甲", "cavalry": "骑兵",
            "flying": "飞行", "monster": "魔物", "dragon": "龙族",
        }

        for eid, title, title_en, cur_tier, cur_mtype, cur_base, cur_gr, cur_cskill in conn.execute(
            """
            SELECT e.id, e.title, e.title_en, cd.tier, cd.movement_type,
                   cd.base_stats_json, cd.growths_json, cd.class_skill
            FROM content_entries e
            LEFT JOIN class_details cd ON cd.entry_id = e.id
            WHERE e.category_id = 'classes'
            """
        ).fetchall():
            info = cls_by_norm.get(norm_cls_key(title_en or title or ""))
            if not info:
                continue

            tier_raw = info.get("tier") or ""
            tier_zh = tier_zh_map.get(tier_raw, cur_tier or tier_raw)
            mtype_zh = cur_mtype
            if info.get("unit_type"):
                mtype_zh = " / ".join(
                    move_zh_map.get(tok.strip().lower(), tok.strip())
                    for tok in info["unit_type"].split(",") if tok.strip()
                )
            movement = info.get("movement")

            # Certification exam requirements (e.g. "最高级考试（Lv.30+）· 枪术 C、斧术 A、飞行 A")
            exam_reqs = info.get("exam_requirements") or {}
            req_tokens = [f"{TH_WEAPON_CATEGORIES.get(k, k)} {v}" for k, v in exam_reqs.items()]
            min_lv = TH_CLASS_TIER_MIN_LEVEL.get(tier_raw)
            promo_parts = []
            if min_lv:
                promo_parts.append(f"{tier_zh}考试（{min_lv}）")
            if req_tokens:
                promo_parts.append("、".join(req_tokens))
            promo_req = " · ".join(promo_parts) if promo_parts else None

            # Class skill EXP bonuses -> weapon_ranks_json
            sk_bonuses = info.get("skill_bonuses") or {}
            wr_json = (
                json.dumps(
                    {"兵种技能经验加成": " · ".join(f"{TH_WEAPON_CATEGORIES.get(k, k)} +{v}" for k, v in sk_bonuses.items())},
                    ensure_ascii=False,
                )
                if sk_bonuses else None
            )

            # Base stats & growth rates
            base_stats = info.get("base_stats") or {}
            base_json = (
                localize_stat_dict(json.dumps(base_stats, ensure_ascii=False))
                if any(v != 0 for v in base_stats.values()) else cur_base
            )
            growths = info.get("player_growths") or {}
            growth_json = (
                localize_stat_dict(json.dumps(growths, ensure_ascii=False), is_growth=True)
                if growths else cur_gr
            )

            # Class innate + mastery skills (clean Chinese)
            innate = [_th_skill_zh_title(s.rstrip("†*").strip()) for s in (info.get("innate_abilities") or []) if s.strip()]
            mastery = [_th_skill_zh_title(s.rstrip("†*").strip()) for s in (info.get("mastery_abilities") or []) if s.strip()]
            m_exp = info.get("mastery_exp")
            cskill_parts = []
            if innate:
                cskill_parts.append("兵种自带特技：" + "、".join(innate))
            if mastery:
                exp_s = f"（精通需 {m_exp} 经验）" if m_exp else ""
                cskill_parts.append("兵种精通特技：" + "、".join(mastery) + exp_s)
            class_skill_zh = "；".join(cskill_parts) if cskill_parts else cur_cskill

            # Structured stat bonuses, dismount modifiers, and Chinese class notes
            desc_payload: dict[str, Any] = {}
            stat_bonuses = _format_signed_stat_map(info.get("stat_bonuses") or {}, STAT_KEY_ZH)
            if stat_bonuses:
                desc_payload["stat_bonuses"] = stat_bonuses
            dismount_mods = _format_signed_stat_map(info.get("dismount_modifiers") or {}, STAT_KEY_ZH)
            if dismount_mods:
                desc_payload["dismount_modifiers"] = dismount_mods
            notes_zh = translate_th_class_notes(info.get("notes"))
            if notes_zh:
                desc_payload["fields"] = {"兵种特性与备注": notes_zh}

            # Rich subtitle matching Engage's polish (e.g. "最高级 · 飞行 · 移动 8 · 枪术 C / 斧术 A / 飞行 A")
            sub_parts = [p for p in (
                tier_zh,
                mtype_zh,
                f"移动 {movement}" if movement is not None else "",
                " / ".join(req_tokens) if req_tokens else "",
            ) if p]
            new_sub = " · ".join(sub_parts)
            new_desc = json.dumps(desc_payload, ensure_ascii=False) if desc_payload else ""
            search_txt = " ".join(p for p in (title, title_en or "", new_sub, promo_req or "", class_skill_zh or "", notes_zh or "") if p)

            conn.execute(
                "UPDATE content_entries SET subtitle=?, description=?, search_text=? WHERE id=?",
                (new_sub, new_desc, search_txt, eid),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO class_details(
                    entry_id, tier, movement, movement_type, weapon_ranks_json,
                    base_stats_json, growths_json, caps_json, promotion_requirement, class_skill
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    eid,
                    tier_zh or None,
                    movement,
                    mtype_zh or None,
                    wr_json,
                    base_json,
                    growth_json,
                    None,
                    promo_req,
                    class_skill_zh,
                ),
            )

    # 3. Enrich ALL 186/186 Three Houses `battalions` with Lv.1 & Lv.5 stats, rank, gambit, endurance + 12 real FE16 icons
    bat_path = th_root / "sources" / "extracts" / "three-houses-battalions-complete.json"
    bat_manifest_path = th_root / "assets" / "battalion-icons-manifest.json"
    if bat_path.is_file() and _has_table(conn, "mechanic_details"):
        bat_list = json.loads(bat_path.read_text(encoding="utf-8")).get("battalions", [])
        bat_by_norm = {
            b["name"].replace("’", "'").replace("‘", "'").strip().lower(): b
            for b in bat_list if b.get("name")
        }
        bat_icons_meta = (
            json.loads(bat_manifest_path.read_text(encoding="utf-8")).get("icons", {})
            if bat_manifest_path.is_file() else {}
        )
        assets_base = th_root / "assets"
        dest_app_dir = output_game_dir / "assets" / "app"
        dest_app_dir.mkdir(parents=True, exist_ok=True)
        asset_cols = {row[1] for row in conn.execute("PRAGMA table_info(content_assets)")}
        bat_icon_cache: dict[str, str] = {}

        def attach_bat_icon(entry_id: str, icon_fn: str) -> None:
            nonlocal copied_count, total_bytes
            meta = bat_icons_meta.get(icon_fn)
            if not meta:
                return
            src_file = assets_base / meta["local_path"]
            if not src_file.is_file():
                return
            aid = bat_icon_cache.get(icon_fn)
            if not aid:
                aid = f"fe16_bat_icon:{icon_fn}"
                out_name = f"fe16_{hashlib.sha256(aid.encode('utf-8')).hexdigest()[:24]}.webp"
                dst_file = dest_app_dir / out_name
                app_rel = f"assets/app/{out_name}"
                w, h, app_b, app_sha = _convert_image_to_webp(src_file, dst_file, max_dim=128, lossless_small=True)
                _upsert_content_asset_row(
                    conn, asset_cols, aid, meta["local_path"], app_rel, src_file,
                    "three_houses_battalion_icon", meta, w=w, h=h, app_bytes=app_b, app_sha=app_sha,
                )
                bat_icon_cache[icon_fn] = aid
                copied_count += 1
                total_bytes += app_b
            conn.execute(
                "INSERT OR IGNORE INTO entry_assets(entry_id, asset_id, role, locator) VALUES(?,?,?,?)",
                (entry_id, aid, "battalion_icon_candidate", icon_fn),
            )

        for eid, title, title_en, cur_rule, cur_params in conn.execute(
            """
            SELECT e.id, e.title, e.title_en, md.rule_description, md.parameters_json
            FROM content_entries e
            LEFT JOIN mechanic_details md ON md.entry_id = e.id
            WHERE e.category_id = 'battalions'
            """
        ).fetchall():
            key = (title_en or title or "").replace("’", "'").replace("‘", "'").strip().lower()
            binfo = bat_by_norm.get(key)
            params: dict[str, Any] = {}
            if cur_params:
                try:
                    params = json.loads(cur_params)
                except Exception:
                    params = {}
            # Clean any leftover English unit subgroup words
            if "兵种类别" in params:
                params["兵种类别"] = (
                    str(params["兵种类别"])
                    .replace("Archers", "弓兵")
                    .replace("Mages", "魔道")
                    .replace("Melee", "近战")
                    .replace("Ranged", "远程")
                    .replace("Support", "支援")
                )

            auth_rank = ""
            gambit_zh = ""
            endur = None
            desc_payload: dict[str, Any] = {}
            if binfo:
                auth_rank = (binfo.get("authority_rank") or "").strip()
                gambit_zh = _translate_th_gambit(binfo.get("gambit") or "")
                endur = binfo.get("endurance")
                if auth_rank:
                    params["所需指挥等级"] = auth_rank
                if gambit_zh:
                    params["骑士团计策"] = gambit_zh
                if endur is not None:
                    params["满级耐久"] = str(endur)
                lv5 = _format_signed_stat_map(binfo.get("lv5_stats") or {}, TH_BATTALION_STAT_ZH)
                lv1 = _format_signed_stat_map(binfo.get("lv1_stats") or {}, TH_BATTALION_STAT_ZH)
                if lv5:
                    desc_payload["battalion_lv5_stats"] = lv5
                if lv1:
                    desc_payload["battalion_lv1_stats"] = lv1
            else:
                auth_rank = str(params.get("所需指挥等级") or "").strip()
                gambit_zh = str(params.get("骑士团计策") or "").strip()

            sub_parts = []
            if auth_rank:
                sub_parts.append(f"指挥 {auth_rank} 级")
            if gambit_zh:
                sub_parts.append(f"计策：{gambit_zh}")
            if endur is not None:
                sub_parts.append(f"耐久 {endur}")
            new_sub = " · ".join(sub_parts)
            new_desc = json.dumps(desc_payload, ensure_ascii=False) if desc_payload else ""
            search_txt = " ".join(p for p in (title, title_en or "", new_sub, str(params.get("兵种类别") or "")) if p)

            conn.execute(
                "UPDATE content_entries SET subtitle=?, description=?, search_text=? WHERE id=?",
                (new_sub, new_desc, search_txt, eid),
            )
            conn.execute(
                "UPDATE mechanic_details SET parameters_json=? WHERE entry_id=?",
                (json.dumps(params, ensure_ascii=False) if params else None, eid),
            )

            # Attach real FE16 battalion icon by unit type + authority tier
            unit_cat_txt = f"{params.get('兵种类别', '')} {title} {title_en or ''}"
            if any(k in unit_cat_txt for k in ("飞行", "天马", "飞龙", "Flying", "Pegasus", "Wyvern")):
                u_slug = "flying"
            elif any(k in unit_cat_txt for k in ("重甲", "重装", "Armored", "Fortress")):
                u_slug = "armored"
            elif any(k in unit_cat_txt for k in ("骑兵", "骑马", "Cavalry", "Paladin", "Horsemen")):
                u_slug = "cavalry"
            else:
                u_slug = "infantry"
            tier_suf = "_gold" if auth_rank == "A" else ("_silver" if auth_rank in ("B", "C") else "")
            attach_bat_icon(eid, f"Is_fe16_battalion_{u_slug}{tier_suf}.png")

    # 4. Enrich ALL 304 real Three Houses `supports` with official Simplified Chinese dialogues & remove 22 bogus rows
    sup_path = th_root / "sources" / "extracts" / "three-houses-supports-zh.json"
    if sup_path.is_file() and _has_table(conn, "support_details"):
        sup_pairs = json.loads(sup_path.read_text(encoding="utf-8")).get("pairs", {})
        sup_lookup: dict[tuple[str, str], dict[str, Any]] = {}
        for p in sup_pairs.values():
            a, b = p["char1"].lower(), p["char2"].lower()
            sup_lookup[(a, b)] = p
            sup_lookup[(b, a)] = p

        def norm_sup_char(s: str) -> str:
            return re.sub(r"\s*\([MF]\)\s*", "", s).strip().lower()

        bogus_sup_ids: list[str] = []
        for eid, title, title_en in conn.execute(
            "SELECT id, title, title_en FROM content_entries WHERE category_id='supports'"
        ).fetchall():
            parts = [p.strip() for p in (title_en or "").split("+")]
            matched_pair = None
            if len(parts) == 2:
                matched_pair = sup_lookup.get((norm_sup_char(parts[0]), norm_sup_char(parts[1])))
            if not matched_pair:
                bogus_sup_ids.append(eid)
                continue

            rank_list: list[str] = matched_pair.get("rank_list") or list(matched_pair.get("ranks", {}).keys())
            ranks_str = " / ".join(rank_list)
            convs = []
            for rk in rank_list:
                raw_lines = matched_pair.get("ranks", {}).get(rk, [])
                if raw_lines:
                    convs.append({
                        "rank": rk,
                        "lines": [{"s": ln["speaker"], "t": ln["text"]} for ln in raw_lines],
                    })
            conv_json = json.dumps({"conversations": convs}, ensure_ascii=False)
            new_sub = f"支援等级 {ranks_str} · 共 {len(convs)} 段对话"
            search_txt = f"{title} {title_en or ''} {new_sub}"
            conn.execute(
                "UPDATE content_entries SET subtitle=?, description=?, search_text=? WHERE id=?",
                (new_sub, "", search_txt, eid),
            )
            conn.execute(
                "UPDATE support_details SET support_rank=?, dialogue_summary=? WHERE entry_id=?",
                (ranks_str, conv_json, eid),
            )

        if bogus_sup_ids:
            _delete_entries_cascade(conn, bogus_sup_ids)
        _refresh_category_count(conn, "supports")

    # 5. Enrich `routes` (4) and `dlc` (1) with authentic Chinese guides
    for eid, title, title_en in conn.execute(
        "SELECT id, title, title_en FROM content_entries WHERE category_id IN ('routes', 'dlc')"
    ).fetchall():
        guide = None
        for k, g in TH_ROUTE_GUIDES.items():
            if k.lower() in (title_en or "").lower() or k.lower() in (title or "").lower():
                guide = g
                break
        if not guide:
            if "红花" in title:
                guide = TH_ROUTE_GUIDES["Crimson Flower"]
            elif "银雪" in title:
                guide = TH_ROUTE_GUIDES["Silver Snow"]
            elif "苍月" in title:
                guide = TH_ROUTE_GUIDES["Azure Moon"]
            elif "翠风" in title:
                guide = TH_ROUTE_GUIDES["Verdant Wind"]
            elif "煤暗" in title:
                guide = TH_ROUTE_GUIDES["Cindered Shadows"]
        if guide:
            sub = guide.get("路线归属") or guide.get("篇章定位") or ""
            if guide.get("章节规模"):
                sub = f"{sub} · {guide['章节规模']}" if sub else guide["章节规模"]
            conn.execute(
                "UPDATE content_entries SET subtitle=?, description=? WHERE id=?",
                (sub, json.dumps({"fields": guide}, ensure_ascii=False), eid),
            )

    # 5b. Enrich ALL 40 Three Houses `recruitment` & `character_details` with structured Chinese join conditions & route availability
    rec_rows = conn.execute(
        """
        SELECT e.id, e.title, e.title_en, md.parameters_json
        FROM content_entries e
        LEFT JOIN mechanic_details md ON md.entry_id = e.id
        WHERE e.category_id = 'recruitment'
        """
    ).fetchall()
    for eid, title, title_en, cur_params in rec_rows:
        matched_key = None
        src_en = title_en or title or ""
        for k in TH_RECRUITMENT_GUIDE:
            if src_en.startswith(f"{k} ") or src_en.startswith(f"{k}—") or src_en == k:
                matched_key = k
                break
        if not matched_key and cur_params:
            try:
                p_obj = json.loads(cur_params)
                cname = str(p_obj.get("角色") or p_obj.get("character_name") or "")
                for k, g in TH_RECRUITMENT_GUIDE.items():
                    if g["name_zh"].startswith(cname) or cname == k:
                        matched_key = k
                        break
            except Exception:
                pass
        if not matched_key:
            continue
        guide = TH_RECRUITMENT_GUIDE[matched_key]
        new_title = f"{guide['name_zh']} · 入队与招募条件"
        new_sub = guide["subtitle"]
        is_stat_recruit = "≥" in guide["stat_req"] and "Lv." not in guide["stat_req"]
        params_zh: dict[str, str] = {
            "角色": guide["name_zh"],
            "所属学级 / 阵营": guide["house_zh"],
            "招募方式": guide["kind"],
            "最早加入章节": guide["chapter"],
            "属性门槛": guide["stat_req"],
            "技能门槛": guide["skill_req"],
            "可用路线": guide["routes"],
            "招募贴士": guide["notes"],
        }
        formula_zh = TH_SUPPORT_DISCOUNT_RULE if is_stat_recruit else None
        search_txt = " ".join(
            [
                new_title,
                title_en or "",
                new_sub,
                guide["house_zh"],
                guide["kind"],
                guide["stat_req"],
                guide["skill_req"],
                guide["routes"],
                guide["notes"],
            ]
        )
        conn.execute(
            "UPDATE content_entries SET title=?, subtitle=?, description=?, search_text=? WHERE id=?",
            (new_title, new_sub, "", search_txt, eid),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO mechanic_details(
                entry_id, mechanic_type, game_version, rule_description, parameters_json, formula_text
            ) VALUES(?,?,?,?,?,?)
            """,
            (
                eid,
                "招募与加入条件",
                None,
                guide["notes"],
                json.dumps(params_zh, ensure_ascii=False),
                formula_zh,
            ),
        )

    # Also sync clean structured route_availability & join_condition on Three Houses `character_details`
    if _has_table(conn, "character_details"):
        char_rows = conn.execute(
            """
            SELECT e.id, e.title, e.title_en, cd.join_condition, cd.route_availability
            FROM content_entries e
            JOIN character_details cd ON cd.entry_id = e.id
            WHERE e.category_id = 'characters'
            """
        ).fetchall()
        for ceid, ctitle, ctitle_en, cur_join, cur_route in char_rows:
            src_low = (ctitle_en or "").strip().lower()
            matched_guide = None
            for k, g in TH_RECRUITMENT_GUIDE.items():
                if k.lower() in src_low or ctitle == g["name_zh"] or ctitle.startswith(g["name_zh"].split("（")[0]):
                    matched_guide = g
                    break
            if not matched_guide:
                continue
            rich_join = " · ".join(
                p for p in (
                    matched_guide["kind"],
                    matched_guide["chapter"],
                    f"需求：{matched_guide['stat_req']} / {matched_guide['skill_req']}"
                    if "无需" not in matched_guide["stat_req"] and "不可" not in matched_guide["stat_req"]
                    else "",
                ) if p
            )
            if matched_guide["notes"]:
                rich_join = f"{rich_join}\n{matched_guide['notes']}"
            conn.execute(
                "UPDATE character_details SET route_availability=?, join_condition=? WHERE entry_id=?",
                (matched_guide["routes"], rich_join, ceid),
            )

    # 6. Replace all raw scraper-audit category descriptions with clean player-facing Chinese descriptions
    for cat_id, desc_zh in TH_CATEGORY_DESCRIPTIONS_ZH.items():
        conn.execute("UPDATE categories SET description=? WHERE id=?", (desc_zh, cat_id))

    return (copied_count, total_bytes)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(65536):
            digest.update(chunk)
    return digest.hexdigest()


def _copy_or_optimize_asset(src: Path, dst: Path, max_dim: int = 192) -> int:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if Image is None:
        shutil.copy2(src, dst)
        return dst.stat().st_size
    try:
        with Image.open(src) as img:
            w, h = img.size
            if max(w, h) <= max_dim and src.stat().st_size <= 24 * 1024:
                shutil.copy2(src, dst)
            else:
                copy_img = img.copy()
                if max(w, h) > max_dim:
                    copy_img.thumbnail((max_dim, max_dim))
                copy_img.save(dst, format="WEBP", quality=82, method=4)
    except Exception:
        shutil.copy2(src, dst)
    return dst.stat().st_size


def prepare_single_game_pack(
    game_id: str,
    source_db: Path,
    source_assets_root: Path | None,
    output_game_dir: Path,
    max_image_dim: int = 192,
) -> dict[str, Any]:
    if not source_db.is_file():
        raise FileNotFoundError(f"找不到游戏数据库：{source_db}")
    with closing(sqlite3.connect(f"{source_db.resolve().as_uri()}?mode=ro", uri=True)) as src_conn:
        app_id = src_conn.execute("PRAGMA application_id").fetchone()[0]
        user_ver = src_conn.execute("PRAGMA user_version").fetchone()[0]
        if app_id != APPLICATION_ID or user_ver != SCHEMA_VERSION:
            raise ValueError(f"{game_id} 数据库格式不符合 game-data-v1：app_id={app_id}, version={user_ver}")

    output_game_dir.mkdir(parents=True, exist_ok=True)
    pack_db = output_game_dir / "pack.db"
    fd, tmp_name = tempfile.mkstemp(prefix=f".{game_id}-", suffix=".db", dir=output_game_dir)
    os.close(fd)
    tmp_db = Path(tmp_name)
    asset_files_copied = 0
    asset_total_bytes = 0
    manifest_assets_dir: Path | None = None
    manifest_file: Path | None = None
    if game_id == "three-houses" and source_assets_root:
        if (source_assets_root / "assets" / "manifest.json").is_file():
            manifest_assets_dir = source_assets_root / "assets"
            manifest_file = manifest_assets_dir / "manifest.json"
        elif (source_assets_root / "manifest.json").is_file():
            manifest_assets_dir = source_assets_root
            manifest_file = source_assets_root / "manifest.json"

    engage_wiki_conversions: list[tuple[Path, str, bool, int]] = []
    engage_rank_files: dict[str, Path] = {}
    try:
        shutil.copy2(source_db, tmp_db)
        with closing(sqlite3.connect(tmp_db)) as conn:
            conn.execute("PRAGMA foreign_keys=OFF")
            with conn:
                for table in STRIP_TABLES:
                    exists = conn.execute(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,),
                    ).fetchone()
                    if exists:
                        conn.execute(f'DELETE FROM "{table}"')
                if game_id == "engage":
                    _propagate_engage_bond_ring_portraits(conn)
                    prof_unlocks = _consolidate_engage_weapon_proficiency(conn)
                    _consolidate_engage_characters(conn, prof_unlocks)
                    _consolidate_engage_emblems(conn)
                    _consolidate_engage_classes(conn)
                    _consolidate_engage_country_investment(conn)
                    _consolidate_engage_growth_mechanics(conn)
                    _consolidate_engage_paralogues(conn)
                    _sanitize_engage_entries(conn)
                    _cleanup_engage_bond_rings(conn)
                    if source_assets_root:
                        engage_wiki_conversions, engage_rank_files = _register_engage_wiki_icons(
                            conn, source_assets_root,
                        )
                if game_id == "three-houses":
                    th_skill_meta: dict[str, dict[str, Any]] = {}
                    if source_assets_root:
                        th_skill_meta = _prepare_three_houses_skills_catalog(
                            conn, source_assets_root / "sources" / "extracts" / "three-houses-skills-complete.json"
                        )
                        _enrich_three_houses_characters(
                            conn,
                            source_assets_root / "sources" / "extracts" / "character-stats.json",
                            th_skill_meta,
                        )
                    if manifest_assets_dir and manifest_file:
                        asset_files_copied, asset_total_bytes = _ingest_three_houses_manifest_assets(
                            conn, manifest_assets_dir, manifest_file, output_game_dir,
                        )
                localize_pack_db(conn, game_id)
                if game_id == "engage":
                    for cat_id, desc_zh in ENGAGE_CATEGORY_DESCRIPTIONS_ZH.items():
                        conn.execute("UPDATE categories SET description=? WHERE id=?", (desc_zh, cat_id))
                    conn.execute("DELETE FROM categories WHERE id='index' AND COALESCE(record_count, 0) = 0")
                if game_id == "three-houses":
                    if source_assets_root:
                        extra_cnt, extra_bytes = _finalize_three_houses_skills_and_icons(
                            conn, source_assets_root, output_game_dir,
                        )
                        asset_files_copied += extra_cnt
                        asset_total_bytes += extra_bytes
                        extra_cnt2, extra_bytes2 = _enrich_three_houses_classes_battalions_supports(
                            conn, source_assets_root, output_game_dir,
                        )
                        asset_files_copied += extra_cnt2
                        asset_total_bytes += extra_bytes2
                    else:
                        _finalize_three_houses_magic_entries(conn)
                for stmt in INDEX_STATEMENTS:
                    conn.execute(stmt)
                if _has_table(conn, "character_details"):
                    conn.execute("CREATE INDEX IF NOT EXISTS idx_char_details_affiliation ON character_details(affiliation, entry_id)")
                if _has_table(conn, "class_details"):
                    conn.execute("CREATE INDEX IF NOT EXISTS idx_class_details_tier ON class_details(tier, entry_id)")
                conn.execute("DELETE FROM content_metadata WHERE key IN ('stripped_workbook_cells','pack_flavor')")
                conn.execute("INSERT INTO content_metadata VALUES('pack_flavor','android-readonly')")
                conn.execute("INSERT INTO content_metadata VALUES('stripped_workbook_cells','1')")
            conn.execute("ANALYZE")
            conn.execute("VACUUM")
            conn.execute("PRAGMA foreign_keys=ON")
            if conn.execute("PRAGMA quick_check").fetchone() != ("ok",):
                raise RuntimeError(f"{game_id} SQLite quick_check 失败")
            fk_issues = conn.execute("PRAGMA foreign_key_check").fetchall()
            if fk_issues:
                raise RuntimeError(f"{game_id} SQLite 外键校验失败: {fk_issues[:5]}")
            for table in STRIP_TABLES:
                exists = conn.execute(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,),
                ).fetchone()
                if exists and conn.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0] != 0:
                    raise RuntimeError(f"{game_id} 未清空原始工作簿表：{table}")
            plan_entries = " ".join(
                str(r) for r in conn.execute(
                    "EXPLAIN QUERY PLAN SELECT id,title FROM content_entries WHERE category_id=? ORDER BY title COLLATE NOCASE, id LIMIT 40 OFFSET 0",
                    ("characters",),
                ).fetchall()
            )
            if "INDEX" not in plan_entries or "SCAN content_entries" in plan_entries:
                raise RuntimeError(f"{game_id} 查询未命中分类标题索引：{plan_entries}")
            plan_rel = " ".join(
                str(r) for r in conn.execute(
                    "EXPLAIN QUERY PLAN SELECT target_entry_id FROM content_relations WHERE source_entry_id=?",
                    ("test",),
                ).fetchall()
            )
            if "INDEX" not in plan_rel or "SCAN content_relations" in plan_rel:
                raise RuntimeError(f"{game_id} 查询未命中关系索引：{plan_rel}")
            entry_count = conn.execute("SELECT COUNT(*) FROM content_entries").fetchone()[0]
            cat_count = conn.execute("SELECT COUNT(*) FROM categories WHERE applicable=1").fetchone()[0]
            rel_count = conn.execute("SELECT COUNT(*) FROM content_relations").fetchone()[0]
            asset_rows = conn.execute("SELECT DISTINCT app_file FROM content_assets WHERE app_file IS NOT NULL").fetchall()
        os.replace(tmp_db, pack_db)
    finally:
        tmp_db.unlink(missing_ok=True)

    if manifest_file is None and source_assets_root and (source_assets_root / "assets").is_dir():
        dest_assets = output_game_dir / "assets"
        if dest_assets.exists():
            shutil.rmtree(dest_assets)
        referenced = {row[0] for row in asset_rows if row[0]}
        for rel_path in sorted(referenced):
            src_file = source_assets_root / rel_path
            if src_file.is_file():
                asset_total_bytes += _copy_or_optimize_asset(src_file, output_game_dir / rel_path, max_dim=max_image_dim)
                asset_files_copied += 1
        for src_png, app_rel, dark_badge, pixel_min in engage_wiki_conversions:
            dst_file = output_game_dir / app_rel
            _, _, b, _ = _convert_image_to_webp(
                src_png,
                dst_file,
                max_dim=128,
                dark_badge_bg=dark_badge,
                pixel_upscale_min=pixel_min,
                lossless_small=True,
            )
            asset_total_bytes += b
            asset_files_copied += 1
        for track_key, src_png in engage_rank_files.items():
            dst_file = output_game_dir / "assets" / "app" / f"rank_{track_key}.webp"
            _, _, b, _ = _convert_image_to_webp(
                src_png,
                dst_file,
                max_dim=128,
                dark_badge_bg=True,
                lossless_small=True,
            )
            asset_total_bytes += b
            asset_files_copied += 1

    db_sha = _sha256(pack_db)
    (output_game_dir / "pack.sha256").write_text(db_sha + "\n", encoding="ascii")
    return {
        "game_id": game_id,
        "db_sha256": db_sha,
        "db_bytes": pack_db.stat().st_size,
        "categories": cat_count,
        "entries": entry_count,
        "relations": rel_count,
        "assets_count": asset_files_copied,
        "assets_bytes": asset_total_bytes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="构建精简高索引多游戏只读 SQLite 资源包")
    parser.add_argument("--games-root", type=Path, default=Path("data/games"))
    parser.add_argument("--output-root", type=Path, default=Path("android-app/build/generated/multi-game-assets/games"))
    parser.add_argument("--max-image-dim", type=int, default=192)
    args = parser.parse_args()

    manifests = {}
    for game_id in ("engage", "three-houses"):
        game_dir = args.games_root / game_id
        has_assets = (game_dir / "assets" / "app").is_dir() or (game_dir / "assets" / "manifest.json").is_file()
        summary = prepare_single_game_pack(
            game_id=game_id,
            source_db=game_dir / "database" / "content.db",
            source_assets_root=game_dir if has_assets else None,
            output_game_dir=args.output_root / game_id,
            max_image_dim=args.max_image_dim,
        )
        manifests[game_id] = summary
        print(
            f"Prepared {game_id}: {summary['categories']} categories, {summary['entries']} entries, "
            f"{summary['relations']} relations, db={summary['db_bytes'] // 1024} KB, "
            f"assets={summary['assets_count']} ({summary['assets_bytes'] // 1024} KB)"
        )

    args.output_root.mkdir(parents=True, exist_ok=True)
    (args.output_root / "manifest.json").write_text(
        json.dumps({"schema_version": 1, "games": manifests}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
