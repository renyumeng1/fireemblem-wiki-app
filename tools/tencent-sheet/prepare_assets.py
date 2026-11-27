from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any

from PIL import Image, ImageOps


WIKI_SHEET_IDS = (
    "sd3o0k",
    "lnafol",
    "0x9327",
    "hbns9r",
    "qgu8m4",
    "14hhhl",
    "twr89r",
    "vbvhs5",
    "6bb00l",
    "7ypvuc",
    "bxlbpf",
    "fdm9oj",
    "d98iur",
    "h32fox",
    "rw261m",
    "lbz2o6",
)
IMAGE_COLUMNS_BY_SHEET = {"lbz2o6": frozenset({8})}
MAX_IMAGE_EDGE = 1600
WEBP_QUALITY = 86


class AssetPreparationError(Exception):
    pass


def prepare_assets(source_root: Path, package_root: Path) -> dict[str, int]:
    referenced: dict[str, str] = {}
    for sheet_id in WIKI_SHEET_IDS:
        table_path = package_root / "tables" / f"{sheet_id}.json"
        if not table_path.is_file():
            raise AssetPreparationError(f"缺少图鉴工作表：{table_path}")
        table = _read_json(table_path)
        for cell in table.get("cells", []):
            image_columns = IMAGE_COLUMNS_BY_SHEET.get(sheet_id)
            if image_columns is not None and cell.get("column") not in image_columns:
                continue
            image = cell.get("image_reference")
            if not isinstance(image, dict):
                continue
            local_file = image.get("local_file")
            checksum = image.get("source_asset_sha256")
            if not isinstance(local_file, str) or not isinstance(checksum, str):
                raise AssetPreparationError(f"图片引用缺少路径或校验值：{sheet_id}/{cell.get('address')}")
            previous = referenced.setdefault(local_file, checksum)
            if previous != checksum:
                raise AssetPreparationError(f"同一图片引用了不同校验值：{local_file}")

    source_manifest = _read_json(source_root / "assets-manifest.json")
    manifest_by_path = {
        asset["file"]: asset
        for asset in source_manifest
        if isinstance(asset, dict) and isinstance(asset.get("file"), str)
    }
    output_directory = package_root / "assets"
    output_directory.mkdir(parents=True, exist_ok=True)
    output_assets: list[dict[str, Any]] = []
    total_source_bytes = 0
    total_output_bytes = 0

    for local_file, expected_checksum in sorted(referenced.items()):
        relative_path = PurePosixPath(local_file)
        if (
            relative_path.is_absolute()
            or ".." in relative_path.parts
            or len(relative_path.parts) != 2
            or relative_path.parts[0] != "assets"
            or relative_path.suffix.lower() not in {".png", ".jpg", ".jpeg"}
        ):
            raise AssetPreparationError(f"图片路径超出资源目录：{local_file}")
        source_path = source_root.joinpath(*relative_path.parts)
        if not source_path.is_file():
            raise AssetPreparationError(f"图片文件缺失：{source_path}")
        source_checksum = _sha256(source_path)
        manifest_entry = manifest_by_path.get(local_file)
        if source_checksum != expected_checksum or manifest_entry is None or source_checksum != manifest_entry.get("sha256"):
            raise AssetPreparationError(f"图片校验失败：{local_file}")

        output_name = f"{PurePosixPath(local_file).stem}.webp"
        output_path = output_directory / output_name
        file_descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{PurePosixPath(local_file).stem}.", suffix=".webp", dir=output_directory
        )
        os.close(file_descriptor)
        try:
            with Image.open(source_path) as original:
                image = ImageOps.exif_transpose(original)
                image.thumbnail((MAX_IMAGE_EDGE, MAX_IMAGE_EDGE), Image.Resampling.LANCZOS)
                has_alpha = "A" in image.getbands() or "transparency" in image.info
                image = image.convert("RGBA" if has_alpha else "RGB")
                image.save(temporary_name, format="WEBP", quality=WEBP_QUALITY, method=6)
                width, height = image.size
            os.replace(temporary_name, output_path)
        finally:
            if os.path.exists(temporary_name):
                os.unlink(temporary_name)

        output_bytes = output_path.stat().st_size
        source_bytes = source_path.stat().st_size
        total_source_bytes += source_bytes
        total_output_bytes += output_bytes
        output_assets.append(
            {
                "source_file": local_file,
                "file": f"assets/{output_name}",
                "source_sha256": source_checksum,
                "sha256": _sha256(output_path),
                "source_bytes": source_bytes,
                "bytes": output_bytes,
                "dimensions": [width, height],
            }
        )

    result = {
        "schema_version": 1,
        "format": "webp",
        "max_image_edge": MAX_IMAGE_EDGE,
        "quality": WEBP_QUALITY,
        "source_bytes": total_source_bytes,
        "optimized_bytes": total_output_bytes,
        "assets": output_assets,
    }
    manifest_path = package_root / "image-assets-manifest.json"
    manifest_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"assets": len(output_assets), "source_bytes": total_source_bytes, "optimized_bytes": total_output_bytes}


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AssetPreparationError(f"无法读取 JSON：{path}") from error


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="将图鉴引用图片压缩为 App 可离线使用的 WebP 资源")
    parser.add_argument("--source", type=Path, default=Path("data/tencent-sheet"))
    parser.add_argument("--package", type=Path, default=Path("data/derived/tencent-sheet"))
    arguments = parser.parse_args()
    try:
        summary = prepare_assets(arguments.source, arguments.package)
    except AssetPreparationError as error:
        parser.error(str(error))
    ratio = summary["optimized_bytes"] / max(summary["source_bytes"], 1)
    print(
        f"Prepared {summary['assets']} wiki images: "
        f"{summary['source_bytes'] / 1048576:.1f} MiB → "
        f"{summary['optimized_bytes'] / 1048576:.1f} MiB ({ratio:.0%})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
