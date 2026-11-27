from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import uuid
from collections import Counter
from pathlib import Path
from typing import Any


GENERATOR_ID = "fireemblem/tools/tencent-sheet"
SCHEMA_VERSION = 1
KNOWN_CELL_FIELDS = {
    "row",
    "column",
    "address",
    "type",
    "value",
    "formula",
    "rich_text",
    "image",
}
SHEET_ID_PATTERN = re.compile(r"[A-Za-z0-9_-]+\Z")
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}\Z")


class PipelineError(Exception):
    pass


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def _load_json(path: Path) -> tuple[Any, str]:
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise PipelineError(f"Cannot read {path}: {exc}") from exc
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise PipelineError(f"Invalid UTF-8 JSON in {path}: {exc}") from exc
    return value, hashlib.sha256(raw).hexdigest()


def _is_within(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def _require_non_overlapping_paths(source_dir: Path, output_dir: Path) -> None:
    source = source_dir.resolve()
    output = output_dir.resolve()
    if _is_within(output, source) or _is_within(source, output):
        raise PipelineError("Input and output directories must not overlap")


def _safe_source_path(source_dir: Path, relative_path: str) -> Path:
    if not isinstance(relative_path, str) or not relative_path:
        raise PipelineError("Catalog table_file must be a non-empty relative path")
    candidate = (source_dir / relative_path).resolve()
    if not _is_within(candidate, source_dir.resolve()) or candidate == source_dir.resolve():
        raise PipelineError(f"Source path escapes snapshot directory: {relative_path}")
    return candidate


def _load_context(source_dir: Path) -> dict[str, Any]:
    source_dir = source_dir.resolve()
    if not source_dir.is_dir():
        raise PipelineError(f"Snapshot directory does not exist: {source_dir}")

    source_files: dict[str, dict[str, str]] = {}
    loaded: dict[str, Any] = {}
    for filename in ("catalog.json", "source.json", "sheets.json", "assets-manifest.json"):
        value, checksum = _load_json(source_dir / filename)
        loaded[filename] = value
        source_files[filename] = {"path": filename, "sha256": checksum}

    catalog = loaded["catalog.json"]
    source_metadata = loaded["source.json"]
    descriptors = loaded["sheets.json"]
    asset_manifest = loaded["assets-manifest.json"]
    if not isinstance(catalog, list) or not isinstance(source_metadata, dict):
        raise PipelineError("catalog.json must be a list and source.json must be an object")
    if not isinstance(descriptors, list) or not isinstance(asset_manifest, list):
        raise PipelineError("sheets.json and assets-manifest.json must be arrays")
    if "revision" not in source_metadata:
        raise PipelineError("source.json must include a revision")
    if not isinstance(source_metadata.get("missing_sheets", []), list):
        raise PipelineError("source.json missing_sheets must be an array")

    catalog_by_id: dict[str, dict[str, Any]] = {}
    for entry in catalog:
        if not isinstance(entry, dict):
            raise PipelineError("Each catalog entry must be an object")
        sheet_id = entry.get("sheet_id")
        if not isinstance(sheet_id, str) or not SHEET_ID_PATTERN.fullmatch(sheet_id):
            raise PipelineError(f"Invalid catalog sheet_id: {sheet_id!r}")
        if sheet_id in catalog_by_id:
            raise PipelineError(f"Duplicate catalog sheet_id: {sheet_id}")
        if entry.get("status") not in {"available", "unavailable"}:
            raise PipelineError(f"Unsupported status for sheet {sheet_id}: {entry.get('status')!r}")
        catalog_by_id[sheet_id] = entry

    descriptor_by_id: dict[str, dict[str, Any]] = {}
    for descriptor in descriptors:
        if not isinstance(descriptor, dict) or not isinstance(descriptor.get("id"), str):
            raise PipelineError("Each sheets.json entry must have a string id")
        sheet_id = descriptor["id"]
        if sheet_id in descriptor_by_id:
            raise PipelineError(f"Duplicate sheet descriptor: {sheet_id}")
        descriptor_by_id[sheet_id] = descriptor
    if set(catalog_by_id) != set(descriptor_by_id):
        raise PipelineError("catalog.json and sheets.json list different sheet IDs")
    for sheet_id, entry in catalog_by_id.items():
        descriptor = descriptor_by_id[sheet_id]
        if entry.get("name") != descriptor.get("name"):
            raise PipelineError(f"Sheet name differs between catalog and descriptor: {sheet_id}")
        if entry["status"] == "available" and descriptor.get("hidden") is True:
            raise PipelineError(f"Hidden sheet is marked available in catalog: {sheet_id}")

    missing_by_id: dict[str, dict[str, Any]] = {}
    for missing in source_metadata.get("missing_sheets", []):
        if isinstance(missing, dict) and isinstance(missing.get("sheet_id"), str):
            missing_by_id[missing["sheet_id"]] = missing

    asset_by_file: dict[str, dict[str, Any]] = {}
    for asset in asset_manifest:
        if not isinstance(asset, dict) or not isinstance(asset.get("file"), str):
            raise PipelineError("Each assets-manifest.json entry must identify a file")
        file_name = asset["file"]
        if file_name in asset_by_file:
            raise PipelineError(f"Duplicate asset manifest file: {file_name}")
        checksum = asset.get("sha256")
        if checksum is not None and (not isinstance(checksum, str) or not SHA256_PATTERN.fullmatch(checksum)):
            raise PipelineError(f"Invalid asset SHA-256 for {file_name}")
        asset_by_file[file_name] = asset

    return {
        "source_dir": source_dir,
        "catalog": catalog,
        "catalog_by_id": catalog_by_id,
        "descriptors": descriptor_by_id,
        "source_metadata": source_metadata,
        "source_files": source_files,
        "missing_by_id": missing_by_id,
        "asset_by_file": asset_by_file,
    }


def _validate_source_table(
    context: dict[str, Any], entry: dict[str, Any], table: Any, table_path: Path
) -> list[dict[str, Any]]:
    sheet_id = entry["sheet_id"]
    if not isinstance(table, dict):
        raise PipelineError(f"Table must be a JSON object: {table_path}")
    for field in ("sheet_id", "name", "hidden", "row_count", "column_count", "revision", "cells", "merges"):
        if field not in table:
            raise PipelineError(f"Table {sheet_id} is missing required field {field!r}")
    if table["sheet_id"] != sheet_id:
        raise PipelineError(f"Table ID mismatch for {sheet_id}: {table['sheet_id']!r}")
    for field in ("name", "row_count", "column_count", "revision"):
        if entry.get(field) is not None and table.get(field) != entry.get(field):
            raise PipelineError(f"Table/catalog {field} mismatch for {sheet_id}")
    source_revision = table["revision"]
    expected_revision = context["source_metadata"]["revision"]
    if source_revision != expected_revision:
        raise PipelineError(f"Source revision mismatch for {sheet_id}: {source_revision!r} != {expected_revision!r}")
    descriptor = context["descriptors"][sheet_id]
    if table["hidden"] != descriptor.get("hidden"):
        raise PipelineError(f"Table/descriptor hidden status mismatch for {sheet_id}")
    for table_field, descriptor_field in (("row_count", "rows"), ("column_count", "columns")):
        if table[table_field] != descriptor.get(descriptor_field):
            raise PipelineError(f"Table/descriptor {table_field} mismatch for {sheet_id}")
    cells = table["cells"]
    if not isinstance(cells, list) or not isinstance(table["merges"], list):
        raise PipelineError(f"Table cells and merges must be arrays for {sheet_id}")
    if table.get("stored_cell_count") != len(cells):
        raise PipelineError(f"stored_cell_count does not match cells for {sheet_id}")
    for index, cell in enumerate(cells):
        if not isinstance(cell, dict):
            raise PipelineError(f"Cell {index} in {sheet_id} is not an object")
        if not all(field in cell for field in ("row", "column", "address", "type", "value")):
            raise PipelineError(f"Cell {index} in {sheet_id} lacks position/type/value fields")
        if not isinstance(cell["row"], int) or isinstance(cell["row"], bool) or cell["row"] < 1:
            raise PipelineError(f"Invalid one-based row in {sheet_id} cell {index}")
        if not isinstance(cell["column"], int) or isinstance(cell["column"], bool) or cell["column"] < 1:
            raise PipelineError(f"Invalid one-based column in {sheet_id} cell {index}")
        if not isinstance(cell["address"], str) or not isinstance(cell["type"], str):
            raise PipelineError(f"Invalid address/type in {sheet_id} cell {index}")
        if cell["type"] == "formula" and "formula" not in cell:
            raise PipelineError(f"Formula cell lacks its calculation model in {sheet_id} cell {index}")
        if "image" in cell and not isinstance(cell["image"], dict):
            raise PipelineError(f"Image reference must be an object in {sheet_id} cell {index}")
    return cells


def _normalize_cell(cell: dict[str, Any], asset_by_file: dict[str, dict[str, Any]]) -> dict[str, Any]:
    normalized: dict[str, Any] = {
        "row": cell["row"],
        "column": cell["column"],
        "address": cell["address"],
        "type": cell["type"],
    }
    if cell["type"] == "formula":
        normalized["formula"] = {"cached_value": cell["value"], "model": cell["formula"]}
    else:
        normalized["value"] = cell["value"]
    if "rich_text" in cell:
        normalized["rich_text_runs"] = cell["rich_text"]
    if "image" in cell:
        image_reference = dict(cell["image"])
        asset = asset_by_file.get(image_reference.get("local_file"))
        image_reference["source_asset_sha256"] = asset.get("sha256") if asset else None
        normalized["image_reference"] = image_reference
    extras = {key: value for key, value in cell.items() if key not in KNOWN_CELL_FIELDS}
    if extras:
        normalized["source_fields"] = extras
    return normalized


def _derive_sheet(context: dict[str, Any], entry: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any], dict[str, int], set[tuple[str, str]]]:
    relative_source = entry.get("table_file")
    table_path = _safe_source_path(context["source_dir"], relative_source)
    table, source_checksum = _load_json(table_path)
    cells = _validate_source_table(context, entry, table, table_path)
    normalized_cells = [_normalize_cell(cell, context["asset_by_file"]) for cell in cells]
    source_relative_path = Path(relative_source).as_posix()
    output_relative_path = f"tables/{entry['sheet_id']}.json"
    metadata = {key: value for key, value in table.items() if key != "cells"}
    document = {
        "schema_version": SCHEMA_VERSION,
        "provenance": {
            "source_file": source_relative_path,
            "source_sha256": source_checksum,
        },
        "metadata": metadata,
        "cells": normalized_cells,
    }
    cell_types = Counter(cell["type"] for cell in cells)
    image_refs = {
        (cell["image"].get("local_file", ""), cell["image"].get("url", ""))
        for cell in cells
        if "image" in cell
    }
    sheet_summary = {
        "sheet_id": entry["sheet_id"],
        "name": entry["name"],
        "status": "available",
        "revision": table["revision"],
        "source_file": source_relative_path,
        "source_sha256": source_checksum,
        "output_file": output_relative_path,
        "output_sha256": "",
        "stored_cell_count": len(cells),
        "cell_types": dict(sorted(cell_types.items())),
        "image_reference_count": sum(1 for cell in cells if "image" in cell),
        "merge_count": len(table["merges"]),
    }
    stats = {
        "cells": len(cells),
        "rich_text_cells": cell_types.get("rich_text", 0),
        "formula_cells": cell_types.get("formula", 0),
        "image_references": sheet_summary["image_reference_count"],
        "merge_ranges": len(table["merges"]),
    }
    return document, sheet_summary, stats, image_refs


def _unavailable_sheets(context: dict[str, Any]) -> list[dict[str, Any]]:
    unavailable: list[dict[str, Any]] = []
    for entry in context["catalog"]:
        if entry["status"] != "unavailable":
            continue
        sheet_id = entry["sheet_id"]
        descriptor = context["descriptors"][sheet_id]
        missing = context["missing_by_id"].get(sheet_id, {})
        unavailable_entry: dict[str, Any] = {
            "sheet_id": sheet_id,
            "name": entry["name"],
            "status": "unavailable",
            "hidden": bool(descriptor.get("hidden", missing.get("hidden", False))),
            "reason": missing.get("reason", "Unavailable in source snapshot; no reason was recorded."),
        }
        if "rows" in descriptor and "columns" in descriptor:
            unavailable_entry["dimensions"] = {"rows": descriptor["rows"], "columns": descriptor["columns"]}
        unavailable.append(unavailable_entry)
    return unavailable


def _make_manifest(
    context: dict[str, Any],
    sheet_records: list[dict[str, Any]],
    totals: dict[str, int],
    all_image_refs: set[tuple[str, str]],
) -> dict[str, Any]:
    unavailable = _unavailable_sheets(context)
    unresolved_assets = sorted(
        {file_name for file_name, _ in all_image_refs if not context["asset_by_file"].get(file_name)}
    )
    source_metadata = context["source_metadata"]
    asset_hashes_missing = sorted(
        {
            file_name
            for file_name, _ in all_image_refs
            if context["asset_by_file"].get(file_name, {}).get("sha256") is None
        }
    )
    cell_types: Counter[str] = Counter()
    for record in sheet_records:
        cell_types.update(record["cell_types"])
    return {
        "schema_version": SCHEMA_VERSION,
        "generator": GENERATOR_ID,
        "source": {
            "uri": source_metadata.get("url"),
            "title": source_metadata.get("title"),
            "revision": source_metadata.get("revision"),
            "retrieved_at": source_metadata.get("retrieved_at"),
            "state": source_metadata.get("state"),
            "files": [context["source_files"][name] for name in sorted(context["source_files"])],
        },
        "coverage": {
            "listed_sheets": len(context["catalog"]),
            "available_sheets": len(sheet_records),
            "unavailable_sheets": len(unavailable),
            "typed_cells": totals["cells"],
            "cell_types": dict(sorted(cell_types.items())),
            "rich_text_cells": totals["rich_text_cells"],
            "formula_cells": totals["formula_cells"],
            "image_references": totals["image_references"],
            "unique_image_assets": len({file_name for file_name, _ in all_image_refs if file_name}),
            "unresolved_image_assets": unresolved_assets,
            "image_assets_without_sha256": asset_hashes_missing,
            "merge_ranges": totals["merge_ranges"],
        },
        "sheets": sheet_records,
        "unavailable_sheets": unavailable,
    }


def _safe_output_file(root: Path, relative_path: str) -> Path:
    path = (root / relative_path).resolve()
    if not _is_within(path, root.resolve()) or path == root.resolve():
        raise PipelineError(f"Generated path escapes output directory: {relative_path}")
    return path


def _ensure_owned_output(output_dir: Path) -> None:
    if not output_dir.exists():
        return
    if not output_dir.is_dir():
        raise PipelineError(f"Output path exists and is not a directory: {output_dir}")
    manifest_path = output_dir / "manifest.json"
    if not manifest_path.is_file():
        if any(output_dir.iterdir()):
            raise PipelineError(f"Refusing to replace non-empty unowned output directory: {output_dir}")
        return
    manifest, _ = _load_json(manifest_path)
    if not isinstance(manifest, dict) or manifest.get("generator") != GENERATOR_ID:
        raise PipelineError(f"Refusing to replace output not owned by {GENERATOR_ID}: {output_dir}")
    expected_files = {"manifest.json"}
    for sheet in manifest.get("sheets", []):
        if isinstance(sheet, dict) and isinstance(sheet.get("output_file"), str):
            expected_files.add(sheet["output_file"])
    actual_files = {
        path.relative_to(output_dir).as_posix()
        for path in output_dir.rglob("*")
        if path.is_file()
    }
    if actual_files != expected_files:
        raise PipelineError(f"Refusing to replace output with unowned or unexpected files: {output_dir}")


def convert_snapshot(source_dir: Path | str, output_dir: Path | str) -> dict[str, Any]:
    source_path = Path(source_dir).resolve()
    target_path = Path(output_dir).resolve()
    _require_non_overlapping_paths(source_path, target_path)
    context = _load_context(source_path)
    _ensure_owned_output(target_path)

    target_path.parent.mkdir(parents=True, exist_ok=True)
    stage_path = Path(tempfile.mkdtemp(prefix=f".{target_path.name}.stage-", dir=target_path.parent))
    totals = Counter()
    sheet_records: list[dict[str, Any]] = []
    all_image_refs: set[tuple[str, str]] = set()
    try:
        for entry in context["catalog"]:
            if entry["status"] != "available":
                continue
            document, record, stats, image_refs = _derive_sheet(context, entry)
            output_bytes = _json_bytes(document)
            record["output_sha256"] = hashlib.sha256(output_bytes).hexdigest()
            output_path = _safe_output_file(stage_path, record["output_file"])
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_bytes(output_bytes)
            sheet_records.append(record)
            totals.update(stats)
            all_image_refs.update(image_refs)

        manifest = _make_manifest(context, sheet_records, dict(totals), all_image_refs)
        manifest_bytes = _json_bytes(manifest)
        (stage_path / "manifest.json").write_bytes(manifest_bytes)
        _publish_stage(stage_path, target_path)
    except Exception:
        if stage_path.exists():
            shutil.rmtree(stage_path)
        raise

    return {
        "output_dir": str(target_path),
        "available_sheets": manifest["coverage"]["available_sheets"],
        "unavailable_sheets": manifest["coverage"]["unavailable_sheets"],
        "typed_cells": manifest["coverage"]["typed_cells"],
        "image_references": manifest["coverage"]["image_references"],
        "files_written": len(sheet_records) + 1,
        "manifest_sha256": hashlib.sha256(manifest_bytes).hexdigest(),
    }


def _publish_stage(stage_path: Path, target_path: Path) -> None:
    backup_path: Path | None = None
    if target_path.exists():
        backup_path = target_path.parent / f".{target_path.name}.backup-{uuid.uuid4().hex}"
        os.replace(target_path, backup_path)
    try:
        os.replace(stage_path, target_path)
    except Exception:
        if backup_path is not None and backup_path.exists():
            os.replace(backup_path, target_path)
        raise
    if backup_path is not None:
        shutil.rmtree(backup_path)


def validate_package(source_dir: Path | str, output_dir: Path | str) -> dict[str, Any]:
    source_path = Path(source_dir).resolve()
    output_path = Path(output_dir).resolve()
    _require_non_overlapping_paths(source_path, output_path)
    context = _load_context(source_path)
    if not output_path.is_dir():
        raise PipelineError(f"Output package does not exist: {output_path}")
    manifest_path = output_path / "manifest.json"
    manifest, _ = _load_json(manifest_path)
    if not isinstance(manifest, dict):
        raise PipelineError("manifest.json must be an object")

    totals = Counter()
    expected_records: list[dict[str, Any]] = []
    all_image_refs: set[tuple[str, str]] = set()
    expected_files = {"manifest.json"}
    for entry in context["catalog"]:
        if entry["status"] != "available":
            continue
        document, record, stats, image_refs = _derive_sheet(context, entry)
        expected_bytes = _json_bytes(document)
        record["output_sha256"] = hashlib.sha256(expected_bytes).hexdigest()
        output_relative = record["output_file"]
        expected_files.add(output_relative)
        actual_path = _safe_output_file(output_path, output_relative)
        if not actual_path.is_file():
            raise PipelineError(f"Missing converted sheet: {output_relative}")
        actual_bytes = actual_path.read_bytes()
        if actual_bytes != expected_bytes:
            raise PipelineError(f"Converted sheet differs from deterministic source mapping: {output_relative}")
        expected_records.append(record)
        totals.update(stats)
        all_image_refs.update(image_refs)

    expected_manifest = _make_manifest(context, expected_records, dict(totals), all_image_refs)
    expected_manifest_bytes = _json_bytes(expected_manifest)
    if (output_path / "manifest.json").read_bytes() != expected_manifest_bytes:
        raise PipelineError("manifest.json differs from source coverage or checksums")
    actual_files = {
        path.relative_to(output_path).as_posix()
        for path in output_path.rglob("*")
        if path.is_file()
    }
    if actual_files != expected_files:
        raise PipelineError("Output package has missing or unexpected files")
    return {
        "valid": True,
        "available_sheets": expected_manifest["coverage"]["available_sheets"],
        "unavailable_sheets": expected_manifest["coverage"]["unavailable_sheets"],
        "typed_cells": expected_manifest["coverage"]["typed_cells"],
        "image_references": expected_manifest["coverage"]["image_references"],
        "manifest_sha256": hashlib.sha256(expected_manifest_bytes).hexdigest(),
    }


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _parse_cli(command: str) -> argparse.Namespace:
    repo_root = _repo_root()
    default_source = repo_root / "data" / "tencent-sheet"
    default_output = repo_root / "data" / "derived" / "tencent-sheet"
    parser = argparse.ArgumentParser(description=f"Tencent spreadsheet snapshot {command}")
    parser.add_argument("--input-dir", type=Path, default=default_source)
    parser.add_argument("--output-dir", type=Path, default=default_output)
    args = parser.parse_args()
    allowed_output_root = (repo_root / "data" / "derived").resolve()
    if not _is_within(args.output_dir.resolve(), allowed_output_root):
        parser.error(f"--output-dir must be inside {allowed_output_root}")
    return args


def convert_main() -> int:
    args = _parse_cli("conversion")
    try:
        result = convert_snapshot(args.input_dir, args.output_dir)
    except (PipelineError, OSError) as exc:
        print(f"Conversion failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


def validate_main() -> int:
    args = _parse_cli("validation")
    try:
        result = validate_package(args.input_dir, args.output_dir)
    except (PipelineError, OSError) as exc:
        print(f"Validation failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 0
