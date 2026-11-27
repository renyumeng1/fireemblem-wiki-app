from __future__ import annotations

import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from typing import Any

from prepare_endings import _extract_next_stream


ARCHIVE_PAGE_URI = "https://fireemblem.top/archive/mechanics"
GROWTH_PAGE_URI = "https://fireemblem.top/topics/growth-mechanics"
OUTPUT_SCHEMA_VERSION = 1

EXPECTED_GROWTH_SECTION_IDS = (
    "overview",
    "rate",
    "random",
    "fixed",
    "caps",
    "recruitment",
    "evidence",
)


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _extract_unique_mechanics_array(stream: str, key: str, validator) -> list[dict[str, Any]]:
    token = f'"{key}":'
    decoder = json.JSONDecoder()
    candidates: dict[str, list[dict[str, Any]]] = {}
    offset = 0
    while True:
        index = stream.find(token, offset)
        if index < 0:
            break
        value_start = index + len(token)
        while value_start < len(stream) and stream[value_start].isspace():
            value_start += 1
        try:
            value, _ = decoder.raw_decode(stream, value_start)
        except json.JSONDecodeError:
            offset = value_start
            continue
        offset = value_start + 1
        if isinstance(value, list) and value and all(isinstance(item, dict) for item in value):
            try:
                validated = validator(value)
            except ValueError:
                continue
            signature = json.dumps(validated, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            candidates[signature] = validated
    if len(candidates) != 1:
        raise ValueError(f"机制页面中应有唯一有效的 {key} 清单，实际找到 {len(candidates)} 个")
    return next(iter(candidates.values()))


def _valid_groups(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for item in raw:
        group_id = item.get("id")
        title = item.get("title")
        category = item.get("category")
        description = item.get("description")
        count = item.get("count")
        if (not isinstance(group_id, str) or not group_id.strip() or group_id.strip() in seen_ids
                or not isinstance(title, str) or not title.strip()
                or not isinstance(category, str) or not category.strip()
                or not isinstance(description, str)
                or not isinstance(count, int) or isinstance(count, bool) or count <= 0):
            raise ValueError("机制分组字段无效或重复")
        href = item.get("href")
        normalized.append({
            "id": group_id.strip(),
            "title": title.strip(),
            "category": category.strip(),
            "description": description.strip(),
            "href": href.strip() if isinstance(href, str) and href.strip() else None,
            "count": count,
        })
        seen_ids.add(group_id.strip())
    return normalized


def _valid_entries(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for item in raw:
        entry_id = item.get("id")
        title = item.get("title")
        group_id = item.get("group")
        kind = item.get("kind")
        paragraphs = item.get("paragraphs")
        sources = item.get("sources", [])
        if (not isinstance(entry_id, str) or not entry_id.strip() or entry_id.strip() in seen_ids
                or not isinstance(title, str) or not title.strip()
                or not isinstance(group_id, str) or not group_id.strip()
                or not isinstance(kind, str) or not kind.strip()
                or not isinstance(paragraphs, list) or not paragraphs
                or not all(isinstance(p, str) for p in paragraphs)
                or not isinstance(sources, list)):
            raise ValueError("机制条目字段缺失或无效")
        cleaned_paragraphs = [p.strip() for p in paragraphs if p.strip()]
        if not cleaned_paragraphs:
            raise ValueError(f"机制条目缺少正文段落：{entry_id}")
        normalized.append({
            "id": entry_id.strip(),
            "title": title.strip(),
            "group": group_id.strip(),
            "kind": kind.strip(),
            "paragraphs": cleaned_paragraphs,
            "sources": [str(s).strip() for s in sources if str(s).strip()],
        })
        seen_ids.add(entry_id.strip())
    return normalized


class _GrowthSectionParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.sections: list[dict[str, Any]] = []
        self._current_section_id: str | None = None
        self._section_depth = 0
        self._skip_depth = 0
        self._block_tag: str | None = None
        self._block_buf: list[str] = []
        self._in_table = False
        self._table_rows: list[list[str]] = []
        self._current_row: list[str] = []
        self._cell_tag: str | None = None
        self._cell_buf: list[str] = []
        self._current_title: str = ""
        self._blocks: list[dict[str, Any]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attr_map = dict(attrs)
        if tag in {"script", "style", "svg"}:
            self._skip_depth += 1
            return
        if self._skip_depth > 0:
            return
        if tag == "section":
            if self._current_section_id is None:
                sec_id = attr_map.get("id")
                if sec_id and sec_id in EXPECTED_GROWTH_SECTION_IDS:
                    self._current_section_id = sec_id
                    self._section_depth = 1
                    self._current_title = ""
                    self._blocks = []
            else:
                self._section_depth += 1
            return
        if self._current_section_id is None:
            return
        if tag == "br":
            target = self._cell_buf if self._cell_tag else self._block_buf
            target.append("\n")
            return
        if tag == "table":
            self._in_table = True
            self._table_rows = []
            return
        if self._in_table:
            if tag == "tr":
                self._current_row = []
            elif tag in {"th", "td"} and self._cell_tag is None:
                self._cell_tag = tag
                self._cell_buf = []
            return
        if tag in {"h2", "h3", "p", "li", "pre"} and self._block_tag is None:
            self._block_tag = tag
            self._block_buf = []

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "svg"} and self._skip_depth > 0:
            self._skip_depth -= 1
            return
        if self._skip_depth > 0 or self._current_section_id is None:
            return
        if tag == "section":
            self._section_depth -= 1
            if self._section_depth == 0:
                if not self._current_title or not self._blocks:
                    raise ValueError(f"成长机制章节内容为空：{self._current_section_id}")
                self.sections.append({
                    "id": self._current_section_id,
                    "title": self._current_title,
                    "blocks": self._blocks,
                })
                self._current_section_id = None
            return
        if self._in_table:
            if tag == self._cell_tag:
                cell_text = re.sub(r"[ \t\r]+", " ", "".join(self._cell_buf)).strip()
                cell_text = re.sub(r"\s*\n\s*", " / ", cell_text)
                self._current_row.append(cell_text)
                self._cell_tag = None
                self._cell_buf = []
            elif tag == "tr":
                if any(self._current_row):
                    self._table_rows.append(self._current_row)
                self._current_row = []
            elif tag == "table":
                if self._table_rows:
                    lines = [" · ".join(c for c in row if c) for row in self._table_rows]
                    lines = [line for line in lines if line]
                    if lines:
                        self._blocks.append({"kind": "table", "text": "\n".join(lines)})
                self._in_table = False
                self._table_rows = []
            return
        if tag == self._block_tag:
            raw_text = "".join(self._block_buf)
            lines = [re.sub(r"[ \t\r]+", " ", line).strip() for line in raw_text.splitlines()]
            cleaned = "\n".join(line for line in lines if line)
            self._block_tag = None
            self._block_buf = []
            if not cleaned:
                return
            if tag == "h2" and not self._current_title:
                self._current_title = cleaned.replace("\n", " ")
            elif tag == "h3":
                self._blocks.append({"kind": "heading", "text": cleaned.replace("\n", " ")})
            elif tag == "li":
                self._blocks.append({"kind": "bullet", "text": cleaned})
            else:
                self._blocks.append({"kind": "paragraph", "text": cleaned})

    def handle_data(self, data: str) -> None:
        if self._skip_depth > 0 or self._current_section_id is None:
            return
        if self._cell_tag is not None:
            self._cell_buf.append(data)
        elif self._block_tag is not None:
            self._block_buf.append(data)


def extract_growth_sections(growth_html: str) -> list[dict[str, Any]]:
    parser = _GrowthSectionParser()
    parser.feed(growth_html)
    parser.close()
    by_id = {sec["id"]: sec for sec in parser.sections}
    if set(by_id) != set(EXPECTED_GROWTH_SECTION_IDS) or len(parser.sections) != len(EXPECTED_GROWTH_SECTION_IDS):
        raise ValueError(f"成长机制页面章节不完整：{list(by_id.keys())}")
    return [by_id[sec_id] for sec_id in EXPECTED_GROWTH_SECTION_IDS]


def extract_mechanics(archive_html: str, growth_html: str) -> dict[str, Any]:
    stream = _extract_next_stream(archive_html)
    groups = _extract_unique_mechanics_array(stream, "groups", _valid_groups)
    entries = _extract_unique_mechanics_array(stream, "entries", _valid_entries)
    group_ids = {group["id"] for group in groups}
    counts_by_group: dict[str, int] = {}
    for entry in entries:
        group_id = entry["group"]
        if group_id not in group_ids:
            raise ValueError(f"机制条目引用未知分组：{entry['id']} -> {group_id}")
        counts_by_group[group_id] = counts_by_group.get(group_id, 0) + 1
    for group in groups:
        actual = counts_by_group.get(group["id"], 0)
        if actual != group["count"]:
            raise ValueError(f"机制分组数量不匹配：{group['id']} expected={group['count']} actual={actual}")
    growth_sections = extract_growth_sections(growth_html)
    archive_hash = _sha256(archive_html.encode("utf-8"))
    growth_hash = _sha256(growth_html.encode("utf-8"))
    revision = _sha256((archive_hash + growth_hash).encode("ascii"))
    return {
        "schema_version": OUTPUT_SCHEMA_VERSION,
        "source": {
            "archive_uri": ARCHIVE_PAGE_URI,
            "growth_uri": GROWTH_PAGE_URI,
            "archive_sha256": archive_hash,
            "growth_sha256": growth_hash,
            "revision": revision,
        },
        "groups": groups,
        "entries": entries,
        "growth_sections": growth_sections,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="提取机制词典与成长机制章节为结构化 JSON")
    parser.add_argument(
        "--archive-page",
        type=Path,
        default=Path("data/research/site-plan-audit/archive-mechanics.html"),
    )
    parser.add_argument(
        "--growth-page",
        type=Path,
        default=Path("data/research/site-plan-audit/topics-growth-mechanics.html"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("data/derived/mechanics-guide.json"),
    )
    args = parser.parse_args()
    data = extract_mechanics(
        args.archive_page.read_text(encoding="utf-8"),
        args.growth_page.read_text(encoding="utf-8"),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"Prepared {len(data['groups'])} mechanics groups, {len(data['entries'])} entries, "
        f"and {len(data['growth_sections'])} growth sections."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
