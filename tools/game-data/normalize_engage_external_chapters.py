from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PACKAGE = REPO_ROOT / "data" / "games" / "engage"
SOURCE_ID = "fewiki-engage-chapters"
EXPECTED_TABLES = {
    2: "Main story",
    3: "Paralogues",
    4: "Divine Paralogues",
    5: "Fell Xenologue",
}
CHAPTER_TYPE_IDS = {
    "Main story": "main_story",
    "Paralogues": "paralogues",
    "Divine Paralogues": "divine_paralogues",
    "Fell Xenologue": "fell_xenologue",
}
SHA256_PATTERN = re.compile(r"[a-f0-9]{64}\Z")


class NormalizeError(ValueError):
    pass


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise NormalizeError(f"无法读取 JSON：{path}") from error


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _required_text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise NormalizeError(f"缺少必填文本：{label}")
    return value


def _optional_text(value: Any, label: str) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise NormalizeError(f"文本字段类型错误：{label}")
    return value


def _timestamp(value: str, label: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise NormalizeError(f"时间戳无效：{label}") from error
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _source_manifest_record(source: dict[str, Any]) -> dict[str, Any]:
    source_url = _required_text(source.get("source_url"), "source.source_url")
    license_url = _required_text(source.get("license_url"), "source.license_url")
    attribution = _required_text(source.get("attribution"), "source.attribution")
    page_history_url = _required_text(source.get("page_history_url"), "source.page_history_url")
    digest = _required_text(source.get("captured_content_sha256"), "source.captured_content_sha256")
    retrieved_at = _required_text(source.get("retrieved_at"), "source.retrieved_at")
    if not SHA256_PATTERN.fullmatch(digest):
        raise NormalizeError("Fire Emblem Wiki 抓取内容 SHA-256 格式无效")
    _timestamp(retrieved_at, "source.retrieved_at")

    license_notice = _required_text(source.get("license"), "source.license")
    title = _required_text(source.get("source_title"), "source.source_title")
    game_version_status = source.get("game_version_status", "not_stated_on_source_page")
    notes = (
        f"Text-only extract from the four chapter-index tables; no maps or other images were copied. "
        f"{license_notice}. Attribution: {attribution} Page history: {page_history_url}. "
        "See licenses/GFDL-1.3.txt for the license text. The source page does not state a game version/build."
    )
    return {
        "source_id": SOURCE_ID,
        "source_type": "wiki_article_table",
        "title": title,
        "url": source_url,
        "language": "en",
        "game_version": None,
        "game_version_status": game_version_status,
        "revision": source.get("page_last_modified_text"),
        "retrieved_at": retrieved_at,
        "access_status": "readable",
        "rights_status": "licensed",
        "license_status": "licensed_with_attribution",
        "license": license_notice,
        "license_url": license_url,
        "attribution": attribution,
        "page_history_url": page_history_url,
        "sha256": digest,
        "capture_scope": _required_text(source.get("capture_scope"), "source.capture_scope"),
        "notes": notes,
    }


def _chapter_record(row: dict[str, Any], source_digest: str) -> dict[str, Any]:
    chapter_type = _required_text(row.get("chapter_type"), "record.chapter_type")
    chapter_type_id = CHAPTER_TYPE_IDS.get(chapter_type)
    if chapter_type_id is None:
        raise NormalizeError(f"未知章节类型：{chapter_type}")

    title = _required_text(row.get("title"), "record.title")
    stable_key = _required_text(row.get("source_key"), "record.source_key")
    objective = _required_text(row.get("objective"), f"{stable_key}.objective")
    defeat_condition = _required_text(row.get("defeat_condition"), f"{stable_key}.defeat_condition")
    chapter_url = _required_text(row.get("chapter_url"), f"{stable_key}.chapter_url")
    if urlparse(chapter_url).hostname != "fireemblemwiki.org":
        raise NormalizeError(f"章节详情链接不是 Fire Emblem Wiki 页面：{stable_key}")

    locator = row.get("source_locator")
    if not isinstance(locator, dict):
        raise NormalizeError(f"来源定位字段无效：{stable_key}")
    page_url = _required_text(locator.get("page_url"), f"{stable_key}.source_locator.page_url")
    section = _required_text(locator.get("section"), f"{stable_key}.source_locator.section")
    table_index = locator.get("table_index")
    header_row = locator.get("header_row")
    source_row = locator.get("row")
    if not all(isinstance(value, int) and value > 0 for value in (table_index, header_row, source_row)):
        raise NormalizeError(f"表格行列定位无效：{stable_key}")
    if section != chapter_type or EXPECTED_TABLES.get(table_index) != section:
        raise NormalizeError(f"章节类型与来源表格不匹配：{stable_key}")

    source_fields = row.get("source_fields")
    if not isinstance(source_fields, dict):
        raise NormalizeError(f"缺少原始来源字段：{stable_key}")
    route = _optional_text(row.get("route"), f"{stable_key}.route")
    chapter_label = _optional_text(row.get("chapter_label"), f"{stable_key}.chapter_label")
    new_units = _optional_text(row.get("new_units"), f"{stable_key}.new_units")
    new_emblems = _optional_text(row.get("new_emblems"), f"{stable_key}.new_emblems")
    bosses = _optional_text(row.get("bosses"), f"{stable_key}.bosses")
    unknown_fields = {
        "route": "not_stated_in_source",
        "location": "not_present_in_index_table",
        "recommended_level": "not_present_in_index_table",
        "rewards": "not_present_in_index_table",
        "map_asset_id": "no_map_asset_copied",
    }
    if chapter_label is None:
        unknown_fields["chapter_number"] = "not_stated_in_index_table"
    source_columns = {
        "new_units": "New Units",
        "new_emblems": "New Emblems",
    }
    for field_name, source_column in source_columns.items():
        if source_column not in source_fields:
            unknown_fields[field_name] = "column_not_present_in_this_source_table"
        elif source_fields[source_column] is None or not str(source_fields[source_column]).strip():
            unknown_fields[field_name] = "source_cell_blank"
    if bosses is None:
        unknown_fields["bosses"] = "source_cell_blank"
    source_locator = {
        "page_url": page_url,
        "section": section,
        "table_index": table_index,
        "header_row": header_row,
        "row": source_row,
    }
    locator_text = (
        f"Table {table_index}, {section}, source row {source_row} (header row {header_row}); "
        f"linked chapter page: {chapter_url}"
    )
    source_values = [title, chapter_type, chapter_label, objective, defeat_condition, new_units, new_emblems, bosses]
    search_text = " ".join(value for value in source_values if isinstance(value, str) and value)
    description = (
        f"English chapter-index entry from {section}. Linked chapter page: {chapter_url}. "
        "The index does not provide a map asset, location, recommended level, or reward list."
    )

    return {
        "category_id": "chapters",
        "stable_key": stable_key,
        "title": title,
        "title_en": title,
        "subtitle": chapter_type,
        "description": description,
        "search_text": search_text,
        "verification_status": "source_only",
        "kind": "chapter",
        "chapter_type": chapter_type,
        "chapter_type_id": chapter_type_id,
        "route": route,
        "chapter_label": chapter_label,
        "source_url": page_url,
        "chapter_page_url": chapter_url,
        "source_locator": source_locator,
        "source_fields": source_fields,
        "unknown_fields": unknown_fields,
        "details": {
            "route": route,
            "chapter_number": chapter_label,
            "location": None,
            "objective": objective,
            "defeat_condition": defeat_condition,
            "recommended_level": None,
            "deployment": {
                "new_units": new_units,
                "new_emblems": new_emblems,
            },
            "enemies": {"bosses": bosses},
            "rewards": None,
            "map_asset_id": None,
        },
        "sources": [{
            "source_id": SOURCE_ID,
            "locator": locator_text,
            "source_sha256": source_digest,
            "source_language": "en",
            "translation_status": "not_translated",
            "assertion_status": "unverified",
        }],
        "assets": [],
        "relations": [],
    }


def _merge_source_manifest(path: Path, source_record: dict[str, Any]) -> None:
    manifest = _read_json(path)
    if not isinstance(manifest, dict) or not isinstance(manifest.get("sources"), list):
        raise NormalizeError("sources/manifest.json 必须包含 sources 数组")
    sources = manifest["sources"]
    matching = [index for index, item in enumerate(sources) if item.get("source_id") == SOURCE_ID]
    if len(matching) > 1:
        raise NormalizeError(f"来源清单中存在重复 ID：{SOURCE_ID}")
    if matching:
        sources[matching[0]] = source_record
    else:
        sources.append(source_record)

    generated_at = manifest.get("generated_at")
    timestamps = []
    if isinstance(generated_at, str):
        timestamps.append(( _timestamp(generated_at, "manifest.generated_at"), generated_at))
    source_retrieved_at = source_record["retrieved_at"]
    timestamps.append((_timestamp(source_retrieved_at, "source.retrieved_at"), source_retrieved_at))
    manifest["generated_at"] = max(timestamps, key=lambda item: (item[0], item[1]))[1]
    _write_json(path, manifest)


def normalize(package_root: Path) -> dict[str, Any]:
    root = package_root.resolve()
    if root != DEFAULT_PACKAGE.resolve():
        raise NormalizeError(f"Engage package path must be {DEFAULT_PACKAGE.resolve()}")

    capture_path = root / "sources" / "extracts" / "fewiki-chapter-index.json"
    capture = _read_json(capture_path)
    if not isinstance(capture, dict) or capture.get("game_id") != "engage":
        raise NormalizeError("章节索引不属于 Engage")
    source_data = capture.get("source")
    if not isinstance(source_data, dict):
        raise NormalizeError("章节索引缺少 source 元数据")
    source_record = _source_manifest_record(source_data)

    tables = capture.get("tables")
    if not isinstance(tables, list):
        raise NormalizeError("章节索引缺少 tables 数组")
    table_map = {table.get("table_index"): table for table in tables if isinstance(table, dict)}
    if set(table_map) != set(EXPECTED_TABLES):
        raise NormalizeError("章节索引必须包含主线、外传、神圣外传和邪龙之章四张表")
    for table_index, section in EXPECTED_TABLES.items():
        if table_map[table_index].get("section") != section:
            raise NormalizeError(f"章节表格名称不匹配：Table {table_index}")

    rows = capture.get("records")
    if not isinstance(rows, list):
        raise NormalizeError("章节索引缺少 records 数组")
    expected_counts = {
        table_index: int(table_map[table_index].get("record_count", -1))
        for table_index in EXPECTED_TABLES
    }
    records = [_chapter_record(row, source_record["sha256"]) for row in rows]
    source_keys = [record["stable_key"] for record in records]
    if len(records) != sum(expected_counts.values()) or len(set(source_keys)) != len(source_keys):
        raise NormalizeError("章节索引记录数与稳定键校验失败")

    actual_counts = Counter(record["source_locator"]["table_index"] for record in records)
    if dict(actual_counts) != expected_counts:
        raise NormalizeError(f"章节表格记录数不匹配：{dict(actual_counts)}")

    source_url = source_record["url"]
    if any(record["source_url"] != source_url for record in records):
        raise NormalizeError("章节记录的来源页 URL 不一致")

    output = {
        "schema_version": 1,
        "game_id": "engage",
        "category_id": "chapters",
        "source_id": SOURCE_ID,
        "source_language": "en",
        "translation_status": "not_translated",
        "records": records,
    }
    output_path = root / "normalized" / "chapters-external" / "records.json"
    _write_json(output_path, output)
    _merge_source_manifest(root / "sources" / "manifest.json", source_record)

    return {
        "game_id": "engage",
        "category_id": "chapters",
        "source_id": SOURCE_ID,
        "records": len(records),
        "groups": {
            section: actual_counts[table_index]
            for table_index, section in EXPECTED_TABLES.items()
        },
        "records_file": output_path.relative_to(root).as_posix(),
        "source_manifest": "sources/manifest.json",
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="将 Fire Emblem Wiki 的 Engage 章节索引规范化为可追溯的 app 资料记录"
    )
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    arguments = parser.parse_args()
    try:
        result = normalize(arguments.package)
    except (NormalizeError, OSError, KeyError, TypeError) as error:
        print(f"Engage 外部章节规范化失败：{error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
