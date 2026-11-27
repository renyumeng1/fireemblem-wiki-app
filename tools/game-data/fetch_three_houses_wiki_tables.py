from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen
from typing import Any

from bs4 import BeautifulSoup


REPO_ROOT = Path(__file__).resolve().parents[2]
PACKAGE = REPO_ROOT / "data" / "games" / "three-houses"
STATE_PATH = PACKAGE / "sources" / ".fireemblemwiki-request-state.json"
MIN_INTERVAL_SECONDS = 10.5
MAX_PAGE_BYTES = 10 * 1024 * 1024


def _read_state() -> dict[str, Any]:
    if not STATE_PATH.is_file():
        return {}
    try:
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def _write_state(requested_url: str, requested_at: float) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STATE_PATH.write_text(json.dumps({
        "last_request_epoch": requested_at,
        "last_request_utc": datetime.fromtimestamp(requested_at, timezone.utc).isoformat(),
        "last_url": requested_url,
        "minimum_interval_seconds": MIN_INTERVAL_SECONDS,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _respect_crawl_delay() -> None:
    state = _read_state()
    last_request = state.get("last_request_epoch")
    if isinstance(last_request, (int, float)):
        wait_seconds = MIN_INTERVAL_SECONDS - (time.time() - float(last_request))
        if wait_seconds > 0:
            time.sleep(wait_seconds)


def _text(cell: Any) -> str:
    return " ".join(cell.stripped_strings)[:500]


def _parse_tables(html: bytes, source_url: str, table_filter: set[int] | None) -> tuple[str, list[dict[str, Any]]]:
    soup = BeautifulSoup(html, "lxml")
    content = soup.select_one("#mw-content-text") or soup
    tables: list[dict[str, Any]] = []
    for table_index, table in enumerate(soup.find_all("table"), start=1):
        if table_filter is not None and table_index not in table_filter:
            continue
        if table.find_parent("table"):
            continue
        rows = []
        headers: list[str] = []
        for row_index, row in enumerate(table.find_all("tr"), start=1):
            cells = row.find_all(["th", "td"], recursive=False)
            if not cells:
                continue
            values = [_text(cell) for cell in cells]
            if not any(values):
                continue
            if not rows and all(cell.name == "th" for cell in cells):
                headers = values
                continue
            links = [
                {"text": _text(anchor), "url": urljoin(source_url, anchor.get("href", ""))}
                for cell in cells
                for anchor in cell.find_all("a", href=True)
                if _text(anchor)
            ]
            links_by_cell = [
                [
                    {"text": _text(anchor), "url": urljoin(source_url, anchor.get("href", ""))}
                    for anchor in cell.find_all("a", href=True)
                    if _text(anchor)
                ]
                for cell in cells
            ]
            rows.append({"row_index": row_index, "cells": values, "links": links, "links_by_cell": links_by_cell})
        if not rows:
            continue
        heading = table.find_previous(["h2", "h3", "h4"])
        tables.append({
            "table_index": table_index,
            "section": " ".join(heading.stripped_strings) if heading else "",
            "headers": headers,
            "row_count": len(rows),
            "rows": rows,
        })
    title = soup.title.get_text(" ", strip=True) if soup.title else ""
    return title, tables


def fetch_tables(
    url: str,
    output: Path,
    table_filter: set[int] | None,
    link_terms: set[str] | None = None,
) -> dict[str, Any]:
    parsed = urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != "fireemblemwiki.org" or not parsed.path.startswith("/wiki/"):
        raise ValueError("仅允许访问 fireemblemwiki.org 的普通 /wiki/ 页面。")
    output = output.resolve()
    package = PACKAGE.resolve()
    if not output.is_relative_to(package):
        raise ValueError(f"输出路径必须位于 {package} 内。")
    _respect_crawl_delay()
    request = Request(url, headers={"User-Agent": "Mozilla/5.0 (compatible; FECompanionDataResearch/1.0)"})
    try:
        with urlopen(request, timeout=30) as response:
            requested_at = time.time()
            _write_state(url, requested_at)
            if response.status != 200:
                raise ValueError(f"普通页面访问返回 HTTP {response.status}；未尝试其他访问路径。")
            body = response.read(MAX_PAGE_BYTES + 1)
            if len(body) > MAX_PAGE_BYTES:
                raise ValueError("页面超过 10 MiB 安全读取上限；未保存。")
            final_url = response.geturl()
    except HTTPError as error:
        _write_state(url, time.time())
        raise ValueError(f"普通页面访问返回 HTTP {error.code}；未尝试绕过。") from error
    except URLError as error:
        raise ValueError(f"页面请求失败：{error.reason}") from error

    title, tables = _parse_tables(body, final_url, table_filter)
    captured_at = datetime.fromtimestamp(requested_at, timezone.utc).isoformat()
    page_links: list[dict[str, str]] = []
    if link_terms:
        soup = BeautifulSoup(body, "lxml")
        content = soup.select_one("#mw-content-text") or soup
        seen_links: set[tuple[str, str]] = set()
        for anchor in content.find_all("a", href=True):
            text = _text(anchor)
            target = urljoin(final_url, anchor.get("href", ""))
            if not text or not any(term in (text + " " + target).casefold() for term in link_terms):
                continue
            link = (text, target)
            if link in seen_links:
                continue
            seen_links.add(link)
            page_links.append({"text": text, "url": target})
    capture = {
        "title": title,
        "source_url": final_url,
        "domain": "fireemblemwiki.org",
        "language": "en",
        "retrieved_at": captured_at,
        "captured_content_sha256": hashlib.sha256(body).hexdigest(),
        "extractor": "tools/game-data/fetch_three_houses_wiki_tables.py; selected visible table text and links only; full page and images are not retained",
        "tables": tables,
    }
    if page_links:
        capture["page_links"] = page_links
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(capture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result = {
        "output": output.as_posix(),
        "title": title,
        "source_url": final_url,
        "retrieved_at": captured_at,
        "captured_content_sha256": capture["captured_content_sha256"],
        "tables": [{"table_index": table["table_index"], "section": table["section"], "headers": table["headers"], "row_count": table["row_count"]} for table in tables],
    }
    if page_links:
        result["page_links"] = page_links
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Capture table text and source locators from one ordinary Fire Emblem Wiki page.")
    parser.add_argument("--url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--tables", help="Optional comma-separated one-based table indexes.")
    parser.add_argument("--link-terms", help="Optional comma-separated terms for listing matching page links.")
    args = parser.parse_args()
    table_filter = None
    if args.tables:
        try:
            table_filter = {int(value.strip()) for value in args.tables.split(",") if value.strip()}
        except ValueError:
            print("--tables must be a comma-separated list of positive integers.", file=sys.stderr)
            return 2
        if not table_filter or any(index < 1 for index in table_filter):
            print("--tables must contain positive table indexes.", file=sys.stderr)
            return 2
    try:
        terms = {term.strip().casefold() for term in args.link_terms.split(",") if term.strip()} if args.link_terms else None
        print(json.dumps(fetch_tables(args.url, args.output, table_filter, terms), ensure_ascii=False, indent=2))
    except (OSError, ValueError) as error:
        print(f"表格采集失败：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
