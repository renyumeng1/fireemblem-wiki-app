from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from collections.abc import Callable
from typing import Any
from urllib.parse import urljoin

from prepare_endings import _JSON_PARSE, _decode_js_string, _extract_next_stream


PAGE_URI = "https://fireemblem.top/archive/supports"
OUTPUT_SCHEMA_VERSION = 1
_SUPPORT_BUNDLE_PATH = re.compile(r'<script[^>]+src="([^"]*/5657-[^"]+\.js)"')
_STAT_NAMES = ["物攻", "魔攻", "物防", "魔防", "命中", "回避", "必杀", "必杀回避", "攻速"]
_TYPE_LABELS = {
    "援護": "援护",
    "攻勢": "攻势",
    "連携": "连携",
    "防衛": "防卫",
    "魔道": "魔道",
    "騎兵": "骑兵",
    "飛兵": "飞兵",
    "無し": "无",
}


def extract_supports(page_html: str, page_bundle: str) -> dict[str, Any]:
    stream = _extract_next_stream(page_html)
    people = _extract_unique_array(stream, "people", _valid_people)
    jobs = _extract_unique_array(stream, "jobs", _valid_jobs)
    source_data = _extract_support_payload(page_bundle)
    normalized_people, name_indexes = _normalize_people(people, source_data)
    normalized_jobs = _normalize_jobs(jobs, source_data)
    support_types = _normalize_support_types(source_data)
    pairs = _normalize_pairs(source_data, name_indexes)
    page_bundle_uri = _bundle_uri(page_html)
    page_hash = _sha256(page_html.encode("utf-8"))
    bundle_hash = _sha256(page_bundle.encode("utf-8"))
    revision = _sha256((page_hash + bundle_hash).encode("ascii"))
    return {
        "schema_version": OUTPUT_SCHEMA_VERSION,
        "source": {
            "uri": PAGE_URI,
            "bundle_uri": page_bundle_uri,
            "page_sha256": page_hash,
            "bundle_sha256": bundle_hash,
            "revision": revision,
        },
        "stat_names": _STAT_NAMES,
        "thresholds": _validate_thresholds(source_data.get("pw")),
        "people": normalized_people,
        "jobs": normalized_jobs,
        "support_types": support_types,
        "pairs": pairs,
    }


def _extract_unique_array(
    stream: str,
    property_name: str,
    validator: Callable[[list[dict[str, Any]]], list[dict[str, Any]]],
) -> list[dict[str, Any]]:
    token = f'"{property_name}":'
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
            key = json.dumps(validated, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            candidates[key] = validated
    if len(candidates) != 1:
        raise ValueError(f"网页数据中应有唯一有效的 {property_name} 清单，实际找到 {len(candidates)} 个")
    return next(iter(candidates.values()))


def _valid_people(value: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized = []
    seen_names: set[str] = set()
    for person in value:
        name = person.get("name")
        href = person.get("href")
        if (not isinstance(name, str) or not name.strip() or name.strip() in seen_names
                or not isinstance(href, str) or not href.startswith("/database/characters/")):
            raise ValueError("角色字段缺失、重复或格式错误")
        display_name = person.get("displayName", name)
        if not isinstance(display_name, str):
            raise ValueError("角色显示名称格式错误")
        normalized.append({
            "name": name.strip(),
            "display_name": _display_name(display_name, name.strip()),
        })
        seen_names.add(name.strip())
    return normalized


def _valid_jobs(value: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized = []
    seen_ids: set[str] = set()
    for job in value:
        job_id = job.get("id")
        name = job.get("name")
        support_type = job.get("type")
        tiers = job.get("tiers")
        if (not isinstance(job_id, str) or not job_id.strip() or job_id in seen_ids
                or not isinstance(name, str) or not name.strip()
                or support_type not in _TYPE_LABELS
                or not isinstance(tiers, list) or any(not isinstance(tier, str) for tier in tiers)):
            raise ValueError("职业字段缺失、重复或格式错误")
        display_name = job.get("displayName", name)
        if not isinstance(display_name, str):
            raise ValueError("职业显示名称格式错误")
        normalized.append({
            "source_id": job_id,
            "name": name.strip(),
            "display_name": _display_name(display_name, name.strip()),
            "support_type": support_type,
            "support_type_label": _TYPE_LABELS[support_type],
            "tiers": [tier.strip() for tier in tiers if tier.strip()],
        })
        seen_ids.add(job_id)
    return normalized


def _extract_support_payload(bundle: str) -> dict[str, Any]:
    candidates: dict[str, dict[str, Any]] = {}
    for match in _JSON_PARSE.finditer(bundle):
        try:
            payload = json.loads(_decode_js_string(match.group(1)))
        except (ValueError, json.JSONDecodeError):
            continue
        if isinstance(payload, dict) and {"hg", "AS", "gK", "M1", "pw"}.issubset(payload):
            key = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            candidates[key] = payload
    if len(candidates) != 1:
        raise ValueError(f"支援脚本应包含唯一的支援数据对象，实际找到 {len(candidates)} 个")
    return next(iter(candidates.values()))


def _normalize_people(people: list[dict[str, Any]], source_data: dict[str, Any]) -> tuple[list[dict[str, str]], dict[int, str]]:
    source_names = source_data.get("hg")
    if (not isinstance(source_names, list) or not source_names
            or any(not isinstance(person, dict) or not isinstance(person.get("name"), str)
                   or not person["name"].strip() for person in source_names)):
        raise ValueError("支援数据中的角色列表无效")
    indexed_names = [person["name"].strip() for person in source_names]
    page_names = [person["name"] for person in people]
    if len(set(indexed_names)) != len(indexed_names) or set(indexed_names) != set(page_names):
        raise ValueError("页面角色清单与支援数据角色不一致")
    people_by_name = {person["name"]: person for person in people}
    ordered = [{"name": name, "display_name": people_by_name[name]["display_name"]} for name in indexed_names]
    return ordered, dict(enumerate(indexed_names))


def _normalize_jobs(jobs: list[dict[str, Any]], source_data: dict[str, Any]) -> list[dict[str, Any]]:
    support_types = source_data.get("gK")
    if not isinstance(support_types, dict) or not support_types:
        raise ValueError("支援兵种加成类型缺失")
    unsupported = {job["support_type"] for job in jobs} - set(support_types) - {"無し"}
    if unsupported:
        raise ValueError(f"职业引用了未知支援类型：{'、'.join(sorted(unsupported))}")
    return jobs


def _normalize_support_types(source_data: dict[str, Any]) -> list[dict[str, Any]]:
    stat_names = source_data.get("M1")
    if stat_names != _STAT_NAMES:
        raise ValueError("支援加成属性顺序与已核实的九项属性不一致")
    source_types = source_data.get("gK")
    if not isinstance(source_types, dict) or set(source_types) != set(_TYPE_LABELS) - {"無し"}:
        raise ValueError("支援类型必须与当前页面的七类加成表一致")
    result = []
    for type_id, levels in source_types.items():
        if (not isinstance(levels, list) or len(levels) != 5
                or any(not isinstance(level, list) or len(level) != len(_STAT_NAMES)
                       or any(not _integer(value) or not -100 <= value <= 100 for value in level)
                       for level in levels)):
            raise ValueError(f"支援类型的 1–5 级加成格式无效：{type_id}")
        result.append({"id": type_id, "label": _TYPE_LABELS[type_id], "levels": levels})
    result.append({"id": "無し", "label": _TYPE_LABELS["無し"], "levels": [[0] * len(_STAT_NAMES) for _ in range(5)]})
    return result


def _normalize_pairs(source_data: dict[str, Any], name_indexes: dict[int, str]) -> list[dict[str, Any]]:
    source_pairs = source_data.get("AS")
    if not isinstance(source_pairs, list) or not source_pairs:
        raise ValueError("角色支援关系列表为空")
    normalized = []
    seen: set[tuple[int, int]] = set()
    for pair in source_pairs:
        if not isinstance(pair, dict):
            raise ValueError("角色支援关系格式无效")
        first_index, second_index = pair.get("a"), pair.get("b")
        if (not _integer(first_index) or not _integer(second_index) or first_index == second_index
                or first_index not in name_indexes or second_index not in name_indexes):
            raise ValueError("支援关系角色索引无效")
        key = (min(first_index, second_index), max(first_index, second_index))
        if key in seen:
            raise ValueError(f"支援关系重复：{name_indexes[key[0]]}/{name_indexes[key[1]]}")
        seen.add(key)
        affinity = pair.get("affinity")
        initial_level = pair.get("initial")
        bonuses = pair.get("bonus")
        talks = pair.get("talks")
        if (not _integer(affinity) or affinity not in range(1, 5)
                or not _integer(initial_level) or initial_level not in range(0, 6)
                or not isinstance(bonuses, list) or len(bonuses) != len(_STAT_NAMES)
                or any(not _integer(value) or not -100 <= value <= 100 for value in bonuses)
                or not isinstance(talks, list)):
            raise ValueError("支援关系属性、等级或对话字段格式无效")
        normalized_talks = [_normalize_talk(talk) for talk in talks]
        normalized.append({
            "first_name": name_indexes[first_index],
            "second_name": name_indexes[second_index],
            "source_first_index": first_index,
            "source_second_index": second_index,
            "affinity": affinity,
            "initial_level": initial_level,
            "combination_bonus": bonuses,
            "talks": normalized_talks,
        })
    expected_count = len(name_indexes) * (len(name_indexes) - 1) // 2
    if len(seen) != expected_count:
        raise ValueError(f"支援关系不完整：应有 {expected_count} 组，实际有 {len(seen)} 组")
    expected = {(first, second) for first in name_indexes for second in name_indexes if first < second}
    missing = expected - seen
    if missing:
        first_index, second_index = min(missing)
        raise ValueError(f"支援关系缺失：{name_indexes[first_index]}/{name_indexes[second_index]}")
    return normalized


def _normalize_talk(talk: Any) -> dict[str, Any]:
    if not isinstance(talk, dict):
        raise ValueError("支援对话条件格式无效")
    rank = talk.get("rank")
    start = talk.get("start")
    end = talk.get("end")
    route = talk.get("route")
    any_joined = talk.get("anyJoined")
    conditions = talk.get("conditions")
    pending = talk.get("pending")
    date_pending = talk.get("datePending")
    if (not _integer(rank) or rank not in range(1, 5)
            or not _valid_chapter(start, allow_open_end=False) or not _valid_chapter(end, allow_open_end=True)
            or not _integer(route) or route < 0
            or not isinstance(any_joined, list) or any(not isinstance(name, str) for name in any_joined)
            or not isinstance(conditions, list) or any(not isinstance(item, str) for item in conditions)
            or not _integer(pending) or pending < 0 or not isinstance(date_pending, bool)):
        raise ValueError("支援对话条件字段缺失或越界")
    return {
        "rank": rank,
        "start": start,
        "end": end,
        "route": route,
        "any_joined": any_joined,
        "conditions": conditions,
        "pending": pending,
        "date_pending": date_pending,
    }


def _validate_thresholds(value: Any) -> list[int]:
    if (not isinstance(value, list) or len(value) != 5
            or any(not _integer(number) or number < 0 for number in value)
            or value[0] != 0 or any(first >= second for first, second in zip(value, value[1:]))):
        raise ValueError("支援等级 1–5 的累计门槛无效")
    return value


def _valid_chapter(value: Any, allow_open_end: bool) -> bool:
    if (not isinstance(value, list) or len(value) != 2
            or any(not _integer(part) or part < 0 for part in value)):
        return False
    if allow_open_end and value == [0, 0]:
        return True
    return value[0] > 0


def _integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _display_name(value: str, fallback: str) -> str:
    normalized = value.strip()
    return fallback if not normalized or normalized == "$undefined" else normalized


def _bundle_uri(page_html: str) -> str:
    match = _SUPPORT_BUNDLE_PATH.search(page_html)
    if not match:
        raise ValueError("网页中没有可识别的支援数据脚本地址")
    return urljoin(PAGE_URI, match.group(1).replace("&amp;", "&"))


def _sha256(value: bytes | str) -> str:
    payload = value.encode("utf-8") if isinstance(value, str) else value
    return hashlib.sha256(payload).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="从已保存的授权支援页与脚本生成离线语义资料")
    parser.add_argument("--page", type=Path, required=True, help="本地保存的支援页面 HTML")
    parser.add_argument("--bundle", type=Path, required=True, help="页面引用的本地共享脚本")
    parser.add_argument("--output", type=Path, required=True, help="写入规范化 JSON 的忽略目录路径")
    arguments = parser.parse_args()
    try:
        page_bytes = arguments.page.read_bytes()
        bundle_bytes = arguments.bundle.read_bytes()
        output = extract_supports(page_bytes.decode("utf-8"), bundle_bytes.decode("utf-8"))
    except (OSError, UnicodeError, ValueError) as error:
        parser.error(str(error))
    output["source"]["page_sha256"] = _sha256(page_bytes)
    output["source"]["bundle_sha256"] = _sha256(bundle_bytes)
    output["source"]["revision"] = _sha256(
        (output["source"]["page_sha256"] + output["source"]["bundle_sha256"]).encode("ascii")
    )
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(output['pairs'])} support pairs and {sum(len(pair['talks']) for pair in output['pairs'])} conversation conditions.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
