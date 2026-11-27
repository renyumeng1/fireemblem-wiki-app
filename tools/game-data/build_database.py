from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
import tempfile
import unicodedata
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

GENERATOR = "fireemblem/tools/game-data/build_database"
APPLICATION_ID = 0x46454744
SCHEMA_VERSION = 1
GAME_IDS = {"engage", "three-houses"}
STATUS_VALUES = {"confirmed", "pending_review", "conflicting", "unverified", "source_only"}
SAFE_ID = re.compile(r"[a-z][a-z0-9_-]*\Z")
REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = Path(__file__).resolve().parent / "schema" / "game-data-v1.sql"


class DatabaseBuildError(Exception):
    pass


def _json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _read_json(path: Path, default: Any = None) -> Any:
    if not path.exists() and default is not None:
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise DatabaseBuildError(f"无法读取 JSON：{path}") from error


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _normalize_id_component(value: str) -> str:
    return unicodedata.normalize("NFC", " ".join(value.split()))


def _entry_id(game_id: str, category_id: str, stable_key: str) -> str:
    name = json.dumps([game_id, category_id, _normalize_id_component(stable_key)], ensure_ascii=False)
    return f"{category_id}:{uuid.uuid5(uuid.NAMESPACE_URL, name)}"


def _load_package_sources(root: Path, manifest: dict[str, Any]) -> list[dict[str, Any]]:
    source_manifest = _read_json(root / "sources" / "manifest.json", {"sources": []})
    if isinstance(source_manifest, list):
        source_records = source_manifest
    else:
        source_records = source_manifest.get("sources", [])
    if source_records:
        return source_records
    source = manifest.get("source")
    if not isinstance(source, dict):
        return []
    game_id = manifest.get("game_id", "unknown")
    source_id = "local-engage-workbook" if game_id == "engage" else f"{game_id}-source"
    return [{
        "source_id": source_id,
        "source_type": source.get("kind", "source"),
        "title": source.get("title") or source.get("file_name") or source_id,
        "url": source.get("url"),
        "language": source.get("language", "ja" if game_id == "engage" else "en"),
        "game_version": source.get("game_version"),
        "revision": source.get("revision") or source.get("sha256_after") or source.get("sha256"),
        "retrieved_at": source.get("retrieved_at") or manifest.get("generated_at"),
        "access_status": "local_file" if source.get("kind") == "local_user_provided_workbook" else "readable",
        "rights_status": "unknown",
        "sha256": source.get("sha256_after") or source.get("sha256"),
        "notes": source.get("redistribution_status", ""),
    }]


def _source_row(source: dict[str, Any]) -> tuple[Any, ...]:
    access_status = source.get("access_status", "unavailable")
    if access_status not in {"readable", "restricted", "http_403", "unavailable", "local_file"}:
        access_status = "unavailable"
    rights_status = source.get("rights_status", "unknown")
    if rights_status not in {"unknown", "personal_use_only", "licensed", "public_domain", "redistribution_prohibited"}:
        rights_status = "unknown"
    return (
        source["source_id"], source.get("source_type", "reference"), source.get("title", source["source_id"]),
        source.get("url"), source.get("language"), source.get("game_version"), source.get("revision"),
        source.get("retrieved_at") or datetime.now(timezone.utc).isoformat(), access_status, rights_status,
        source.get("sha256"), source.get("notes", ""),
    )


def _load_categories(root: Path) -> list[dict[str, Any]]:
    categories = _read_json(root / "normalized" / "categories.json", [])
    if not isinstance(categories, list):
        raise DatabaseBuildError("normalized/categories.json 必须是数组")
    result = []
    seen: set[str] = set()
    for position, category in enumerate(categories):
        if not isinstance(category, dict):
            raise DatabaseBuildError("分类清单必须由对象组成")
        category_id = category.get("id")
        if not isinstance(category_id, str) or not SAFE_ID.fullmatch(category_id) or category_id in seen:
            raise DatabaseBuildError(f"分类 ID 不合法或重复：{category_id!r}")
        seen.add(category_id)
        coverage_status = category.get("coverage_status", "pending")
        if coverage_status not in {"complete", "partial", "pending", "not_applicable", "blocked"}:
            raise DatabaseBuildError(f"分类覆盖状态不合法：{category_id}/{coverage_status}")
        result.append({
            "id": category_id,
            "title": str(category.get("title", category_id)),
            "description": str(category.get("description", "")),
            "position": int(category.get("position", position)),
            "applicable": bool(category.get("applicable", True)),
            "coverage_status": coverage_status,
            "source_count": int(category.get("source_count", 0)),
        })
    return result


def _load_records(root: Path) -> tuple[list[dict[str, Any]], list[Path]]:
    records: list[dict[str, Any]] = []
    files: list[Path] = []
    normalized = root / "normalized"
    if not normalized.exists():
        return records, files
    for path in sorted(normalized.glob("*/records.json")):
        data = _read_json(path)
        current = data.get("records", []) if isinstance(data, dict) else data
        if not isinstance(current, list):
            raise DatabaseBuildError(f"資料記錄必須是陣列：{path}")
        for record in current:
            if not isinstance(record, dict):
                raise DatabaseBuildError(f"記錄必須是物件：{path}")
            record = dict(record)
            record.setdefault("category_id", path.parent.name)
            records.append(record)
        files.append(path)
    return records, files


def _load_workbook(root: Path) -> tuple[list[dict[str, Any]], list[dict[str, Any]], Path | None]:
    manifest = _read_json(root / "manifest.json", {})
    if manifest.get("game_id") != "engage":
        return [], [], None
    sheet_summaries = manifest.get("sheets", [])
    cells: list[dict[str, Any]] = []
    sheets: list[dict[str, Any]] = []
    for summary in sheet_summaries:
        sheet_path = root / summary["json_file"]
        table = _read_json(sheet_path)
        descriptor = table["sheet"]
        sheet_id = f"sheet-{int(descriptor['sheet_number']):02d}"
        sheets.append({**summary, "sheet_id": sheet_id, "source_part": table.get("source_part")})
        for cell in table.get("cells", []):
            cells.append({"sheet_id": sheet_id, **cell})
    return sheets, cells, root / "raw" / "image-placements.json"


def _load_ocr_results(
    root: Path,
    game_id: str,
    source_ids: set[str],
    asset_hashes: dict[str, str],
    placements: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], Path | None]:
    if game_id != "engage":
        return [], None
    path = root / "reports" / "ocr-results.jsonl"
    if not path.is_file():
        return [], None
    source_id = "local-engage-workbook"
    if source_id not in source_ids:
        raise DatabaseBuildError("Engage OCR 结果缺少工作簿来源")
    placements_by_asset: dict[str, list[dict[str, Any]]] = {}
    for placement in placements:
        asset_id = placement.get("asset_id")
        if asset_id:
            placements_by_asset.setdefault(asset_id, []).append({
                "sheet_number": placement.get("sheet_number"),
                "sheet_name": placement.get("sheet_name"),
                "from_cell": placement.get("from_cell"),
                "placement_id": placement.get("placement_id"),
            })
    results: list[dict[str, Any]] = []
    seen: set[str] = set()
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            try:
                result = json.loads(line)
            except json.JSONDecodeError as error:
                raise DatabaseBuildError(f"OCR JSONL 第 {line_number} 行无效") from error
            asset_id = result.get("asset_id")
            if asset_id not in asset_hashes:
                raise DatabaseBuildError(f"OCR 引用了未登记图片：{asset_id}")
            if asset_id in seen:
                raise DatabaseBuildError(f"OCR 图片重复：{asset_id}")
            seen.add(asset_id)
            source_hash = result.get("source_sha256")
            if source_hash and not re.fullmatch(r"[a-f0-9]{64}", str(source_hash)):
                raise DatabaseBuildError(f"OCR 图片 SHA-256 无效：{asset_id}")
            if source_hash and source_hash != asset_hashes[asset_id]:
                raise DatabaseBuildError(f"OCR 与原图 SHA-256 不匹配：{asset_id}")
            blocks = result.get("blocks", [])
            if not isinstance(blocks, list):
                raise DatabaseBuildError(f"OCR blocks 必须是数组：{asset_id}")
            review_status = result.get("review_status", "pending_review")
            if review_status not in {"pending_review", "reviewed", "unreadable", "not_data_image"}:
                review_status = "pending_review"
            locations = placements_by_asset.get(asset_id, [])
            human_review = result.get("human_review")
            if isinstance(human_review, dict) and review_status != "pending_review":
                recognized_text = str(human_review.get("corrected_text") or "")
            else:
                recognized_text = "\n".join(
                    str(block.get("text", "")) for block in blocks if block.get("text")
                )
            results.append({
                "asset_id": asset_id,
                "source_id": source_id,
                "locator": _json_text(locations),
                "engine": result.get("engine"),
                "language": result.get("language"),
                "recognized_text": recognized_text,
                "boxes_json": _json_text(blocks),
                "review_status": review_status,
                "reviewer_notes": result.get("reviewer_notes", ""),
            })
    summary_path = root / "reports" / "ocr-summary.json"
    if summary_path.is_file():
        summary = _read_json(summary_path)
        expected_count = int(summary.get("assets_in_scope", len(asset_hashes)))
        if expected_count != len(results) or seen != set(asset_hashes):
            raise DatabaseBuildError(
                f"OCR 覆盖不完整：summary={expected_count}, results={len(results)}, assets={len(asset_hashes)}"
            )
    return results, path


def _insert_details(connection: sqlite3.Connection, entry_id: str, kind: str, details: dict[str, Any]) -> None:
    text = lambda key: details.get(key)
    json_value = lambda key: None if key not in details or details[key] is None else _json_text(details[key])
    def numeric(key: str) -> int | float | None:
        value = details.get(key)
        if value is None or value == "":
            return None
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return None
        return int(parsed) if parsed.is_integer() else parsed

    tables: dict[str, tuple[str, tuple[Any, ...]]] = {
        "character": ("character_details", (
            entry_id, text("gender"), text("age"), text("affiliation"), text("join_condition"),
            text("route_availability"), json_value("base_stats"), json_value("growths"), json_value("caps"),
            json_value("support_availability"),
        )),
        "class": ("class_details", (
            entry_id, text("tier"), numeric("movement"), text("movement_type"), json_value("weapon_ranks"),
            json_value("base_stats"), json_value("growths"), json_value("caps"), text("promotion_requirement"),
            text("class_skill"),
        )),
        "skill": ("skill_details", (
            entry_id, text("skill_type"), text("weapon_type"), text("requirement"), text("cost"),
            text("activation_condition"), json_value("effect"),
        )),
        "equipment": ("equipment_details", (
            entry_id, text("equipment_type"), text("subtype"), text("rank"), numeric("might"), numeric("hit"),
            numeric("critical"), numeric("weight"), numeric("durability"), numeric("price"), text("effect"),
            text("acquisition"), json_value("extra_stats"),
        )),
        "chapter": ("chapter_details", (
            entry_id, text("route"), text("chapter_number"), text("location"), text("objective"),
            text("defeat_condition"), text("recommended_level"), json_value("deployment"), json_value("enemies"),
            json_value("rewards"), text("map_asset_id"),
        )),
        "support": ("support_details", (
            entry_id, text("character_a_id"), text("character_b_id"), text("support_rank"),
            text("availability_condition"), text("support_points"), text("dialogue_summary"),
        )),
        "activity": ("activity_details", (
            entry_id, text("activity_type"), text("availability_condition"), json_value("options"), json_value("effects"),
        )),
        "mechanic": ("mechanic_details", (
            entry_id, text("mechanic_type"), text("game_version"), text("rule_description"),
            json_value("parameters"), text("formula_text"),
        )),
        "emblem": ("emblem_details", (
            entry_id, json_value("bond_level_effects"), json_value("sync_skills"), json_value("engage_skills"),
            json_value("inherited_skills"), text("voice_actor"), text("profile"),
        )),
        "bond_ring": ("bond_ring_details", (
            entry_id, text("emblem_name") or "", text("ring_rank") or "", json_value("stat_bonuses") or "{}",
            text("skill_name"), text("skill_effect"),
        )),
        "achievement": ("achievement_details", (
            entry_id, text("achievement_type"), text("requirement"), text("reward"), text("chapter"), text("notes"),
        )),
    }
    if kind == "item":
        kind = "equipment"
    detail = tables.get(kind)
    if detail is None:
        return
    table, values = detail
    connection.execute(f"INSERT INTO {table} VALUES ({','.join('?' for _ in values)})", values)


def build_database(game_id: str, package_root: Path) -> dict[str, Any]:
    if game_id not in GAME_IDS:
        raise DatabaseBuildError(f"不支持的 game_id：{game_id}")
    root = package_root.resolve()
    expected_root = (REPO_ROOT / "data" / "games" / game_id).resolve()
    if root != expected_root:
        raise DatabaseBuildError(f"资料包路径必须是 {expected_root}")
    manifest = _read_json(root / "manifest.json")
    if manifest.get("game_id") != game_id:
        raise DatabaseBuildError("资料包 manifest 的 game_id 不匹配")
    schema_bytes = SCHEMA_PATH.read_bytes()
    sources = _load_package_sources(root, manifest)
    source_ids = {source["source_id"] for source in sources}
    if len(source_ids) != len(sources):
        raise DatabaseBuildError("来源 ID 重复")
    categories = _load_categories(root)
    records, record_files = _load_records(root)
    sheets, cells, placements_path = _load_workbook(root)
    placements = _read_json(placements_path, []) if placements_path else []
    asset_manifest = _read_json(root / "assets" / "manifest.json", {"assets": []})
    assets = asset_manifest if isinstance(asset_manifest, list) else asset_manifest.get("assets", [])
    asset_hashes = {}
    for asset in assets:
        digest = asset.get("sha256") or asset.get("source_sha256")
        asset_id = asset.get("asset_id") or (f"sha256:{digest}" if digest else None)
        if asset_id and digest:
            asset_hashes[asset_id] = digest
    ocr_results, ocr_path = _load_ocr_results(root, game_id, source_ids, asset_hashes, placements)
    category_ids = {category["id"] for category in categories}
    for record in records:
        if record.get("category_id") not in category_ids:
            raise DatabaseBuildError(f"記錄引用未聲明分类：{record.get('category_id')}")
        if not record.get("title") or not record.get("stable_key"):
            raise DatabaseBuildError("每条记录必须提供 title 和 stable_key")
        if record.get("verification_status", "pending_review") not in STATUS_VALUES:
            raise DatabaseBuildError(f"记录验证状态不合法：{record.get('stable_key')}")
        for source_link in record.get("sources", []):
            if source_link.get("source_id") not in source_ids:
                raise DatabaseBuildError(f"记录引用未登记来源：{source_link.get('source_id')}")

    output = root / "database" / "content.db"
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        uri = output.resolve().as_uri() + "?mode=ro"
        with closing(sqlite3.connect(uri, uri=True)) as previous:
            if previous.execute("PRAGMA application_id").fetchone()[0] != APPLICATION_ID:
                raise DatabaseBuildError(f"拒绝覆盖不属于此生成器的资料库：{output}")
    normalized_hashes = [(path.relative_to(root).as_posix(), _sha256_file(path)) for path in record_files]
    version_bytes = (root / "manifest.json").read_bytes() + schema_bytes + _json_text(normalized_hashes).encode("utf-8")
    assets_manifest_path = root / "assets" / "manifest.json"
    if assets_manifest_path.is_file():
        version_bytes += assets_manifest_path.read_bytes()
    source_manifest_path = root / "sources" / "manifest.json"
    if source_manifest_path.is_file():
        version_bytes += source_manifest_path.read_bytes()
    if ocr_path is not None:
        version_bytes += ocr_path.read_bytes()
    content_version = _sha256(version_bytes)

    temporary_descriptor, temporary_name = tempfile.mkstemp(prefix=".game-content-", suffix=".db", dir=output.parent)
    os.close(temporary_descriptor)
    temporary = Path(temporary_name)
    connection = sqlite3.connect(temporary)
    try:
        connection.execute("PRAGMA foreign_keys=ON")
        connection.executescript(schema_bytes.decode("utf-8"))
        with connection:
            for source in sources:
                connection.execute("INSERT INTO sources VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", _source_row(source))
            counts = {category["id"]: 0 for category in categories}
            for record in records:
                category_id = record["category_id"]
                entry_id = _entry_id(game_id, category_id, str(record["stable_key"]))
                counts[category_id] += 1
            for position, category in enumerate(categories):
                connection.execute("INSERT INTO categories VALUES (?,?,?,?,?,?,?,?)", (
                    category["id"], category["title"], category["description"],
                    category["position"] if category["position"] is not None else position,
                    int(category["applicable"]), category["coverage_status"],
                    category["source_count"], counts[category["id"]],
                ))

            asset_ids: set[str] = set()
            for asset in assets:
                asset_id = asset.get("asset_id")
                if not asset_id:
                    digest = asset.get("sha256") or asset.get("source_sha256")
                    if not digest:
                        continue
                    asset_id = f"sha256:{digest}"
                if asset_id in asset_ids:
                    raise DatabaseBuildError(f"重複 asset_id：{asset_id}")
                asset_ids.add(asset_id)
                original_file = asset.get("original_file") or asset.get("file") or asset.get("source_file")
                source_hash = asset.get("sha256") or asset.get("source_sha256")
                if not original_file or not source_hash:
                    raise DatabaseBuildError(f"资源缺少路径或 SHA-256：{asset_id}")
                app_file = asset.get("app_file")
                app_path = (root / app_file).resolve() if app_file else None
                source_path = (root / original_file).resolve()
                if not source_path.is_relative_to(root) or (app_path is not None and not app_path.is_relative_to(root)):
                    raise DatabaseBuildError(f"资源路径越出资料包：{asset_id}")
                if not source_path.is_file() or _sha256_file(source_path) != source_hash:
                    raise DatabaseBuildError(f"原始资源缺失或 SHA-256 不匹配：{asset_id}")
                if app_path is not None:
                    if not app_path.is_file():
                        raise DatabaseBuildError(f"App 资源路径不存在：{asset_id}")
                    app_hash = asset.get("app_sha256")
                    if app_hash and _sha256_file(app_path) != app_hash:
                        raise DatabaseBuildError(f"App 资源 SHA-256 不匹配：{asset_id}")
                rights = asset.get("rights_status", "unknown")
                if rights not in {"unknown", "personal_use_only", "licensed", "public_domain", "redistribution_prohibited"}:
                    rights = "unknown"
                classification_status = asset.get("classification_status", "pending_review")
                if classification_status == "pending_visual_review":
                    classification_status = "pending_review"
                if classification_status not in {"reviewed", "pending_review", "not_applicable"}:
                    classification_status = "pending_review"
                connection.execute("INSERT INTO content_assets VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (
                    asset_id, original_file, app_file, source_hash, asset.get("app_sha256"), asset.get("mime_type"),
                    asset.get("width"), asset.get("height"), asset.get("source_bytes", asset.get("bytes", 0)),
                    app_path.stat().st_size if app_path and app_path.is_file() else asset.get("app_bytes"),
                    asset.get("classification", "unclassified_pending_visual_review"),
                    classification_status, rights,
                    int(bool(asset.get("redistribution_allowed", False))), _json_text(asset.get("processing", {})),
                ))

            connection.executemany("""INSERT INTO image_ocr_results (
                asset_id,source_id,locator,engine,language,recognized_text,boxes_json,review_status,reviewer_notes
            ) VALUES (?,?,?,?,?,?,?,?,?)""", [(
                result["asset_id"], result["source_id"], result["locator"], result.get("engine"),
                result.get("language"), result["recognized_text"], result["boxes_json"],
                result["review_status"], result.get("reviewer_notes", ""),
            ) for result in ocr_results])

            source_fallback_id = sources[0]["source_id"] if sources else None
            entry_ids: dict[tuple[str, str], str] = {}
            entry_id_set: set[str] = set()
            for record in records:
                category_id = record["category_id"]
                stable_key = str(record["stable_key"])
                entry_id = _entry_id(game_id, category_id, stable_key)
                if entry_id in entry_id_set:
                    raise DatabaseBuildError(f"重复 entry ID：{category_id}/{stable_key}")
                entry_ids[(category_id, stable_key)] = entry_id
                entry_id_set.add(entry_id)
                status = record.get("verification_status", "pending_review")
                subtitle = record.get("subtitle")
                description = record.get("description")
                title = str(record["title"])
                search_text = " ".join(str(value) for value in [title, subtitle or "", description or ""] if value)
                connection.execute("INSERT INTO content_entries VALUES (?,?,?,?,?,?,?,?,?,?)", (
                    entry_id, category_id, stable_key, title, record.get("title_ja"), record.get("title_en"),
                    subtitle, description, search_text, status,
                ))

            # Details may reference another content entry (for example, a
            # support pair referencing characters that appear later in the
            # normalized input). Insert every entry before any typed details.
            for record in records:
                category_id = record["category_id"]
                stable_key = str(record["stable_key"])
                entry_id = entry_ids[(category_id, stable_key)]
                status = record.get("verification_status", "pending_review")
                kind = record.get("kind", "")
                details = record.get("details", {})
                if not isinstance(details, dict):
                    raise DatabaseBuildError(f"details 必须是对象：{stable_key}")
                details = dict(details)
                if kind == "support":
                    for prefix in ("character_a", "character_b"):
                        target_stable_key = details.pop(f"{prefix}_stable_key", None)
                        target_category_id = details.pop(f"{prefix}_category_id", "characters")
                        if target_stable_key and not details.get(f"{prefix}_id"):
                            target_id = entry_ids.get((target_category_id, str(target_stable_key)))
                            if target_id is None:
                                raise DatabaseBuildError(
                                    f"支援详情目标不存在：{stable_key} -> {target_category_id}/{target_stable_key}"
                                )
                            details[f"{prefix}_id"] = target_id
                _insert_details(connection, entry_id, kind, details)
                links = record.get("sources", [])
                if not links and source_fallback_id:
                    links = [{"source_id": source_fallback_id, "locator": "", "assertion_status": "unverified"}]
                for link in links:
                    translation = link.get("translation_status", "not_translated")
                    if translation not in {"not_translated", "machine_draft", "human_reviewed", "not_applicable"}:
                        translation = "not_translated"
                    assertion = link.get("assertion_status", status)
                    if assertion not in {"confirmed", "pending_review", "conflicting", "unverified"}:
                        assertion = "unverified"
                    connection.execute("INSERT INTO entry_sources VALUES (?,?,?,?,?,?,?)", (
                        entry_id, link["source_id"], link.get("locator", ""), link.get("source_sha256"),
                        link.get("source_language"), translation, assertion,
                    ))
                for link in record.get("assets", []):
                    if link.get("asset_id") not in asset_ids:
                        raise DatabaseBuildError(f"记录引用未登记图片：{link.get('asset_id')}")
                    connection.execute("INSERT INTO entry_assets VALUES (?,?,?,?)", (
                        entry_id, link["asset_id"], link.get("role", "illustration"), link.get("locator", ""),
                    ))

            # Relations are staged last for the same reason: both endpoints
            # must already exist for the foreign keys to hold.
            for record in records:
                category_id = record["category_id"]
                stable_key = str(record["stable_key"])
                entry_id = entry_ids[(category_id, stable_key)]
                for relation in record.get("relations", []):
                    target_id = relation.get("target_id")
                    if not target_id:
                        target_category_id = relation.get("target_category_id")
                        target_stable_key = relation.get("target_stable_key")
                        if target_category_id and target_stable_key:
                            target_id = entry_ids.get((target_category_id, str(target_stable_key)))
                            if target_id is None:
                                raise DatabaseBuildError(
                                    f"关系目标不存在：{stable_key} -> {target_category_id}/{target_stable_key}"
                                )
                    if not target_id:
                        continue
                    if target_id not in entry_id_set:
                        raise DatabaseBuildError(f"关系引用不存在的条目：{stable_key} -> {target_id}")
                    connection.execute("INSERT INTO content_relations VALUES (?,?,?,?,?,?,?)", (
                        entry_id, target_id, relation["relation_type"], relation.get("evidence", ""),
                        relation.get("verification_status", "unverified"), relation.get("source_id"),
                        relation.get("locator", ""),
                    ))

            for sheet in sheets:
                source_id = sheet.get("source_id") or source_fallback_id
                if not source_id:
                    raise DatabaseBuildError("Engage 工作簿缺少来源记录")
                connection.execute("""INSERT INTO workbook_sheets (
                    sheet_id,source_id,sheet_number,name,visibility,dimension,explicit_cell_count,
                    formula_count,merge_count,json_file,json_sha256,csv_file,csv_sha256
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""", (
                    sheet["sheet_id"], source_id, sheet["sheet_number"], sheet["name"], sheet["state"],
                    sheet.get("dimension"), sheet["explicit_cell_count"], sheet["formula_count"],
                    sheet["merge_count"], sheet["json_file"], sheet["json_sha256"], sheet["csv_file"],
                    sheet["csv_sha256"],
                ))
            connection.executemany(
                "INSERT INTO workbook_cells VALUES (?,?,?,?,?,?,?,?,?)",
                [(
                    cell["sheet_id"], cell["address"], cell["row"], cell["column"], cell["type"],
                    cell.get("style_index"), _json_text(cell.get("value")), cell.get("raw_value"),
                    _json_text(cell["formula"]) if cell.get("formula") is not None else None,
                ) for cell in cells],
            )
            if placements_path and placements_path.is_file():
                placement_rows = []
                for placement in placements:
                    sheet_id = f"sheet-{int(placement['sheet_number']):02d}"
                    asset_id = placement.get("asset_id")
                    if asset_id not in asset_ids:
                        asset_id = None
                    placement_rows.append((
                        placement["placement_id"], sheet_id, asset_id, placement.get("drawing_part", ""),
                        placement.get("anchor_type", "unknown"), placement.get("from_cell"),
                        _json_text(placement.get("from")), _json_text(placement.get("to")),
                        _json_text(placement.get("shape", {})), placement.get("relationship_status", "missing_or_external"),
                    ))
                connection.executemany("INSERT INTO workbook_image_placements VALUES (?,?,?,?,?,?,?,?,?,?)", placement_rows)

            metadata = {
                "game_id": game_id,
                "schema_version": str(SCHEMA_VERSION),
                "content_version": content_version,
                "generated_at": datetime.now(timezone.utc).isoformat(),
                "source_count": str(len(sources)),
                "category_count": str(len(categories)),
                "entry_count": str(len(records)),
                "asset_count": str(len(assets)),
                "workbook_sheet_count": str(len(sheets)),
                "workbook_cell_count": str(len(cells)),
                "image_placement_count": str(len(placements)),
                "ocr_result_count": str(len(ocr_results)),
                "ocr_with_text_count": str(sum(bool(result["recognized_text"]) for result in ocr_results)),
                "ocr_pending_review_count": str(sum(result["review_status"] == "pending_review" for result in ocr_results)),
                "generator": GENERATOR,
            }
            connection.executemany("INSERT INTO content_metadata VALUES (?,?)", metadata.items())
            connection.execute("INSERT INTO validation_runs VALUES (?,?,?,?)", (
                "build-" + content_version[:24], metadata["generated_at"], "partial",
                _json_text({
                    "note": (
                        "SQLite checks passed; Three Houses facts remain source-only and still need independent verification."
                        if game_id == "three-houses"
                        else "SQLite checks passed; workbook content and OCR remain source-only or pending human review."
                    ),
                    "ocr_result_count": len(ocr_results),
                    "ocr_with_text_count": sum(bool(result["recognized_text"]) for result in ocr_results),
                    "ocr_pending_review_count": sum(result["review_status"] == "pending_review" for result in ocr_results),
                }),
            ))
        if connection.execute("PRAGMA foreign_key_check").fetchall():
            raise DatabaseBuildError("foreign_key_check 失败")
        integrity = connection.execute("PRAGMA quick_check").fetchone()
        if integrity != ("ok",):
            raise DatabaseBuildError(f"quick_check 失败：{integrity}")
        connection.close()
        os.replace(temporary, output)
    finally:
        connection.close()
        temporary.unlink(missing_ok=True)

    digest = _sha256_file(output)
    (output.with_suffix(".sha256")).write_text(digest + "\n", encoding="ascii")
    report = {
        "generator": GENERATOR,
        "schema_version": SCHEMA_VERSION,
        "game_id": game_id,
        "content_version": content_version,
        "database_sha256": digest,
        "database_bytes": output.stat().st_size,
        "sources": len(sources),
        "categories": {category["id"]: counts[category["id"]] for category in categories},
        "entries": len(records),
        "assets": len(assets),
        "workbook_sheets": len(sheets),
        "workbook_cells": len(cells),
        "image_placements": len(placements),
        "ocr_results": len(ocr_results),
        "ocr_with_text": sum(bool(result["recognized_text"]) for result in ocr_results),
        "ocr_pending_review": sum(result["review_status"] == "pending_review" for result in ocr_results),
        "foreign_key_check": "passed",
        "quick_check": "passed",
    }
    (root / "reports").mkdir(parents=True, exist_ok=True)
    (root / "reports" / "database.json").write_bytes((json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="按共享 schema 构建单款游戏 SQLite 数据库")
    parser.add_argument("--game", required=True, choices=sorted(GAME_IDS))
    parser.add_argument("--package", type=Path)
    arguments = parser.parse_args()
    package = arguments.package or (REPO_ROOT / "data" / "games" / arguments.game)
    try:
        report = build_database(arguments.game, package)
    except (DatabaseBuildError, OSError, sqlite3.Error) as error:
        print(f"资料库生成失败：{error}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
