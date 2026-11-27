from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import html
import json
import os
from pathlib import Path
import re
import sys
import time
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, quote, urlencode, urlparse
from urllib.request import Request, urlopen


REPO_ROOT = Path(__file__).resolve().parents[2]
ASSETS_ROOT = REPO_ROOT / "data" / "games" / "three-houses" / "assets"
MANIFEST_PATH = ASSETS_ROOT / "manifest.json"
API_URL = "https://fireemblem.fandom.com/api.php"
FANDOM_HOST = "fireemblem.fandom.com"
CDN_HOST = "static.wikia.nocookie.net"
USER_AGENT = "FECompanionLocalAssetResearch/1.0"
DEFAULT_MIN_INTERVAL_SECONDS = 1.0
MAX_API_BYTES = 24 * 1024 * 1024
MAX_IMAGE_BYTES = 32 * 1024 * 1024
METADATA_BATCH_SIZE = 50

CATEGORIES = (
    {
        "id": "portraits",
        "wiki_title": "Category:Three Houses Portraits",
        "directory": "portraits",
        "previously_reported_count": 163,
    },
    {
        "id": "sprites",
        "wiki_title": "Category:Three Houses Sprites",
        "directory": "sprites",
        "previously_reported_count": 330,
    },
)

LICENSE_METADATA_FIELDS = (
    "License",
    "LicenseShortName",
    "UsageTerms",
    "Artist",
    "Author",
    "Credit",
    "Source",
    "Permission",
    "AttributionRequired",
    "Copyrighted",
    "Restrictions",
    "DateTime",
)


class DownloadError(ValueError):
    pass


class AccessDenied(DownloadError):
    pass


class RequestPacer:
    def __init__(self, minimum_interval_seconds: float) -> None:
        if minimum_interval_seconds < 0:
            raise ValueError("请求间隔不能为负数。")
        self.minimum_interval_seconds = minimum_interval_seconds
        self.last_request_at: float | None = None

    def wait(self) -> None:
        if self.last_request_at is not None:
            elapsed = time.monotonic() - self.last_request_at
            wait_seconds = self.minimum_interval_seconds - elapsed
            if wait_seconds > 0:
                time.sleep(wait_seconds)
        self.last_request_at = time.monotonic()


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _checked_https_url(url: str, allowed_host: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != allowed_host:
        raise DownloadError(f"拒绝访问非许可主机或非 HTTPS 地址：{url}")


def _request_bytes(
    url: str,
    pacer: RequestPacer,
    *,
    allowed_host: str,
    accept: str,
    max_bytes: int,
) -> tuple[bytes, dict[str, str], str]:
    _checked_https_url(url, allowed_host)
    pacer.wait()
    request = Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": accept,
            "Accept-Encoding": "identity",
        },
    )
    try:
        with urlopen(request, timeout=45) as response:
            final_url = response.geturl()
            _checked_https_url(final_url, allowed_host)
            if response.status != 200:
                raise DownloadError(f"HTTP {response.status}: {url}")
            body = response.read(max_bytes + 1)
            if len(body) > max_bytes:
                raise DownloadError(f"响应超过 {max_bytes} 字节上限：{url}")
            headers = {key.lower(): value for key, value in response.headers.items()}
            return body, headers, final_url
    except HTTPError as error:
        if error.code in {401, 403, 429}:
            raise AccessDenied(f"HTTP {error.code}；按要求停止，不尝试其他访问路径：{url}") from error
        raise DownloadError(f"HTTP {error.code}: {url}") from error
    except URLError as error:
        raise DownloadError(f"请求失败：{error.reason}（{url}）") from error


def _fetch_json(url: str, pacer: RequestPacer) -> dict[str, Any]:
    body, _, _ = _request_bytes(
        url,
        pacer,
        allowed_host=FANDOM_HOST,
        accept="application/json",
        max_bytes=MAX_API_BYTES,
    )
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise DownloadError(f"Fandom API 未返回有效 UTF-8 JSON：{url}") from error
    if not isinstance(payload, dict):
        raise DownloadError(f"Fandom API 返回了非对象 JSON：{url}")
    if payload.get("error"):
        error = payload["error"]
        raise DownloadError(f"Fandom API 错误：{error}")
    return payload


def _api_url(parameters: dict[str, str]) -> str:
    return f"{API_URL}?{urlencode(parameters)}"


def _category_files(
    category: dict[str, Any],
    pacer: RequestPacer,
    files_by_id: dict[int, dict[str, Any]],
) -> int:
    continuation: dict[str, str] = {}
    listed_ids: set[int] = set()
    while True:
        parameters = {
            "action": "query",
            "list": "categorymembers",
            "cmtitle": category["wiki_title"],
            "cmtype": "file",
            "cmlimit": "500",
            "format": "json",
        }
        parameters.update(continuation)
        payload = _fetch_json(_api_url(parameters), pacer)
        query = payload.get("query", {})
        members = query.get("categorymembers", []) if isinstance(query, dict) else []
        if not isinstance(members, list):
            raise DownloadError(f"Fandom API 未返回文件列表：{category['wiki_title']}")
        for member in members:
            if not isinstance(member, dict) or member.get("ns") != 6:
                continue
            title = member.get("title")
            page_id = member.get("pageid")
            if not isinstance(title, str) or not title.startswith("File:") or not isinstance(page_id, int):
                continue
            listed_ids.add(page_id)
            entry = files_by_id.setdefault(page_id, {
                "pageid": page_id,
                "title": title,
                "categories": set(),
            })
            entry["categories"].add(category["id"])
        next_values = payload.get("continue")
        if not isinstance(next_values, dict):
            break
        continuation = {str(key): str(value) for key, value in next_values.items()}
    return len(listed_ids)


def _chunks(items: list[dict[str, Any]], size: int) -> Iterable[list[dict[str, Any]]]:
    for start in range(0, len(items), size):
        yield items[start:start + size]


def _clean_metadata_value(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    text = html.unescape(re.sub(r"<[^>]*>", " ", value))
    text = " ".join(text.split())
    return text[:2000] or None


def _image_metadata(
    files_by_id: dict[int, dict[str, Any]],
    pacer: RequestPacer,
) -> list[dict[str, Any]]:
    pending = sorted(files_by_id.values(), key=lambda item: (item["title"].casefold(), item["pageid"]))
    for chunk in _chunks(pending, METADATA_BATCH_SIZE):
        titles = "|".join(item["title"] for item in chunk)
        parameters = {
            "action": "query",
            "prop": "imageinfo",
            "titles": titles,
            "iiprop": "url|size|mime|extmetadata",
            "format": "json",
        }
        payload = _fetch_json(_api_url(parameters), pacer)
        query = payload.get("query", {})
        pages = query.get("pages", {}) if isinstance(query, dict) else {}
        if not isinstance(pages, dict):
            raise DownloadError("Fandom API 未返回文件元数据。")
        by_page_id = {int(item["pageid"]): item for item in chunk}
        for raw_page in pages.values():
            if not isinstance(raw_page, dict):
                continue
            page_id = raw_page.get("pageid")
            if not isinstance(page_id, int) or page_id not in by_page_id:
                continue
            target = by_page_id[page_id]
            image_info = raw_page.get("imageinfo")
            if not isinstance(image_info, list) or not image_info or not isinstance(image_info[0], dict):
                target["metadata_error"] = "file page has no imageinfo"
                continue
            info = image_info[0]
            original_url = info.get("url")
            if not isinstance(original_url, str):
                target["metadata_error"] = "imageinfo has no original URL"
                continue
            target.update({
                "original_url": original_url,
                "source_mime_type": info.get("mime"),
                "source_bytes": info.get("size"),
                "width": info.get("width"),
                "height": info.get("height"),
            })
            extmetadata = info.get("extmetadata")
            if isinstance(extmetadata, dict):
                selected = {
                    key: cleaned
                    for key in LICENSE_METADATA_FIELDS
                    if (cleaned := _clean_metadata_value(
                        (extmetadata.get(key) or {}).get("value")
                        if isinstance(extmetadata.get(key), dict)
                        else None
                    ))
                }
                target["license_metadata"] = selected
    return [item for item in pending if item.get("original_url")]


def _source_page_url(title: str) -> str:
    normalized_title = title.replace(" ", "_")
    safe_chars = "()_-,.'"
    return f"https://fireemblem.fandom.com/wiki/{quote(normalized_title, safe=safe_chars)}"


def _local_category(categories: set[str]) -> str:
    if "portraits" in categories:
        return "portraits"
    if "sprites" in categories:
        return "sprites"
    raise DownloadError(f"文件没有期望的来源分类：{sorted(categories)}")


def _safe_stem(title: str) -> str:
    file_name = title.split(":", 1)[-1]
    stem = Path(file_name).stem
    stem = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", stem).strip(" .")
    if not stem:
        stem = "image"
    if stem.upper() in {"CON", "PRN", "AUX", "NUL", *(f"COM{index}" for index in range(1, 10)), *(f"LPT{index}" for index in range(1, 10))}:
        stem = f"_{stem}"
    return stem[:120]


def _extension_from_content(body: bytes, content_type: str, title: str) -> str:
    if body.startswith(b"\x89PNG\r\n\x1a\n"):
        return ".png"
    if body.startswith(b"\xff\xd8\xff"):
        return ".jpg"
    if body.startswith((b"GIF87a", b"GIF89a")):
        return ".gif"
    if len(body) >= 12 and body[:4] == b"RIFF" and body[8:12] == b"WEBP":
        return ".webp"
    if body.startswith(b"BM"):
        return ".bmp"
    if body.startswith(b"\x00\x00\x01\x00"):
        return ".ico"
    sample = body[:512].lstrip().lower()
    if b"<svg" in sample:
        return ".svg"
    if content_type == "image/avif":
        return ".avif"
    if not content_type.startswith("image/"):
        raise DownloadError(f"CDN 返回的不是图像类型：{content_type or 'unknown'}")
    known = {
        "image/png": ".png",
        "image/jpeg": ".jpg",
        "image/gif": ".gif",
        "image/webp": ".webp",
        "image/svg+xml": ".svg",
        "image/bmp": ".bmp",
        "image/x-icon": ".ico",
    }
    return known.get(content_type, Path(title).suffix.lower() or ".img")


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _atomic_write_manifest(manifest: dict[str, Any]) -> None:
    manifest["updated_at"] = _utc_now()
    temporary = MANIFEST_PATH.with_name(f".{MANIFEST_PATH.name}.tmp")
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, MANIFEST_PATH)


def _load_manifest() -> dict[str, Any]:
    if not MANIFEST_PATH.is_file():
        return {}
    try:
        value = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise DownloadError(f"现有素材清单无法读取：{MANIFEST_PATH}") from error
    if not isinstance(value, dict):
        raise DownloadError("现有素材清单格式不是 JSON 对象。")
    return value


def _new_manifest(category_counts: dict[str, int]) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "game_id": "three-houses",
        "source": {
            "source_id": "fire-emblem-fandom-images",
            "title": "Fire Emblem Fandom",
            "api_url": API_URL,
            "rights_status": "no_reuse_grant_verified",
            "rights_note": (
                "User-requested local development copies. Fandom text licensing does not automatically "
                "cover images; individual image reuse grants were not verified. Not approved for public release."
            ),
        },
        "scope": {
            "purpose": "user_requested_local_development_copy",
            "categories": [
                {
                    "category_id": category["id"],
                    "wiki_category": category["wiki_title"],
                    "expected_count_from_prior_api_review": category["previously_reported_count"],
                    "listed_file_count": category_counts[category["id"]],
                }
                for category in CATEGORIES
            ],
            "excluded_categories": [
                "Category:Three Houses Images",
                "Category:Three Houses Screenshots",
            ],
        },
        "generated_at": _utc_now(),
        "run_status": "running",
        "assets": [],
        "failures": [],
    }


def _manifest_existing_by_pageid(manifest: dict[str, Any]) -> dict[int, dict[str, Any]]:
    existing: dict[int, dict[str, Any]] = {}
    for asset in manifest.get("assets", []):
        if not isinstance(asset, dict):
            continue
        page_id = asset.get("source_page_id")
        if isinstance(page_id, int):
            existing[page_id] = asset
    return existing


def _existing_file_is_valid(asset: dict[str, Any]) -> Path | None:
    if asset.get("download_variant") != "format=original":
        return None
    if asset.get("download_mime_type") != asset.get("source_mime_type"):
        return None
    relative = asset.get("local_path")
    expected_hash = asset.get("sha256")
    if not isinstance(relative, str) or not isinstance(expected_hash, str):
        return None
    path = (ASSETS_ROOT / relative).resolve()
    if not path.is_relative_to(ASSETS_ROOT.resolve()) or not path.is_file():
        return None
    try:
        if _sha256_file(path) == expected_hash:
            return path
    except OSError:
        return None
    return None


def _download_image(
    item: dict[str, Any],
    pacer: RequestPacer,
) -> tuple[bytes, str, str, str, str]:
    original_url = item["original_url"]
    parsed_url = urlparse(original_url)
    query = [(key, value) for key, value in parse_qsl(parsed_url.query, keep_blank_values=True) if key.casefold() != "format"]
    query.append(("format", "original"))
    download_url = parsed_url._replace(query=urlencode(query)).geturl()
    source_mime_type = item.get("source_mime_type")
    accept = source_mime_type if isinstance(source_mime_type, str) else "application/octet-stream"
    body, headers, final_url = _request_bytes(
        download_url,
        pacer,
        allowed_host=CDN_HOST,
        accept=accept,
        max_bytes=MAX_IMAGE_BYTES,
    )
    content_type = headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if source_mime_type and content_type != source_mime_type:
        raise DownloadError(
            f"CDN 返回 {content_type or 'unknown'}，API 源格式为 {source_mime_type}；未将变体写入 original：{download_url}"
        )
    extension = _extension_from_content(body, content_type, item["title"])
    expected_extensions = {
        "image/png": ".png",
        "image/jpeg": ".jpg",
        "image/gif": ".gif",
        "image/webp": ".webp",
        "image/svg+xml": ".svg",
        "image/bmp": ".bmp",
        "image/x-icon": ".ico",
    }
    expected_extension = expected_extensions.get(source_mime_type)
    if expected_extension and extension != expected_extension:
        raise DownloadError(
            f"响应文件签名与 API MIME 不一致（{extension} != {expected_extension}）：{download_url}"
        )
    return body, content_type, extension, download_url, final_url


def _destination_for(item: dict[str, Any], body: bytes, extension: str) -> Path:
    category = _local_category(item["categories"])
    stem = _safe_stem(item["title"])
    base = f"{item['pageid']}_{stem}"
    directory = ASSETS_ROOT / "original" / category
    destination = directory / f"{base}{extension}"
    digest_prefix = _sha256_bytes(body)[:10]
    if destination.exists() and _sha256_file(destination) != _sha256_bytes(body):
        destination = directory / f"{base}_{digest_prefix}{extension}"
    return destination


def _asset_record(
    item: dict[str, Any],
    destination: Path,
    body: bytes,
    content_type: str,
    download_url: str,
    response_url: str,
) -> dict[str, Any]:
    relative = destination.resolve().relative_to(ASSETS_ROOT.resolve()).as_posix()
    source_categories = sorted(item["categories"])
    return {
        "asset_id": f"fandom-file:{item['pageid']}",
        "source_page_id": item["pageid"],
        "title": item["title"],
        "local_category": _local_category(item["categories"]),
        "source_categories": source_categories,
        "source_category_titles": [
            next(category["wiki_title"] for category in CATEGORIES if category["id"] == category_id)
            for category_id in source_categories
        ],
        "source_page_url": _source_page_url(item["title"]),
        "original_url": item["original_url"],
        "download_request_url": download_url,
        "download_response_url": response_url,
        "download_variant": "format=original",
        "source_mime_type": item.get("source_mime_type"),
        "download_mime_type": content_type,
        "source_bytes": item.get("source_bytes"),
        "downloaded_bytes": len(body),
        "width": item.get("width"),
        "height": item.get("height"),
        "sha256": _sha256_bytes(body),
        "local_path": relative,
        "retrieved_at": _utc_now(),
        "license_metadata": item.get("license_metadata", {}),
        "rights_status": "no_reuse_grant_verified",
        "public_release_authorized": False,
        "use_scope": "local_development_copy_requested_by_user",
    }


def download_assets(minimum_interval_seconds: float) -> dict[str, Any]:
    ASSETS_ROOT.mkdir(parents=True, exist_ok=True)
    if not ASSETS_ROOT.resolve().is_relative_to((REPO_ROOT / "data" / "games" / "three-houses").resolve()):
        raise DownloadError("素材输出路径超出 Three Houses 资料包。")

    pacer = RequestPacer(minimum_interval_seconds)
    files_by_id: dict[int, dict[str, Any]] = {}
    category_counts: dict[str, int] = {}
    for category in CATEGORIES:
        category_counts[category["id"]] = _category_files(category, pacer, files_by_id)
        print(f"{category['wiki_title']}: {category_counts[category['id']]} 个文件")

    items = _image_metadata(files_by_id, pacer)
    if len(items) != len(files_by_id):
        missing = [
            {"source_page_id": item["pageid"], "title": item["title"], "reason": item.get("metadata_error", "missing image metadata")}
            for item in files_by_id.values()
            if not item.get("original_url")
        ]
    else:
        missing = []

    manifest = _load_manifest()
    if manifest.get("source", {}).get("source_id") == "fire-emblem-fandom-images":
        manifest["scope"]["categories"] = _new_manifest(category_counts)["scope"]["categories"]
        manifest["failures"] = []
    else:
        manifest = _new_manifest(category_counts)
    manifest.setdefault("assets", [])
    manifest["run_status"] = "running"
    existing_by_id = _manifest_existing_by_pageid(manifest)
    files_by_page_id = {item["pageid"]: item for item in items}
    for entry in manifest["assets"]:
        if isinstance(entry, dict) and entry.get("source_page_id") in files_by_page_id:
            item = files_by_page_id[entry["source_page_id"]]
            entry["source_categories"] = sorted(item["categories"])
            entry["source_category_titles"] = [
                next(category["wiki_title"] for category in CATEGORIES if category["id"] == category_id)
                for category_id in entry["source_categories"]
            ]
    manifest["failures"].extend(missing)
    _atomic_write_manifest(manifest)

    completed = 0
    total = len(items)
    try:
        for item in items:
            existing_asset = existing_by_id.get(item["pageid"])
            if existing_asset and (existing_path := _existing_file_is_valid(existing_asset)):
                completed += 1
                if completed % 25 == 0 or completed == total:
                    print(f"已存在且哈希匹配：{completed}/{total}")
                continue
            try:
                body, content_type, extension, download_url, response_url = _download_image(item, pacer)
                destination = _destination_for(item, body, extension)
                destination.parent.mkdir(parents=True, exist_ok=True)
                if destination.exists() and _sha256_file(destination) == _sha256_bytes(body):
                    pass
                else:
                    temporary = destination.with_name(f".{destination.name}.part")
                    temporary.write_bytes(body)
                    os.replace(temporary, destination)
                record = _asset_record(item, destination, body, content_type, download_url, response_url)
                current_records = [
                    entry for entry in manifest["assets"]
                    if not isinstance(entry, dict) or entry.get("source_page_id") != item["pageid"]
                ]
                current_records.append(record)
                manifest["assets"] = current_records
                completed += 1
            except AccessDenied as error:
                manifest["failures"].append({
                    "source_page_id": item["pageid"],
                    "title": item["title"],
                    "source_page_url": _source_page_url(item["title"]),
                    "reason": str(error),
                    "status": "access_denied",
                })
                manifest["run_status"] = "stopped_access_denied"
                _atomic_write_manifest(manifest)
                raise
            except (DownloadError, OSError) as error:
                manifest["failures"].append({
                    "source_page_id": item["pageid"],
                    "title": item["title"],
                    "source_page_url": _source_page_url(item["title"]),
                    "reason": str(error),
                    "status": "download_failed",
                })
            if completed % 10 == 0 or completed == total:
                downloaded_counts = {
                    category["id"]: sum(
                        1 for entry in manifest["assets"]
                        if isinstance(entry, dict) and category["id"] in entry.get("source_categories", [])
                    )
                    for category in CATEGORIES
                }
                print(f"已完成 {completed}/{total} 个唯一文件；分类计数：{downloaded_counts}")
            _atomic_write_manifest(manifest)
    except AccessDenied:
        raise

    missing_count = len(manifest["failures"])
    manifest["run_status"] = "complete_with_gaps" if missing_count else "complete"
    manifest["completed_at"] = _utc_now()
    _atomic_write_manifest(manifest)
    return {
        "manifest": MANIFEST_PATH.as_posix(),
        "listed_files_by_category": category_counts,
        "unique_listed_files": len(files_by_id),
        "downloaded_assets": len(manifest["assets"]),
        "failed_items": missing_count,
        "run_status": manifest["run_status"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="将 Fandom 风花雪月人物肖像和精灵图保存为本地开发副本，并保留来源/权利状态。"
    )
    parser.add_argument(
        "--min-interval",
        type=float,
        default=DEFAULT_MIN_INTERVAL_SECONDS,
        help="每个 Fandom API/CDN 请求的最小间隔秒数（默认 1.0）。",
    )
    arguments = parser.parse_args()
    try:
        summary = download_assets(arguments.min_interval)
    except (DownloadError, OSError, json.JSONDecodeError) as error:
        print(f"下载停止：{error}", file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
