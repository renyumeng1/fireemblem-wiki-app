from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PACKAGE = REPO_ROOT / "data" / "games" / "engage"
SOURCE_ID = "local-engage-workbook"
STAT_NAMES = {
    "HP": "hp", "力量": "strength", "魔力": "magic", "技巧": "skill",
    "速度": "speed", "幸运": "luck", "防守": "defense", "魔防": "resistance",
    "体格": "build", "移动": "movement", "回避": "avoid", "命中": "hit",
    "必杀": "critical", "必避": "critical_avoid",
}

# All 33 worksheets are represented by a category; a worksheet may feed more
# than one category.  Start/header rows were read from the extracted workbook.
SHEET_CATEGORIES: dict[int, list[str]] = {
    1: ["index"], 2: ["characters"], 3: ["paralogues"], 4: ["emblems"],
    5: ["bond-rings"], 6: ["chapters"], 7: ["classes"], 8: ["skills"],
    9: ["weapons"], 10: ["items"], 11: ["country-investment"], 12: ["gifts"],
    13: ["characters"], 14: ["supports"], 15: ["characters"], 16: ["engravings"],
    17: ["animal-drops"], 18: ["classes"], 19: ["weapon-proficiency"],
    20: ["well-drops"], 21: ["achievements"], 22: ["relay-trials"],
    23: ["enchanter-effects"], 24: ["dlc-chapters"], 25: ["characters", "dlc-characters"],
    26: ["meals"], 27: ["game-mechanics"], 28: ["growth-mechanics"],
    29: ["community-skill-ratings"], 30: ["characters"], 31: ["bond-rings"], 32: ["characters"],
    33: ["classes"],
}

START_ROWS = {
    1: 2, 2: 4, 3: 3, 4: 3, 5: 4, 6: 2, 7: 5, 8: 3, 9: 4, 10: 3,
    11: 4, 12: 4, 13: 4, 14: 4, 15: 4, 16: 3, 17: 3, 18: 5, 19: 3,
    20: 4, 21: 4, 22: 3, 23: 5, 24: 3, 25: 4, 26: 7, 27: 4, 28: 3,
    29: 6, 30: 7, 31: 6, 32: 3, 33: 3,
}
HEADER_ROWS = {
    26: (5, 6), 27: (2, 3), 28: (1, 2), 29: (5, 6), 30: (5, 6),
    31: (5, 6), 32: (1, 2), 33: (1, 2),
}
TITLE_COLUMNS: dict[int, tuple[int, ...]] = {
    1: (2, 3, 4), 2: (4, 5), 3: (3, 2), 4: (3, 2), 5: (3, 2),
    6: (3, 4, 2), 7: (2,), 8: (4,), 9: (4,), 10: (3,), 11: (2, 3),
    12: (3,), 13: (4, 5), 14: (3,), 15: (4,), 16: (3, 2), 17: (3, 2),
    18: (2,), 19: (2, 3), 20: (4,), 21: (3, 6, 2), 22: (2,),
    23: (3, 8, 2), 24: (2, 3, 5), 25: (4,), 26: (3, 19),
    27: (2, 7, 17, 20), 28: (1, 2), 29: (4, 3), 30: (4, 3),
    31: (4, 3), 32: (1,), 33: (2,),
}

# Side-by-side tables are emitted as separate entries so unrelated facts do
# not get glued together into one searchable row.
SPLIT_BLOCKS: dict[int, list[tuple[str, tuple[int, ...], tuple[int, ...]]]] = {
    21: [
        ("condition-1", (2, 3, 4, 5), (3, 2)),
        ("condition-2", (2, 6, 7, 8), (6, 2)),
    ],
    27: [
        ("combat-formulas", (2, 3, 4, 5), (2, 3)),
        ("terrain-effects", tuple(range(7, 16)), (7,)),
        ("combat-terms", (17, 18), (17,)),
        ("system-rules", (20, 21, 22, 23), (20, 21)),
    ],
}

COMMUNITY_RATING_CATEGORY = "community-skill-ratings"
COMMUNITY_RATING_LAYOUT = (
    ("S", "S", "C5", ((6, 7, tuple(range(4, 19))),), 11),
    ("A", "A", "C8", ((9, 10, tuple(range(4, 19))),), 14),
    ("B", "B", "C11", ((12, 13, tuple(range(4, 19))),), 15),
    ("C", "C", "C14", (
        (15, 16, tuple(range(4, 15))),
        (18, 19, tuple(range(4, 11))),
    ), 15),
    ("low_value_not_recommended", "low_value_not_recommended", "O16", (
        (18, 19, tuple(range(15, 19))),
    ), 4),
    ("special", "special", "C20", (
        (21, 22, tuple(range(4, 13)) + (18,)),
        (24, 25, tuple(range(4, 15))),
        (27, 28, tuple(range(4, 11)) + tuple(range(12, 19))),
    ), 35),
)
COMMUNITY_RATING_COUNTS = {
    "S": 11, "A": 14, "B": 15, "C": 15,
    "low_value_not_recommended": 4, "special": 35,
}

CATEGORY_TITLES = {
    item["id"]: item["title"]
    for item in json.loads((DEFAULT_PACKAGE / "normalized" / "categories.json").read_text(encoding="utf-8"))
} if (DEFAULT_PACKAGE / "normalized" / "categories.json").is_file() else {}


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _column_name(number: int) -> str:
    label = ""
    while number:
        number, remainder = divmod(number - 1, 26)
        label = chr(65 + remainder) + label
    return label


def _column_number(reference: str) -> int:
    letters = "".join(character for character in reference if character.isalpha())
    number = 0
    for character in letters.upper():
        number = number * 26 + ord(character) - 64
    return number


def _address(row: int, column: int) -> str:
    return f"{_column_name(column)}{row}"


def _display(value: Any) -> str | None:
    if value is None or value == "":
        return None
    if isinstance(value, (str, int, float, bool)):
        return str(value).strip()
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _is_empty(value: Any) -> bool:
    return value is None or value == ""


def _parse_range(reference: str) -> tuple[int, int, int, int]:
    start, _, end = reference.partition(":")
    def parse(address: str) -> tuple[int, int]:
        letters = "".join(ch for ch in address if ch.isalpha())
        digits = "".join(ch for ch in address if ch.isdigit())
        return int(digits), _column_number(letters)
    row1, col1 = parse(start)
    row2, col2 = parse(end or start)
    return row1, col1, row2, col2


def _load_grid(table: dict[str, Any]) -> tuple[dict[tuple[int, int], dict[str, Any]], int, int]:
    grid: dict[tuple[int, int], dict[str, Any]] = {}
    max_row = max_col = 0
    for cell in table.get("cells", []):
        row, column = int(cell.get("row", 0)), int(cell.get("column", 0))
        if row <= 0 or column <= 0:
            continue
        grid[(row, column)] = {
            "value": cell.get("value"), "address": cell.get("address") or _address(row, column),
            "formula": cell.get("formula"), "type": cell.get("type"),
        }
        max_row, max_col = max(max_row, row), max(max_col, column)
    for merge in table.get("merges", []):
        row1, col1, row2, col2 = _parse_range(merge)
        anchor = grid.get((row1, col1))
        if anchor is None or _is_empty(anchor.get("value")):
            continue
        for row in range(row1, row2 + 1):
            for column in range(col1, col2 + 1):
                current = grid.get((row, column))
                if current is None or _is_empty(current.get("value")):
                    grid[(row, column)] = {**anchor, "address": anchor["address"], "merged_from": anchor["address"]}
    return grid, max_row, max_col


def _header_levels(grid: dict[tuple[int, int], dict[str, Any]], header_rows: tuple[int, ...], max_col: int) -> dict[int, tuple[str | None, ...]]:
    headers: dict[int, tuple[str | None, ...]] = {}
    for column in range(1, max_col + 1):
        parts = []
        for row in header_rows:
            cell = grid.get((row, column), {})
            value = _display(cell.get("value"))
            if value and (not parts or value != parts[-1]):
                parts.append(value)
        headers[column] = tuple(parts)
    return headers


def _header_names(levels: dict[int, tuple[str | None, ...]]) -> dict[int, str]:
    initial: dict[int, str] = {}
    for column, parts in levels.items():
        if not parts:
            continue
        initial[column] = " / ".join(parts)
    frequencies = Counter(initial.values())
    return {
        column: (f"{label} [{_column_name(column)}]" if frequencies[label] > 1 else label)
        for column, label in initial.items()
    }


def _row_fields(
    grid: dict[tuple[int, int], dict[str, Any]], row: int, columns: tuple[int, ...] | None,
    names: dict[int, str],
) -> tuple[dict[str, Any], dict[str, str], dict[str, Any], dict[int, Any]]:
    allowed = set(columns) if columns else None
    fields: dict[str, Any] = {}
    locations: dict[str, str] = {}
    formulas: dict[str, Any] = {}
    raw_by_col: dict[int, Any] = {}
    for (current_row, column), cell in sorted(grid.items(), key=lambda item: item[0][1]):
        if current_row != row or (allowed is not None and column not in allowed):
            continue
        value = cell.get("value")
        if _is_empty(value):
            continue
        key = names.get(column, f"列{_column_name(column)}")
        # Formula value is the cached result already; preserve formula/cache
        # separately in the normalized record and in the raw workbook table.
        fields[key] = value
        locations[key] = str(cell.get("address") or _address(row, column))
        raw_by_col[column] = value
        if cell.get("formula") is not None:
            formulas[str(cell.get("address") or _address(row, column))] = cell["formula"]
    return fields, locations, formulas, raw_by_col


def _text(value: Any) -> str | None:
    value = _display(value)
    return value if value else None


def _numeric(value: Any) -> int | float | None:
    if value is None:
        return None
    text = str(value).strip().replace(",", "").replace("G", "").replace("g", "")
    text = text.removesuffix("%").strip()
    try:
        parsed = float(text)
    except ValueError:
        return None
    return int(parsed) if parsed.is_integer() else parsed


def _base_header(levels: dict[int, tuple[str | None, ...]], column: int) -> str:
    parts = levels.get(column, ())
    return (parts[-1] if parts else "").strip()


def _stats(
    row_values: dict[int, Any], levels: dict[int, tuple[str | None, ...]],
    columns: range | tuple[int, ...] | list[int],
) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for column in columns:
        label = _base_header(levels, column)
        stat = STAT_NAMES.get(label)
        value = row_values.get(column)
        number = _numeric(value)
        if stat and number is not None:
            result[stat] = number
    return result


def _value_for_header(
    row_values: dict[int, Any], levels: dict[int, tuple[str | None, ...]],
    words: tuple[str, ...], *, exclude: tuple[str, ...] = (),
) -> Any:
    for column, parts in levels.items():
        label = " ".join(parts)
        if all(word in label for word in words) and not any(word in label for word in exclude):
            if column in row_values:
                return row_values[column]
    return None


def _row_range_locator(row: int, columns: tuple[int, ...] | None, raw_by_col: dict[int, Any]) -> tuple[int, int]:
    selected = [column for column in raw_by_col if columns is None or column in columns]
    if not selected:
        return 1, 1
    return min(selected), max(selected)


def _community_rating_records(
    root: Path, manifest: dict[str, Any], placements: list[dict[str, Any]],
    assets_by_id: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    sheet = next(
        (item for item in manifest.get("sheets", []) if int(item["sheet_number"]) == 29),
        None,
    )
    if sheet is None:
        raise ValueError("缺少 Engage 隐藏工作表 29")
    table = json.loads((root / sheet["json_file"]).read_text(encoding="utf-8"))
    grid, _, _ = _load_grid(table)
    sheet_name = str(sheet["name"])
    rating_title = _text(grid.get((3, 3), {}).get("value"))
    if not rating_title:
        raise ValueError("特技评级表缺少标题单元格 C3")
    version_match = re.search(r"\bVer\s*([\d.]+)", rating_title, re.IGNORECASE)
    note_match = re.search(r"[（(](.*?)[）)]", rating_title)
    rating_version = version_match.group(1) if version_match else None
    rating_note = note_match.group(1) if note_match else None
    source_sha = manifest["source"]["sha256_after"]
    category_title = CATEGORY_TITLES.get(COMMUNITY_RATING_CATEGORY, "社区特技评级")

    icons_by_name_cell: dict[tuple[int, int], dict[str, Any]] = {}
    chart_placements = []
    for placement in placements:
        sheet_number = int(placement.get("sheet_number", 0))
        from_cell = str(placement.get("from_cell") or "")
        if sheet_number == 29 and placement.get("asset_id"):
            anchor_row, anchor_column, _, _ = _parse_range(from_cell)
            key = (anchor_row + 1, anchor_column)
            if key in icons_by_name_cell:
                raise ValueError(f"评级特技图标锚点重复：{from_cell}")
            icons_by_name_cell[key] = placement
        if sheet_number == 1 and from_cell == "F38" and placement.get("asset_id"):
            chart_placements.append(placement)
    if len(chart_placements) != 1:
        raise ValueError(f"索引页 F38 应有唯一评级总图，实际为 {len(chart_placements)} 张")
    chart_placement = chart_placements[0]
    chart_asset_id = str(chart_placement["asset_id"])
    if chart_asset_id not in assets_by_id:
        raise ValueError(f"评级总图未登记在资源清单中：{chart_asset_id}")

    records: list[dict[str, Any]] = []
    observed_counts: Counter[str] = Counter()
    seen_name_cells: set[tuple[int, int]] = set()
    for group, display_group, header_cell, row_pairs, expected_count in COMMUNITY_RATING_LAYOUT:
        header_row, header_column, _, _ = _parse_range(header_cell)
        header_text = _text(grid.get((header_row, header_column), {}).get("value"))
        if not header_text:
            raise ValueError(f"评级表缺少分组标题：{header_cell}")
        group_label = header_text if group == "low_value_not_recommended" else (
            "特殊" if group == "special" else header_text
        )
        for name_row, cost_row, columns in row_pairs:
            for column in columns:
                name = _text(grid.get((name_row, column), {}).get("value"))
                cost = _text(grid.get((cost_row, column), {}).get("value"))
                if not name and not cost:
                    continue
                if not name or not cost:
                    raise ValueError(
                        f"评级表名称/SP 成本不成对：{_address(name_row, column)}/{_address(cost_row, column)}"
                    )
                cost_match = re.fullmatch(r"([0-9]+)\s*SP", cost, re.IGNORECASE)
                if not cost_match:
                    raise ValueError(f"评级表 SP 成本格式无效：{_address(cost_row, column)}={cost}")
                if (name_row, column) in seen_name_cells:
                    raise ValueError(f"评级条目单元格重复：{_address(name_row, column)}")
                seen_name_cells.add((name_row, column))
                icon_placement = icons_by_name_cell.get((name_row, column))
                if icon_placement is None:
                    raise ValueError(f"评级条目缺少对应图标：{_address(name_row, column)}")
                icon_asset_id = str(icon_placement["asset_id"])
                if icon_asset_id not in assets_by_id:
                    raise ValueError(f"评级特技图标未登记在资源清单中：{icon_asset_id}")

                cost_value = int(cost_match.group(1))
                name_cell = _address(name_row, column)
                cost_cell = _address(cost_row, column)
                source_locator = (
                    f"sheet-29!{name_cell},{cost_cell},{header_cell} ({sheet_name})"
                )
                rating_kind = (
                    "tier" if group in {"S", "A", "B", "C"}
                    else "not_recommended" if group == "low_value_not_recommended"
                    else "special"
                )
                display_label = (
                    f"{group}级" if group in {"S", "A", "B", "C"}
                    else group_label
                )
                details_effect = {
                    "claim_type": "community_opinion",
                    "rating_kind": rating_kind,
                    "rating_group": group,
                    "rating_group_label": group_label,
                    "rating_scope": rating_title,
                    "source_version": rating_version,
                    "source_note": rating_note,
                    "source_attribution": sheet_name,
                    "sp_cost": cost_value,
                    "official": False,
                    "skill_name_may_be_simplified": True,
                }
                source_payload = {
                    "source_sheet": sheet_name,
                    "source_row": name_row,
                    "source_cells": {
                        "skill_name": name_cell,
                        "sp_cost": cost_cell,
                        "rating_group": header_cell,
                    },
                    "claim_type": "community_opinion",
                    "normalization_status": "source_only_pending_review",
                }
                records.append({
                    "category_id": COMMUNITY_RATING_CATEGORY,
                    "stable_key": f"sheet-29:community-rating:{group}:{unicodedata.normalize('NFC', name).strip()}",
                    "title": name,
                    "title_en": None,
                    "subtitle": f"{display_label} · {cost}",
                    "description": json.dumps(source_payload, ensure_ascii=False, sort_keys=True),
                    "verification_status": "source_only",
                    "kind": "skill",
                    "details": {
                        "skill_type": "社区评级条目",
                        "weapon_type": None,
                        "requirement": None,
                        "cost": cost,
                        "activation_condition": None,
                        "effect": details_effect,
                    },
                    "sources": [{
                        "source_id": SOURCE_ID,
                        "locator": source_locator,
                        "source_sha256": source_sha,
                        "source_language": "zh-Hans",
                        "translation_status": "not_applicable",
                        "assertion_status": "unverified",
                    }],
                    "assets": [
                        {
                            "asset_id": icon_asset_id,
                            "role": "community_skill_rating_icon",
                            "locator": f"{sheet_name}!{icon_placement.get('from_cell') or ''}",
                        },
                        {
                            "asset_id": chart_asset_id,
                            "role": "community_skill_rating_chart",
                            "locator": f"索引!{chart_placement.get('from_cell') or 'F38'}",
                        },
                    ],
                    "relations": [],
                    "_sheet_number": 29,
                    "_row": name_row,
                })
                observed_counts[group] += 1
        if observed_counts[group] != expected_count:
            raise ValueError(
                f"评级表 {display_group} 条目数量变化：expected={expected_count}, actual={observed_counts[group]}"
            )

    if len(records) != 94 or dict(observed_counts) != COMMUNITY_RATING_COUNTS:
        raise ValueError(
            f"评级表分组计数不符：expected={COMMUNITY_RATING_COUNTS}, actual={dict(observed_counts)}"
        )
    if len(icons_by_name_cell) != len(records):
        raise ValueError(
            f"评级表图标锚点数不符：entries={len(records)}, icons={len(icons_by_name_cell)}"
        )
    return records


def _details(
    category_id: str, sheet_number: int, row_values: dict[int, Any],
    levels: dict[int, tuple[str | None, ...]], fields: dict[str, Any],
) -> tuple[str, dict[str, Any]]:
    if category_id in {"characters", "dlc-characters"}:
        base_stats: dict[str, Any] = {}
        growths: dict[str, Any] = {}
        caps: dict[str, Any] = {}
        if sheet_number == 2:
            growths = _stats(row_values, levels, range(12, 21))
            base_stats = _stats(row_values, levels, range(23, 32))
        elif sheet_number == 13:
            caps = _stats(row_values, levels, range(6, 13))
        elif sheet_number == 25:
            base_stats = _stats(row_values, levels, range(17, 27))
        detail = {
            "affiliation": _text(row_values.get(2)),
            "join_condition": _text(_value_for_header(row_values, levels, ("加入条件",))),
            "route_availability": _text(_value_for_header(row_values, levels, ("加入章节",))),
            "base_stats": base_stats or None,
            "growths": growths or None,
            "caps": caps or None,
        }
        return "character", detail
    if category_id == "classes":
        growth_columns = range(8, 17) if sheet_number == 7 else (range(7, 16) if sheet_number == 33 else range(4, 13))
        cap_columns = range(18, 27) if sheet_number == 7 else range(18, 27)
        base_columns = range(4, 13) if sheet_number == 18 else ()
        detail = {
            "tier": _text(row_values.get(3)),
            "movement": _numeric(row_values.get(4)) if sheet_number == 7 else None,
            "movement_type": _text(row_values.get(6)) if sheet_number == 33 else None,
            "weapon_ranks": {"source_text": _text(row_values.get(5))} if row_values.get(5) is not None else None,
            "base_stats": _stats(row_values, levels, base_columns) or None,
            "growths": _stats(row_values, levels, growth_columns) or None,
            "caps": _stats(row_values, levels, cap_columns) or None,
            "promotion_requirement": _text(_value_for_header(row_values, levels, ("必要资质",))),
            "class_skill": _text(_value_for_header(row_values, levels, ("习得特技",))),
        }
        return "class", detail
    if category_id == "skills":
        return "skill", {
            "skill_type": _text(row_values.get(2)),
            "weapon_type": _text(_value_for_header(row_values, levels, ("武器类型",))),
            "requirement": _text(_value_for_header(row_values, levels, ("要求",)))
                or _text(_value_for_header(row_values, levels, ("角色要求",)))
                or _text(_value_for_header(row_values, levels, ("兵种要求",))),
            "cost": _text(_value_for_header(row_values, levels, ("SP",))),
            "activation_condition": _text(_value_for_header(row_values, levels, ("发动条件",))),
            "effect": fields,
        }
    if category_id in {"weapons", "items", "well-drops"}:
        parent = "武器基础信息"
        might = _numeric(_value_for_header(row_values, levels, ("威力",), exclude=("满强化",)))
        hit = _numeric(_value_for_header(row_values, levels, ("命中",), exclude=("满强化",)))
        critical = _numeric(_value_for_header(row_values, levels, ("必杀",), exclude=("满强化",)))
        weight = _numeric(_value_for_header(row_values, levels, ("重量",), exclude=("满强化",)))
        durability = _numeric(_value_for_header(row_values, levels, ("使用次数",)))
        price = _numeric(_value_for_header(row_values, levels, ("价格",)))
        if sheet_number == 9:
            might, hit, critical, weight = map(_numeric, (row_values.get(8), row_values.get(9), row_values.get(10), row_values.get(11)))
            price = _numeric(row_values.get(7))
        effect = _value_for_header(row_values, levels, ("效果",)) or _value_for_header(row_values, levels, ("特效",))
        acquisition = _value_for_header(row_values, levels, ("获取途径",)) or _value_for_header(row_values, levels, ("获取方法",))
        if sheet_number == 9:
            effect, acquisition = row_values.get(14), row_values.get(6)
        return "equipment", {
            "equipment_type": _text(row_values.get(2)) if sheet_number == 9 else CATEGORY_TITLES.get(category_id, category_id),
            "subtype": _text(row_values.get(3)) if sheet_number == 9 else None,
            "rank": _text(_value_for_header(row_values, levels, ("武器等级",))) or _text(_value_for_header(row_values, levels, ("所需武器等级",))),
            "might": might, "hit": hit, "critical": critical, "weight": weight,
            "durability": durability, "price": price, "effect": _text(effect), "acquisition": _text(acquisition),
            "extra_stats": {"source_fields": fields, "primary_header_group": parent if sheet_number == 9 else None},
        }
    if category_id == "bond-rings":
        stats = _stats(row_values, levels, range(6, 15))
        return "bond_ring", {
            "emblem_name": _text(row_values.get(2)) or "未知",
            "ring_rank": _text(row_values.get(5)) or "未知",
            "stat_bonuses": stats,
            "skill_name": _text(row_values.get(15)),
            "skill_effect": _text(row_values.get(16)),
        }
    if category_id in {"chapters", "paralogues", "dlc-chapters"}:
        return "chapter", {
            "route": "dlc" if category_id == "dlc-chapters" else "main_or_paralogue",
            "chapter_number": _text(row_values.get(2)),
            "location": _text(_value_for_header(row_values, levels, ("触发地点",))) or _text(row_values.get(5)),
            "objective": _text(_value_for_header(row_values, levels, ("触发条件",))) or _text(_value_for_header(row_values, levels, ("目标",))),
            "defeat_condition": None,
            "recommended_level": None,
            "rewards": fields,
        }
    if category_id == "emblems":
        return "emblem", {"profile": json.dumps(fields, ensure_ascii=False, sort_keys=True)}
    if category_id == "achievements":
        return "achievement", {
            "achievement_type": _text(row_values.get(2)),
            "requirement": _text(_value_for_header(row_values, levels, ("达成条件",))) or _text(row_values.get(3)),
            "reward": _text(_value_for_header(row_values, levels, ("奖励",))) or _text(row_values.get(4)),
            "chapter": None,
            "notes": json.dumps(fields, ensure_ascii=False, sort_keys=True),
        }
    if category_id in {"game-mechanics", "growth-mechanics"}:
        formula_values = {key: value for key, value in fields.items() if "公式" in key or "計算" in key or "计算" in key}
        return "mechanic", {
            "mechanic_type": CATEGORY_TITLES.get(category_id, category_id),
            "game_version": "Engage; workbook revision/version not stated",
            "rule_description": json.dumps(fields, ensure_ascii=False, sort_keys=True),
            "parameters": fields,
            "formula_text": json.dumps(formula_values, ensure_ascii=False, sort_keys=True) if formula_values else None,
        }
    if category_id == "supports":
        return "character", {
            "support_availability": {str(key): value for key, value in fields.items()},
        }
    return "activity", {
        "activity_type": CATEGORY_TITLES.get(category_id, category_id),
        "availability_condition": None,
        "options": fields,
        "effects": fields,
    }


def _make_records(
    root: Path, manifest: dict[str, Any], categories: list[dict[str, Any]],
) -> tuple[dict[str, list[dict[str, Any]]], dict[int, int], list[dict[str, Any]]]:
    category_titles = {category["id"]: category["title"] for category in categories}
    assets_manifest = json.loads((root / "assets" / "manifest.json").read_text(encoding="utf-8"))
    placements = json.loads((root / "raw" / "image-placements.json").read_text(encoding="utf-8"))
    placements_by_sheet_row: dict[tuple[int, int], list[dict[str, Any]]] = defaultdict(list)
    for placement in placements:
        match = re.search(r"\d+", str(placement.get("from_cell") or ""))
        if match and placement.get("asset_id"):
            placements_by_sheet_row[(int(placement["sheet_number"]), int(match.group()))].append(placement)
    assets_by_id = {asset["asset_id"]: asset for asset in assets_manifest.get("assets", [])}
    source_sha = manifest["source"]["sha256_after"]
    output: dict[str, list[dict[str, Any]]] = {category["id"]: [] for category in categories}
    per_sheet_counts: dict[int, int] = Counter()
    warnings: list[dict[str, Any]] = []

    for sheet in manifest.get("sheets", []):
        number = int(sheet["sheet_number"])
        table = json.loads((root / sheet["json_file"]).read_text(encoding="utf-8"))
        grid, max_row, max_col = _load_grid(table)
        header_rows = HEADER_ROWS.get(number, (2, 3))
        levels = _header_levels(grid, header_rows, max_col)
        names = _header_names(levels)
        start = START_ROWS.get(number, max(header_rows) + 1)
        mapped_categories = SHEET_CATEGORIES[number]

        # Sheet 1 is a workbook navigation page; its cells remain fully
        # available in raw JSON and workbook_cells, not as app data entities.
        # Sheet 32 is a formula-driven character simulator: preserve its
        # formulas/cached values only and do not claim its examples are facts.
        if number == 29:
            warnings.append({
                "sheet_number": number, "sheet_name": sheet["name"],
                "kind": "preserved_raw_with_community_projection",
                "reason": "隐藏特技评级附录已拆分为社区评价条目；原表格单元格、94 个图标锚点和索引页评级总图继续保留。",
            })
            continue
        if number in {1, 32, 30}:
            warnings.append({
                "sheet_number": number, "sheet_name": sheet["name"],
                "kind": "preserved_raw_only",
                "reason": "导航页、交互式模拟器或纯图像评级内容未作为独立事实实体导入；原始工作表和图片锚点完整保留。",
            })
            continue

        for row in range(start, max_row + 1):
            groups = SPLIT_BLOCKS.get(number)
            row_groups = groups or [("row", tuple(range(1, max_col + 1)), TITLE_COLUMNS.get(number, (2,)))]
            for group_id, columns, title_columns in row_groups:
                fields, field_locations, formulas, row_values = _row_fields(grid, row, columns, names)
                if not fields:
                    continue
                title = next((_text(row_values.get(column)) for column in title_columns if _text(row_values.get(column))), None)
                if not title:
                    # Use a non-numeric source label as a last resort; avoid
                    # creating rows from blank formula/cache placeholders.
                    title = next((
                        _text(value) for column, value in sorted(row_values.items())
                        if isinstance(value, str) and _text(value) and not _numeric(value)
                    ), None)
                if not title or title in {"--", "-", "图片", "图标", "头像", "专属职业", "兵种名称", "角色名称"}:
                    continue
                nonempty_values = [_display(value) for value in row_values.values() if not _is_empty(value)]
                unique_values = set(nonempty_values)
                if len(unique_values) == 1 and next(iter(unique_values)) in {title, "专属职业"}:
                    continue

                for category_id in mapped_categories:
                    # Split sheets feed one category each; sheet 25 is the
                    # only intentional multi-category source.
                    if number == 25 and category_id == "characters":
                        category_title_cols = (4,)
                    else:
                        category_title_cols = title_columns
                    category_title = next((_text(row_values.get(column)) for column in category_title_cols if _text(row_values.get(column))), title)
                    subtitle = None
                    title_en = None
                    if number == 2:
                        title_en = _text(row_values.get(5))
                        subtitle = title_en
                    elif number == 25 and category_id == "dlc-characters":
                        subtitle = _text(row_values.get(5))
                    elif number in {3, 5, 11, 16, 19}:
                        subtitle = " / ".join(filter(None, (_text(row_values.get(2)), _text(row_values.get(5))))) or None

                    first_col, last_col = _row_range_locator(row, columns, row_values)
                    locator = f"sheet-{number:02d}!{_address(row, first_col)}:{_address(row, last_col)} ({sheet['name']})"
                    category_fields = dict(fields)
                    category_locations = dict(field_locations)
                    kind, details = _details(category_id, number, row_values, levels, category_fields)
                    if category_id == "supports" and number == 14:
                        support = {
                            _text(grid.get((3, column), {}).get("value")) or f"列{_column_name(column)}": value
                            for column, value in row_values.items() if column >= 4
                        }
                        details = {"support_availability": support}
                        category_fields = {"角色": category_title, "支援关系与状态": support}
                    if category_id == "gifts" and number == 12:
                        preferences = {
                            _text(grid.get((3, column), {}).get("value")) or f"列{_column_name(column)}": value
                            for column, value in row_values.items() if column >= 4
                        }
                        kind = "activity"
                        details = {"activity_type": "赠礼偏好矩阵", "options": preferences, "effects": preferences}
                        category_fields = {"角色": category_title, "赠礼偏好": preferences}

                    # Chapter sheet 6 is a link/index, not a chapter table.
                    if number == 6:
                        details = {"route": "main_or_paralogue", "objective": json.dumps(category_fields, ensure_ascii=False)}

                    source_links = [{
                        "source_id": SOURCE_ID, "locator": locator, "source_sha256": source_sha,
                        "source_language": "zh-Hans", "translation_status": "not_applicable",
                        "assertion_status": "unverified",
                    }]
                    asset_links = []
                    for placement in placements_by_sheet_row.get((number, row), []):
                        asset = assets_by_id.get(placement["asset_id"])
                        if not asset:
                            continue
                        roles = asset.get("candidate_roles") or []
                        asset_links.append({
                            "asset_id": asset["asset_id"],
                            "role": roles[0] if roles else "workbook_image",
                            "locator": f"{sheet['name']}!{placement.get('from_cell') or ''}",
                        })
                    if number == 25 and category_id == "dlc-characters":
                        pass
                    # A source row can feed multiple categories only when the
                    # workbook explicitly maps the same row to both.
                    stable_base = f"sheet-{number:02d}:{unicodedata.normalize('NFC', category_title).strip()}"
                    if subtitle:
                        stable_base += f":{unicodedata.normalize('NFC', subtitle).strip()}"
                    if group_id != "row":
                        stable_base += f":{group_id}"
                    description_payload = {
                        "source_sheet": sheet["name"], "source_row": row,
                        "source_cells": category_locations,
                        "fields": category_fields,
                        "formula_cache": formulas,
                        "normalization_status": "source_only_pending_review",
                    }
                    record = {
                        "category_id": category_id,
                        "stable_key": stable_base,
                        "title": category_title,
                        "title_en": title_en,
                        "subtitle": subtitle,
                        "description": json.dumps(description_payload, ensure_ascii=False, sort_keys=True),
                        "verification_status": "source_only",
                        "kind": kind,
                        "details": details,
                        "sources": source_links,
                        "assets": asset_links,
                        "relations": [],
                        "_sheet_number": number,
                        "_row": row,
                    }
                    output[category_id].append(record)
                    per_sheet_counts[number] += 1

    if COMMUNITY_RATING_CATEGORY not in output:
        raise ValueError(f"分类清单缺少 {COMMUNITY_RATING_CATEGORY}")
    community_records = _community_rating_records(root, manifest, placements, assets_by_id)
    output[COMMUNITY_RATING_CATEGORY].extend(community_records)
    per_sheet_counts[29] += len(community_records)

    # Resolve rare duplicate labels deterministically, retaining row evidence
    # only as a collision suffix rather than using row numbers as identity.
    for category_id, records in output.items():
        used: set[str] = set()
        for record in records:
            key = record["stable_key"]
            if key in used:
                key = f"{key}:row-{record['_row']}"
                suffix = 2
                while key in used:
                    key = f"{record['stable_key']}:row-{record['_row']}-{suffix}"
                    suffix += 1
                record["stable_key"] = key
            used.add(key)
            record.pop("_sheet_number", None)
            record.pop("_row", None)
    return output, per_sheet_counts, warnings


def _sheet_source_index_entry(summary: dict[str, Any]) -> dict[str, Any]:
    number = int(summary["sheet_number"])
    return {
        "sheet_id": f"sheet-{number:02d}",
        "sheet_number": number,
        "name": summary["name"],
        "visibility": summary["state"],
        "source_dimension": summary.get("dimension"),
        "observed_range": summary.get("observed_range"),
        "raw_json": summary["json_file"],
        "raw_json_sha256": summary["json_sha256"],
        "raw_csv": summary["csv_file"],
        "formula_count": summary["formula_count"],
        "cell_count": summary["explicit_cell_count"],
        "merge_count": summary["merge_count"],
        "image_placement_count": summary["image_placement_count"],
    }


def _category_assets_index(
    category_id: str, assets_manifest: dict[str, Any], placements: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    asset_roles: dict[str, set[str]] = defaultdict(set)
    for placement in placements:
        asset_id = placement.get("asset_id")
        if not asset_id:
            continue
        sheet_number = int(placement.get("sheet_number", 0))
        if category_id == COMMUNITY_RATING_CATEGORY:
            if sheet_number == 29:
                asset_roles[asset_id].add("community_skill_rating_icon_candidate")
            elif sheet_number == 1 and placement.get("from_cell") == "F38":
                asset_roles[asset_id].add("community_skill_rating_chart")
        elif category_id == "skills" and sheet_number == 8:
            asset_roles[asset_id].add("skill_icon_candidate")

    assets = assets_manifest if isinstance(assets_manifest, list) else assets_manifest.get("assets", [])
    result = []
    for asset in assets:
        asset_id = asset.get("asset_id")
        roles = asset_roles.get(asset_id or "")
        if not roles:
            continue
        result.append({
            "asset_id": asset_id,
            "sha256": asset.get("sha256") or asset.get("source_sha256"),
            "original_file": asset.get("original_file") or asset.get("file") or asset.get("source_file"),
            "app_file": asset.get("app_file"),
            "candidate_roles": sorted(roles),
            "classification_status": asset.get("classification_status", "pending_visual_review"),
            "source_locations": asset.get("source_locations", []),
        })
    return sorted(result, key=lambda item: item["asset_id"] or "")


def normalize(package_root: Path) -> dict[str, Any]:
    root = package_root.resolve()
    if root != DEFAULT_PACKAGE.resolve():
        raise ValueError(f"Engage package path must be {DEFAULT_PACKAGE.resolve()}")
    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("game_id") != "engage" or len(manifest.get("sheets", [])) != 33:
        raise ValueError("需要完整的 Engage 33 工作表提取包")
    categories_path = root / "normalized" / "categories.json"
    categories = json.loads(categories_path.read_text(encoding="utf-8"))
    ocr_summary = json.loads((root / "reports" / "ocr-summary.json").read_text(encoding="utf-8"))
    assets_manifest = json.loads((root / "assets" / "manifest.json").read_text(encoding="utf-8"))
    placements = json.loads((root / "raw" / "image-placements.json").read_text(encoding="utf-8"))
    records_by_category, per_sheet_counts, warnings = _make_records(root, manifest, categories)

    category_counts: dict[str, int] = {}
    for category in categories:
        category_id = category["id"]
        records = records_by_category.get(category_id, [])
        category_dir = root / "normalized" / category_id
        category_dir.mkdir(parents=True, exist_ok=True)
        payload = {"schema_version": 1, "game_id": "engage", "category_id": category_id, "records": records}
        record_bytes = _json_bytes(payload)
        (category_dir / "records.json").write_bytes(record_bytes)
        category_counts[category_id] = len(records)
        category["record_count"] = len(records)
        category["coverage_status"] = "not_applicable" if not category.get("applicable", True) else ("partial" if records else "pending")
        index_path = category_dir / "source-index.json"
        if category_id in {"skills", COMMUNITY_RATING_CATEGORY}:
            allowed_sheets = {
                number for number, mapped in SHEET_CATEGORIES.items() if category_id in mapped
            }
            existing_index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.is_file() else {}
            source_rows = [
                source for source in existing_index.get("sources", [])
                if int(source.get("sheet_number", 0)) in allowed_sheets
            ]
            if not source_rows:
                source_rows = [
                    _sheet_source_index_entry(summary)
                    for summary in manifest["sheets"]
                    if int(summary["sheet_number"]) in allowed_sheets
                ]
            existing_index.update({
                "schema_version": 1,
                "category_id": category_id,
                "title": category["title"],
                "normalization_status": "source_only_pending_review",
                "sources": source_rows,
                "assets_index": "assets-index.json",
                "records_file": "records.json",
                "record_count": len(records),
            })
            index_path.write_bytes(_json_bytes(existing_index))
            asset_index_path = category_dir / "assets-index.json"
            asset_index_path.write_bytes(_json_bytes(
                _category_assets_index(category_id, assets_manifest, placements)
            ))
        elif index_path.is_file():
            source_index = json.loads(index_path.read_text(encoding="utf-8"))
            source_index["normalization_status"] = "source_only_pending_review"
            source_index["records_file"] = "records.json"
            source_index["record_count"] = len(records)
            index_path.write_bytes(_json_bytes(source_index))

    human_review = ocr_summary.get("human_review")
    ocr_coverage = {key: value for key, value in ocr_summary.items() if key != "processed_asset_ids"}
    category_bytes = _json_bytes(categories)
    categories_path.write_bytes(category_bytes)
    manifest.setdefault("coverage", {})["normalization"] = "source_only_pending_review"
    manifest["coverage"]["ocr"] = ocr_coverage
    manifest["coverage"]["visual_review"] = human_review or "pending"
    manifest["coverage"]["category_record_count"] = sum(category_counts.values())
    manifest["coverage"]["category_counts"] = category_counts
    manifest["normalized_categories_sha256"] = _sha256(category_bytes)
    manifest["updated_at"] = datetime.now(timezone.utc).isoformat()
    manifest_path.write_bytes(_json_bytes(manifest))

    coverage_report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "game_id": "engage",
        "source": {
            "file": manifest["source"]["file_name"],
            "sha256": manifest["source"]["sha256_after"],
            "rights_status": "unknown",
        },
        "workbook": manifest["coverage"],
        "sheets": [
            {"sheet_number": item["sheet_number"], "name": item["name"], "visibility": item["state"],
             "rows_normalized": per_sheet_counts.get(item["sheet_number"], 0),
             "raw_json": item["json_file"], "formula_count": item["formula_count"],
             "image_placement_count": item["image_placement_count"]}
            for item in manifest["sheets"]
        ],
        "categories": [
            {"id": item["id"], "title": item["title"], "applicable": item["applicable"],
             "coverage_status": item["coverage_status"], "source_count": item["source_count"],
             "record_count": item["record_count"]}
            for item in categories
        ],
        "ocr": ocr_coverage,
        "normalization_status": "source_only_pending_review",
        "warnings": warnings,
    }
    (root / "reports" / "coverage.json").write_bytes(_json_bytes(coverage_report))
    (root / "reports" / "normalization.json").write_bytes(_json_bytes({
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "category_counts": category_counts,
        "total_records": sum(category_counts.values()),
        "records_by_sheet": {str(key): value for key, value in sorted(per_sheet_counts.items())},
        "warnings": warnings,
    }))
    review_counts = (human_review or {}).get("review_status_counts", {})
    pending_ocr = (human_review or {}).get("assets_pending_review", ocr_summary.get("assets_in_scope", 0))
    reviewed_assets = (human_review or {}).get("assets_reviewed", 0)
    visual_review_status = (
        f"{reviewed_assets} 张图片已完成人工视觉归类"
        if reviewed_assets >= ocr_summary.get("assets_in_scope", 0)
        else f"{reviewed_assets}/{ocr_summary.get('assets_in_scope', 0)} 张图片已完成人工视觉归类"
    )
    gaps = [
        "Engage 工作簿来源权利未评估：本地保留原图和 WebP 副本不构成再分发许可。",
        f"OCR 扫描了 {ocr_summary.get('assets_in_scope', 0)} 张唯一图片，其中 {ocr_summary.get('assets_with_ocr_text', 0)} 张检测到文字、{ocr_summary.get('detected_text_blocks', 0)} 个文本块；人工复核状态为 {review_counts}，仍有 {pending_ocr} 项待复核。OCR 转录不作为已确认游戏事实。",
        f"视觉复核：{visual_review_status}；不可读内容保留在缺口中，原图、App 副本、哈希和工作表锚点均保留。",
        "第 06 张工作表只有攻略合集导航入口，没有关卡表格或地图数据；具体章节与地图需补充可核验来源。",
        "第 32 张隐藏工作表是公式驱动的角色信息模拟器，保留在原始工作表/公式数据中，未把其示例输出误作静态事实。",
        "所有从工作簿生成的语义条目标记为 source_only；未完成独立来源交叉核验的名称、数值、解锁条件和关系不标记 confirmed。",
        "工作簿的公式缓存值按原样保存在 workbook_cells；本步骤未重算公式，空缓存仍为未知。",
    ]
    (root / "reports" / "gaps.md").write_text(
        "# Engage 资料缺口与复核状态\n\n" + "\n".join(f"- {item}" for item in gaps) + "\n",
        encoding="utf-8",
    )
    readme = """# Fire Emblem Engage data package

This package keeps the supplied workbook read-only and separates raw extraction, normalized records, media, reports, and the App-facing SQLite database.

## Directories

- `raw/sheets/` and `raw/csv/`: all 33 extracted worksheets, including hidden sheets, with cell coordinates, cached values, formulas, merges, and visibility.
- `raw/image-placements.json`: every picture placement and anchor, including duplicate references.
- `assets/original/`: content-addressed original image bytes.
- `assets/app/`: lossless WebP copies, preserved at their original dimensions.
- `normalized/<category>/records.json`: row-sourced records with field-to-cell provenance and asset links.
- `database/content.db`: independent SQLite database using `tools/game-data/schema/game-data-v1.sql`.
- `reports/ocr-review.csv`: OCR candidates for manual review; none have been promoted to confirmed facts.
- `reports/coverage.json`, `reports/gaps.md`, and `reports/database.json`: scope, limits, and generated database summary.

## Build

From the repository root:

```powershell
py -3 -X utf8 -B tools/game-data/normalize_engage.py
py -3 -X utf8 -B tools/game-data/build_database.py --game engage
```

## Evidence and rights

Each normalized row stores its source worksheet and row/cell locator. Workbook values are `source_only` until independently checked. OCR is candidate text only and remains `pending_review`. Image classification is pending visual review. Workbook image rights are unknown, and the package marks them as not cleared for redistribution.
"""
    readme = readme.replace(
        "OCR is candidate text only and remains " + chr(96) + "pending_review" + chr(96) + ".",
        "OCR text is a transcription aid, not a confirmed game fact.",
    )
    readme = readme.replace("Image classification is pending visual review.", visual_review_status + ".")
    (root / "README.md").write_text(readme, encoding="utf-8")
    return {
        "game_id": "engage",
        "categories": len(categories),
        "total_records": sum(category_counts.values()),
        "category_counts": category_counts,
        "sheets": len(manifest["sheets"]),
        "ocr_assets": ocr_summary.get("assets_in_scope", 0),
        "ocr_assets_pending_review": pending_ocr,
        "normalization_status": "source_only_pending_review",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="将 Engage 工作簿来源行规范化为分类 JSON 记录")
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    arguments = parser.parse_args()
    try:
        result = normalize(arguments.package)
    except (OSError, ValueError, json.JSONDecodeError, KeyError) as error:
        print(f"Engage 规范化失败：{error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
