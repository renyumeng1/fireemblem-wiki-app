from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

from bs4 import BeautifulSoup


def _cell_value(cell: Any, include_links: bool, max_chars: int) -> Any:
    text = " ".join(cell.stripped_strings)
    if not include_links:
        return text[:max_chars]
    links = [
        {"text": " ".join(anchor.stripped_strings), "url": anchor.get("href")}
        for anchor in cell.find_all("a", href=True)
        if " ".join(anchor.stripped_strings)
    ]
    return {"text": text[:max_chars], "links": links}


def parse_capture(
    capture: dict[str, Any], table_index_filter: int | None, max_rows: int | None,
    max_cells: int, max_chars: int, include_links: bool,
) -> dict[str, Any]:
    html = capture.get("content", "")
    if not isinstance(html, str) or not html:
        raise ValueError("Defuddle JSON must include non-empty string field 'content'.")
    soup = BeautifulSoup(html, "lxml")
    tables = []
    for table_index, table in enumerate(soup.find_all("table"), start=1):
        if table_index_filter is not None and table_index != table_index_filter:
            continue
        heading = table.find_previous(["h2", "h3", "h4"])
        heading_text = " ".join(heading.stripped_strings) if heading else ""
        rows = []
        for row_index, row in enumerate(table.find_all("tr"), start=1):
            cells = row.find_all(["th", "td"], recursive=False)
            values = [_cell_value(cell, include_links, max_chars) for cell in cells[:max_cells]]
            if not values or not any((value.get("text", "") if isinstance(value, dict) else value) for value in values):
                continue
            rows.append({"row_index": row_index, "cells": values})
            if max_rows is not None and len(rows) >= max_rows:
                break
        if rows:
            tables.append({
                "table_index": table_index,
                "section": heading_text,
                "row_count_in_capture": len(rows),
                "rows": rows,
            })
    return {
        "title": capture.get("title", ""),
        "domain": capture.get("domain", ""),
        "language": capture.get("language", ""),
        "captured_content_sha256": hashlib.sha256(html.encode("utf-8")).hexdigest(),
        "tables": tables,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Extract table text and link locators from a Defuddle JSON capture without retaining a page snapshot."
    )
    parser.add_argument("--max-rows", type=int)
    parser.add_argument("--table", type=int, dest="table_index")
    parser.add_argument("--max-cells", type=int, default=12)
    parser.add_argument("--max-cell-chars", type=int, default=180)
    parser.add_argument("--include-links", action="store_true")
    parser.add_argument("--source-url", default="")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        capture = json.load(sys.stdin)
        result = parse_capture(
            capture, args.table_index, args.max_rows, args.max_cells,
            args.max_cell_chars, args.include_links,
        )
        result["source_url"] = args.source_url
        result["retrieved_at"] = datetime.now(timezone.utc).isoformat()
        result["extractor"] = "tools/game-data/parse_three_houses_source.py + Defuddle"
    except (json.JSONDecodeError, ValueError) as error:
        print(f"页面提取失败：{error}", file=sys.stderr)
        return 1
    output_text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output_text, encoding="utf-8")
    else:
        sys.stdout.write(output_text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
