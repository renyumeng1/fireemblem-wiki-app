from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PACKAGE = REPO_ROOT / "data" / "games" / "engage"
GENERATOR = "fireemblem/tools/game-data/organize-engage-categories"

# One workbook sheet can contribute to more than one app-facing category.
SHEET_CATEGORIES: dict[int, list[str]] = {
    1: ["index"],
    2: ["characters"],
    3: ["paralogues"],
    4: ["emblems"],
    5: ["bond-rings"],
    6: ["chapters"],
    7: ["classes"],
    8: ["skills"],
    9: ["weapons"],
    10: ["items"],
    11: ["country-investment"],
    12: ["gifts"],
    13: ["characters"],
    14: ["supports"],
    15: ["characters"],
    16: ["engravings"],
    17: ["animal-drops"],
    18: ["classes"],
    19: ["weapon-proficiency"],
    20: ["well-drops"],
    21: ["achievements"],
    22: ["relay-trials"],
    23: ["enchanter-effects"],
    24: ["dlc-chapters"],
    25: ["characters", "dlc-characters"],
    26: ["meals"],
    27: ["game-mechanics"],
    28: ["growth-mechanics"],
    29: ["community-skill-ratings"],
    30: ["characters"],
    31: ["bond-rings"],
    32: ["characters"],
    33: ["classes"],
}

CATEGORY_INFO = {
    "index": ("索引", "工作簿目录与导航页。", False),
    "characters": ("角色", "可加入角色、能力上限、隐藏等级、评价及模拟器相关数据。", True),
    "paralogues": ("外传", "外传任务与解锁信息。", True),
    "emblems": ("纹章士", "纹章士资料与相关效果。", True),
    "bond-rings": ("牵绊戒指", "牵绊戒指资料、能力与特技评价。", True),
    "chapters": ("章节与关卡", "主线关卡索引。", True),
    "classes": ("职业", "职业能力、成长和职业数据。", True),
    "skills": ("技能与特技", "游戏内特技名称、类别、SP 消耗和效果说明；社区评价单独归类。", True),
    "community-skill-ratings": ("社区特技评级", "工作簿隐藏附录中的社区实用度评价与 SP 成本，不代表官方结论。", True),
    "weapons": ("武器", "武器属性与武器资质查询。", True),
    "items": ("道具与材料", "道具与物品资料。", True),
    "country-investment": ("国家投资", "国家投资等级、花费与奖励。", True),
    "gifts": ("赠礼", "角色赠礼与互动资料。", True),
    "supports": ("支援关系", "角色支援关系与效果。", True),
    "engravings": ("刻印与附魔", "纹章刻印和附魔效果。", True),
    "enchanter-effects": ("附魔师效果", "附魔师相关技能与效果。", True),
    "animal-drops": ("牧场产出", "动物与牧场产出获取信息。", True),
    "weapon-proficiency": ("武器资质", "武器资质获取速查。", True),
    "well-drops": ("神秘水井产出", "神秘水井物品掉落与获取信息。", True),
    "achievements": ("成就", "成就条件与奖励。", True),
    "relay-trials": ("续战与奖励", "续战称号与奖励。", True),
    "dlc-chapters": ("DLC 关卡", "邪龙之章关卡数据。", True),
    "dlc-characters": ("DLC 角色", "邪龙之章终章角色资料。", True),
    "meals": ("料理", "料理效果与角色互动资料。", True),
    "game-mechanics": ("计算规则", "工作簿中列出的计算公式和相关说明。", True),
    "growth-mechanics": ("成长机制", "固定成长机制附录。", True),
}

IMAGE_ROLE_BY_SHEET = {
    2: "character_portrait_candidate",
    4: "emblem_illustration_or_icon_candidate",
    5: "bond_ring_portrait_candidate",
    8: "skill_icon_candidate",
    9: "weapon_icon_candidate",
    10: "item_icon_candidate",
    12: "character_portrait_candidate",
    13: "character_portrait_candidate",
    14: "support_matrix_visual_candidate",
    16: "engraving_icon_candidate",
    23: "enchanter_effect_visual_candidate",
    24: "dlc_map_or_guide_visual_candidate",
    25: "dlc_character_portrait_candidate",
    26: "meal_visual_candidate",
    29: "community_skill_rating_icon_candidate",
    30: "character_rating_visual_candidate",
    31: "bond_ring_rating_visual_candidate",
}

CATEGORY_ORDER = (
    "index", "characters", "paralogues", "emblems", "bond-rings", "chapters",
    "classes", "skills", "community-skill-ratings", "weapons", "items",
    "country-investment", "gifts", "supports", "engravings", "animal-drops",
    "weapon-proficiency", "well-drops", "achievements", "relay-trials",
    "enchanter-effects", "dlc-chapters", "dlc-characters", "meals",
    "game-mechanics", "growth-mechanics",
)


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def organize(package_root: Path) -> dict[str, Any]:
    root = package_root.resolve()
    if root != DEFAULT_PACKAGE.resolve():
        raise ValueError(f"Engage output must be {DEFAULT_PACKAGE.resolve()}")
    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("game_id") != "engage":
        raise ValueError("manifest.game_id must be engage")
    if len(manifest.get("sheets", [])) != 33:
        raise ValueError("Expected the complete 33-sheet extraction before category mapping")

    categories: dict[str, dict[str, Any]] = {}
    sheets_by_category: dict[str, list[dict[str, Any]]] = {}
    for summary in manifest["sheets"]:
        number = summary["sheet_number"]
        if number not in SHEET_CATEGORIES:
            raise ValueError(f"No category mapping for sheet {number}")
        raw_path = root / summary["json_file"]
        table = json.loads(raw_path.read_text(encoding="utf-8"))
        cells = table.get("cells", [])
        coordinates = [
            (cell["row"], cell["column"])
            for cell in cells
            if isinstance(cell.get("row"), int) and isinstance(cell.get("column"), int)
        ]
        observed_range = None
        if coordinates:
            min_row = min(row for row, _ in coordinates)
            max_row = max(row for row, _ in coordinates)
            min_column = min(column for _, column in coordinates)
            max_column = max(column for _, column in coordinates)

            def column_name(value: int) -> str:
                result = ""
                while value:
                    value, remainder = divmod(value - 1, 26)
                    result = chr(65 + remainder) + result
                return result

            start = f"{column_name(min_column)}{min_row}"
            end = f"{column_name(max_column)}{max_row}"
            observed_range = start if start == end else f"{start}:{end}"
        table["observed_range"] = observed_range
        raw_bytes = _json_bytes(table)
        raw_path.write_bytes(raw_bytes)
        summary["observed_range"] = observed_range
        summary["json_sha256"] = _sha256(raw_bytes)
        summary["json_bytes"] = len(raw_bytes)

        for category_id in SHEET_CATEGORIES[number]:
            title, description, applicable = CATEGORY_INFO[category_id]
            category = categories.setdefault(category_id, {
                "id": category_id,
                "title": title,
                "description": description,
                "position": len(categories),
                "applicable": applicable,
                "coverage_status": "pending",
                "source_count": 0,
                "record_count": 0,
            })
            category["source_count"] += 1
            sheets_by_category.setdefault(category_id, []).append({
                "sheet_id": f"sheet-{number:02d}",
                "sheet_number": number,
                "name": summary["name"],
                "visibility": summary["state"],
                "source_dimension": summary.get("dimension"),
                "observed_range": observed_range,
                "raw_json": summary["json_file"],
                "raw_json_sha256": summary["json_sha256"],
                "raw_csv": summary["csv_file"],
                "formula_count": summary["formula_count"],
                "cell_count": summary["explicit_cell_count"],
                "merge_count": summary["merge_count"],
                "image_placement_count": summary["image_placement_count"],
            })

    placements = json.loads((root / "raw" / "image-placements.json").read_text(encoding="utf-8"))
    assets_manifest_path = root / "assets" / "manifest.json"
    assets_manifest = json.loads(assets_manifest_path.read_text(encoding="utf-8"))
    assets_by_id = {asset["asset_id"]: asset for asset in assets_manifest["assets"]}
    assets_by_category: dict[str, set[str]] = {category_id: set() for category_id in categories}
    asset_source_categories: dict[str, set[str]] = {asset_id: set() for asset_id in assets_by_id}
    asset_roles: dict[str, set[str]] = {asset_id: set() for asset_id in assets_by_id}
    asset_locations: dict[str, list[dict[str, Any]]] = {asset_id: [] for asset_id in assets_by_id}
    for placement in placements:
        asset_id = placement.get("asset_id")
        if asset_id not in assets_by_id:
            continue
        number = placement.get("sheet_number")
        for category_id in SHEET_CATEGORIES.get(number, []):
            asset_source_categories[asset_id].add(category_id)
            assets_by_category[category_id].add(asset_id)
        role = IMAGE_ROLE_BY_SHEET.get(number)
        if role:
            asset_roles[asset_id].add(role)
        if number == 1 and placement.get("from_cell") == "F38":
            rating_category = "community-skill-ratings"
            asset_source_categories[asset_id].add(rating_category)
            assets_by_category[rating_category].add(asset_id)
            asset_roles[asset_id].add("community_skill_rating_chart")
        asset_locations[asset_id].append({
            "sheet_number": number,
            "sheet_name": placement.get("sheet_name"),
            "from_cell": placement.get("from_cell"),
            "placement_id": placement.get("placement_id"),
        })
    for asset_id, asset in assets_by_id.items():
        asset["source_categories"] = sorted(asset_source_categories[asset_id])
        asset["source_locations"] = asset_locations[asset_id]
        asset["placement_count"] = len(asset_locations[asset_id])
        asset["candidate_roles"] = sorted(asset_roles[asset_id])
        if not asset.get("classification_review"):
            asset["classification_status"] = "pending_visual_review"
        app_path = root / asset["app_file"] if asset.get("app_file") else None
        asset["app_bytes"] = app_path.stat().st_size if app_path and app_path.is_file() else None
        asset["processing"] = {
            "operation": asset.get("app_status", "not_generated"),
            "resized": False,
            "lossless": asset.get("app_status") == "lossless_webp_no_resize",
            "source_sha256": asset["sha256"],
            "app_sha256": asset.get("app_sha256"),
            "app_file": asset.get("app_file"),
        }
    assets_json = _json_bytes(assets_manifest)
    assets_manifest_path.write_bytes(assets_json)

    normalized = root / "normalized"
    for category_id, category in categories.items():
        category_dir = normalized / category_id
        category_dir.mkdir(parents=True, exist_ok=True)
        index_path = category_dir / "source-index.json"
        index_bytes = _json_bytes({
            "schema_version": 1,
            "category_id": category_id,
            "title": category["title"],
            "normalization_status": "pending_human_review",
            "sources": sheets_by_category[category_id],
            "assets_index": f"assets-index.json",
        })
        index_path.write_bytes(index_bytes)
        asset_index_path = category_dir / "assets-index.json"
        asset_index = [
            {
                "asset_id": asset_id,
                "sha256": assets_by_id[asset_id]["sha256"],
                "original_file": assets_by_id[asset_id]["original_file"],
                "app_file": assets_by_id[asset_id].get("app_file"),
                "candidate_roles": assets_by_id[asset_id]["candidate_roles"],
                "classification_status": assets_by_id[asset_id].get(
                    "classification_status", "pending_visual_review"
                ),
                "source_locations": assets_by_id[asset_id]["source_locations"],
            }
            for asset_id in sorted(assets_by_category[category_id])
        ]
        asset_index_path.write_bytes(_json_bytes(asset_index))
    ordered_categories = [categories[category_id] for category_id in CATEGORY_ORDER if category_id in categories]
    for position, category in enumerate(ordered_categories):
        category["position"] = position
    categories_bytes = _json_bytes(ordered_categories)
    (normalized / "categories.json").write_bytes(categories_bytes)

    source = manifest["source"]
    sources_dir = root / "sources"
    sources_dir.mkdir(parents=True, exist_ok=True)
    source_manifest = {
        "schema_version": 1,
        "game_id": "engage",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "sources": [{
            "source_id": "local-engage-workbook",
            "source_type": "user-provided-spreadsheet",
            "title": source["file_name"],
            "url": None,
            "local_path": "data/" + source["file_name"],
            "language": "zh-Hans",
            "game_version": "Engage; workbook version not stated in file metadata",
            "revision": source["sha256_after"],
            "retrieved_at": manifest["generated_at"],
            "access_status": "local_file",
            "rights_status": "unknown",
            "sha256": source["sha256_after"],
            "notes": "User-provided local workbook. The workbook remains outside this package and was not modified. Redistribution rights are not established.",
        }],
    }
    (sources_dir / "manifest.json").write_bytes(_json_bytes(source_manifest))
    manifest.setdefault("coverage", {})["category_directory_count"] = len(categories)
    manifest["coverage"]["normalization"] = "source_mapped_pending_human_review"
    manifest["normalized_categories_sha256"] = _sha256(categories_bytes)
    manifest["assets_manifest_sha256"] = _sha256(assets_json)
    (root / "manifest.json").write_bytes(_json_bytes(manifest))
    return {
        "sheets": len(manifest["sheets"]),
        "categories": len(categories),
        "category_ids": [category["id"] for category in ordered_categories],
        "normalization": manifest["coverage"]["normalization"],
        "normalized_categories_sha256": manifest["normalized_categories_sha256"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="按业务类别建立 Engage 工作簿来源索引")
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    arguments = parser.parse_args()
    try:
        summary = organize(arguments.package)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Engage 分类整理失败：{error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
