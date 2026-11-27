from __future__ import annotations

import csv
from collections import Counter
from datetime import datetime, timezone
from io import StringIO
import hashlib
import json
import os
from pathlib import Path
import tempfile
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
PACKAGE = REPO_ROOT / "data" / "games" / "engage"
VALID_REVIEW_STATUSES = {"reviewed", "not_data_image", "unreadable"}
CSV_REVIEW_FIELDS = (
    "human_corrected_text",
    "visual_classification",
    "review_batch_id",
    "review_reason",
)


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    results = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict):
            raise ValueError(f"{path.name}:{line_number} 必须是 JSON 对象")
        results.append(value)
    return results


def _write_atomic(path: Path, data: bytes) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, path)
    except Exception:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _jsonl_bytes(values: list[dict[str, Any]]) -> bytes:
    return "".join(
        json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n"
        for value in values
    ).encode("utf-8")


def _locations_key(locations: list[dict[str, Any]]) -> list[tuple[Any, Any, Any]]:
    return [
        (location.get("sheet_number"), location.get("sheet_name"), location.get("from_cell"))
        for location in locations
    ]


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apply_reviews(package: Path = PACKAGE) -> dict[str, Any]:
    review_path = package / "reports" / "visual-review-batches.jsonl"
    ocr_path = package / "reports" / "ocr-results.jsonl"
    csv_path = package / "reports" / "ocr-review.csv"
    summary_path = package / "reports" / "ocr-summary.json"
    manifest_path = package / "assets" / "manifest.json"
    package_manifest_path = package / "manifest.json"
    coverage_path = package / "reports" / "coverage.json"

    decisions = _read_jsonl(review_path)
    decision_by_asset: dict[str, dict[str, Any]] = {}
    for decision in decisions:
        asset_id = decision.get("asset_id")
        if not isinstance(asset_id, str) or asset_id in decision_by_asset:
            raise ValueError(f"人工复核日志中的 asset_id 缺失或重复：{asset_id!r}")
        if decision.get("review_status") not in VALID_REVIEW_STATUSES:
            raise ValueError(f"不支持的人工复核状态：{asset_id}")
        if decision.get("classification_status") != "reviewed":
            raise ValueError(f"复核过的图片必须有已复核分类：{asset_id}")
        if not isinstance(decision.get("visual_classification"), str) or not decision["visual_classification"]:
            raise ValueError(f"图片缺少视觉分类：{asset_id}")
        if not isinstance(decision.get("corrected_text"), str):
            raise ValueError(f"人工更正文案必须是字符串：{asset_id}")
        if not isinstance(decision.get("reason"), str) or not decision["reason"]:
            raise ValueError(f"人工复核缺少原因：{asset_id}")
        if not isinstance(decision.get("batch_id"), str) or not decision["batch_id"]:
            raise ValueError(f"人工复核缺少批次 ID：{asset_id}")
        decision_by_asset[asset_id] = decision

    manifest = _read_json(manifest_path)
    manifest.setdefault("rights_status", "unknown")
    manifest.setdefault("redistribution_allowed", False)
    assets = manifest.get("assets", [])
    asset_by_id = {asset.get("asset_id"): asset for asset in assets}
    if len(asset_by_id) != len(assets):
        raise ValueError("图片 manifest 中存在重复 asset_id")
    for asset in assets:
        asset.setdefault("rights_status", "unknown")
        asset.setdefault("redistribution_allowed", False)

    ocr_results = _read_jsonl(ocr_path)
    ocr_by_id = {result.get("asset_id"): result for result in ocr_results}
    if len(ocr_by_id) != len(ocr_results):
        raise ValueError("OCR 结果中存在重复 asset_id")

    for asset_id, decision in decision_by_asset.items():
        asset = asset_by_id.get(asset_id)
        result = ocr_by_id.get(asset_id)
        if asset is None or result is None:
            raise ValueError(f"复核记录引用了缺失的图片或 OCR 结果：{asset_id}")
        if decision.get("source_locations") is not None:
            if _locations_key(decision["source_locations"]) != _locations_key(asset.get("source_locations", [])):
                raise ValueError(f"复核记录的工作表锚点与图片 manifest 不一致：{asset_id}")
        source_path = (package / asset["original_file"]).resolve()
        if not source_path.is_relative_to(package.resolve()) or _sha256_file(source_path) != asset["sha256"]:
            raise ValueError(f"复核图片路径越界、缺失或 SHA-256 不匹配：{asset_id}")
        candidate_text = " | ".join(
            str(block.get("text", "")) for block in result.get("blocks", []) if block.get("text")
        )
        if decision.get("ocr_candidate_text") != candidate_text:
            raise ValueError(f"复核记录的 OCR 候选与当前识别结果不一致：{asset_id}")

        human_review = {
            "batch_id": decision["batch_id"],
            "visual_classification": decision["visual_classification"],
            "corrected_text": decision["corrected_text"],
            "reason": decision["reason"],
        }
        reviewed_at = decision.get("reviewed_at")
        if reviewed_at:
            human_review["reviewed_at"] = reviewed_at
        result["review_status"] = decision["review_status"]
        result["human_review"] = human_review
        result["reviewer_notes"] = decision["reason"]
        asset["classification"] = decision["visual_classification"]
        asset["classification_status"] = "reviewed"
        classification_review = {
            "batch_id": decision["batch_id"],
            "reason": decision["reason"],
        }
        if reviewed_at:
            classification_review["reviewed_at"] = reviewed_at
        asset["classification_review"] = classification_review

    csv_text = csv_path.read_text(encoding="utf-8-sig")
    reader = csv.DictReader(StringIO(csv_text))
    fieldnames = list(reader.fieldnames or [])
    for field in CSV_REVIEW_FIELDS:
        if field not in fieldnames:
            fieldnames.append(field)
    csv_rows = list(reader)
    for row in csv_rows:
        decision = decision_by_asset.get(row.get("asset_id", ""))
        if decision is None:
            row.setdefault("reviewer_notes", "")
            for field in CSV_REVIEW_FIELDS:
                row.setdefault(field, "")
            continue
        row["review_status"] = decision["review_status"]
        row["reviewer_notes"] = decision["reason"]
        row["human_corrected_text"] = decision["corrected_text"]
        row["visual_classification"] = decision["visual_classification"]
        row["review_batch_id"] = decision["batch_id"]
        row["review_reason"] = decision["reason"]

    csv_buffer = StringIO(newline="")
    writer = csv.DictWriter(csv_buffer, fieldnames=fieldnames, lineterminator="\r\n")
    writer.writeheader()
    writer.writerows(csv_rows)
    csv_bytes = ("\ufeff" + csv_buffer.getvalue()).encode("utf-8")

    summary = _read_json(summary_path)
    review_status_counts = Counter(result.get("review_status", "pending_review") for result in ocr_results)
    classification_counts = Counter(asset.get("classification", "") for asset in assets)
    pending_count = review_status_counts.get("pending_review", 0)
    latest_reviewed_at = max(
        (decision.get("reviewed_at", "") for decision in decisions),
        default="",
    ) or None
    summary.setdefault("ocr_scan_generated_at", summary.get("generated_at"))
    summary["human_review"] = {
        "review_status_counts": dict(sorted(review_status_counts.items())),
        "assets_reviewed": len(decision_by_asset),
        "assets_pending_review": pending_count,
        "visual_classification_counts": dict(sorted(
            (name, count) for name, count in classification_counts.items()
            if name and name != "unclassified_pending_visual_review"
        )),
        "reviewed_batches": list(dict.fromkeys(decision["batch_id"] for decision in decisions)),
        "latest_reviewed_at": latest_reviewed_at,
    }
    summary["review_updated_at"] = latest_reviewed_at
    summary["human_review_status"] = (
        "complete" if pending_count == 0
        else "partial" if pending_count < len(ocr_results)
        else "pending"
    )
    summary["all_results_require_human_review"] = pending_count == len(ocr_results)
    summary["remaining_results_require_human_review"] = pending_count > 0
    summary["all_results_require_human_review_semantics"] = (
        "True only when every OCR result remains pending_review; inspect human_review.review_status_counts for partial progress."
    )

    outputs = {
        ocr_path: _jsonl_bytes(ocr_results),
        csv_path: csv_bytes,
        manifest_path: _json_bytes(manifest),
        summary_path: _json_bytes(summary),
    }
    package_manifest = _read_json(package_manifest_path)
    source_metadata = package_manifest.setdefault("source", {})
    source_metadata.setdefault("rights_status", "unknown")
    source_metadata.setdefault("redistribution_allowed", False)
    package_manifest["assets_manifest_sha256"] = hashlib.sha256(_json_bytes(manifest)).hexdigest()
    package_manifest["updated_at"] = datetime.now(timezone.utc).isoformat()
    outputs[package_manifest_path] = _json_bytes(package_manifest)
    if coverage_path.is_file():
        coverage = _read_json(coverage_path)
        coverage["ocr"] = {key: value for key, value in summary.items() if key != "processed_asset_ids"}
        coverage["visual_review"] = summary["human_review"]
        outputs[coverage_path] = _json_bytes(coverage)

    for path, content in outputs.items():
        _write_atomic(path, content)

    return {
        "reviewed_assets": len(decision_by_asset),
        "review_status_counts": dict(sorted(review_status_counts.items())),
        "updated_files": [str(path.relative_to(package)) for path in outputs],
    }


def main() -> int:
    result = apply_reviews()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
