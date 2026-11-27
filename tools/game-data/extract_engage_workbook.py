from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import mimetypes
import os
import posixpath
import shutil
import sys
import tempfile
import zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
import xml.etree.ElementTree as ET

from PIL import Image, ImageOps, features


GENERATOR = "fireemblem/tools/game-data/extract-engage-workbook"
SCHEMA_VERSION = 1
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = REPO_ROOT / "data" / "《火焰之纹章 ENGAGE结合》在线数据表.xlsx"
DEFAULT_OUTPUT = REPO_ROOT / "data" / "games" / "engage"

MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
DOC_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
DRAWING_MAIN = "http://schemas.openxmlformats.org/drawingml/2006/main"
NS = {"m": MAIN, "r": DOC_REL, "p": PKG_REL, "xdr": DRAWING, "a": DRAWING_MAIN}


class ExtractionError(Exception):
    pass


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _column_name(number: int) -> str:
    result = ""
    while number:
        number, remainder = divmod(number - 1, 26)
        result = chr(65 + remainder) + result
    return result


def _column_number(reference: str) -> int | None:
    letters = "".join(character for character in reference if character.isalpha())
    if not letters:
        return None
    number = 0
    for character in letters.upper():
        number = number * 26 + ord(character) - 64
    return number


def _read_xml(archive: zipfile.ZipFile, name: str) -> ET.Element:
    try:
        return ET.fromstring(archive.read(name))
    except (KeyError, ET.ParseError) as error:
        raise ExtractionError(f"无法读取工作簿 XML 部件 {name}: {error}") from error


def _part_relationships(archive: zipfile.ZipFile, source_part: str) -> dict[str, dict[str, str]]:
    directory, filename = posixpath.split(source_part)
    relation_part = posixpath.join(directory, "_rels", filename + ".rels")
    try:
        root = _read_xml(archive, relation_part)
    except ExtractionError as error:
        if "无法读取工作簿 XML 部件" in str(error) and relation_part not in archive.namelist():
            return {}
        raise
    result: dict[str, dict[str, str]] = {}
    for relation in root.findall("p:Relationship", NS):
        relation_id = relation.attrib.get("Id")
        target = relation.attrib.get("Target")
        if not relation_id or not target:
            continue
        if target.startswith("/"):
            target_part = posixpath.normpath(target.lstrip("/"))
        else:
            target_part = posixpath.normpath(posixpath.join(directory, target))
        result[relation_id] = {
            "target": target_part,
            "type": relation.attrib.get("Type", ""),
            "target_mode": relation.attrib.get("TargetMode", "Internal"),
        }
    return result


def _shared_strings(archive: zipfile.ZipFile) -> list[dict[str, Any]]:
    if "xl/sharedStrings.xml" not in archive.namelist():
        return []
    root = _read_xml(archive, "xl/sharedStrings.xml")
    strings: list[dict[str, Any]] = []
    for item in root.findall("m:si", NS):
        runs: list[dict[str, Any]] = []
        for child in item:
            if child.tag == f"{{{MAIN}}}t":
                runs.append({"text": child.text or "", "properties": None})
            elif child.tag == f"{{{MAIN}}}r":
                text_node = child.find("m:t", NS)
                properties = child.find("m:rPr", NS)
                prop_data = None
                if properties is not None:
                    prop_data = [
                        {"name": prop.tag.rsplit("}", 1)[-1], "attributes": dict(prop.attrib)}
                        for prop in list(properties)
                    ]
                runs.append({"text": text_node.text or "" if text_node is not None else "", "properties": prop_data})
        strings.append({"text": "".join(run["text"] for run in runs), "rich_text_runs": runs})
    return strings


def _styles(archive: zipfile.ZipFile) -> dict[str, Any]:
    if "xl/styles.xml" not in archive.namelist():
        return {"custom_number_formats": {}, "cell_formats": []}
    root = _read_xml(archive, "xl/styles.xml")
    custom_formats: dict[str, str] = {}
    number_formats = root.find("m:numFmts", NS)
    if number_formats is not None:
        for item in number_formats.findall("m:numFmt", NS):
            if "numFmtId" in item.attrib:
                custom_formats[item.attrib["numFmtId"]] = item.attrib.get("formatCode", "")
    xfs = root.find("m:cellXfs", NS)
    formats = []
    if xfs is not None:
        for item in xfs.findall("m:xf", NS):
            formats.append({"attributes": dict(item.attrib)})
    return {"custom_number_formats": custom_formats, "cell_formats": formats}


def _cell_payload(cell: ET.Element, shared: list[dict[str, Any]]) -> dict[str, Any]:
    reference = cell.attrib.get("r", "")
    row_text = "".join(character for character in reference if character.isdigit())
    row = int(row_text) if row_text else None
    column = _column_number(reference)
    cell_type = cell.attrib.get("t", "n")
    raw_value_node = cell.find("m:v", NS)
    raw_value = raw_value_node.text if raw_value_node is not None else None
    formula_node = cell.find("m:f", NS)
    inline_node = cell.find("m:is", NS)
    value: Any = raw_value
    rich_runs = None

    if cell_type == "s" and raw_value is not None:
        try:
            shared_item = shared[int(raw_value)]
        except (ValueError, IndexError):
            shared_item = None
        if shared_item is not None:
            value = shared_item["text"]
            rich_runs = shared_item["rich_text_runs"] if len(shared_item["rich_text_runs"]) > 1 else None
    elif cell_type == "b" and raw_value in {"0", "1"}:
        value = raw_value == "1"
    elif cell_type == "inlineStr" and inline_node is not None:
        inline_texts = [node.text or "" for node in inline_node.iter(f"{{{MAIN}}}t")]
        value = "".join(inline_texts)
        inline_runs = []
        for run in inline_node.findall("m:r", NS):
            text_node = run.find("m:t", NS)
            prop = run.find("m:rPr", NS)
            inline_runs.append({
                "text": text_node.text or "" if text_node is not None else "",
                "properties": None if prop is None else [
                    {"name": child.tag.rsplit("}", 1)[-1], "attributes": dict(child.attrib)}
                    for child in list(prop)
                ],
            })
        if inline_runs:
            rich_runs = inline_runs

    payload: dict[str, Any] = {
        "row": row,
        "column": column,
        "address": reference,
        "type": cell_type,
        "style_index": int(cell.attrib["s"]) if cell.attrib.get("s", "").isdigit() else None,
        "value": value,
        "raw_value": raw_value,
    }
    if formula_node is not None:
        payload["formula"] = {
            "text": formula_node.text or "",
            "attributes": dict(formula_node.attrib),
            "cached_value": value,
            "cached_raw_value": raw_value,
        }
    if rich_runs is not None:
        payload["rich_text_runs"] = rich_runs
    return payload


def _row_and_column_metadata(sheet_root: ET.Element) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    rows = []
    sheet_data = sheet_root.find("m:sheetData", NS)
    if sheet_data is not None:
        for row in sheet_data.findall("m:row", NS):
            attrs = dict(row.attrib)
            if attrs:
                rows.append(attrs)
    cols = sheet_root.find("m:cols", NS)
    columns = [dict(col.attrib) for col in cols.findall("m:col", NS)] if cols is not None else []
    return rows, columns


def _drawing_placements(
    archive: zipfile.ZipFile,
    drawing_part: str,
    sheet_number: int,
    sheet_name: str,
    media_lookup: dict[str, dict[str, Any]],
) -> tuple[list[dict[str, Any]], int]:
    root = _read_xml(archive, drawing_part)
    relationships = _part_relationships(archive, drawing_part)
    placements: list[dict[str, Any]] = []
    chart_frames = len(root.findall(".//xdr:graphicFrame", NS))

    def marker_data(anchor: ET.Element | None) -> dict[str, int] | None:
        if anchor is None:
            return None
        values: dict[str, int] = {}
        for name in ("col", "colOff", "row", "rowOff"):
            child = anchor.find(f"xdr:{name}", NS)
            if child is not None and child.text is not None:
                try:
                    values[name] = int(child.text)
                except ValueError:
                    pass
        return values or None

    for anchor_index, anchor in enumerate(list(root), start=1):
        if anchor.tag not in {
            f"{{{DRAWING}}}twoCellAnchor",
            f"{{{DRAWING}}}oneCellAnchor",
            f"{{{DRAWING}}}absoluteAnchor",
        }:
            continue
        from_data = marker_data(anchor.find("xdr:from", NS))
        to_data = marker_data(anchor.find("xdr:to", NS))
        anchor_type = anchor.tag.rsplit("}", 1)[-1]
        for pic_index, picture in enumerate(anchor.findall(".//xdr:pic", NS), start=1):
            blip = picture.find(".//a:blip", NS)
            relation_id = None
            if blip is not None:
                relation_id = blip.attrib.get(f"{{{DOC_REL}}}embed") or blip.attrib.get(f"{{{DOC_REL}}}link")
            relation = relationships.get(relation_id or "", {})
            part = relation.get("target")
            media = media_lookup.get(part or "")
            nonvisual = picture.find("xdr:nvPicPr/xdr:cNvPr", NS)
            shape = {}
            if nonvisual is not None:
                shape = {key: nonvisual.attrib[key] for key in ("id", "name", "descr", "title") if key in nonvisual.attrib}
            from_cell = None
            if from_data and "row" in from_data and "col" in from_data:
                from_cell = f"{_column_name(from_data['col'] + 1)}{from_data['row'] + 1}"
            placement_id = f"sheet-{sheet_number:02d}-anchor-{anchor_index:04d}-picture-{pic_index:02d}"
            placements.append({
                "placement_id": placement_id,
                "sheet_number": sheet_number,
                "sheet_name": sheet_name,
                "drawing_part": drawing_part,
                "anchor_index": anchor_index,
                "picture_index_in_anchor": pic_index,
                "anchor_type": anchor_type,
                "from": from_data,
                "from_cell": from_cell,
                "to": to_data,
                "shape": shape,
                "relationship_id": relation_id,
                "media_part": part,
                "asset_id": media.get("asset_id") if media else None,
                "source_sha256": media.get("sha256") if media else None,
                "relationship_status": "resolved" if media else "missing_or_external",
            })
    return placements, chart_frames


def _extract_media(
    archive: zipfile.ZipFile,
    stage: Path,
) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    original_dir = stage / "assets" / "original"
    app_dir = stage / "assets" / "app"
    original_dir.mkdir(parents=True, exist_ok=True)
    app_dir.mkdir(parents=True, exist_ok=True)
    parts = sorted(name for name in archive.namelist() if name.startswith("xl/media/") and not name.endswith("/"))
    media_lookup: dict[str, dict[str, Any]] = {}
    parts_list: list[dict[str, Any]] = []
    assets_by_hash: dict[str, dict[str, Any]] = {}
    can_write_webp = bool(features.check("webp"))

    for media_part in parts:
        data = archive.read(media_part)
        checksum = _sha256(data)
        extension = Path(media_part).suffix.lower().lstrip(".") or "bin"
        if extension not in {"png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff", "emf", "wmf", "svg"}:
            extension = "bin"
        asset = assets_by_hash.get(checksum)
        if asset is None:
            asset_id = f"sha256:{checksum}"
            original_relative = f"assets/original/{checksum}.{extension}"
            original_path = stage / original_relative
            original_path.parent.mkdir(parents=True, exist_ok=True)
            original_path.write_bytes(data)
            width = height = None
            image_format = None
            animated = False
            app_relative = None
            app_status = "not_generated_unsupported_or_unreadable"
            mime_type = mimetypes.guess_type(media_part)[0]
            try:
                with Image.open(io.BytesIO(data)) as image:
                    width, height = image.size
                    image_format = image.format
                    animated = bool(getattr(image, "is_animated", False))
                    if can_write_webp and not animated:
                        clean = ImageOps.exif_transpose(image.copy())
                        if "A" in clean.getbands() or "transparency" in clean.info:
                            clean = clean.convert("RGBA")
                        elif clean.mode not in {"RGB", "L"}:
                            clean = clean.convert("RGB")
                        app_name = f"{checksum}.webp"
                        app_path = stage / "assets" / "app" / app_name
                        clean.save(app_path, format="WEBP", lossless=True, quality=100, method=6)
                        app_relative = f"assets/app/{app_name}"
                        app_status = "lossless_webp_no_resize"
                        mime_type = "image/webp"
            except (OSError, ValueError, Image.DecompressionBombError):
                pass
            asset = {
                "asset_id": asset_id,
                "sha256": checksum,
                "original_file": original_relative,
                "extension": extension,
                "mime_type": mime_type or "application/octet-stream",
                "image_format": image_format,
                "width": width,
                "height": height,
                "animated": animated,
                "source_bytes": len(data),
                "app_file": app_relative,
                "app_status": app_status,
                "app_sha256": _sha256_file(stage / app_relative) if app_relative else None,
                "classification": "unclassified_pending_visual_review",
                "rights_status": "unknown",
                "redistribution_allowed": False,
                "redistribution_status": "not_assessed_do_not_redistribute",
            }
            assets_by_hash[checksum] = asset
        media_lookup[media_part] = asset
        parts_list.append({
            "media_part": media_part,
            "asset_id": asset["asset_id"],
            "sha256": checksum,
            "bytes": len(data),
            "duplicate_content": sum(1 for previous in parts_list if previous["sha256"] == checksum) > 0,
        })
    return media_lookup, parts_list, list(assets_by_hash.values())


def _sheet_data(
    archive: zipfile.ZipFile,
    sheet_part: str,
    sheet_descriptor: dict[str, Any],
    shared: list[dict[str, Any]],
    media_lookup: dict[str, dict[str, Any]],
) -> tuple[dict[str, Any], list[dict[str, Any]], int]:
    root = _read_xml(archive, sheet_part)
    dimension = root.find("m:dimension", NS)
    cells = [_cell_payload(cell, shared) for cell in root.findall(".//m:sheetData/m:row/m:c", NS)]
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
        first_address = f"{_column_name(min_column)}{min_row}"
        last_address = f"{_column_name(max_column)}{max_row}"
        observed_range = first_address if first_address == last_address else f"{first_address}:{last_address}"
    merges_node = root.find("m:mergeCells", NS)
    merges = [item.attrib.get("ref", "") for item in merges_node.findall("m:mergeCell", NS)] if merges_node is not None else []
    row_metadata, column_metadata = _row_and_column_metadata(root)
    drawing_node = root.find("m:drawing", NS)
    placements: list[dict[str, Any]] = []
    chart_count = 0
    drawing_part = None
    if drawing_node is not None:
        drawing_rel_id = drawing_node.attrib.get(f"{{{DOC_REL}}}id")
        relationship = _part_relationships(archive, sheet_part).get(drawing_rel_id or "")
        drawing_part = relationship.get("target") if relationship else None
        if drawing_part and drawing_part in archive.namelist():
            placements, chart_count = _drawing_placements(
                archive, drawing_part, sheet_descriptor["sheet_number"], sheet_descriptor["name"], media_lookup,
            )
    formula_count = sum(1 for cell in cells if "formula" in cell)
    table = {
        "schema_version": SCHEMA_VERSION,
        "sheet": sheet_descriptor,
        "source_part": sheet_part,
        "dimension": dimension.attrib.get("ref") if dimension is not None else None,
        "observed_range": observed_range,
        "rows": row_metadata,
        "columns": column_metadata,
        "cells": cells,
        "merges": merges,
        "drawing_part": drawing_part,
        "summary": {
            "explicit_cell_count": len(cells),
            "formula_count": formula_count,
            "merge_count": len(merges),
            "image_placement_count": len(placements),
            "native_chart_count": chart_count,
        },
    }
    return table, placements, formula_count


def _write_sheet(stage: Path, table: dict[str, Any], sheet_number: int) -> dict[str, Any]:
    sheet_id = f"sheet-{sheet_number:02d}"
    json_relative = f"raw/sheets/{sheet_id}.json"
    csv_relative = f"raw/csv/{sheet_id}.csv"
    json_path = stage / json_relative
    csv_path = stage / csv_relative
    json_path.parent.mkdir(parents=True, exist_ok=True)
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    json_data = _json_bytes(table)
    json_path.write_bytes(json_data)
    with csv_path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=[
            "row", "column", "address", "type", "style_index", "value", "raw_value", "formula", "formula_attributes",
        ])
        writer.writeheader()
        for cell in table["cells"]:
            formula = cell.get("formula") or {}
            writer.writerow({
                "row": cell.get("row"),
                "column": cell.get("column"),
                "address": cell.get("address"),
                "type": cell.get("type"),
                "style_index": cell.get("style_index"),
                "value": cell.get("value") if not isinstance(cell.get("value"), (dict, list)) else json.dumps(cell["value"], ensure_ascii=False),
                "raw_value": cell.get("raw_value"),
                "formula": formula.get("text"),
                "formula_attributes": json.dumps(formula.get("attributes", {}), ensure_ascii=False, sort_keys=True),
            })
    return {
        "sheet_number": sheet_number,
        "name": table["sheet"]["name"],
        "state": table["sheet"]["state"],
        "dimension": table["dimension"],
        "observed_range": table["observed_range"],
        **table["summary"],
        "json_file": json_relative,
        "json_sha256": _sha256(json_data),
        "json_bytes": len(json_data),
        "csv_file": csv_relative,
        "csv_sha256": _sha256_file(csv_path),
        "csv_bytes": csv_path.stat().st_size,
    }


def _workbook_descriptors(archive: zipfile.ZipFile) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    workbook = _read_xml(archive, "xl/workbook.xml")
    workbook_rels = _part_relationships(archive, "xl/workbook.xml")
    descriptors = []
    sheets_node = workbook.find("m:sheets", NS)
    if sheets_node is None:
        raise ExtractionError("工作簿未包含 sheets 清单")
    for index, sheet in enumerate(sheets_node.findall("m:sheet", NS), start=1):
        relation_id = sheet.attrib.get(f"{{{DOC_REL}}}id")
        relation = workbook_rels.get(relation_id or "")
        if relation is None:
            raise ExtractionError(f"工作表缺少有效的 workbook relationship：{sheet.attrib.get('name')}")
        descriptors.append({
            "sheet_number": index,
            "name": sheet.attrib.get("name", f"Sheet{index}"),
            "state": sheet.attrib.get("state", "visible"),
            "sheet_id": sheet.attrib.get("sheetId"),
            "relationship_id": relation_id,
            "part": relation["target"],
        })
    calc = workbook.find("m:calcPr", NS)
    defined_names = workbook.find("m:definedNames", NS)
    metadata = {
        "date1904": workbook.find("m:workbookPr", NS).attrib.get("date1904") if workbook.find("m:workbookPr", NS) is not None else None,
        "calculation_properties": dict(calc.attrib) if calc is not None else {},
        "defined_names": [dict(item.attrib, text=item.text or "") for item in defined_names.findall("m:definedName", NS)] if defined_names is not None else [],
    }
    return descriptors, metadata


def extract_workbook(input_path: Path, output_path: Path, expected_sha256: str | None = None) -> dict[str, Any]:
    source = input_path.resolve()
    output = output_path.resolve()
    if not source.is_file():
        raise ExtractionError(f"找不到输入工作簿：{source}")
    if source == output or source.is_relative_to(output) or output.is_relative_to(source):
        raise ExtractionError("工作簿输入与输出目录不能重叠")
    allowed_root = (REPO_ROOT / "data" / "games" / "engage").resolve()
    if output != allowed_root:
        raise ExtractionError(f"输出目录必须固定为 {allowed_root}")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ExtractionError(f"拒绝覆盖非空或非目录输出路径：{output}")
    output.parent.mkdir(parents=True, exist_ok=True)

    source_hash_before = _sha256_file(source)
    if expected_sha256 and source_hash_before.lower() != expected_sha256.lower():
        raise ExtractionError(f"输入 SHA-256 与预期不符：{source_hash_before}")
    stage = Path(tempfile.mkdtemp(prefix=".engage-extract-", dir=output.parent))
    try:
        with zipfile.ZipFile(source, "r") as archive:
            bad_part = archive.testzip()
            if bad_part:
                raise ExtractionError(f"XLSX ZIP 部件 CRC 校验失败：{bad_part}")
            descriptors, workbook_metadata = _workbook_descriptors(archive)
            shared = _shared_strings(archive)
            styles = _styles(archive)
            (stage / "raw").mkdir(parents=True, exist_ok=True)
            (stage / "raw" / "shared-strings.json").write_bytes(_json_bytes(shared))
            (stage / "raw" / "styles.json").write_bytes(_json_bytes(styles))
            (stage / "raw" / "workbook-metadata.json").write_bytes(_json_bytes(workbook_metadata))
            media_lookup, media_parts, assets = _extract_media(archive, stage)

            sheet_summaries = []
            all_placements: list[dict[str, Any]] = []
            total_formulas = 0
            total_cells = 0
            total_merges = 0
            total_charts = 0
            for descriptor in descriptors:
                table, placements, formula_count = _sheet_data(
                    archive, descriptor["part"], descriptor, shared, media_lookup,
                )
                summary = _write_sheet(stage, table, descriptor["sheet_number"])
                sheet_summaries.append(summary)
                all_placements.extend(placements)
                total_formulas += formula_count
                total_cells += summary["explicit_cell_count"]
                total_merges += summary["merge_count"]
                total_charts += summary["native_chart_count"]

            placement_json = _json_bytes(all_placements)
            (stage / "raw" / "image-placements.json").write_bytes(placement_json)
            assets_manifest = {
                "schema_version": SCHEMA_VERSION,
                "classification_status": "pending_visual_review",
                "rights_status": "unknown",
                "redistribution_allowed": False,
                "redistribution_status": "not_assessed_do_not_redistribute",
                "assets": sorted(assets, key=lambda asset: asset["sha256"]),
                "media_parts": media_parts,
                "placements_file": "../raw/image-placements.json",
                "placement_count": len(all_placements),
            }
            assets_json = _json_bytes(assets_manifest)
            (stage / "assets" / "manifest.json").write_bytes(assets_json)

            source_hash_after = _sha256_file(source)
            if source_hash_after != source_hash_before:
                raise ExtractionError("原始工作簿在提取期间发生变化；未发布输出")
            coverage = {
                "workbook_sheets": len(descriptors),
                "visible_sheets": sum(1 for item in descriptors if item["state"] == "visible"),
                "hidden_sheets": sum(1 for item in descriptors if item["state"] == "hidden"),
                "very_hidden_sheets": sum(1 for item in descriptors if item["state"] == "veryHidden"),
                "explicit_cells": total_cells,
                "formulas": total_formulas,
                "merges": total_merges,
                "embedded_image_objects": len(all_placements),
                "media_parts": len(media_parts),
                "unique_media_contents": len(assets),
                "duplicate_media_references": len(media_parts) - len(assets),
                "unresolved_picture_relationships": sum(1 for item in all_placements if item["asset_id"] is None),
                "native_excel_chart_frames": total_charts,
                "visual_review": "pending",
                "ocr": "not_run",
            }
            manifest = {
                "schema_version": SCHEMA_VERSION,
                "generator": GENERATOR,
                "game_id": "engage",
                "generated_at": datetime.now(timezone.utc).isoformat(),
                "source": {
                    "kind": "local_user_provided_workbook",
                    "file_name": source.name,
                    "file_size": source.stat().st_size,
                    "sha256_before": source_hash_before,
                    "sha256_after": source_hash_after,
                    "rights_status": "unknown",
                    "redistribution_allowed": False,
                    "redistribution_status": "source_rights_not_assessed",
                },
                "coverage": coverage,
                "sheets": sheet_summaries,
                "assets_manifest_sha256": _sha256(assets_json),
                "image_placements_sha256": _sha256(placement_json),
                "notes": [
                    "XLSX was read as an OOXML ZIP archive and was never saved or rewritten.",
                    "Raw worksheet values, formula text, cached values, explicit cell coordinates, styles, merges, row/column metadata and drawing anchors are preserved.",
                    "Image OCR and human verification have not been performed by this extraction stage.",
                    "Local possession of the workbook does not establish redistribution rights for its images or contents.",
                ],
            }
            (stage / "manifest.json").write_bytes(_json_bytes(manifest))

        if output.exists():
            output.rmdir()
        os.replace(stage, output)
        return {"output": str(output), **coverage, "input_sha256": source_hash_before}
    except Exception:
        shutil.rmtree(stage, ignore_errors=True)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description="只读提取 Engage XLSX 的工作表、公式、图片与锚点")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--expected-sha256")
    arguments = parser.parse_args()
    try:
        summary = extract_workbook(arguments.input, arguments.output, arguments.expected_sha256)
    except (ExtractionError, OSError, zipfile.BadZipFile) as error:
        print(f"Engage 提取失败：{error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
