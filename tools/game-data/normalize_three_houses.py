from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
PACKAGE = REPO_ROOT / "data" / "games" / "three-houses"
EXTRACTS = PACKAGE / "sources" / "extracts"
STAT8 = ["HP", "Str", "Mag", "Dex", "Spd", "Lck", "Def", "Res"]
STAT9 = STAT8 + ["Cha"]

CATEGORIES = [
    ("characters", "角色", "当前收录可操作角色名册摘录；非可操作剧情角色和路线可用性尚未覆盖完整。", "partial", 0),
    ("recruitment", "招募与加入条件", "收录来源页列出的加入条件；路线资格与具体条件尚未覆盖完整。", "partial", 0),
    ("routes", "路线", "收录四条剧情路线名称；逐章攻略覆盖仍不完整。", "partial", 1),
    ("classes", "职业与资格", "收录职业名称、阶级、单位类型、基础数值及来源表中可读的职业字段。", "partial", 1),
    ("growth", "成长与属性", "收录职业成长修正；角色成长值与数值冲突核查尚未完成。", "partial", 2),
    ("crests", "纹章", "收录纹章目录、持有者、圣遗物和纹章效果摘录；角色关联尚未完整核验。", "partial", 0),
    ("battalions", "军团", "收录来源目录中的玩家/敌方军团名称及公会战技、等级、价格；完整战斗修正与入手条件尚未覆盖。", "partial", 0),
    ("skills", "技能、战技与魔法", "收录 FE16 标记的战技/黑魔法/白魔法，以及职业内置/精通能力；习得条件与完整数值效果尚未覆盖。", "partial", 0),
    ("equipment", "武器、装备与道具", "收录武器/装备/道具目录、来源表数值及商店信息；路线、版本差异与来源冲突尚未完整核查。", "partial", 0),
    ("supports", "支援与对话", "只收录支援配对索引；支援等级、解锁条件和对话内容尚未采集。", "partial", 0),
    ("social", "礼物、茶会与料理", "收录礼物/料理偏好、特殊餐厅活动和茶会分数机制；角色话题、完整支援增益及部分 DLC 数据尚未覆盖。", "partial", 0),
    ("monastery", "修道院活动", "收录行动点数等级、圣人像奖励、房间奖励、商店目录和 Amiibo 音乐索引；完整日历活动流程尚未覆盖。", "partial", 0),
    ("chapters", "章节与外传", "收录章节、外传和 DLC 章节目录；地图、敌军细节和奖励尚未覆盖完整。", "partial", 0),
    ("battles", "地图、敌人与目标", "收录章节目录页能读到的目标、失败条件、新单位与首领摘要；地图坐标、敌军清单、增援和完整奖励未收录。", "partial", 0),
    ("mechanics", "机制与规则", "收录餐厅动机值与茶会评分/额外点数规则；核心战斗公式与成长计算仍未覆盖。", "partial", 0),
    ("dlc", "DLC", "收录灰狼之章及其章节索引；DLC 全部内容和版本细节尚未覆盖。", "partial", 1),
]

def _read_json(path: Path, default: Any = None) -> Any:
    if not path.is_file():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _media_inventory(asset_manifest: dict[str, Any]) -> dict[str, Any]:
    raw_assets = asset_manifest.get("assets", [])
    if not isinstance(raw_assets, list):
        raise ValueError("Three Houses asset manifest 'assets' must be a list")
    assets = [item for item in raw_assets if isinstance(item, dict)]
    category_counts: dict[str, int] = {}
    mime_counts: dict[str, int] = {}
    rights_counts: dict[str, int] = {}
    smaller_response_mime_counts: dict[str, int] = {}
    format_original_count = 0
    source_mime_match_count = 0
    smaller_response_count = 0
    for item in assets:
        category = item.get("local_category", "unspecified")
        category_counts[category] = category_counts.get(category, 0) + 1
        mime = item.get("download_mime_type", "unspecified")
        mime_counts[mime] = mime_counts.get(mime, 0) + 1
        rights = item.get("rights_status", "unspecified")
        rights_counts[rights] = rights_counts.get(rights, 0) + 1
        if item.get("download_variant") == "format=original":
            format_original_count += 1
        if item.get("source_mime_type") == item.get("download_mime_type"):
            source_mime_match_count += 1
        source_bytes = item.get("source_bytes")
        downloaded_bytes = item.get("downloaded_bytes")
        if isinstance(source_bytes, int) and isinstance(downloaded_bytes, int) and downloaded_bytes < source_bytes:
            smaller_response_count += 1
            smaller_response_mime_counts[mime] = smaller_response_mime_counts.get(mime, 0) + 1

    failures = asset_manifest.get("failures", [])
    failed_item_count = len(failures) if isinstance(failures, list) else None
    public_release_authorized_count = sum(item.get("public_release_authorized") is True for item in assets)
    summary: dict[str, Any] = {
        "asset_count": len(assets),
        "category_counts": category_counts,
        "manifest_run_status": asset_manifest.get("run_status"),
        "failed_item_count": failed_item_count,
        "mime_counts": mime_counts,
        "format_original_records": format_original_count,
        "source_mime_matches": source_mime_match_count,
        "responses_smaller_than_api_source_size": smaller_response_count,
        "smaller_response_mime_counts": smaller_response_mime_counts,
        "rights_status_counts": rights_counts,
        "public_release_authorized_count": public_release_authorized_count,
    }

    if not assets:
        summary["gap_note"] = (
            "The local asset manifest lists no image copies. No portrait, sprite, icon, or map is verified for app use."
        )
        return summary

    source = asset_manifest.get("source")
    source_title = source.get("title") if isinstance(source, dict) else None
    source_title = source_title or "registered sources"
    category_text = ", ".join(f"{count} {category}" for category, count in sorted(category_counts.items()))
    rights_text = ", ".join(f"{status} ({count})" for status, count in sorted(rights_counts.items()))
    note = (
        f"The asset manifest lists {len(assets)} local-development image copies from {source_title} "
        f"({category_text}) under `assets/original/`. They are not app-ready assets: rights statuses are {rights_text}, "
        f"and {public_release_authorized_count} file(s) are marked as authorized for public release."
    )
    if format_original_count or smaller_response_count:
        smaller_types = ", ".join(
            f"{count} {mime}" for mime, count in sorted(smaller_response_mime_counts.items())
        ) or "none"
        note += (
            f" The manifest records {format_original_count} `format=original` requests and "
            f"{source_mime_match_count} source/download MIME matches; {smaller_response_count} response(s) "
            f"are smaller than the API-reported source size ({smaller_types}). These are same-format CDN responses, "
            "not a byte-exact-original guarantee."
        )
    summary["gap_note"] = note
    return summary


def _slug(value: str) -> str:
    value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii")
    value = re.sub(r"[^a-zA-Z0-9]+", "-", value).strip("-").lower()
    return value or "entry"


def _number(value: str) -> int | float | str | None:
    value = value.strip().replace("−", "-").replace("–", "-")
    if not value or value in {"--", "—", "-"}:
        return None
    cleaned = value.replace("%", "").replace(",", "")
    try:
        number = float(cleaned)
    except ValueError:
        return value
    return int(number) if number.is_integer() else number


def _source_link(source_id: str, locator: str, sha256: str | None, *, assertion: str, translation: str = "not_translated") -> dict[str, Any]:
    return {
        "source_id": source_id,
        "locator": locator,
        "source_sha256": sha256,
        "source_language": "en",
        "translation_status": translation,
        "assertion_status": assertion,
    }


def _community_translation_source(capture: dict[str, Any]) -> dict[str, Any]:
    license_name = capture["license"]
    terms = capture.get("terms", [])
    entity_count = len({
        (item.get("category_id"), item.get("stable_key"))
        for item in terms
    })
    return {
        "source_id": capture["source_id"],
        "source_type": capture["translation_type"],
        "title": capture["source_title"],
        "url": capture["source_url"],
        "language": capture["locale"],
        "locale": capture["locale"],
        "game_version": None,
        "revision": capture["revision"],
        "revision_timestamp": capture["revision_timestamp"],
        "retrieved_at": capture["captured_at"],
        "access_status": "readable",
        "access_observations": capture.get("access_observations"),
        "rights_status": "licensed",
        "license": license_name,
        "commercial_use_allowed": capture["commercial_use_allowed"],
        "share_alike_required": capture["share_alike_required"],
        "sha256": None,
        "notes": (
            f"translation_type={capture['translation_type']}; locale={capture['locale']}; {license_name}; "
            "commercial_use_allowed=false; share_alike_required=true. The license is non-commercial and requires "
            "share-alike for adaptations. Attribution: " + capture["attribution"] + " " + capture["capture_method"] +
            f" Only {len(terms)} exact term entries across {entity_count} entity records are retained with locators; "
            "no article body or images were copied."
        ),
    }


def _apply_community_translations(
    records_by_category: dict[str, list[dict[str, Any]]], capture: dict[str, Any] | None,
) -> dict[str, Any]:
    if not capture:
        return {"term_count": 0, "record_count": 0, "source_sha256": None}
    if capture.get("translation_type") != "community_translation_unofficial":
        raise ValueError("Moegirl translation extract must be marked community_translation_unofficial")
    if capture.get("locale") != "zh-Hans-CN":
        raise ValueError("Moegirl translation extract must retain the rendered zh-Hans-CN locale")
    if capture.get("license") != "CC BY-NC-SA 4.0" or capture.get("commercial_use_allowed") is not False or capture.get("share_alike_required") is not True:
        raise ValueError("Moegirl translation extract reuse restrictions are incomplete")
    terms = capture.get("terms")
    if not isinstance(terms, list) or not terms:
        raise ValueError("Moegirl translation extract must contain at least one source-backed term")

    by_key = {
        (category_id, record.get("stable_key")): record
        for category_id, records in records_by_category.items()
        for record in records
    }
    grouped: dict[tuple[str, str], list[dict[str, Any]]] = {}
    seen_terms: set[tuple[str, str, str]] = set()
    for item in terms:
        key = (item.get("category_id"), item.get("stable_key"))
        record = by_key.get(key)
        if record is None:
            raise ValueError(f"Community translation targets missing entity: {key}")
        if item.get("source_name_en") != record.get("title_en"):
            raise ValueError(f"Community translation source name does not match retained English title: {key}")
        term = item.get("term")
        if not isinstance(term, str) or not term.strip():
            raise ValueError(f"Community translation term is empty: {key}")
        section = item.get("section")
        locator_text = item.get("locator")
        if not isinstance(section, str) or not section.strip() or not isinstance(locator_text, str) or not locator_text.strip():
            raise ValueError(f"Community translation locator is incomplete: {key}")
        source_href = item.get("source_href")
        if source_href is not None and (
            not isinstance(source_href, str) or not source_href.startswith("/") or source_href.startswith("//")
        ):
            raise ValueError(f"Community translation href must be a relative page link: {key}")
        mapping_method = item.get("mapping_method", "source_text")
        mapping_confidence = item.get("mapping_confidence", "high")
        if mapping_method not in {"source_text", "phonetic+affiliation", "phonetic+unique_title"}:
            raise ValueError(f"Unsupported community translation mapping method: {mapping_method}")
        if mapping_confidence not in {"high", "medium"}:
            raise ValueError(f"Unsupported community translation mapping confidence: {mapping_confidence}")
        unique_term = (*key, term)
        if unique_term in seen_terms:
            raise ValueError(f"Duplicate community translation term: {unique_term}")
        seen_terms.add(unique_term)
        grouped.setdefault(key, []).append(item)

    if not grouped:
        raise ValueError("Community translation extract has no entity targets")

    source_id = capture["source_id"]
    for key, localized_terms in grouped.items():
        record = by_key[key]
        names = [item["term"] for item in localized_terms]
        record["title"] = " / ".join(names)
        record["search_text"] = " ".join([record.get("search_text", ""), *names]).strip()
        for item in localized_terms:
            mapping_method = item.get("mapping_method", "source_text")
            mapping_confidence = item.get("mapping_confidence", "high")
            source_href = item.get("source_href")
            href_note = f"; href={source_href}" if source_href else ""
            mapping_note = f"; note={item['mapping_note']}" if item.get("mapping_note") else ""
            locator = (
                f"Revision {capture['revision']}; section #{item['section']}; {item['locator']}"
                f"{href_note}; mapping={mapping_method}; confidence={mapping_confidence}{mapping_note}"
            )
            record.setdefault("sources", []).append({
                "source_id": source_id,
                "locator": locator,
                "source_sha256": None,
                "source_language": capture["locale"],
                "translation_status": "human_reviewed",
                "assertion_status": "unverified",
            })
    translated_character_keys = {
        stable_key for category_id, stable_key in grouped if category_id == "characters"
    }
    mapping_methods = {
        method: sum(1 for item in terms if item.get("mapping_method", "source_text") == method)
        for method in sorted({item.get("mapping_method", "source_text") for item in terms})
    }
    mapping_confidences = {
        confidence: sum(1 for item in terms if item.get("mapping_confidence", "high") == confidence)
        for confidence in sorted({item.get("mapping_confidence", "high") for item in terms})
    }
    return {
        "term_count": len(terms),
        "record_count": len(grouped),
        "character_record_count": len(translated_character_keys),
        "source_sha256": None,
        "mapping_method_counts": mapping_methods,
        "mapping_confidence_counts": mapping_confidences,
        "untranslated_character_stable_keys": [
            record["stable_key"]
            for record in records_by_category.get("characters", [])
            if record.get("stable_key") not in translated_character_keys
        ],
    }


def _source_text(value: Any) -> str:
    return unicodedata.normalize("NFKC", " ".join(str(value or "").split())).strip()


def _character_rows(capture: dict[str, Any] | None, table_indices: set[int]) -> list[dict[str, Any]]:
    if not capture:
        return []
    result: list[dict[str, Any]] = []
    for table in capture.get("tables", []):
        if table.get("table_index") not in table_indices:
            continue
        headers = [_source_text(value) for value in table.get("headers", [])]
        required = {"Name", "Class", "First appears in", "Joins in"}
        if not required.issubset(headers):
            continue
        indexes = {name: headers.index(name) for name in required}
        for row in table.get("rows", []):
            cells = row.get("cells", [])
            if len(cells) <= max(indexes.values()):
                continue
            values = {key: _source_text(cells[index]) for key, index in indexes.items()}
            name = values["Name"]
            if not name or name.lower() in {"name", "total"}:
                continue
            result.append({
                "name": name,
                "class": values["Class"],
                "first_appears_in": values["First appears in"],
                "joins_in": values["Joins in"],
                "section": _source_text(table.get("section")),
                "table_index": table["table_index"],
                "row_index": row.get("row_index"),
            })
    return result


def _character_capture_records(capture: dict[str, Any] | None) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    rows = _character_rows(capture, set(range(2, 9)))
    digest = (capture or {}).get("captured_content_sha256")
    contexts: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        contexts.setdefault(row["name"], []).append(row)

    characters: list[dict[str, Any]] = []
    for name, entries in contexts.items():
        stable_key = _slug(name)
        source_sections = list(dict.fromkeys(entry["section"] for entry in entries if entry["section"]))
        main_entries = [entry for entry in entries if entry["table_index"] != 8]
        base_entry = main_entries[0] if main_entries else entries[0]
        affiliation = base_entry["section"] if base_entry["section"] in {
            "Black Eagles", "Blue Lions", "Golden Deer", "Church of Seiros / Knights of Seiros"
        } else None
        join_values = list(dict.fromkeys(entry["joins_in"] for entry in main_entries if entry["joins_in"]))
        details_text = "; ".join(
            f"{entry['section']}: class {entry['class']}; first appears {entry['first_appears_in']}; joins {entry['joins_in']}"
            for entry in entries
        )
        sources = [
            _source_link(
                "fewiki-characters",
                f"Table {entry['table_index']}, source row {entry['row_index']}; section={entry['section']}",
                digest,
                assertion="unverified",
            )
            for entry in entries
        ]
        characters.append({
            "category_id": "characters",
            "stable_key": stable_key,
            "title": name,
            "title_en": name,
            "subtitle": f"Source roster class: {base_entry['class']}" if base_entry["class"] else None,
            "description": "Roster-section records: " + "; ".join(source_sections) + (f". {details_text}" if details_text else ""),
            "search_text": " ".join([name, *source_sections, *(entry["class"] for entry in entries), *(entry["first_appears_in"] for entry in entries), *(entry["joins_in"] for entry in entries)]),
            "verification_status": "source_only",
            "kind": "character",
            "details": {
                "affiliation": affiliation,
                "join_condition": join_values[0] if len(join_values) == 1 else None,
                "route_availability": None,
            },
            "sources": sources,
        })

    recruitments: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for entry in _character_rows(capture, set(range(2, 8))):
        if not entry["joins_in"]:
            continue
        identity = (entry["name"], entry["section"])
        if identity in seen:
            continue
        seen.add(identity)
        stable_key = f"{_slug(entry['name'])}-{_slug(entry['section'])}-join"
        locator = f"Table {entry['table_index']}, source row {entry['row_index']}, Joins in column"
        recruitments.append({
            "category_id": "recruitment",
            "stable_key": stable_key,
            "title": f"{entry['name']} — {entry['section']} join condition",
            "title_en": f"{entry['name']} — {entry['section']} join condition",
            "subtitle": entry["joins_in"],
            "description": "Exact join-condition wording from the cited roster table; route eligibility details are not inferred.",
            "search_text": f"{entry['name']} {entry['section']} {entry['joins_in']} {entry['first_appears_in']}",
            "verification_status": "source_only",
            "kind": "mechanic",
            "details": {
                "mechanic_type": "recruitment_condition",
                "game_version": "Fire Emblem: Three Houses; patch not recorded",
                "rule_description": entry["joins_in"],
                "parameters": {
                    "character_name": entry["name"],
                    "roster_section": entry["section"],
                    "first_appears_in": entry["first_appears_in"],
                },
            },
            "sources": [_source_link("fewiki-characters", locator, digest, assertion="unverified")],
            "relations": [{
                "relation_type": "recruitment_condition_for",
                "target_category_id": "characters",
                "target_stable_key": _slug(entry["name"]),
                "evidence": f"Exact name and join cell are in the same roster row: {entry['name']} / {entry['joins_in']}.",
                "verification_status": "unverified",
                "source_id": "fewiki-characters",
                "locator": locator,
            }],
        })
    return characters, recruitments


def _chapter_capture_records(
    capture: dict[str, Any] | None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    if not capture:
        return [], [], []
    digest = capture.get("captured_content_sha256")
    chapters: list[dict[str, Any]] = []
    battles: list[dict[str, Any]] = []
    routes = {
        "Silver Snow": "black-eagles-silver-snow",
        "Azure Moon": "blue-lions-azure-moon",
        "Verdant Wind": "golden-deer-verdant-wind",
        "Crimson Flower": "black-eagles-crimson-flower",
    }
    dlc_records = [{
        "category_id": "dlc",
        "stable_key": "cindered-shadows",
        "title": "Cindered Shadows",
        "title_en": "Cindered Shadows",
        "subtitle": "DLC campaign",
        "description": "The source chapter catalog groups a seven-chapter campaign under Cindered Shadows DLC; product and patch details remain incomplete.",
        "search_text": "Cindered Shadows Ashen Wolves DLC campaign",
        "verification_status": "source_only",
        "kind": "mechanic",
        "details": {
            "mechanic_type": "dlc_campaign",
            "game_version": "Fire Emblem: Three Houses; patch not recorded",
            "rule_description": "Cindered Shadows is a separately labeled DLC chapter group in the cited chapter catalog.",
            "parameters": {"chapter_count_in_source_table": 7},
        },
        "sources": [_source_link(
            "fewiki-chapters",
            "Table 7 heading: Cindered Shadows DLC; seven chapter rows",
            digest,
            assertion="unverified",
        )],
    }]

    for table in capture.get("tables", []):
        table_index = table.get("table_index")
        if table_index == 1:
            continue
        headers = [_source_text(value) for value in table.get("headers", [])]
        if "Title" not in headers:
            continue
        indexes = {name: headers.index(name) for name in headers if name}
        title_index = indexes["Title"]
        number_index = 0 if title_index > 0 else None
        section = _source_text(table.get("section"))
        is_paralogue_table = table_index in {8, 9}
        route_label = "Paralogues" if is_paralogue_table else section
        for row in table.get("rows", []):
            cells = row.get("cells", [])
            if len(cells) <= title_index:
                continue
            chapter_title = _source_text(cells[title_index])
            if not chapter_title or chapter_title.lower() == "title":
                continue
            chapter_number = _source_text(cells[number_index]) if number_index is not None and len(cells) > number_index else None
            if chapter_number and chapter_number.lower() in {"title", "chapter"}:
                chapter_number = None
            full_title = f"{chapter_number}: {chapter_title}" if chapter_number and chapter_number.lower().startswith("chapter") else chapter_title
            stable_key = f"{_slug(section)}-{_slug(chapter_title)}"
            locator = f"Table {table_index}, source row {row.get('row_index')}; section={section}"
            def cell_value(name: str, indexes=indexes, cells=cells) -> str | None:
                index = indexes.get(name)
                if index is None or index >= len(cells):
                    return None
                value = _source_text(cells[index])
                return value or None
            objective = cell_value("Mission") or cell_value("Objectives")
            defeat = cell_value("Defeat")
            new_units = cell_value("New Units") or cell_value("Focus Unit(s)")
            bosses = cell_value("Bosses")
            chapter_record = {
                "category_id": "chapters",
                "stable_key": stable_key,
                "title": full_title,
                "title_en": full_title,
                "subtitle": route_label,
                "description": "Chapter catalog entry; maps, full enemy rosters, reinforcement timing and rewards are not covered by this record.",
                "search_text": " ".join(value for value in [full_title, section, route_label, objective, defeat, new_units, bosses] if value),
                "verification_status": "source_only",
                "kind": "chapter",
                "details": {
                    "route": route_label,
                    "chapter_number": chapter_number,
                    "objective": objective,
                    "defeat_condition": defeat,
                    "deployment": {"source_new_units": new_units} if new_units else None,
                    "enemies": {"source_bosses": bosses} if bosses else None,
                },
                "sources": [_source_link("fewiki-chapters", locator, digest, assertion="unverified")],
                "relations": [],
            }
            if section in routes:
                chapter_record["relations"].append({
                    "relation_type": "chapter_of_route",
                    "target_category_id": "routes",
                    "target_stable_key": routes[section],
                    "evidence": f"The chapter is listed in the {section} section of the cited chapter catalog.",
                    "verification_status": "unverified",
                    "source_id": "fewiki-chapters",
                    "locator": locator,
                })
            elif section == "Cindered Shadows DLC":
                chapter_record["relations"].append({
                    "relation_type": "chapter_of_dlc_campaign",
                    "target_category_id": "dlc",
                    "target_stable_key": "cindered-shadows",
                    "evidence": "The chapter is listed under the Cindered Shadows DLC section.",
                    "verification_status": "unverified",
                    "source_id": "fewiki-chapters",
                    "locator": locator,
                })
            chapters.append(chapter_record)
            if any((objective, defeat, new_units, bosses)):
                battle_key = f"{stable_key}-battle-summary"
                battle_locator = f"{locator}; battle summary fields"
                battles.append({
                    "category_id": "battles",
                    "stable_key": battle_key,
                    "title": f"{full_title} battle summary",
                    "title_en": f"{full_title} battle summary",
                    "subtitle": route_label,
                    "description": "Only objective, defeat condition, newly listed units and boss text present in the source chapter table are retained; map cells, enemy roster, reinforcements and full rewards are not inferred.",
                    "search_text": " ".join(value for value in [full_title, route_label, objective, defeat, new_units, bosses] if value),
                    "verification_status": "source_only",
                    "kind": "chapter",
                    "details": {
                        "route": route_label,
                        "chapter_number": chapter_number,
                        "objective": objective,
                        "defeat_condition": defeat,
                        "deployment": {"source_new_units": new_units} if new_units else None,
                        "enemies": {"source_bosses": bosses} if bosses else None,
                        "rewards": None,
                    },
                    "sources": [_source_link("fewiki-chapters", battle_locator, digest, assertion="unverified")],
                    "relations": [{
                        "relation_type": "battle_summary_for_chapter",
                        "target_category_id": "chapters",
                        "target_stable_key": stable_key,
                        "evidence": "Both records are projected from the same source chapter row; this entry separates battle-condition fields from the route/chapter catalog record.",
                        "verification_status": "unverified",
                        "source_id": "fewiki-chapters",
                        "locator": battle_locator,
                    }],
                })
    return chapters, dlc_records, battles


def _support_capture_records(capture: dict[str, Any] | None, character_records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not capture:
        return []
    table = next((item for item in capture.get("tables", []) if "Character" in item.get("headers", [])), None)
    if not table:
        return []
    headers = [_source_text(value) for value in table.get("headers", [])]
    if not headers or headers[0] != "Character":
        return []
    digest = capture.get("captured_content_sha256")
    characters_by_name = {_source_text(item.get("title_en")): item["stable_key"] for item in character_records}
    pairs: dict[tuple[str, str], dict[str, Any]] = {}
    for row in table.get("rows", []):
        cells = row.get("cells", [])
        if len(cells) < 2:
            continue
        person = _source_text(cells[0])
        if not person or person == "--":
            continue
        for column_index, target_raw in enumerate(cells[1:], start=1):
            target = _source_text(target_raw)
            if not target or target == "--" or target == person:
                continue
            names = tuple(sorted((person, target), key=lambda name: (name.casefold(), name)))
            pair = pairs.setdefault(names, {"names": names, "locators": []})
            pair["locators"].append(
                f"Table {table['table_index']}, source row {row.get('row_index')}, support-list position {column_index}"
            )

    records: list[dict[str, Any]] = []
    for (name_a, name_b), pair in pairs.items():
        key_a = characters_by_name.get(name_a)
        key_b = characters_by_name.get(name_b)
        stable_key = f"{_slug(name_a)}--{_slug(name_b)}"
        locators = list(dict.fromkeys(pair["locators"]))
        links = [
            _source_link("fewiki-supports", locator, digest, assertion="unverified")
            for locator in locators
        ]
        relations = []
        primary_locator = locators[0]
        for name, stable in ((name_a, key_a), (name_b, key_b)):
            if stable:
                relations.append({
                    "relation_type": "support_pair_character",
                    "target_category_id": "characters",
                    "target_stable_key": stable,
                    "evidence": f"Exact character label {name!r} appears in the cited support-list row.",
                    "verification_status": "unverified",
                    "source_id": "fewiki-supports",
                    "locator": primary_locator,
                })
        details: dict[str, Any] = {
            "support_rank": None,
            "availability_condition": None,
            "support_points": None,
            "dialogue_summary": "Pair listed in the source support index; rank-specific conversations and route conditions were not captured.",
        }
        if key_a:
            details["character_a_stable_key"] = key_a
        if key_b:
            details["character_b_stable_key"] = key_b
        records.append({
            "category_id": "supports",
            "stable_key": stable_key,
            "title": f"{name_a} + {name_b}",
            "title_en": f"{name_a} + {name_b}",
            "subtitle": "Support pair; rank and conditions not captured",
            "description": "The source index lists this pair; no dialogue text or rank-specific availability is included.",
            "search_text": f"{name_a} {name_b} support",
            "verification_status": "source_only",
            "kind": "support",
            "details": details,
            "sources": links,
            "relations": relations,
        })
    return records


def _class_capture_records() -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any] | None]:
    capture = _read_json(EXTRACTS / "classes.json")
    if not capture:
        return [], [], None
    table = next((item for item in capture.get("tables", []) if item.get("table_index") == 2), None)
    if not table:
        return [], [], capture
    digest = capture.get("captured_content_sha256")
    class_records: list[dict[str, Any]] = []
    growth_records: list[dict[str, Any]] = []
    seen_keys: set[str] = set()
    for row in table.get("rows", []):
        if row.get("row_index", 0) <= 2:
            continue
        cells = row.get("cells", [])
        if len(cells) < 32:
            continue
        name = str(cells[0]).strip()
        if not name or name.lower() in {"class", "total", "notes"}:
            continue
        base_stats = {key: _number(str(value)) for key, value in zip(STAT8, cells[6:14])}
        player_growths = {key: _number(str(value)) for key, value in zip(STAT9, cells[14:23])}
        enemy_growths = {key: _number(str(value)) for key, value in zip(STAT9, cells[23:32])}
        stat_bonuses = {key: _number(str(value)) for key, value in zip(STAT9, cells[32:41])}
        dismount_modifiers = {key: _number(str(value)) for key, value in zip(["Mov", "Str", "Spd", "Def"], cells[41:45])}
        innate = _source_text(cells[68]) if len(cells) > 68 else ""
        mastery = _source_text(cells[69]) if len(cells) > 69 else ""
        mastery_exp = _source_text(cells[70]) if len(cells) > 70 else ""
        class_skill = "; ".join(value for value in [f"Innate: {innate}" if innate and innate not in {"--", "—"} else "", f"Mastery: {mastery} (mastery EXP {mastery_exp})" if mastery and mastery not in {"--", "—"} else ""] if value)
        class_key = _slug(name)
        if class_key in seen_keys:
            class_key = f"{class_key}-row-{row['row_index']}"
        seen_keys.add(class_key)
        display_title = name
        locator = f"Table 2, source row {row['row_index']} (class={name})"
        translation_status = "not_translated"
        class_records.append({
            "category_id": "classes",
            "stable_key": class_key,
            "title": display_title,
            "title_en": name,
            "subtitle": f"{cells[2]} · {cells[3]}" if len(cells) > 3 else None,
            "description": "Class values and the source-language title are retained from the cited table; no record-level official Simplified Chinese title has been verified.",
            "search_text": " ".join(value for value in [display_title, name, str(cells[2]), str(cells[3]), str(cells[4])] if value),
            "verification_status": "source_only",
            "kind": "class",
            "details": {
                "tier": str(cells[2]) if len(cells) > 2 else None,
                "movement_type": str(cells[3]) if len(cells) > 3 else None,
                "base_stats": base_stats,
                "growths": player_growths,
                "class_skill": class_skill or None,
            },
            "sources": [_source_link("fewiki-classes", locator, digest, assertion="unverified", translation=translation_status)],
        })
        growth_key = f"class-{class_key}"
        growth_sources = [_source_link("fewiki-classes", locator, digest, assertion="unverified", translation=translation_status)]
        verification = "source_only"
        if class_key == "myrmidon":
            growth_sources.append(_source_link(
                "serenes-class-growth-reference",
                "Player class growth table 3, row 2 (Myrmidon); selected cross-check only",
                None,
                assertion="confirmed",
            ))
        growth_records.append({
            "category_id": "growth",
            "stable_key": growth_key,
            "title": f"{display_title} growth rates",
            "title_en": f"{name} growth rates",
            "subtitle": "Class growth modifiers",
            "description": "Player and enemy growth modifiers. Blank source cells remain null; values are percentage points, not probabilities.",
            "search_text": " ".join([display_title, name, "growth", *[str(v) for v in player_growths.values() if v is not None]]),
            "verification_status": verification,
            "kind": "mechanic",
            "details": {
                "mechanic_type": "class_growth_rates",
                "game_version": "Fire Emblem: Three Houses; exact patch not recorded",
                "rule_description": "Class growth-rate modifiers; percentages are stored as points (for example, 10 means +10%).",
                "parameters": {
                    "player_growth_rates": player_growths,
                    "enemy_growth_rates": enemy_growths,
                    "class_stat_bonuses": stat_bonuses,
                    "dismount_modifiers": dismount_modifiers,
                },
            },
            "sources": growth_sources,
            "relations": [{
                "relation_type": "class_growth_profile",
                "target_category_id": "classes",
                "target_stable_key": class_key,
                "evidence": f"Exact class label match in Fire Emblem Wiki table 2, source row {row['row_index']}.",
                "verification_status": "confirmed",
                "source_id": "fewiki-classes",
                "locator": locator,
            }],
        })
    return class_records, growth_records, capture


def _table_by_index(capture: dict[str, Any] | None, index: int) -> dict[str, Any] | None:
    if not capture:
        return None
    return next((table for table in capture.get("tables", []) if table.get("table_index") == index), None)


def _optional_text(value: Any) -> str | None:
    text = _source_text(value)
    return None if not text or text in {"--", "—", "–", "N/A"} else text


def _equipment_stable_key(prefix: str, name: str) -> str:
    normalized = _source_text(name)
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:10]
    return f"{prefix}-{_slug(normalized)}-{digest}"


def _equipment_capture_records(
    item_capture: dict[str, Any] | None,
    weapon_capture: dict[str, Any] | None,
) -> list[dict[str, Any]]:
    records: dict[str, dict[str, Any]] = {}

    def merge(
        key: str,
        name: str,
        subtype: str,
        details: dict[str, Any],
        source_link: dict[str, Any],
        category: str | None = None,
    ) -> None:
        record = records.get(key)
        if record is None:
            extra = dict(details.get("extra_stats") or {})
            if category:
                extra["source_categories"] = [category]
            details["extra_stats"] = extra or None
            record = {
                "category_id": "equipment",
                "stable_key": key,
                "title": name,
                "title_en": name,
                "subtitle": subtype,
                "description": "Source table entry; values and acquisition text are retained as published and remain unverified.",
                "search_text": f"{name} {subtype} {category or ''}",
                "verification_status": "source_only",
                "kind": "item",
                "details": details,
                "sources": [source_link],
            }
            records[key] = record
            return
        if source_link not in record["sources"]:
            record["sources"].append(source_link)
        extra = record["details"].setdefault("extra_stats", {})
        if not isinstance(extra, dict):
            extra = {"previous_extra_stats": extra}
            record["details"]["extra_stats"] = extra
        if category:
            categories = extra.setdefault("source_categories", [])
            if category not in categories:
                categories.append(category)
        source_types = extra.setdefault("source_table_types", [])
        if subtype not in source_types:
            source_types.append(subtype)
        if not record.get("subtitle"):
            record["subtitle"] = subtype
        record["search_text"] += f" {category or ''} {subtype}"

    if weapon_capture:
        table = next((item for item in weapon_capture.get("tables", []) if "Weapon" in item.get("headers", [])), None)
        if table:
            headers = [_source_text(value) for value in table.get("headers", [])]
            indexes = {header: headers.index(header) for header in headers if header}
            digest = weapon_capture.get("captured_content_sha256")
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) < len(headers):
                    continue
                name = _source_text(cells[indexes["Weapon"]])
                if not name or name.lower() in {"weapon", "total"}:
                    continue
                repair = _optional_text(cells[indexes["Repair material"]]) if "Repair material" in indexes else None
                notes = _optional_text(cells[indexes["Other effects and notes"]]) if "Other effects and notes" in indexes else None
                details = {
                    "equipment_type": "weapon",
                    "subtype": _optional_text(cells[indexes["Type"]]) if "Type" in indexes else None,
                    "rank": _optional_text(cells[indexes["Level"]]) if "Level" in indexes else None,
                    "might": _number(str(cells[indexes["Might"]])) if "Might" in indexes else None,
                    "hit": _number(str(cells[indexes["Hit"]])) if "Hit" in indexes else None,
                    "critical": _number(str(cells[indexes["Crit"]])) if "Crit" in indexes else None,
                    "weight": _number(str(cells[indexes["Weight"]])) if "Weight" in indexes else None,
                    "durability": _number(str(cells[indexes["Uses"]])) if "Uses" in indexes else None,
                    "price": _number(str(cells[indexes["Purchase price"]])) if "Purchase price" in indexes else None,
                    "effect": notes,
                    "acquisition": None,
                    "extra_stats": {
                        "range": _optional_text(cells[indexes["Range"]]) if "Range" in indexes else None,
                        "uses_source_text": _optional_text(cells[indexes["Uses"]]) if "Uses" in indexes else None,
                        "sell_price": _number(str(cells[indexes["Sell price"]])) if "Sell price" in indexes else None,
                        "repair_material": repair,
                        "source_table": table["table_index"],
                    },
                }
                locator = f"Table {table['table_index']}, source row {row.get('row_index')}, weapon={name}"
                merge(_equipment_stable_key("weapon", name), name, "Weapon", details, _source_link("fewiki-weapons", locator, digest, assertion="unverified"))

    if item_capture:
        digest = item_capture.get("captured_content_sha256")
        for table_index in (3, 4):
            table = _table_by_index(item_capture, table_index)
            if not table:
                continue
            headers = [_source_text(value) for value in table.get("headers", [])]
            if "Item" not in headers:
                continue
            indexes = {header: headers.index(header) for header in headers if header}
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) < len(headers):
                    continue
                name = _source_text(cells[indexes["Item"]])
                if not name or name.lower() in {"item", "total"}:
                    continue
                purchase = _number(str(cells[indexes["Purchase price"]])) if "Purchase price" in indexes else None
                sell = _number(str(cells[indexes["Sell price"]])) if "Sell price" in indexes else None
                effect = _optional_text(cells[indexes["Effects and notes"]]) if "Effects and notes" in indexes else None
                equipment_type = "equipment" if table_index == 3 else "item"
                extras: dict[str, Any] = {"source_table": table_index, "sell_price": sell}
                if "Protection" in indexes:
                    extras["protection"] = _number(str(cells[indexes["Protection"]]))
                if "Resilience" in indexes:
                    extras["resilience"] = _number(str(cells[indexes["Resilience"]]))
                if "Uses" in indexes:
                    extras["uses_source_text"] = _optional_text(cells[indexes["Uses"]])
                details = {
                    "equipment_type": equipment_type,
                    "subtype": "Equipment" if table_index == 3 else "Item",
                    "rank": None,
                    "might": None,
                    "hit": None,
                    "critical": None,
                    "weight": _number(str(cells[indexes["Weight"]])) if "Weight" in indexes else None,
                    "durability": _number(str(cells[indexes["Uses"]])) if "Uses" in indexes else None,
                    "price": purchase,
                    "effect": effect,
                    "acquisition": None,
                    "extra_stats": extras,
                }
                locator = f"Table {table_index}, source row {row.get('row_index')}, item={name}"
                merge(_equipment_stable_key("item", name), name, "Equipment" if table_index == 3 else "Item", details, _source_link("fewiki-items", locator, digest, assertion="unverified"))

        # The category index identifies additional items even when that page
        # does not publish their combat statistics.
        table = _table_by_index(item_capture, 5)
        if table:
            for row in table.get("rows", []):
                if row.get("row_index", 0) <= 2:
                    continue
                cells = row.get("cells", [])
                if len(cells) < 2:
                    continue
                category = _source_text(cells[0])
                for link in row.get("links", []):
                    name = _source_text(link.get("text"))
                    if not name or name.casefold() == category.casefold():
                        continue
                    locator = f"Table 5, source row {row.get('row_index')}, category={category}"
                    details = {
                        "equipment_type": "item",
                        "subtype": category,
                        "rank": None,
                        "might": None,
                        "hit": None,
                        "critical": None,
                        "weight": None,
                        "durability": None,
                        "price": None,
                        "effect": None,
                        "acquisition": None,
                        "extra_stats": {"catalog_only": True, "source_table": 5},
                    }
                    merge(_equipment_stable_key("item", name), name, category, details, _source_link("fewiki-items", locator, digest, assertion="unverified"), category)
    return list(records.values())


def _crest_capture_records(capture: dict[str, Any] | None, character_records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    table = next((item for item in (capture or {}).get("tables", []) if "Bloodline" in item.get("headers", [])), None)
    if not table:
        return []
    headers = [_source_text(value) for value in table.get("headers", [])]
    indexes = {header: headers.index(header) for header in headers if header}
    digest = capture.get("captured_content_sha256")
    characters = {_source_text(record.get("title_en")): record["stable_key"] for record in character_records}
    records = []
    for row in table.get("rows", []):
        cells = row.get("cells", [])
        if len(cells) < len(headers):
            continue
        name = _source_text(cells[indexes["Bloodline"]])
        if not name or name.casefold() in {"bloodline", "crest"}:
            continue
        bearers = _source_text(cells[indexes["Bearers"]])
        bearer_names = [_source_text(value) for value in re.split(r"\s*[,;]\s*", bearers) if _source_text(value)]
        locator = f"Table {table['table_index']}, source row {row.get('row_index')}, bloodline={name}"
        relations = []
        for bearer in bearer_names:
            key = characters.get(bearer)
            if key:
                relations.append({
                    "relation_type": "crest_bearer",
                    "target_category_id": "characters",
                    "target_stable_key": key,
                    "evidence": f"The cited bloodline row lists {bearer} under Bearers.",
                    "verification_status": "unverified",
                    "source_id": "fewiki-crests",
                    "locator": locator,
                })
        effect = _optional_text(cells[indexes["Effects ( Three Houses )"]])
        records.append({
            "category_id": "crests",
            "stable_key": _slug(name),
            "title": name,
            "title_en": name,
            "subtitle": f"Bearers: {bearers}" if bearers else "Bloodline",
            "description": "Bloodline, relic, bearer and Three Houses effect fields are source-table excerpts; associations are not independently checked.",
            "search_text": " ".join(value for value in [name, bearers, effect, _source_text(cells[indexes["Hero's Relic"]]), _source_text(cells[indexes["Sacred weapons"]])] if value),
            "verification_status": "source_only",
            "kind": "mechanic",
            "details": {
                "mechanic_type": "crest",
                "game_version": "Fire Emblem: Three Houses; patch not recorded",
                "rule_description": effect,
                "parameters": {
                    "bloodline": name,
                    "bloodmark_equivalent": _optional_text(cells[indexes["Bloodmark equivalent"]]),
                    "hero_relic": _optional_text(cells[indexes["Hero's Relic"]]),
                    "sacred_weapons": _optional_text(cells[indexes["Sacred weapons"]]),
                    "bearers_source_text": bearers,
                    "crest_item": _optional_text(cells[indexes["Crest item"]]),
                    "warriors_three_hopes_effect": _optional_text(cells[indexes["Effects ( Warriors: Three Hopes ) *"]]) if "Effects ( Warriors: Three Hopes ) *" in indexes else None,
                },
            },
            "sources": [_source_link("fewiki-crests", locator, digest, assertion="unverified")],
            "relations": relations,
        })
    return records


def _semantic_record(
    category_id: str,
    stable_key: str,
    title: str,
    title_en: str,
    subtitle: str | None,
    description: str,
    search_text: str,
    kind: str,
    details: dict[str, Any],
    source_id: str,
    locator: str,
    digest: str | None,
) -> dict[str, Any]:
    return {
        "category_id": category_id,
        "stable_key": stable_key,
        "title": title,
        "title_en": title_en,
        "subtitle": subtitle,
        "description": description,
        "search_text": search_text,
        "verification_status": "source_only",
        "kind": kind,
        "details": details,
        "sources": [_source_link(source_id, locator, digest, assertion="unverified")],
        "relations": [],
    }


def _append_source(record: dict[str, Any], link: dict[str, Any]) -> None:
    if link not in record["sources"]:
        record["sources"].append(link)


def _split_names(value: Any) -> list[str]:
    text = _optional_text(value)
    if not text:
        return []
    return [
        name for name in (_source_text(part) for part in re.split(r"\s*[•·]\s*", text))
        if name and name not in {"—", "--", "-"}
    ]


def _names_in_cell(row: dict[str, Any], cell_index: int) -> list[str]:
    grouped = row.get("links_by_cell")
    if isinstance(grouped, list) and cell_index < len(grouped):
        names = [_source_text(link.get("text")) for link in grouped[cell_index] if link.get("text")]
        if names:
            return list(dict.fromkeys(names))
    cells = row.get("cells", [])
    return _split_names(cells[cell_index]) if cell_index < len(cells) else []


def _battalion_capture_records(
    battalion_capture: dict[str, Any] | None,
    monastery_capture: dict[str, Any] | None,
) -> list[dict[str, Any]]:
    records: dict[str, dict[str, Any]] = {}

    def merge(
        name: str,
        *,
        source_id: str,
        digest: str | None,
        locator: str,
        group: str | None = None,
        role: str | None = None,
        shop_listing: dict[str, Any] | None = None,
    ) -> None:
        name = _source_text(name)
        if not name or name.casefold() in {"name", "total", "battalion"}:
            return
        stable_key = _slug(name)
        record = records.get(stable_key)
        if record is None:
            record = _semantic_record(
                "battalions", stable_key, name, name, None,
                "Battalion catalog and guild listing excerpts; combat modifiers, complete acquisition rules and route availability are not inferred.",
                name, "mechanic",
                {
                    "mechanic_type": "battalion",
                    "game_version": "Fire Emblem: Three Houses; exact patch not recorded",
                    "rule_description": "A named battalion appearing in a Three Houses catalog or guild table.",
                    "parameters": {
                        "unit_types": [], "source_groups": [], "player_available": False,
                        "enemy_listed": False, "enemy_only": False, "unused": False,
                        "catalog_listed": False, "shop_listings": [],
                    },
                },
                source_id, locator, digest,
            )
            records[stable_key] = record
        else:
            _append_source(record, _source_link(source_id, locator, digest, assertion="unverified"))
            if name not in record["search_text"].split():
                record["search_text"] += f" {name}"
        parameters = record["details"]["parameters"]
        if group and group not in parameters["source_groups"]:
            parameters["source_groups"].append(group)
        if role == "player_available":
            parameters["player_available"] = True
            parameters["catalog_listed"] = True
        elif role == "enemy_only":
            parameters["enemy_listed"] = True
            parameters["catalog_listed"] = True
        elif role == "unused":
            parameters["unused"] = True
            parameters["catalog_listed"] = True
        parameters["enemy_only"] = parameters["enemy_listed"] and not parameters["player_available"]
        if group and group not in parameters["unit_types"]:
            parameters["unit_types"].append(group)
        if shop_listing:
            listing_key = (shop_listing.get("gambit"), shop_listing.get("level"), shop_listing.get("price"))
            existing = next((
                item for item in parameters["shop_listings"]
                if (item.get("gambit"), item.get("level"), item.get("price")) == listing_key
            ), None)
            if existing is None:
                parameters["shop_listings"].append({
                    "gambit": shop_listing.get("gambit"),
                    "level": shop_listing.get("level"),
                    "price": shop_listing.get("price"),
                    "source_tables": [shop_listing.get("source_table")],
                })
            elif shop_listing.get("source_table") not in existing["source_tables"]:
                existing["source_tables"].append(shop_listing.get("source_table"))

    if battalion_capture:
        digest = battalion_capture.get("captured_content_sha256")
        catalog = next((
            table for table in battalion_capture.get("tables", [])
            if table.get("headers") == ["Battalions in Fire Emblem: Three Houses"]
        ), None)
        current_group: str | None = None
        if catalog:
            for row in catalog.get("rows", []):
                if row.get("row_index", 0) <= 3:
                    continue
                cells = row.get("cells", [])
                if not cells:
                    continue
                label = _source_text(cells[0])
                if label == "Unused":
                    for name in _names_in_cell(row, 1):
                        merge(
                            name, source_id="fewiki-battalions", digest=digest,
                            locator=f"Table {catalog['table_index']}, source row {row.get('row_index')}, unused battalions",
                            role="unused",
                        )
                    continue
                if label.casefold().endswith("battalions"):
                    current_group = label
                    subgroup = _source_text(cells[1]) if len(cells) >= 4 else None
                    player_index, enemy_index = (2, 3) if len(cells) >= 4 else (1, 2)
                else:
                    subgroup = label
                    player_index, enemy_index = 1, 2
                group = "/".join(part for part in [current_group, subgroup] if part)
                for name in _names_in_cell(row, player_index):
                    merge(
                        name, source_id="fewiki-battalions", digest=digest,
                        locator=f"Table {catalog['table_index']}, source row {row.get('row_index')}, player-available; group={group}",
                        group=group, role="player_available",
                    )
                for name in _names_in_cell(row, enemy_index):
                    merge(
                        name, source_id="fewiki-battalions", digest=digest,
                        locator=f"Table {catalog['table_index']}, source row {row.get('row_index')}, enemy-only; group={group}",
                        group=group, role="enemy_only",
                    )

    if monastery_capture:
        digest = monastery_capture.get("captured_content_sha256")
        for table in monastery_capture.get("tables", []):
            headers = [_source_text(value) for value in table.get("headers", [])]
            if table.get("section") != "Battalion Guild" or not {"Name", "Gambit", "Level", "Price"}.issubset(headers):
                continue
            indexes = {header: headers.index(header) for header in headers if header}
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) <= max(indexes.values()):
                    continue
                name = _source_text(cells[indexes["Name"]])
                if not name or name.casefold() in {"name", "total"}:
                    continue
                gambit = _optional_text(cells[indexes["Gambit"]])
                level = _optional_text(cells[indexes["Level"]])
                price = _number(str(cells[indexes["Price"]]))
                locator = f"Table {table['table_index']}, source row {row.get('row_index')}, Battalion Guild listing for {name}"
                merge(
                    name, source_id="fewiki-monastery", digest=digest, locator=locator,
                    shop_listing={"gambit": gambit, "level": level, "price": price, "source_table": table["table_index"]},
                )
    return list(records.values())


def _skill_capture_records(
    arts_capture: dict[str, Any] | None,
    black_magic_capture: dict[str, Any] | None,
    white_magic_capture: dict[str, Any] | None,
    skills_capture: dict[str, Any] | None,
    class_capture: dict[str, Any] | None,
    class_records: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    records: dict[str, dict[str, Any]] = {}
    art_records: dict[str, dict[str, Any]] = {}
    special_effects: dict[str, tuple[str, str, int, str, str | None]] = {}

    if skills_capture:
        digest = skills_capture.get("captured_content_sha256")
        table = next((item for item in skills_capture.get("tables", []) if {"Skill", "Special effect"}.issubset(item.get("headers", []))), None)
        if table:
            headers = [_source_text(value) for value in table.get("headers", [])]
            skill_index = headers.index("Skill")
            effect_index = headers.index("Special effect")
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) <= max(skill_index, effect_index):
                    continue
                name = _source_text(cells[skill_index])
                if name:
                    special_effects[name] = (
                        _source_text(cells[effect_index]), "fewiki-skills", table["table_index"],
                        str(row.get("row_index")), digest,
                    )

    def add_catalog_skill(
        name: str,
        skill_type: str,
        source_id: str,
        digest: str | None,
        locator: str,
        section: str,
    ) -> dict[str, Any]:
        name = _source_text(name)
        stable_key = f"{skill_type.replace('_', '-')}-{_slug(name)}"
        record = records.get(stable_key)
        if record is None:
            record = _semantic_record(
                "skills", stable_key, name, name, section,
                "The source marks this name as present in Fire Emblem: Three Houses; learning conditions and complete combat values are not supplied by this catalog excerpt.",
                f"{name} {section} {skill_type}", "skill",
                {
                    "skill_type": skill_type,
                    "weapon_type": None,
                    "requirement": None,
                    "cost": None,
                    "activation_condition": None,
                    "effect": {"game_presence_marker": "✔", "source_sections": [section]},
                },
                source_id, locator, digest,
            )
            records[stable_key] = record
        else:
            _append_source(record, _source_link(source_id, locator, digest, assertion="unverified"))
            sections = record["details"]["effect"].setdefault("source_sections", [])
            if section not in sections:
                sections.append(section)
        return record

    if arts_capture:
        digest = arts_capture.get("captured_content_sha256")
        for table in arts_capture.get("tables", []):
            headers = [_source_text(value) for value in table.get("headers", [])]
            if "FE16" not in headers or not headers:
                continue
            game_index = headers.index("FE16")
            section = _source_text(table.get("section"))
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) <= game_index or _source_text(cells[game_index]) != "✔":
                    continue
                name = _source_text(cells[0])
                if not name or name.casefold() in {"name", "skill", "combat art"}:
                    continue
                locator = f"Table {table['table_index']}, source row {row.get('row_index')}, FE16=✔; section={section}"
                record = add_catalog_skill(name, "combat_art", "fewiki-combat-arts", digest, locator, section)
                effects = record["details"]["effect"].setdefault("special_effects", [])
                art_records[name] = record
                if name in special_effects:
                    effect, source_id, source_table, source_row, source_digest = special_effects[name]
                    special = {"text": effect, "source_table": source_table, "source_row": int(source_row)}
                    if special not in effects:
                        effects.append(special)
                    _append_source(record, _source_link(
                        source_id, f"Table {source_table}, source row {source_row}; exact combat-art name match; Three Houses applicability of effect wording not separately verified",
                        source_digest, assertion="unverified",
                    ))

    for source_name, capture, skill_type in [
        ("fewiki-black-magic", black_magic_capture, "black_magic"),
        ("fewiki-white-magic", white_magic_capture, "white_magic"),
    ]:
        if not capture:
            continue
        digest = capture.get("captured_content_sha256")
        table = next((item for item in capture.get("tables", []) if "FE16" in item.get("headers", [])), None)
        if not table:
            continue
        headers = [_source_text(value) for value in table.get("headers", [])]
        game_index = headers.index("FE16")
        section = _source_text(table.get("section"))
        for row in table.get("rows", []):
            cells = row.get("cells", [])
            if len(cells) <= game_index or _source_text(cells[game_index]) != "✔":
                continue
            name = _source_text(cells[0])
            if not name or name.casefold() in {"name", "magic", "spell"}:
                continue
            locator = f"Table {table['table_index']}, source row {row.get('row_index')}, FE16=✔"
            add_catalog_skill(name, skill_type, source_name, digest, locator, section)

    class_by_row: dict[int, str] = {}
    for record in class_records:
        for source in record.get("sources", []):
            match = re.search(r"source row (\d+)", source.get("locator", ""))
            if match:
                class_by_row[int(match.group(1))] = record["stable_key"]
    if class_capture:
        digest = class_capture.get("captured_content_sha256")
        class_table = _table_by_index(class_capture, 2)
        if class_table:
            for row in class_table.get("rows", []):
                row_index = int(row.get("row_index", 0))
                class_key = class_by_row.get(row_index)
                cells = row.get("cells", [])
                if not class_key or len(cells) <= 70:
                    continue
                class_name = _source_text(cells[0])
                mastery_exp = _optional_text(cells[70])
                for column, role, field in [(68, "innate", "class_innate_ability"), (69, "mastery", "class_mastery_ability")]:
                    raw = _optional_text(cells[column])
                    if not raw:
                        continue
                    for source_name in re.split(r"\s*,\s*", raw):
                        marker = "†" if source_name.endswith("†") else None
                        name = source_name.removesuffix("†").strip()
                        if not name or name.casefold() in {"--", "—", "-", "none", "n/a"}:
                            continue
                        stable_key = f"class-ability-{_slug(name)}"
                        locator = f"Table 2, source row {row_index}, {role} ability={source_name}; class={class_name}"
                        record = records.get(stable_key)
                        if record is None:
                            record = _semantic_record(
                                "skills", stable_key, name, name, "Class ability",
                                "Exact class ability or mastery text from the source class table; class skill acquisition data and full effect details remain incomplete.",
                                f"{name} {class_name} {role} class ability",
                                "skill",
                                {
                                    "skill_type": "class_ability",
                                    "weapon_type": None,
                                    "requirement": "Class innate ability" if role == "innate" else "Class mastery ability; mastery experience is stored per class relationship.",
                                    "cost": None,
                                    "activation_condition": None,
                                    "effect": {"source_variants": []},
                                },
                                "fewiki-classes", locator, digest,
                            )
                            records[stable_key] = record
                        else:
                            _append_source(record, _source_link("fewiki-classes", locator, digest, assertion="unverified"))
                        variant = {
                            "class_stable_key": class_key,
                            "class_name": class_name,
                            "role": role,
                            "source_text": source_name,
                            "source_marker": marker,
                            "mastery_experience": _number(mastery_exp) if role == "mastery" and mastery_exp else None,
                        }
                        variants = record["details"]["effect"]["source_variants"]
                        if variant not in variants:
                            variants.append(variant)
                        relation = {
                            "relation_type": field,
                            "target_category_id": "classes",
                            "target_stable_key": class_key,
                            "evidence": f"The cited class row lists {source_name} in its {role} ability column for {class_name}.",
                            "verification_status": "unverified",
                            "source_id": "fewiki-classes",
                            "locator": locator,
                        }
                        if relation not in record["relations"]:
                            record["relations"].append(relation)
    return list(records.values())


def _social_capture_records(
    gift_capture: dict[str, Any] | None,
    dining_capture: dict[str, Any] | None,
    tea_capture: dict[str, Any] | None,
    character_records: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    social_records: dict[str, dict[str, Any]] = {}
    mechanics: list[dict[str, Any]] = []
    characters = {_source_text(record.get("title_en")): record["stable_key"] for record in character_records}

    if gift_capture:
        digest = gift_capture.get("captured_content_sha256")
        for table in gift_capture.get("tables", []):
            headers = [_source_text(value) for value in table.get("headers", [])]
            if not {"Gift", "Liked by", "Disliked by"}.issubset(headers) or table.get("table_index") not in {4, 5}:
                continue
            indexes = {header: headers.index(header) for header in headers}
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) <= max(indexes.values()):
                    continue
                name = _source_text(cells[indexes["Gift"]])
                if not name or name.casefold() in {"gift", "total"}:
                    continue
                liked_text = _source_text(cells[indexes["Liked by"]])
                disliked_text = _source_text(cells[indexes["Disliked by"]])
                liked = _split_names(liked_text)
                disliked = _split_names(disliked_text)
                locator = f"Table {table['table_index']}, source row {row.get('row_index')}, gift={name}"
                stable_key = f"gift-{_slug(name)}"
                record = social_records.get(stable_key)
                if record is None:
                    record = _semantic_record(
                        "social", stable_key, name, name, "Gift preference",
                        "Gift preference text from the cited table. Missing names or em dashes are not interpreted as neutral preference.",
                        f"{name} gift {liked_text} {disliked_text}", "activity",
                        {
                            "activity_type": "gift_preference",
                            "availability_condition": None,
                            "options": {"liked_by_source_text": liked_text, "disliked_by_source_text": disliked_text},
                            "effects": {"liked_by": liked, "disliked_by": disliked},
                        },
                        "fewiki-gifts", locator, digest,
                    )
                    social_records[stable_key] = record
                else:
                    _append_source(record, _source_link("fewiki-gifts", locator, digest, assertion="unverified"))
                for preference, people in [("likes_gift", liked), ("dislikes_gift", disliked)]:
                    for person in people:
                        target_key = characters.get(person)
                        if not target_key:
                            continue
                        preference_label = "Liked by" if preference == "likes_gift" else "Disliked by"
                        relation = {
                            "relation_type": preference,
                            "target_category_id": "characters",
                            "target_stable_key": target_key,
                            "evidence": f"The cited gift row lists {person} under {preference_label}.",
                            "verification_status": "unverified",
                            "source_id": "fewiki-gifts",
                            "locator": locator,
                        }
                        if relation not in record["relations"]:
                            record["relations"].append(relation)

    if dining_capture:
        digest = dining_capture.get("captured_content_sha256")
        for table in dining_capture.get("tables", []):
            headers = [_source_text(value) for value in table.get("headers", [])]
            if table.get("table_index") == 5 and {"Dish", "Ingredients", "Liked by", "Disliked by"}.issubset(headers):
                indexes = {header: headers.index(header) for header in headers}
                for row in table.get("rows", []):
                    cells = row.get("cells", [])
                    if len(cells) <= max(indexes.values()):
                        continue
                    name = _source_text(cells[indexes["Dish"]])
                    if not name or name.casefold() in {"dish", "total"}:
                        continue
                    liked_text = _source_text(cells[indexes["Liked by"]])
                    disliked_text = _source_text(cells[indexes["Disliked by"]])
                    liked, disliked = _split_names(liked_text), _split_names(disliked_text)
                    ingredients = _optional_text(cells[indexes["Ingredients"]])
                    category = _optional_text(cells[indexes["Category"]]) if "Category" in indexes else None
                    description = _optional_text(cells[indexes["Description"]]) if "Description" in indexes else None
                    locator = f"Table 5, source row {row.get('row_index')}, dish={name}"
                    record = _semantic_record(
                        "social", f"meal-{_slug(name)}", name, name, category,
                        "Dish description, ingredients, category and preference lists are source excerpts; route availability and image rights are not established.",
                        " ".join(value for value in [name, category, ingredients, description, liked_text, disliked_text] if value),
                        "activity",
                        {
                            "activity_type": "meal_preference",
                            "availability_condition": None,
                            "options": {
                                "description": description, "ingredients": ingredients, "category": category,
                                "liked_by_source_text": liked_text, "disliked_by_source_text": disliked_text,
                            },
                            "effects": {"liked_by": liked, "disliked_by": disliked},
                        },
                        "fewiki-dining-hall", locator, digest,
                    )
                    social_records[record["stable_key"]] = record
                    for preference, people in [("likes_meal", liked), ("dislikes_meal", disliked)]:
                        for person in people:
                            target_key = characters.get(person)
                            if not target_key:
                                continue
                            preference_label = "Liked by" if preference == "likes_meal" else "Disliked by"
                            record["relations"].append({
                                "relation_type": preference,
                                "target_category_id": "characters",
                                "target_stable_key": target_key,
                                "evidence": f"The cited dish row lists {person} under {preference_label}.",
                                "verification_status": "unverified",
                                "source_id": "fewiki-dining-hall",
                                "locator": locator,
                            })
            elif table.get("table_index") == 6 and {"Event", "Served Meal"}.issubset(headers):
                indexes = {header: headers.index(header) for header in headers}
                for row in table.get("rows", []):
                    cells = row.get("cells", [])
                    if len(cells) <= max(indexes.values()):
                        continue
                    name = _source_text(cells[indexes["Event"]])
                    meal = _source_text(cells[indexes["Served Meal"]])
                    if not name or name.casefold() == "event":
                        continue
                    description = _optional_text(cells[indexes["Description"]]) if "Description" in indexes else None
                    boost = _optional_text(cells[indexes["Characters receiving a boost"]]) if "Characters receiving a boost" in indexes else None
                    locator = f"Table 6, source row {row.get('row_index')}, dining event={name}"
                    record = _semantic_record(
                        "social", f"dining-event-{_slug(name)}", name, name, "Dining Hall event",
                        "Special calendar/event meal listing and source-described effect; event schedule details are not inferred.",
                        f"{name} {meal} {description or ''} {boost or ''}", "activity",
                        {
                            "activity_type": "dining_hall_event",
                            "availability_condition": None,
                            "options": {"served_meal": meal, "boosted_characters_source_text": boost},
                            "effects": {"description": description},
                        },
                        "fewiki-dining-hall", locator, digest,
                    )
                    target_key = f"meal-{_slug(meal)}"
                    if meal and target_key in social_records:
                        record["relations"].append({
                            "relation_type": "serves_meal",
                            "target_category_id": "social",
                            "target_stable_key": target_key,
                            "evidence": "The event row names this dish in its Served Meal column.",
                            "verification_status": "unverified",
                            "source_id": "fewiki-dining-hall",
                            "locator": locator,
                        })
                    social_records[record["stable_key"]] = record
            elif table.get("table_index") in {3, 4} and {"Meal Preference", "Motivation gained"}.issubset(headers):
                indexes = {header: headers.index(header) for header in headers}
                for row in table.get("rows", []):
                    cells = row.get("cells", [])
                    if len(cells) <= max(indexes.values()):
                        continue
                    preference = _source_text(cells[indexes["Meal Preference"]])
                    gained = _number(str(cells[indexes["Motivation gained"]]))
                    if not preference:
                        continue
                    locator = f"Table {table['table_index']}, source row {row.get('row_index')}, meal preference={preference}"
                    mechanics.append(_semantic_record(
                        "mechanics", f"meal-motivation-table-{table['table_index']}-{_slug(preference)}",
                        f"Share a Meal: {preference} motivation", f"Share a Meal: {preference} motivation", "Share a Meal",
                        "Motivation value is retained from the table; the excerpt does not label other context that may explain differences between tables.",
                        f"meal motivation {preference} {gained}", "mechanic",
                        {
                            "mechanic_type": "meal_motivation",
                            "game_version": "Fire Emblem: Three Houses; exact patch not recorded",
                            "rule_description": "Source-listed motivation gained for a meal preference.",
                            "parameters": {"preference": preference, "motivation_gained": gained, "source_table": table["table_index"]},
                        },
                        "fewiki-dining-hall", locator, digest,
                    ))

    if tea_capture:
        digest = tea_capture.get("captured_content_sha256")
        table = _table_by_index(tea_capture, 3)
        if table and {"Good tea choice", "Other tea choice"}.issubset(table.get("headers", [])):
            headers = [_source_text(value) for value in table["headers"]]
            indexes = {header: headers.index(header) for header in headers}
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) < 2:
                    continue
                stage = _source_text(cells[0])
                good = _optional_text(cells[indexes["Good tea choice"]])
                other = _optional_text(cells[indexes["Other tea choice"]])
                extra = _source_text(cells[-1]) if len(cells) > len(headers) else None
                if not stage:
                    continue
                locator = f"Table 3, source row {row.get('row_index')}, conversation stage={stage}"
                record = _semantic_record(
                    "social", f"tea-party-conversation-{_slug(stage)}", f"Tea party {stage} conversation", f"Tea party {stage} conversation", "Tea party",
                    "Conversation-stage row excerpt. It does not contain the complete character-specific tea-topic list.",
                    f"tea party {stage} {good or ''} {other or ''} {extra or ''}", "activity",
                    {
                        "activity_type": "tea_party_conversation_stage",
                        "availability_condition": None,
                        "options": {"good_tea_choice_source_text": good, "other_tea_choice_source_text": other, "additional_source_text": extra},
                        "effects": {},
                    },
                    "fewiki-tea-party", locator, digest,
                )
                social_records[record["stable_key"]] = record

        score_table = _table_by_index(tea_capture, 5)
        if score_table and {"Total EP", "In-Game Rating", "Support Points"}.issubset(score_table.get("headers", [])):
            headers = [_source_text(value) for value in score_table["headers"]]
            indexes = {header: headers.index(header) for header in headers}
            for row in score_table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) <= max(indexes.values()):
                    continue
                ep = _source_text(cells[indexes["Total EP"]])
                rating = _optional_text(cells[indexes["In-Game Rating"]])
                support = _number(str(cells[indexes["Support Points"]]))
                charm = _number(str(cells[indexes["Charm Increase Chance\u00a0%"]])) if "Charm Increase Chance\u00a0%" in indexes else None
                if not ep:
                    continue
                locator = f"Table 5, source row {row.get('row_index')}, Total EP={ep}"
                mechanics.append(_semantic_record(
                    "mechanics", f"tea-party-rating-{_slug(ep)}-ep",
                    f"Tea party result at {ep} EP", f"Tea party result at {ep} EP", "Tea party scoring",
                    "Rating and rewards are preserved as source table values; version and route applicability are not separately recorded.",
                    f"tea party {ep} EP {rating or ''} support points {support} charm {charm}", "mechanic",
                    {
                        "mechanic_type": "tea_party_result",
                        "game_version": "Fire Emblem: Three Houses; exact patch not recorded",
                        "rule_description": "Source-listed tea party rating/reward row.",
                        "parameters": {"total_ep": _number(ep), "in_game_rating": rating, "support_points": support, "charm_increase_chance_percent": charm},
                    },
                    "fewiki-tea-party", locator, digest,
                ))
        action_table = _table_by_index(tea_capture, 6)
        if action_table and {"Action", "EP Gained"}.issubset(action_table.get("headers", [])):
            headers = [_source_text(value) for value in action_table["headers"]]
            indexes = {header: headers.index(header) for header in headers}
            for row in action_table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) <= max(indexes.values()):
                    continue
                action = _source_text(cells[indexes["Action"]])
                gained = _optional_text(cells[indexes["EP Gained"]])
                if not action:
                    continue
                locator = f"Table 6, source row {row.get('row_index')}, action={action}"
                mechanics.append(_semantic_record(
                    "mechanics", f"tea-party-extra-ep-{_slug(action)}", f"Tea party extra EP: {action}", f"Tea party extra EP: {action}", "Tea party scoring",
                    "Extra EP value is retained as source text; cumulative scoring application is not inferred.",
                    f"tea party EP {action} {gained or ''}", "mechanic",
                    {
                        "mechanic_type": "tea_party_extra_ep",
                        "game_version": "Fire Emblem: Three Houses; exact patch not recorded",
                        "rule_description": "Source-listed tea party point adjustment.",
                        "parameters": {"action": action, "ep_gained": gained},
                    },
                    "fewiki-tea-party", locator, digest,
                ))
    return list(social_records.values()), mechanics


def _monastery_capture_records(
    capture: dict[str, Any] | None,
    equipment_records: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    if not capture:
        return []
    digest = capture.get("captured_content_sha256")
    records: dict[str, dict[str, Any]] = {}
    equipment = {_source_text(item.get("title_en")): item["stable_key"] for item in equipment_records}

    for table in capture.get("tables", []):
        table_index = table.get("table_index")
        headers = [_source_text(value) for value in table.get("headers", [])]
        section = _source_text(table.get("section"))
        if table_index == 6 and {"Professor level", "Activity points"}.issubset(headers):
            indexes = {header: headers.index(header) for header in headers}
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) <= max(indexes.values()):
                    continue
                level = _source_text(cells[indexes["Professor level"]])
                points = _number(str(cells[indexes["Activity points"]]))
                if not level:
                    continue
                locator = f"Table 6, source row {row.get('row_index')}, professor level={level}"
                record = _semantic_record(
                    "monastery", f"activity-points-{_slug(level)}", f"Activity points at professor level {level}", f"Activity points at professor level {level}", "Activity points",
                    "Source-listed monthly activity-point allowance; calendar and patch-specific changes are not independently checked.",
                    f"professor level {level} activity points {points}", "activity",
                    {
                        "activity_type": "professor_level_activity_points",
                        "availability_condition": None,
                        "options": {"professor_level": level},
                        "effects": {"activity_points": points},
                    },
                    "fewiki-monastery", locator, digest,
                )
                records[record["stable_key"]] = record
        elif table_index == 7 and headers:
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if not cells:
                    continue
                rewards = {header: _optional_text(cells[index]) if index < len(cells) else None for index, header in enumerate(headers)}
                cost = _optional_text(cells[len(headers)]) if len(cells) > len(headers) else None
                if not any(rewards.values()):
                    continue
                identity = cost or f"row-{row.get('row_index')}"
                locator = f"Table 7, source row {row.get('row_index')}, Saint Statues reward tier"
                record = _semantic_record(
                    "monastery", f"saint-statues-{_slug(identity)}", f"Saint Statues reward tier {identity}", f"Saint Statues reward tier {identity}", "Saint Statues",
                    "Statue reward cells and the adjacent cost text are retained by source column; the row level or unlock order is not inferred.",
                    " ".join(["Saint Statues", *[value for value in rewards.values() if value], cost or ""]), "activity",
                    {
                        "activity_type": "saint_statue_reward_tier",
                        "availability_condition": None,
                        "options": {"source_reward_cells": rewards, "source_cost_text": cost},
                        "effects": {},
                    },
                    "fewiki-monastery", locator, digest,
                )
                records[record["stable_key"]] = record
        elif section == "Byleth's Room" and {"Item", "Effect"}.issubset(headers):
            indexes = {header: headers.index(header) for header in headers}
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) <= max(indexes.values()):
                    continue
                name = _source_text(cells[indexes["Item"]])
                effect = _optional_text(cells[indexes["Effect"]])
                if not name:
                    continue
                locator = f"Table {table_index}, source row {row.get('row_index')}, Byleth's Room reward={name}"
                record = _semantic_record(
                    "monastery", f"room-reward-{_slug(name)}", name, name, "Byleth's Room",
                    "Source-listed room reward and effect text; DLC or unlock requirements remain as shown only by the source table.",
                    f"{name} {effect or ''} Byleth's Room", "activity",
                    {
                        "activity_type": "room_reward",
                        "availability_condition": None,
                        "options": {"item_name": name, "source_table": table_index},
                        "effects": {"effect": effect},
                    },
                    "fewiki-monastery", locator, digest,
                )
                equipment_key = equipment.get(name)
                if equipment_key:
                    record["relations"].append({
                        "relation_type": "room_reward_item",
                        "target_category_id": "equipment",
                        "target_stable_key": equipment_key,
                        "evidence": f"An exact item-title match links the room reward name {name} to the equipment catalog entry.",
                        "verification_status": "unverified",
                        "source_id": "fewiki-monastery",
                        "locator": locator,
                    })
                records[record["stable_key"]] = record
        elif section in {"Armory", "Item Shop", "Traveling Merchants and Anna's Shop/Secret Shop"} and {"Name", "Stock", "Price"}.issubset(headers):
            indexes = {header: headers.index(header) for header in headers if header}
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) <= max(indexes.values()):
                    continue
                name = _source_text(cells[indexes["Name"]])
                stock = _optional_text(cells[indexes["Stock"]])
                price_text = _optional_text(cells[indexes["Price"]])
                if not name or name.casefold() in {"name", "total"}:
                    continue
                price = _number(str(price_text)) if price_text is not None else None
                stable_key = f"shop-{_slug(section)}-{_slug(name)}-{_slug(stock or 'unspecified')}-{_slug(str(price) if price is not None else 'unknown-price')}"
                locator = f"Table {table_index}, source row {row.get('row_index')}, {section} listing for {name}"
                record = records.get(stable_key)
                if record is None:
                    record = _semantic_record(
                        "monastery", stable_key, f"{name} — {section}", f"{name} — {section}", section,
                        "Shop name, stock text and price are preserved from the listing; route, month and access conditions are not inferred.",
                        f"{name} {section} {stock or ''} {price_text or ''}", "activity",
                        {
                            "activity_type": "shop_listing",
                            "availability_condition": "Only the cited shop table is known; exact route/month access is not recorded.",
                            "options": {"shop": section, "item_name": name, "stock_source_text": stock, "source_tables": [table_index]},
                            "effects": {"price": price},
                        },
                        "fewiki-monastery", locator, digest,
                    )
                    records[stable_key] = record
                else:
                    _append_source(record, _source_link("fewiki-monastery", locator, digest, assertion="unverified"))
                    source_tables = record["details"]["options"]["source_tables"]
                    if table_index not in source_tables:
                        source_tables.append(table_index)
                equipment_key = equipment.get(name)
                if equipment_key and not any(relation.get("target_stable_key") == equipment_key for relation in record["relations"]):
                    record["relations"].append({
                        "relation_type": "shop_offering_for",
                        "target_category_id": "equipment",
                        "target_stable_key": equipment_key,
                        "evidence": f"The shop row lists {name}, matching the equipment catalog title exactly.",
                        "verification_status": "unverified",
                        "source_id": "fewiki-monastery",
                        "locator": locator,
                    })
        elif table_index == 30 and "Battalion" in headers:
            index = headers.index("Battalion")
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if index >= len(cells):
                    continue
                name = _source_text(cells[index])
                if not name:
                    continue
                locator = f"Table 30, source row {row.get('row_index')}, Recon Training battalion={name}"
                record = _semantic_record(
                    "monastery", f"recon-battalion-{_slug(name)}", name, name, "Recon Training and Recon Missions",
                    "A battalion name is listed on the source page; mission availability and reward details are not included in this table excerpt.",
                    f"{name} recon training recon missions", "activity",
                    {"activity_type": "recon_mission_battalion", "availability_condition": None, "options": {"battalion_name": name}, "effects": {}},
                    "fewiki-monastery", locator, digest,
                )
                records[record["stable_key"]] = record
        elif table_index == 31 and {"Character", "Song"}.issubset(headers):
            indexes = {header: headers.index(header) for header in headers}
            for row in table.get("rows", []):
                cells = row.get("cells", [])
                if len(cells) <= max(indexes.values()):
                    continue
                character = _source_text(cells[indexes["Character"]])
                song = _source_text(cells[indexes["Song"]])
                if not character or not song:
                    continue
                locator = f"Table 31, source row {row.get('row_index')}, amiibo={character}"
                record = _semantic_record(
                    "monastery", f"amiibo-song-{_slug(character)}", f"Amiibo song: {character}", f"Amiibo song: {character}", "Amiibo Gazebo",
                    "Source-listed Amiibo character and song pair; availability and gameplay effects are not inferred.",
                    f"{character} {song} amiibo gazebo", "activity",
                    {"activity_type": "amiibo_gazebo_song", "availability_condition": None, "options": {"amiibo_character": character}, "effects": {"song": song}},
                    "fewiki-monastery", locator, digest,
                )
                records[record["stable_key"]] = record
    return list(records.values())


def _route_records() -> list[dict[str, Any]]:
    # Route names are indexed by the Three Houses overview; no full narrative text is copied.
    source_id = "fewiki-overview"
    routes = [
        ("black-eagles-crimson-flower", "Black Eagles / Crimson Flower"),
        ("black-eagles-silver-snow", "Black Eagles / Silver Snow"),
        ("blue-lions-azure-moon", "Blue Lions / Azure Moon"),
        ("golden-deer-verdant-wind", "Golden Deer / Verdant Wind"),
    ]
    records = []
    for stable_key, original in routes:
        records.append({
            "category_id": "routes",
            "stable_key": stable_key,
            "title": original,
            "title_en": original,
            "subtitle": None,
            "description": "Route name indexing only; chapter-by-chapter coverage is recorded separately.",
            "search_text": f"{original} route campaign",
            "verification_status": "source_only",
            "kind": "mechanic",
            "details": {"mechanic_type": "campaign_route", "game_version": "unknown", "rule_description": original},
            "sources": [_source_link(source_id, "Game overview; route selection and campaign labels", None, assertion="unverified", translation="not_translated")],
        })
    return records


def _capture_source(
    source_id: str,
    title: str,
    url: str,
    capture: dict[str, Any] | None,
    notes: str,
) -> dict[str, Any]:
    return {
        "source_id": source_id,
        "source_type": "wiki_article",
        "title": title,
        "url": (capture or {}).get("source_url") or url,
        "language": "en",
        "game_version": None,
        "revision": None,
        "retrieved_at": (capture or {}).get("retrieved_at") or datetime.now(timezone.utc).isoformat(),
        "access_status": "readable",
        "rights_status": "licensed",
        "sha256": (capture or {}).get("captured_content_sha256"),
        "notes": notes,
    }


def _base_sources(
    class_capture: dict[str, Any] | None,
    character_capture: dict[str, Any] | None,
    chapter_capture: dict[str, Any] | None,
    support_capture: dict[str, Any] | None,
    additional_captures: dict[str, dict[str, Any] | None],
) -> list[dict[str, Any]]:
    retrieved = (class_capture or {}).get("retrieved_at") or datetime.now(timezone.utc).isoformat()
    class_hash = (class_capture or {}).get("captured_content_sha256")
    class_url = (class_capture or {}).get("source_url") or "https://fireemblemwiki.org/wiki/List_of_classes_in_Fire_Emblem:_Three_Houses"
    sources = [
        {
            "source_id": "fewiki-classes", "source_type": "wiki_article", "title": "List of classes in Fire Emblem: Three Houses",
            "url": class_url, "language": "en", "game_version": None, "revision": None,
            "retrieved_at": retrieved, "access_status": "readable", "rights_status": "licensed",
            "sha256": class_hash,
            "notes": "Table-only structured derivative; source site default GNU FDL 1.3 unless page-specific notice overrides it. No page artwork was copied. Value locators identify table and source row.",
        },
        _capture_source(
            "fewiki-characters",
            "List of characters in Fire Emblem: Three Houses",
            "https://fireemblemwiki.org/wiki/List_of_characters_in_Fire_Emblem:_Three_Houses",
            character_capture,
            "Selected playable-roster tables only; no page snapshot or artwork retained. Default GNU FDL 1.3 applies unless a page-specific notice overrides it. Locators include source table and row.",
        ),
        _capture_source(
            "fewiki-chapters",
            "List of chapters in Fire Emblem: Three Houses",
            "https://fireemblemwiki.org/wiki/List_of_chapters_in_Fire_Emblem:_Three_Houses",
            chapter_capture,
            "Visible chapter, route, paralogue and Cindered Shadows table excerpts only; no page snapshot or artwork retained. Default GNU FDL 1.3 applies unless a page-specific notice overrides it.",
        ),
        _capture_source(
            "fewiki-supports",
            "List of supports in Fire Emblem: Three Houses",
            "https://fireemblemwiki.org/wiki/List_of_supports_in_Fire_Emblem:_Three_Houses",
            support_capture,
            "Support-pair index table only; no dialogue text, page snapshot or artwork retained. Default GNU FDL 1.3 applies unless a page-specific notice overrides it. Byleth gender variants remain distinct source labels.",
        ),
        {
            "source_id": "serenes-class-growth-reference", "source_type": "cross_check_reference", "title": "Three Houses class growth rates",
            "url": "https://serenesforest.net/three-houses/classes/growth-rates/", "language": "en-US", "game_version": None,
            "revision": None, "retrieved_at": "2026-11-25T05:10:38.743543+00:00", "access_status": "readable",
            "rights_status": "unknown", "sha256": None,
            "notes": "robots.txt says use=reference and ai-train=no. Used only to cross-check the Myrmidon player growth row; no table or page snapshot was retained or bulk-copied.",
        },
        {
            "source_id": "fewiki-overview", "source_type": "wiki_article", "title": "Fire Emblem: Three Houses",
            "url": "https://fireemblemwiki.org/wiki/Fire_Emblem:_Three_Houses", "language": "en", "game_version": None,
            "revision": None, "retrieved_at": "2026-11-25T05:02:00+00:00", "access_status": "readable",
            "rights_status": "licensed", "sha256": None,
            "notes": "Used for route naming/index only; site default GNU FDL 1.3 unless page-specific notice overrides it. No images or long prose copied.",
        },
        {
            "source_id": "huiji-three-houses", "source_type": "user_provided_wiki", "title": "HuijiWiki Three Houses",
            "url": "https://threehouses.huijiwiki.com/", "language": "zh-CN", "game_version": None, "revision": None,
            "retrieved_at": "2026-11-24T00:00:00+00:00", "access_status": "http_403", "rights_status": "unknown", "sha256": None,
            "notes": "Ordinary request returned HTTP 403; not accessed or bypassed. Reference-only robots signal recorded in research/source-matrix.md.",
        },
        {
            "source_id": "nintendo-three-houses", "source_type": "official_product_page", "title": "Fire Emblem: Three Houses product page",
            "url": "https://www.nintendo.com/us/store/products/fire-emblem-three-houses-switch/", "language": "en-US", "game_version": None,
            "revision": None, "retrieved_at": datetime.now(timezone.utc).isoformat(), "access_status": "readable", "rights_status": "unknown",
            "sha256": None, "notes": "The official supported-languages list includes Simplified Chinese (zh-Hans), confirming game-level locale support only. The page does not provide localized gameplay terms; no such terms are derived from this evidence. No long marketing text or images copied."
        },
        {
            "source_id": "nintendo-three-houses-expansion", "source_type": "official_product_page", "title": "Fire Emblem: Three Houses Expansion Pass",
            "url": "https://www.nintendo.com/us/store/products/fire-emblem-three-houses-expansion-pass-70070000006541-switch/", "language": "en-US", "game_version": None,
            "revision": None, "retrieved_at": "2026-11-24T00:00:00+00:00", "access_status": "readable", "rights_status": "unknown",
            "sha256": None, "notes": "Official DLC product metadata candidate; not used to copy data or media in this package."
        },
        {
            "source_id": "saintoracle-classes", "source_type": "reference_database", "title": "Saint Oracle Three Houses class database",
            "url": "https://hub.saintoracle.com/three-houses/class-database", "language": "en", "game_version": None,
            "revision": None, "retrieved_at": "2026-11-24T00:00:00+00:00", "access_status": "readable", "rights_status": "unknown",
            "sha256": None, "notes": "Reference candidate only; no clear reuse license, so no tables or images copied."
        },
        {
            "source_id": "pegasusknight-fe16", "source_type": "reference_wiki", "title": "Pegasus Knight FE16 Wiki",
            "url": "https://www.pegasusknight.com/wiki/fe16/", "language": "ja", "game_version": None,
            "revision": None, "retrieved_at": "2026-11-24T00:00:00+00:00", "access_status": "readable", "rights_status": "unknown",
            "sha256": None, "notes": "Terms/license grant not established; reference-only, no bulk data or images copied."
        },
        {
            "source_id": "fe3h-assetsdb", "source_type": "third_party_repository", "title": "thierrylee/FE3H-AssetsDB",
            "url": "https://github.com/thierrylee/FE3H-AssetsDB", "language": "en", "game_version": None,
            "revision": None, "retrieved_at": "2026-11-24T00:00:00+00:00", "access_status": "readable", "rights_status": "unknown",
            "sha256": None, "notes": "Repository license is MIT, but README attributes the underlying data to Serenes Forest and Fire Emblem Wiki; upstream rights remain unclear. Not imported; reference only."
        },
        {
            "source_id": "triangleattack-stats", "source_type": "reference_calculator", "title": "Triangle Attack average stats calculator",
            "url": "https://www.fe3h.com/average_stats", "language": "en", "game_version": None,
            "revision": None, "retrieved_at": "2026-11-24T00:00:00+00:00", "access_status": "readable", "rights_status": "unknown",
            "sha256": None, "notes": "Interactive calculator, no data reuse license found; reference only."
        },
        {
            "source_id": "hyperwiki-fe16", "source_type": "reference_wiki", "title": "HyperWiki FE16",
            "url": "https://hyperwiki.jp/feth/", "language": "ja", "game_version": None,
            "revision": None, "retrieved_at": "2026-11-24T00:00:00+00:00", "access_status": "restricted", "rights_status": "unknown",
            "sha256": None, "notes": "robots.txt disallows GPTBot and ChatGPT-User; excluded from AI collection."
        },
        {
            "source_id": "fedatamine", "source_type": "datamining_site", "title": "Fedatamine",
            "url": "https://houses.fedatamine.com/en-us/", "language": "en", "game_version": None,
            "revision": None, "retrieved_at": "2026-11-24T00:00:00+00:00", "access_status": "restricted", "rights_status": "unknown",
            "sha256": None, "notes": "Site identifies extracted files as directly served game data and displays Nintendo copyright; excluded from collection."
        },
        {
            "source_id": "local-manycolours-excluded", "source_type": "local_file", "title": "《火焰之纹章 万紫千红》在线数据表",
            "url": "https://docs.qq.com/sheet/DV0N0VUZLSXRmUWFq?tab=lnafol", "language": "zh-CN", "game_version": None,
            "revision": "84209", "retrieved_at": "2026-11-16T04:10:58.890329+00:00", "access_status": "local_file", "rights_status": "unknown",
            "sha256": None, "notes": "Excluded as a different title/project: source rows describe 伊修玛尔、红花、特洛伊亚 and routes 凯伊篇/迪托利希篇/赛奥朵拉篇/蕾达篇. No content or artwork was copied."
        },
        {
            "source_id": "strategywiki-three-houses", "source_type": "reference_wiki", "title": "StrategyWiki Three Houses",
            "url": "https://strategywiki.org/wiki/Fire_Emblem:_Three_Houses", "language": "en", "game_version": None,
            "revision": None, "retrieved_at": "2026-11-24T00:00:00+00:00", "access_status": "http_403", "rights_status": "unknown",
            "sha256": None, "notes": "Ordinary request returned HTTP 403; excluded without bypass."
        },
    ]
    source_specs = [
        ("fewiki-battalions", "Battalion", "https://fireemblemwiki.org/wiki/Battalion", "Selected Three Houses battalion-directory table only; no images or full page snapshots retained."),
        ("fewiki-weapons", "List of weapons in Fire Emblem: Three Houses", "https://fireemblemwiki.org/wiki/List_of_weapons_in_Fire_Emblem:_Three_Houses", "Selected visible weapon table and catalog rows only; no images or full page snapshots retained."),
        ("fewiki-items", "List of items in Fire Emblem: Three Houses", "https://fireemblemwiki.org/wiki/List_of_items_in_Fire_Emblem:_Three_Houses", "Selected equipment, item and category-index table excerpts only; no images or full page snapshots retained."),
        ("fewiki-crests", "Crests", "https://fireemblemwiki.org/wiki/Crests", "Bloodline table excerpt only; page images and icons were not copied."),
        ("fewiki-combat-arts", "Combat art", "https://fireemblemwiki.org/wiki/Combat_art", "Combat-art catalog rows marked for FE16 only; page images were not copied."),
        ("fewiki-black-magic", "Black magic", "https://fireemblemwiki.org/wiki/Black_magic", "Magic catalog rows marked for FE16 only; page images were not copied."),
        ("fewiki-white-magic", "White magic", "https://fireemblemwiki.org/wiki/White_magic", "Magic catalog rows marked for FE16 only; page images were not copied."),
        ("fewiki-skills", "Skills", "https://fireemblemwiki.org/wiki/Skills", "Exact-name special-effect table used only to annotate matching FE16 combat-art entries; applicability wording is not independently verified."),
        ("fewiki-gifts", "Gift", "https://fireemblemwiki.org/wiki/Gift", "Three Houses gift tables only; Engage and Three Hopes tables are excluded."),
        ("fewiki-dining-hall", "Garreg Mach Monastery/Dining Hall", "https://fireemblemwiki.org/wiki/Garreg_Mach_Monastery/Dining_Hall", "Selected dish preference, event and motivation table excerpts only; page images were not copied."),
        ("fewiki-monastery", "Garreg Mach Monastery", "https://fireemblemwiki.org/wiki/Garreg_Mach_Monastery", "Selected activity-point, statue, shop, room-reward and Amiibo table excerpts only; artwork was not copied."),
        ("fewiki-tea-party", "Tea Party", "https://fireemblemwiki.org/wiki/Tea_Party", "Selected conversation-stage and scoring table excerpts only; character-specific topic tables are not present in this capture."),
    ]
    for source_id, title, url, note in source_specs:
        sources.append(_capture_source(
            source_id, title, url, additional_captures.get(source_id),
            note + " Default GNU FDL 1.3 applies unless a page-specific notice overrides it.",
        ))
    return sources


def normalize() -> dict[str, Any]:
    class_records, growth_records, class_capture = _class_capture_records()
    character_capture = _read_json(EXTRACTS / "characters.json")
    character_records, recruitment_records = _character_capture_records(character_capture)
    chapter_capture = _read_json(EXTRACTS / "chapters.json")
    chapter_records, dlc_records, battle_records = _chapter_capture_records(chapter_capture)
    support_capture = _read_json(EXTRACTS / "supports.json")
    support_records = _support_capture_records(support_capture, character_records)
    captures = {
        "fewiki-battalions": _read_json(EXTRACTS / "battalions.json"),
        "fewiki-weapons": _read_json(EXTRACTS / "weapons.json"),
        "fewiki-items": _read_json(EXTRACTS / "items.json"),
        "fewiki-crests": _read_json(EXTRACTS / "crests.json"),
        "fewiki-combat-arts": _read_json(EXTRACTS / "combat-arts.json"),
        "fewiki-black-magic": _read_json(EXTRACTS / "black-magic.json"),
        "fewiki-white-magic": _read_json(EXTRACTS / "white-magic.json"),
        "fewiki-skills": _read_json(EXTRACTS / "skills.json"),
        "fewiki-gifts": _read_json(EXTRACTS / "gifts.json"),
        "fewiki-dining-hall": _read_json(EXTRACTS / "dining-hall.json"),
        "fewiki-monastery": _read_json(EXTRACTS / "monastery.json"),
        "fewiki-tea-party": _read_json(EXTRACTS / "tea-party.json"),
    }
    equipment_records = _equipment_capture_records(captures["fewiki-items"], captures["fewiki-weapons"])
    crest_records = _crest_capture_records(captures["fewiki-crests"], character_records)
    battalion_records = _battalion_capture_records(captures["fewiki-battalions"], captures["fewiki-monastery"])
    skill_records = _skill_capture_records(
        captures["fewiki-combat-arts"], captures["fewiki-black-magic"], captures["fewiki-white-magic"],
        captures["fewiki-skills"], class_capture, class_records,
    )
    social_records, mechanic_records = _social_capture_records(
        captures["fewiki-gifts"], captures["fewiki-dining-hall"], captures["fewiki-tea-party"], character_records,
    )
    monastery_records = _monastery_capture_records(captures["fewiki-monastery"], equipment_records)
    records_by_category: dict[str, list[dict[str, Any]]] = {item[0]: [] for item in CATEGORIES}
    records_by_category["characters"].extend(character_records)
    records_by_category["recruitment"].extend(recruitment_records)
    records_by_category["classes"].extend(class_records)
    records_by_category["growth"].extend(growth_records)
    records_by_category["routes"].extend(_route_records())
    records_by_category["chapters"].extend(chapter_records)
    records_by_category["battles"].extend(battle_records)
    records_by_category["dlc"].extend(dlc_records)
    records_by_category["supports"].extend(support_records)
    records_by_category["crests"].extend(crest_records)
    records_by_category["battalions"].extend(battalion_records)
    records_by_category["skills"].extend(skill_records)
    records_by_category["equipment"].extend(equipment_records)
    records_by_category["social"].extend(social_records)
    records_by_category["monastery"].extend(monastery_records)
    records_by_category["mechanics"].extend(mechanic_records)
    community_translation_capture = _read_json(EXTRACTS / "moegirl-translations.json")
    localization_summary = _apply_community_translations(records_by_category, community_translation_capture)
    for category_id, _, _, _, _ in CATEGORIES:
        _write_json(PACKAGE / "normalized" / category_id / "records.json", {"records": records_by_category[category_id]})
    source_records = _base_sources(class_capture, character_capture, chapter_capture, support_capture, captures)
    if community_translation_capture:
        source_records.append(_community_translation_source(community_translation_capture))
    categories = []
    for position, (category_id, title, description, coverage, _) in enumerate(CATEGORIES):
        actual_sources = len({
            link.get("source_id")
            for record in records_by_category[category_id]
            for link in record.get("sources", [])
            if link.get("source_id")
        })
        categories.append({
            "id": category_id,
            "title": title,
            "description": description,
            "position": position,
            "applicable": True,
            "coverage_status": coverage if records_by_category[category_id] else ("blocked" if coverage == "blocked" else "pending"),
            "source_count": actual_sources,
        })
    _write_json(PACKAGE / "normalized" / "categories.json", categories)
    _write_json(PACKAGE / "sources" / "manifest.json", {"sources": source_records})
    asset_manifest = _read_json(PACKAGE / "assets" / "manifest.json", {"assets": []})
    if not isinstance(asset_manifest, dict):
        raise ValueError("Three Houses asset manifest must be a JSON object")
    media_inventory = _media_inventory(asset_manifest)
    community_term_count = localization_summary["term_count"]
    community_record_count = localization_summary["record_count"]
    community_license_note = (
        f"The {community_term_count} Moegirlpedia terms across {community_record_count} records are "
        "community_translation_unofficial in zh-Hans-CN, not official localization. "
        "CC BY-NC-SA 4.0 prohibits commercial use and requires share-alike for adaptations; "
        "a commercial app needs separate permission or must omit these terms."
    )
    manifest = {
        "game_id": "three-houses",
        "title": "Fire Emblem: Three Houses",
        "source_policy": "Traceable public or locally authorized sources only; restricted sources are recorded as gaps.",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "schema_version": 1,
        "languages": ["en", "zh-Hans-CN"],
        "officially_supported_locales": [{
            "locale": "zh-Hans",
            "evidence_source_id": "nintendo-three-houses",
            "evidence_locator": "Supported languages: Simplified Chinese",
            "scope": "Game-level language support; does not verify any individual localized term.",
        }],
        "translation_policy": (
            "Keep every source-language title in title_en and include a Simplified Chinese display title only when exact wording is supported by a term-level source. "
            f"{community_license_note} Chinese category labels are app navigation taxonomy, not game localization."
        ),
        "rights_policy": (
            f"{media_inventory['gap_note']} Fire Emblem Wiki text/table derivatives are attributed under the site's default GFDL 1.3 notice, subject to page-specific notices. "
            f"{community_license_note} See ATTRIBUTION.md."
        ),
        "source_count": len(source_records),
        "category_count": len(categories),
        "coverage_summary": "Partial. No single source is complete; see reports/coverage.md and reports/gaps.md.",
    }
    _write_json(PACKAGE / "manifest.json", manifest)
    route_stable_keys = {
        "crimson_flower": "black-eagles-crimson-flower",
        "silver_snow": "black-eagles-silver-snow",
        "azure_moon": "blue-lions-azure-moon",
        "verdant_wind": "golden-deer-verdant-wind",
    }
    coverage = {
        "generated_at": manifest["generated_at"],
        "localization": {
            "officially_supported_locales": manifest["officially_supported_locales"],
            "verified_official_zh_hans_terms": 0,
            "source_backed_community_unofficial_zh_hans_terms": localization_summary["term_count"],
            "community_unofficial_entity_records": localization_summary["record_count"],
            "community_translation_type": community_translation_capture.get("translation_type") if community_translation_capture else None,
            "community_locale": community_translation_capture.get("locale") if community_translation_capture else None,
            "community_license": community_translation_capture.get("license") if community_translation_capture else None,
            "community_commercial_use_allowed": community_translation_capture.get("commercial_use_allowed") if community_translation_capture else None,
            "community_share_alike_required": community_translation_capture.get("share_alike_required") if community_translation_capture else None,
            "community_access_observations": community_translation_capture.get("access_observations") if community_translation_capture else None,
            "community_mapping_method_counts": localization_summary["mapping_method_counts"],
            "community_mapping_confidence_counts": localization_summary["mapping_confidence_counts"],
            "community_character_entity_records": localization_summary["character_record_count"],
            "untranslated_character_stable_keys": localization_summary["untranslated_character_stable_keys"],
            "official_record_level_zh_hans_source_not_found": True,
            "total_entity_records": sum(len(category_records) for category_records in records_by_category.values()),
            "untranslated_title_fields": sum(len(category_records) for category_records in records_by_category.values()) - localization_summary["record_count"],
            "unofficial_drafts_removed": {"class_display_names": 50, "route_display_names": 4},
            "category_labels": "Project-defined navigation taxonomy; not game localization.",
        },
        "routes": [
            {
                "id": stable_key,
                "name_en": name,
                "chapter_count": sum(1 for record in chapter_records if record.get("details", {}).get("route") == route_label),
                "coverage_status": "partial",
            }
            for route_label, stable_key, name in [
                ("Silver Snow", "black-eagles-silver-snow", "Black Eagles / Silver Snow"),
                ("Azure Moon", "blue-lions-azure-moon", "Blue Lions / Azure Moon"),
                ("Verdant Wind", "golden-deer-verdant-wind", "Golden Deer / Verdant Wind"),
                ("Crimson Flower", "black-eagles-crimson-flower", "Black Eagles / Crimson Flower"),
            ]
        ],
        "dlc": [{"id": "cindered-shadows", "coverage_status": "partial", "chapter_count": sum(1 for record in chapter_records if record.get("details", {}).get("route") == "Cindered Shadows DLC")}],
        "categories": [
            {"id": item["id"], "title": item["title"], "coverage_status": item["coverage_status"], "record_count": len(records_by_category[item["id"]]), "source_count": item["source_count"]}
            for item in categories
        ],
        "route_dlc_matrix": {
            category_id: {
                route_id: {"status": "partial", "record_count": sum(
                    1 for record in records_by_category[category_id]
                    if (
                        category_id in {"chapters", "battles"}
                        and record.get("details", {}).get("route") == route_label
                    ) or (
                        category_id == "routes"
                        and record.get("stable_key") == route_stable_keys.get(route_id)
                    ) or (
                        category_id == "dlc"
                        and route_id == "cindered_shadows_dlc"
                        and record.get("stable_key") == "cindered-shadows"
                    )
                )} if (
                    category_id in {"chapters", "battles"}
                    or (category_id == "routes" and route_id != "cindered_shadows_dlc")
                    or (category_id == "dlc" and route_id == "cindered_shadows_dlc")
                ) else {"status": "not_applicable" if category_id in {"routes", "dlc"} else "not_route_disaggregated", "record_count": None}
                for route_label, route_id in [
                    ("Crimson Flower", "crimson_flower"),
                    ("Silver Snow", "silver_snow"),
                    ("Azure Moon", "azure_moon"),
                    ("Verdant Wind", "verdant_wind"),
                    ("Cindered Shadows DLC", "cindered_shadows_dlc"),
                ]
            }
            for category_id, _, _, _, _ in CATEGORIES
        },
        "excluded_sources": ["HuijiWiki (HTTP 403)", "Tencent 万紫千红 workbook (different title)", "HyperWiki (robots exclusion)", "Fedatamine (extracted game-data scope)", "StrategyWiki (HTTP 403)"],
        "support_character_link_gaps": sum(
            1 for record in support_records
            if not record.get("details", {}).get("character_a_stable_key") or not record.get("details", {}).get("character_b_stable_key")
        ),
    }
    _write_json(PACKAGE / "reports" / "coverage.json", coverage)

    coverage_rows = "\n".join(
        f"| {item['title']} (`{item['id']}`) | {item['coverage_status']} | {len(records_by_category[item['id']])} | {item['source_count']} |"
        for item in categories
    )
    route_matrix_rows = []
    route_columns = [
        ("Crimson Flower", "crimson_flower"),
        ("Silver Snow", "silver_snow"),
        ("Azure Moon", "azure_moon"),
        ("Verdant Wind", "verdant_wind"),
        ("Cindered Shadows DLC", "cindered_shadows_dlc"),
    ]
    for item in categories:
        values = []
        for route_label, route_id in route_columns:
            info = coverage["route_dlc_matrix"][item["id"]][route_id]
            if info["record_count"] is None:
                values.append("Not separated by source")
            else:
                values.append(f"Partial ({info['record_count']})")
        route_matrix_rows.append(f"| {item['title']} (`{item['id']}`) | " + " | ".join(values) + " |")
    (PACKAGE / "reports" / "coverage.md").write_text(
        "# Three Houses coverage\n\n"
        "This is a partial, source-traceable dataset. Category counts are extracted records, not proof of full game coverage.\n\n"
        "| Category | Status | Records | Sources |\n|---|---:|---:|---:|\n"
        + coverage_rows
        + "\n\n## Route and DLC chapter rows\n\n"
        + "\n".join(f"- {route['name_en']}: {route['chapter_count']} source-listed rows (partial)." for route in coverage["routes"])
        + f"\n- Cindered Shadows: {coverage['dlc'][0]['chapter_count']} source-listed rows (partial).\n"
        + f"\nSupport-pair entries with at least one unmatched character label: {coverage['support_character_link_gaps']}.\n"
        + "\n## Category by route/DLC coverage\n\n"
        + "Chapter and battle-summary counts are source rows only. Other categories are not split by the cited source pages, so no route/DLC availability is implied.\n\n"
        + "| Category | Crimson Flower | Silver Snow | Azure Moon | Verdant Wind | Cindered Shadows DLC |\n|---|---|---|---|---|---|\n"
        + "\n".join(route_matrix_rows)
        + "\n\n## Simplified Chinese localization\n\n"
        + "- Nintendo's official product page lists Simplified Chinese as a supported language; this confirms locale support, not individual game-term wording.\n"
        + f"- Source-backed community_unofficial zh-Hans-CN terms: {coverage['localization']['source_backed_community_unofficial_zh_hans_terms']} across {coverage['localization']['community_unofficial_entity_records']} entity records; official term-level zh-Hans terms: {coverage['localization']['verified_official_zh_hans_terms']}. {coverage['localization']['untranslated_title_fields']} of {coverage['localization']['total_entity_records']} entity display titles remain untranslated. Every original English title remains in `title_en`.\n"
        + f"- Community names cover {coverage['localization']['community_character_entity_records']} of {len(records_by_category['characters'])} character records; remaining character IDs without a sourced term: {', '.join(coverage['localization']['untranslated_character_stable_keys'])}. Mapping method and confidence counts are in `coverage.json`.\n"
        + "- The Moegirlpedia terms are licensed CC BY-NC-SA 4.0 (`commercial_use_allowed=false`, `share_alike_required=true`): use only in non-commercial distributions, attribute the source, and share adaptations under the same license; commercial distribution needs separate permission or removal of these terms.\n"
        + f"- Moegirlpedia access observations: {community_translation_capture['capture_method'] if community_translation_capture else 'not captured'}\n"
        + "- Chinese Wikipedia revision 95051516 was checked as a candidate only. Its raw wikitext uses Traditional Chinese; `variant=zh-cn` automatically converts the display to Simplified Chinese, so no terms were imported from that rendered page.\n"
        + "- Other entity names and terms remain in their source languages unless an eligible term-level Simplified Chinese source is found.\n"
        + "- Chinese category labels are project-defined navigation taxonomy, not game localization.\n",
        encoding="utf-8",
    )

    gap_notes = [
        "HuijiWiki returned HTTP 403 on ordinary access; no contents were fetched or bypassed.",
        f"Nintendo's official product page lists Simplified Chinese as a supported language but does not expose term-level wording. No official term-level zh-Hans strings were found. {localization_summary['term_count']} community_unofficial zh-Hans-CN terms from Moegirlpedia revision {community_translation_capture['revision'] if community_translation_capture else 'not captured'} cover {localization_summary['record_count']} entity records, including {localization_summary['character_record_count']} character records. Character records without a mapped sourced name: {', '.join(localization_summary['untranslated_character_stable_keys'])}. {community_translation_capture['capture_method'] if community_translation_capture else 'Moegirlpedia access observations were not captured.'} These terms are subject to CC BY-NC-SA 4.0 (`commercial_use_allowed=false`, `share_alike_required=true`), so commercial use requires separate permission or omission. Chinese Wikipedia revision 95051516 remains reference-only because its raw wikitext is Traditional Chinese and its Simplified `variant=zh-cn` view is automatic conversion. {coverage['localization']['untranslated_title_fields']} of {coverage['localization']['total_entity_records']} entity display titles remain untranslated; other names stay in source languages.",
        "Character roster coverage does not include every non-playable story character, full recruitment requirements, or route-by-route availability.",
        "Support records indicate a listed pair only. Rank thresholds, route conditions, conversation text, and support-point mechanics are not captured.",
        "Class data is a single Fire Emblem Wiki table excerpt; only the Myrmidon player growth modifier row received a Serenes Forest spot check. Character growths and full numerical cross-validation remain open.",
        "Weapons, items, crests, battalions, FE16-marked combat arts and magic, gifts, dishes, monastery shop/activity rows and chapter battle summaries are one-source excerpts. They are not independently confirmed and remain partial.",
        "Combat-art effect text is attached only on an exact name match; the general skills-page effect wording is not independently confirmed as specific to Three Houses.",
        "The captured gift tables exclude the Three Hopes table; the captured combat-art/magic rows include only a visible FE16 checkmark. Individual character spell-learning lists are not included.",
        "The chapter list supplies only summary fields. Tactical maps, coordinates, full enemy placement, reinforcement schedules and complete reward tables are unavailable in this package.",
        "The sources do not segment most equipment, class, battalion, skill, social or monastery records by route/DLC; those records must not be interpreted as available in every route.",
        media_inventory["gap_note"] + " No map files are listed in the current local asset manifest.",
        "Serenes Forest, Pegasus Knight, Saint Oracle, FE3H-AssetsDB and Triangle Attack remain reference-only where reuse permission is unclear; no complete tables from them were imported.",
        "The existing Tencent workbook is the unrelated 万紫千红 project and was excluded without copying its rows or artwork.",
        "HyperWiki is excluded under its crawler policy; Fedatamine is excluded because it serves game-extracted data; StrategyWiki returned HTTP 403.",
    ]
    _write_json(PACKAGE / "reports" / "gaps.json", {
        "generated_at": manifest["generated_at"],
        "open_categories": [item["id"] for item in categories if item["coverage_status"] not in {"complete", "not_applicable"}],
        "media": media_inventory,
        "gaps": gap_notes,
    })
    (PACKAGE / "reports" / "gaps.md").write_text(
        "# Known gaps and limits\n\n" + "\n".join(f"- {note}" for note in gap_notes) + "\n",
        encoding="utf-8",
    )
    return {
        "sources": manifest["source_count"],
        "categories": len(categories),
        "entries": sum(len(records) for records in records_by_category.values()),
        "characters": len(character_records),
        "recruitment_conditions": len(recruitment_records),
        "classes": len(class_records),
        "growth_profiles": len(growth_records),
        "routes": len(records_by_category["routes"]),
        "chapters_and_paralogues": len(chapter_records),
        "supports": len(support_records),
        "crests": len(crest_records),
        "battalions": len(battalion_records),
        "skills": len(skill_records),
        "equipment": len(equipment_records),
        "social": len(social_records),
        "monastery": len(monastery_records),
        "battles": len(battle_records),
        "mechanics": len(mechanic_records),
        "assets": media_inventory["asset_count"],
        "community_unofficial_zh_hans_terms": localization_summary["term_count"],
        "community_unofficial_zh_hans_entities": localization_summary["record_count"],
    }


if __name__ == "__main__":
    print(json.dumps(normalize(), ensure_ascii=False, indent=2))
