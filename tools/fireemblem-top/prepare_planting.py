from __future__ import annotations

import argparse
from html.parser import HTMLParser
import hashlib
import json
from pathlib import Path
import re
from typing import Any
from urllib.parse import parse_qs, urljoin, urlsplit


PAGE_URI = "https://fireemblem.top/database/planting"
CHAPTER_IDS = {"カイ": "kai", "救世編": "salvation"}
CHAPTER_LABELS = {"kai": "凯伊篇", "salvation": "救世篇"}
OUTPUT_SCHEMA_VERSION = 1
_PERCENT = re.compile(r"^(\d+(?:\.\d+)?)\s*%$")


class _Node:
    def __init__(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.tag = tag
        self.attrs = dict(attrs)
        self.children: list[_Node] = []
        self.parent: _Node | None = None
        self.content: list[str | _Node] = []

    def text(self) -> str:
        if self.tag == "br":
            return " "
        return _normalize_space("".join(
            part if isinstance(part, str) else part.text() for part in self.content
        ))

    def descendants(self, tag: str | None = None) -> list[_Node]:
        found: list[_Node] = []
        for child in self.children:
            if tag is None or child.tag == tag:
                found.append(child)
            found.extend(child.descendants(tag))
        return found

    def first(self, tag: str) -> _Node | None:
        return next((node for node in self.descendants(tag)), None)


class _TreeParser(HTMLParser):
    _VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
                  "source", "track", "wbr"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = _Node("root", [])
        self.stack = [self.root]

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        node = _Node(tag.lower(), attrs)
        node.parent = self.stack[-1]
        node.parent.children.append(node)
        node.parent.content.append(node)
        if node.tag not in self._VOID_TAGS:
            self.stack.append(node)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        for index in range(len(self.stack) - 1, 0, -1):
            if self.stack[index].tag == tag:
                del self.stack[index:]
                return

    def handle_data(self, data: str) -> None:
        self.stack[-1].content.append(data)


def extract_planting(page_html: str, page_bytes: bytes | None = None) -> dict[str, Any]:
    parser = _TreeParser()
    parser.feed(page_html)
    locations = _extract_locations(parser.root)
    plants = _unique_plants(locations)
    fertilizers = _extract_fertilizers(parser.root)
    harvest = _extract_harvest_parameters(parser.root)
    raw_bytes = page_bytes if page_bytes is not None else page_html.encode("utf-8")
    page_sha256 = hashlib.sha256(raw_bytes).hexdigest()
    return {
        "schema_version": OUTPUT_SCHEMA_VERSION,
        "source": {
            "uri": PAGE_URI,
            "page_sha256": page_sha256,
            "revision": page_sha256,
        },
        "harvest_parameters": harvest,
        "source_notes": _extract_source_notes(parser.root),
        "plants": plants,
        "locations": locations,
        "fertilizers": fertilizers,
    }


def _extract_locations(root: _Node) -> list[dict[str, Any]]:
    tables = [table for table in root.descendants("table") if _location_table_header(table)]
    if len(tables) != 1:
        raise ValueError(f"网页应包含唯一的稀有植物地点表，实际找到 {len(tables)} 个")
    table = tables[0]
    rows = table.descendants("tr")
    locations = []
    for source_row, row in enumerate(rows, start=1):
        cells = [child for child in row.children if child.tag in {"td", "th"}]
        if len(cells) != 4 or any(cell.tag == "th" for cell in cells):
            continue
        name = _text_without_links(cells[0])
        plant_name = _text_without_links(cells[1])
        probability_text = cells[2].text()
        description = cells[3].text()
        if not name or not plant_name or not probability_text or not description:
            raise ValueError(f"种植地点第{source_row}行缺少必要字段")
        map_markers = _extract_map_markers(cells[0], name, source_row)
        if not map_markers:
            raise ValueError(f"种植地点缺少地图标记：{name}")
        locations.append({
            "source_row": source_row,
            "name": name,
            "plant_name": plant_name,
            "plant_probability_text": probability_text,
            "plant_probability_percent": _percentage(probability_text, f"地点概率：{name}"),
            "plant_description": description,
            "map_markers": map_markers,
        })
    if not locations:
        raise ValueError("稀有植物地点表没有数据行")
    return locations


def _location_table_header(table: _Node) -> bool:
    for row in table.descendants("tr"):
        cells = [child for child in row.children if child.tag in {"td", "th"}]
        if [cell.text() for cell in cells] == ["地点", "稀有植物", "稀有植物概率", "植物说明"]:
            return True
    return False


def _text_without_links(node: _Node) -> str:
    return _normalize_space("".join(
        part if isinstance(part, str) else "" if part.tag == "a" else " " if part.tag == "br"
        else _text_without_links(part)
        for part in node.content
    ))


def _extract_fertilizers(root: _Node) -> list[dict[str, Any]]:
    section = _section_with_heading(root, "肥料与获取地点")
    articles = [child for child in section.children if child.tag == "article"]
    if not articles:
        articles = [node for node in section.descendants("article")]
    fertilizers = []
    for source_row, article in enumerate(articles, start=1):
        heading = next((child for child in article.children if child.tag == "h3"), None)
        if heading is None:
            heading = article.first("h3")
        if heading is None:
            raise ValueError(f"肥料第{source_row}项缺少名称")
        match = re.fullmatch(r"(.+?)\s*(\d+(?:\.\d+)?)\s*%", heading.text())
        if match is None:
            raise ValueError(f"肥料参数格式无效：{heading.text()}")
        name = _normalize_space(match.group(1))
        parameter_text = f"{match.group(2)}%"
        copy_node = next((child for child in article.children if child is not heading and child.tag != "details"), None)
        if copy_node is None:
            raise ValueError(f"肥料说明缺失：{name}")
        paragraphs = [_normalize_space(node.text()) for node in copy_node.descendants("p") if node.text()]
        if not paragraphs:
            paragraphs = [copy_node.text()]
        acquisition = [text for text in paragraphs if re.match(r"^(?:可在|可以在|可从|可以从)", text)]
        description = [text for text in paragraphs if text not in acquisition]
        map_markers = _extract_map_markers(article, name, source_row, use_link_group_name=True)
        if not map_markers:
            raise ValueError(f"肥料缺少获取地点：{name}")
        fertilizers.append({
            "source_row": source_row,
            "name": name,
            "parameter_text": parameter_text,
            "parameter_percent": float(match.group(2)),
            "description": "\n".join(description),
            "acquisition_hint": "\n".join(acquisition),
            "copy_text": "\n".join(paragraphs),
            "map_markers": map_markers,
        })
    if not fertilizers:
        raise ValueError("肥料与获取地点章节没有肥料条目")
    return fertilizers


def _extract_map_markers(node: _Node, owner_name: str, source_row: int,
                         use_link_group_name: bool = False) -> list[dict[str, Any]]:
    markers = []
    for anchor in node.descendants("a"):
        href = anchor.attrs.get("href") or ""
        if not urlsplit(href).path.endswith("/maps"):
            continue
        source_uri = urljoin(PAGE_URI, href)
        pin_values = parse_qs(urlsplit(source_uri).query).get("pin", [])
        if len(pin_values) != 1:
            raise ValueError(f"地图来源链接缺少唯一 pin 参数：{href}")
        source_map_id = pin_values[0]
        source_chapter, separator, map_pin_id = source_map_id.partition("|")
        chapter_id = CHAPTER_IDS.get(source_chapter)
        if not separator or not chapter_id or not map_pin_id:
            raise ValueError(f"地图标记格式或篇章未配置：{source_map_id}")
        location_name = _map_link_group_name(anchor) if use_link_group_name else owner_name
        markers.append({
            "chapter_source_token": source_chapter,
            "chapter_id": chapter_id,
            "chapter_label": CHAPTER_LABELS[chapter_id],
            "source_map_id": source_map_id,
            "map_pin_id": map_pin_id,
            "location_name": location_name or owner_name,
            "source_uri": source_uri,
            "source_row": source_row,
        })
    return markers


def _map_link_group_name(anchor: _Node) -> str:
    group = anchor.parent
    while group is not None and group.tag not in {"span", "li", "div"}:
        group = group.parent
    if group is None:
        return ""
    return next((child.text() for child in group.children if child.tag != "a" and child.text()), "")


def _extract_harvest_parameters(root: _Node) -> dict[str, Any]:
    section = _section_with_heading(root, "收获参数怎么理解")
    section_text = section.text()
    cycle = re.search(r"收获周期[^\d]{0,40}(\d+)\s*回合", section_text)
    yield_count = re.search(r"收获(?:个数|数量)[^\d]{0,40}(\d+)\s*(?:个)?", section_text)
    if cycle is None or yield_count is None:
        raise ValueError("收获周期或数量参数缺失")
    tables = [table for table in section.descendants("table") if _harvest_table_header(table)]
    if len(tables) != 1:
        raise ValueError(f"收获次数表应唯一，实际找到 {len(tables)} 个")
    rows = tables[0].descendants("tr")
    probability_row = next((row for row in rows if row.children and row.children[0].text() == "对应概率"), None)
    header_row = next((row for row in rows if row.children and row.children[0].text() == "收获次数"), None)
    if probability_row is None or header_row is None:
        raise ValueError("收获次数或概率表行缺失")
    header_cells = [child for child in header_row.children if child.tag in {"td", "th"}]
    probability_cells = [child for child in probability_row.children if child.tag in {"td", "th"}]
    if len(header_cells) != len(probability_cells) or len(header_cells) < 2:
        raise ValueError("收获概率表列数不一致")
    probabilities = []
    for number_cell, probability_cell in zip(header_cells[1:], probability_cells[1:]):
        number = int(number_cell.text())
        probability_text = probability_cell.text()
        probabilities.append({
            "harvest_number": number,
            "probability_text": probability_text,
            "probability_percent": _percentage(probability_text, f"第{number}次收获概率"),
            "verification_status": "unverified",
        })
    paragraphs = [_normalize_space(node.text()) for node in section.descendants("p") if node.text()]
    yield_evidence = next((text for text in paragraphs if re.match(r"^收获(?:个数|数量)", text)), "")
    cycle_evidence = next((text for text in paragraphs if re.match(r"^收获周期", text)), "")
    probability_note = next((text for text in paragraphs if text.startswith("待确认：")), "")
    if not probability_note:
        raise ValueError("收获概率待核实说明缺失")
    return {
        "cycle_turns": int(cycle.group(1)),
        "cycle_evidence": cycle_evidence or f"收获周期 {cycle.group(1)} 回合",
        "yield_count": int(yield_count.group(1)),
        "yield_count_status": "unverified" if "待确认" in yield_evidence else "source_recorded",
        "yield_evidence": yield_evidence,
        "probabilities": probabilities,
        "probability_note": probability_note,
    }


def _extract_source_notes(root: _Node) -> dict[str, str]:
    paragraphs = [_normalize_space(node.text()) for node in root.descendants("p") if node.text()]
    usage_note = _note_text(paragraphs, "如何使用：")
    fertilizer_note = _note_text(paragraphs, "百分比怎么看：")
    if not usage_note or not fertilizer_note:
        raise ValueError("种植说明或肥料参数说明缺失")
    return {"usage_note": usage_note, "fertilizer_note": fertilizer_note}


def _note_text(paragraphs: list[str], heading: str) -> str:
    paragraph = next((text for text in paragraphs if heading in text), "")
    if not paragraph:
        return ""
    before, after = paragraph.split(heading, 1)
    return _normalize_space(after if not before.strip() else before)


def _harvest_table_header(table: _Node) -> bool:
    for row in table.descendants("tr"):
        cells = [child for child in row.children if child.tag in {"td", "th"}]
        if len(cells) > 2 and cells[0].text() == "收获次数" and all(
            cell.text().isdigit() for cell in cells[1:]
        ):
            return True
    return False


def _section_with_heading(root: _Node, title: str) -> _Node:
    matches = [section for section in root.descendants("section") if any(
        _normalize_space(heading.text()) == title for heading in section.descendants("h2")
    )]
    if len(matches) != 1:
        raise ValueError(f"网页应包含唯一“{title}”章节，实际找到 {len(matches)} 个")
    return matches[0]


def _unique_plants(locations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    plants: dict[str, dict[str, Any]] = {}
    for location in locations:
        name = location["plant_name"]
        plants.setdefault(name, {"name": name})
    return list(plants.values())


def _percentage(value: str, label: str) -> float:
    match = _PERCENT.fullmatch(_normalize_space(value))
    if match is None:
        raise ValueError(f"{label}格式无效：{value}")
    return float(match.group(1))


def _normalize_space(value: str) -> str:
    return " ".join(value.split())


def main() -> int:
    parser = argparse.ArgumentParser(description="从本地种植攻略快照生成离线语义资料")
    parser.add_argument("--page", type=Path, required=True, help="本地保存的种植页面 HTML")
    parser.add_argument("--output", type=Path, required=True, help="写入忽略目录的规范化 JSON")
    arguments = parser.parse_args()
    page_bytes = arguments.page.read_bytes()
    output = extract_planting(page_bytes.decode("utf-8"), page_bytes)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(output['locations'])} planting locations, {len(output['plants'])} plants, "
          f"{len(output['fertilizers'])} fertilizers, and "
          f"{sum(len(item['map_markers']) for item in output['locations'] + output['fertilizers'])} map markers.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
