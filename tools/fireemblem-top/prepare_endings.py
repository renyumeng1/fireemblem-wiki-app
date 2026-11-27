from __future__ import annotations

import argparse
from html.parser import HTMLParser
import hashlib
import json
from pathlib import Path
import re
from typing import Any
from urllib.parse import urljoin


PAGE_URI = "https://fireemblem.top/topics/endings"
OUTPUT_SCHEMA_VERSION = 1
_NEXT_PUSH = re.compile(r'self\.__next_f\.push\((\[\d+,"(?:\\.|[^"\\])*"\])\)')
_JSON_PARSE = re.compile(r"JSON\.parse\(((?:'(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\"))\)")
_BUNDLE_PATH = re.compile(r'<script[^>]+src="([^"]*topics[^"]*page-[^"]+\.js)"')


class _ScriptCollector(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=False)
        self.scripts: list[str] = []
        self._inside_script = False
        self._current: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() == "script":
            self._inside_script = True
            self._current = []

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "script" and self._inside_script:
            self.scripts.append("".join(self._current))
            self._inside_script = False
            self._current = []

    def handle_data(self, data: str) -> None:
        if self._inside_script:
            self._current.append(data)


def extract_endings(page_html: str, page_bundle: str) -> dict[str, Any]:
    page_stream = _extract_next_stream(page_html)
    single_endings = _extract_single_endings(page_stream)
    paired_endings = _extract_paired_endings(page_bundle)
    records = [
        _normalize_record(record, "双人结局", "pair", index)
        for index, record in enumerate(paired_endings, start=1)
    ]
    records.extend(
        _normalize_record(record, "单人结局", "single", len(records) + index)
        for index, record in enumerate(single_endings, start=1)
    )
    source = {
        "uri": PAGE_URI,
        "bundle_uri": _bundle_uri(page_html),
        "page_sha256": hashlib.sha256(page_html.encode("utf-8")).hexdigest(),
        "bundle_sha256": hashlib.sha256(page_bundle.encode("utf-8")).hexdigest(),
    }
    source["revision"] = hashlib.sha256(
        (source["page_sha256"] + source["bundle_sha256"]).encode("ascii")
    ).hexdigest()
    return {"schema_version": OUTPUT_SCHEMA_VERSION, "source": source, "records": records}


def _extract_next_stream(page_html: str) -> str:
    parser = _ScriptCollector()
    parser.feed(page_html)
    chunks: list[str] = []
    for script in parser.scripts:
        for match in _NEXT_PUSH.finditer(script):
            try:
                payload = json.loads(match.group(1))
            except json.JSONDecodeError as error:
                raise ValueError("网页的 Next.js 资料块格式无效") from error
            if len(payload) == 2 and isinstance(payload[1], str):
                chunks.append(payload[1])
    if not chunks:
        raise ValueError("网页中没有可读取的结局资料块")
    return "".join(chunks)


def _extract_single_endings(page_stream: str) -> list[dict[str, Any]]:
    key = '"singleEndings":'
    index = page_stream.find(key)
    if index < 0:
        raise ValueError("网页资料中缺少单人结局列表")
    value_start = index + len(key)
    while value_start < len(page_stream) and page_stream[value_start].isspace():
        value_start += 1
    try:
        records, _ = json.JSONDecoder().raw_decode(page_stream, value_start)
    except json.JSONDecodeError as error:
        raise ValueError("网页中的单人结局列表不是有效 JSON") from error
    return _validate_records(records, expected_character_count=1, label="单人结局")


def _extract_paired_endings(page_bundle: str) -> list[dict[str, Any]]:
    candidates: list[list[dict[str, Any]]] = []
    for match in _JSON_PARSE.finditer(page_bundle):
        literal = match.group(1)
        try:
            value = json.loads(_decode_js_string(literal))
        except (ValueError, json.JSONDecodeError):
            continue
        if isinstance(value, list) and value and all(
            isinstance(record, dict) and isinstance(record.get("characters"), list)
            and len(record["characters"]) == 2 for record in value
        ):
            candidates.append(_validate_records(value, expected_character_count=2, label="双人结局"))
    if len(candidates) != 1:
        raise ValueError(f"页面脚本应包含唯一的双人结局列表，实际找到 {len(candidates)} 个")
    return candidates[0]


def _decode_js_string(literal: str) -> str:
    if len(literal) < 2 or literal[0] not in {"'", '"'} or literal[-1] != literal[0]:
        raise ValueError("无效的 JavaScript 字符串")
    quote = literal[0]
    result: list[str] = []
    index = 1
    while index < len(literal) - 1:
        character = literal[index]
        index += 1
        if character != "\\":
            if character in {"\n", "\r"}:
                raise ValueError("JavaScript 字符串包含未转义换行")
            result.append(character)
            continue
        if index >= len(literal) - 1:
            raise ValueError("JavaScript 字符串转义不完整")
        escaped = literal[index]
        index += 1
        simple_escapes = {"b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t", "v": "\v"}
        if escaped in simple_escapes:
            result.append(simple_escapes[escaped])
        elif escaped in {"\\", "'", '"', "/"}:
            result.append(escaped)
        elif escaped == "0":
            result.append("\0")
        elif escaped == "x":
            result.append(chr(int(literal[index:index + 2], 16)))
            index += 2
        elif escaped == "u":
            if index < len(literal) - 1 and literal[index] == "{":
                end = literal.find("}", index + 1)
                if end < 0:
                    raise ValueError("JavaScript Unicode 转义不完整")
                result.append(chr(int(literal[index + 1:end], 16)))
                index = end + 1
            else:
                result.append(chr(int(literal[index:index + 4], 16)))
                index += 4
        elif escaped == "\n":
            continue
        elif escaped == "\r":
            if index < len(literal) - 1 and literal[index] == "\n":
                index += 1
        else:
            raise ValueError(f"不支持的 JavaScript 转义：\\{escaped}")
    return "".join(result)


def _validate_records(records: Any, expected_character_count: int, label: str) -> list[dict[str, Any]]:
    if not isinstance(records, list) or not records:
        raise ValueError(f"{label}列表为空或格式无效")
    normalized: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for record in records:
        if not isinstance(record, dict):
            raise ValueError(f"{label}条目格式无效")
        record_id = record.get("id")
        characters = record.get("characters")
        paragraphs = record.get("paragraphs")
        if not isinstance(record_id, (int, str)) or str(record_id) in seen_ids:
            raise ValueError(f"{label}编号缺失或重复")
        if not isinstance(characters, list) or len(characters) != expected_character_count:
            raise ValueError(f"{label}角色数量不符合来源资料")
        if any(not isinstance(person, dict) or not isinstance(person.get("name"), str)
               or not person["name"].strip() for person in characters):
            raise ValueError(f"{label}角色名称缺失")
        if not isinstance(paragraphs, list) or not paragraphs or any(
            not isinstance(paragraph, str) or not paragraph.strip() for paragraph in paragraphs
        ):
            raise ValueError(f"{label}正文缺失或格式无效")
        if "condition" in record and not isinstance(record["condition"], str):
            raise ValueError(f"{label}触发条件格式无效")
        if "label" in record and not isinstance(record["label"], str):
            raise ValueError(f"{label}标签格式无效")
        seen_ids.add(str(record_id))
        normalized.append(record)
    return normalized


def _normalize_record(record: dict[str, Any], kind: str, prefix: str, source_row: int) -> dict[str, Any]:
    people = record["characters"]
    names = [person["name"].strip() for person in people]
    titles = [person.get("title", "").strip() for person in people]
    source_id = f"{prefix}-{record['id']}"
    label = record.get("label", "").strip()
    if not label and prefix == "pair":
        label = f"后日谈 {int(record['id']):02d}"
    return {
        "source_id": source_id,
        "source_row": source_row,
        "identity_values": [source_id],
        "title": " × ".join(names),
        "fields": {
            "ending_kind": kind,
            "characters": "、".join(names),
            "character_titles": "、".join(title for title in titles if title),
            "ending_label": label,
            "condition": record.get("condition", "").strip(),
            "story": "\n\n".join(paragraph.strip() for paragraph in record["paragraphs"]),
        },
    }


def _bundle_uri(page_html: str) -> str:
    match = _BUNDLE_PATH.search(page_html)
    if not match:
        raise ValueError("网页中没有可识别的结局脚本地址")
    return urljoin(PAGE_URI, match.group(1).replace("&amp;", "&"))


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="从已保存的攻略 Wiki 页面生成结局语义数据")
    parser.add_argument("--page", type=Path, required=True, help="本地保存的结局页面 HTML")
    parser.add_argument("--bundle", type=Path, required=True, help="页面引用的本地 JavaScript 资源")
    parser.add_argument("--output", type=Path, required=True, help="写入规范化 JSON 的忽略目录路径")
    arguments = parser.parse_args()
    page_bytes = arguments.page.read_bytes()
    bundle_bytes = arguments.bundle.read_bytes()
    output = extract_endings(page_bytes.decode("utf-8"), bundle_bytes.decode("utf-8"))
    output["source"]["page_sha256"] = _sha256(page_bytes)
    output["source"]["bundle_sha256"] = _sha256(bundle_bytes)
    output["source"]["revision"] = hashlib.sha256(
        (output["source"]["page_sha256"] + output["source"]["bundle_sha256"]).encode("ascii")
    ).hexdigest()
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(output['records'])} ending records from {output['source']['uri']}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
