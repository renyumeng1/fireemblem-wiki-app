from __future__ import annotations

import argparse
import csv
import hashlib
from importlib.metadata import version as package_version
import json
import platform
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PACKAGE = REPO_ROOT / "data" / "games" / "engage"


def _json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _json_safe(value: Any) -> Any:
    if hasattr(value, "tolist"):
        return _json_safe(value.tolist())
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)


def _normalize_result(result: Any) -> list[dict[str, Any]]:
    if not result:
        return []
    blocks = []
    for item in result:
        if not isinstance(item, (list, tuple)) or len(item) < 2:
            continue
        box = item[0]
        text = str(item[1]).strip()
        confidence = item[2] if len(item) > 2 else None
        if not text:
            continue
        blocks.append({"box": _json_safe(box), "text": text, "confidence": _json_safe(confidence)})
    return blocks


def run_ocr(package: Path, limit: int | None = None) -> dict[str, Any]:
    try:
        from rapidocr_onnxruntime import RapidOCR
    except ImportError as error:
        raise RuntimeError("请先安装 tools/game-data/requirements-ocr.txt") from error
    engine_version = package_version("rapidocr-onnxruntime")

    root = package.resolve()
    if root != DEFAULT_PACKAGE.resolve():
        raise ValueError(f"Engage package path must be {DEFAULT_PACKAGE.resolve()}")
    asset_manifest_path = root / "assets" / "manifest.json"
    asset_manifest = json.loads(asset_manifest_path.read_text(encoding="utf-8"))
    assets = asset_manifest["assets"]
    if limit is not None:
        assets = assets[:limit]

    reports = root / "reports"
    reports.mkdir(parents=True, exist_ok=True)
    journal_path = reports / "ocr-results.jsonl"
    existing: dict[str, dict[str, Any]] = {}
    if journal_path.exists():
        with journal_path.open("r", encoding="utf-8") as stream:
            for line_number, line in enumerate(stream, start=1):
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError as error:
                    raise RuntimeError(f"OCR journal line {line_number} is invalid; preserve it and repair before resuming") from error
                if isinstance(row, dict) and isinstance(row.get("asset_id"), str):
                    existing[row["asset_id"]] = row

    engine = RapidOCR()
    pending = [asset for asset in assets if asset["asset_id"] not in existing]
    started = time.monotonic()
    with journal_path.open("a", encoding="utf-8", newline="\n") as journal:
        for index, asset in enumerate(pending, start=1):
            image_path = root / asset["original_file"]
            if not image_path.is_file() or _sha256_file(image_path) != asset["sha256"]:
                raise RuntimeError(f"原图路径或 SHA-256 校验失败：{asset['asset_id']}")
            result, elapsed = engine(str(image_path))
            blocks = _normalize_result(result)
            row = {
                "asset_id": asset["asset_id"],
                "source_sha256": asset["sha256"],
                "source_file": asset["original_file"],
                "engine": f"rapidocr-onnxruntime {engine_version}",
                "language": "Chinese + English model; package default",
                "elapsed_seconds": float(elapsed) if isinstance(elapsed, (int, float)) else None,
                "blocks": blocks,
                "review_status": "pending_review",
                "reviewer_notes": "OCR output is a candidate only; verify against the original image before promoting facts.",
            }
            journal.write(_json_text(row) + "\n")
            journal.flush()
            existing[asset["asset_id"]] = row
            if index % 25 == 0 or index == len(pending):
                print(f"OCR progress: {len(existing)}/{len(assets)} assets; {time.monotonic() - started:.1f}s", flush=True)

    placements_path = root / "raw" / "image-placements.json"
    placements = json.loads(placements_path.read_text(encoding="utf-8")) if placements_path.is_file() else []
    placement_by_asset: dict[str, list[dict[str, Any]]] = {}
    for placement in placements:
        asset_id = placement.get("asset_id")
        if asset_id:
            placement_by_asset.setdefault(asset_id, []).append(placement)

    selected_ids = {asset["asset_id"] for asset in assets}
    results = [existing[asset["asset_id"]] for asset in assets if asset["asset_id"] in existing]
    with (reports / "ocr-review.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=[
            "asset_id", "sha256", "original_file", "app_file", "width", "height", "placement_count",
            "source_locations", "recognized_text", "blocks_json", "review_status", "reviewer_notes",
        ])
        writer.writeheader()
        for result in results:
            asset = next(item for item in assets if item["asset_id"] == result["asset_id"])
            source_locations = [
                {"sheet_number": item.get("sheet_number"), "sheet_name": item.get("sheet_name"), "from_cell": item.get("from_cell")}
                for item in placement_by_asset.get(result["asset_id"], [])
            ]
            writer.writerow({
                "asset_id": result["asset_id"],
                "sha256": result["source_sha256"],
                "original_file": result["source_file"],
                "app_file": asset.get("app_file"),
                "width": asset.get("width"),
                "height": asset.get("height"),
                "placement_count": len(source_locations),
                "source_locations": _json_text(source_locations),
                "recognized_text": "\n".join(block["text"] for block in result["blocks"]),
                "blocks_json": _json_text(result["blocks"]),
                "review_status": result["review_status"],
                "reviewer_notes": result["reviewer_notes"],
            })

    blocks_count = sum(len(result["blocks"]) for result in results)
    text_assets = sum(1 for result in results if result["blocks"])
    summary = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "engine": f"rapidocr-onnxruntime {engine_version}",
        "python": platform.python_version(),
        "assets_in_scope": len(assets),
        "assets_with_ocr_text": text_assets,
        "assets_without_detected_text": len(assets) - text_assets,
        "detected_text_blocks": blocks_count,
        "all_results_require_human_review": True,
        "processed_asset_ids": sorted(selected_ids.intersection(existing)),
        "jsonl": "reports/ocr-results.jsonl",
        "review_csv": "reports/ocr-review.csv",
    }
    (reports / "ocr-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {key: value for key, value in summary.items() if key != "processed_asset_ids"}


def main() -> int:
    parser = argparse.ArgumentParser(description="对 Engage 去重图片执行中文/英文 OCR，结果全部标为待人工复核")
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    parser.add_argument("--limit", type=int, help="仅处理前 N 张唯一图片；用于抽样验证模型")
    arguments = parser.parse_args()
    try:
        result = run_ocr(arguments.package, arguments.limit)
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(f"Engage OCR 失败：{error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
