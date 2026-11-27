from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from prepare_endings import _extract_next_stream


PAGE_URI = "https://fireemblem.top/tools/growth-simulator"
OUTPUT_SCHEMA_VERSION = 1

# Verified permutation from website [HP, 力量, 魔力, 速度, 技巧, 守备, 魔防, 幸运, 魅力]
# to BuddyRules GrowthStat [HP, 力量, 魔力, 技巧, 速度, 幸运, 防守, 魔防, 魅力].
STAT_PERMUTATION = (0, 1, 2, 4, 3, 7, 5, 6, 8)
BUDDY_STAT_KEYS = ("HP", "力量", "魔力", "技巧", "速度", "幸运", "守备", "魔防", "魅力")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _perm9(vector: list[int], label: str) -> list[int]:
    if not isinstance(vector, list) or len(vector) != 9 or not all(isinstance(v, int) and not isinstance(v, bool) for v in vector):
        raise ValueError(f"九项属性数组格式无效：{label}")
    return [vector[index] for index in STAT_PERMUTATION]


def _extract_unique_array(stream: str, key: str, validator) -> list[dict[str, Any]]:
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
            sig = json.dumps(validated, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            candidates[sig] = validated
    if len(candidates) != 1:
        raise ValueError(f"成长模拟器页面中应有唯一有效的 {key} 清单，实际找到 {len(candidates)} 个")
    return next(iter(candidates.values()))


def _valid_characters(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for item in raw:
        native_id = item.get("nativeId")
        name = item.get("name")
        code = item.get("code")
        record_id = item.get("recordId")
        start_class = item.get("startClass")
        level = item.get("level")
        minimum = item.get("minimum")
        if (not isinstance(native_id, str) or not native_id.strip() or native_id.strip() in seen_ids
                or not isinstance(name, str) or not name.strip()
                or not isinstance(code, str)
                or not isinstance(record_id, int) or isinstance(record_id, bool)
                or not isinstance(start_class, str)
                or not isinstance(level, int) or isinstance(level, bool) or level < 1
                or not isinstance(minimum, list) or len(minimum) < 2):
            raise ValueError("成长模拟器角色字段无效")
        normalized.append({
            "native_id": native_id.strip(),
            "code": code.strip(),
            "record_id": record_id,
            "name": name.strip(),
            "faction": str(item.get("faction", "")).strip(),
            "gender": str(item.get("gender", "")).strip(),
            "start_class": start_class.strip(),
            "start_level": level,
            "base": _perm9(item.get("base"), f"{native_id}.base"),
            "growth": _perm9(item.get("growth"), f"{native_id}.growth"),
            "caps": _perm9(item.get("caps"), f"{native_id}.caps"),
            "guarantee_pre": int(minimum[0]),
            "guarantee_part3": int(minimum[1]),
        })
        seen_ids.add(native_id.strip())
    return normalized


def _valid_classes(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for item in raw:
        class_id = item.get("id")
        name = item.get("name")
        tier = item.get("tier")
        record_id = item.get("recordId")
        if (not isinstance(class_id, str) or not class_id.strip() or class_id.strip() in seen_ids
                or not isinstance(name, str) or not name.strip()
                or not isinstance(tier, str)
                or not isinstance(record_id, int) or isinstance(record_id, bool)):
            raise ValueError("成长模拟器兵种字段无效")
        allowed = [int(x) for x in item.get("allowedRecords", []) if isinstance(x, int) and not isinstance(x, bool)]
        forbidden = [str(x).strip() for x in item.get("forbiddenPeople", []) if isinstance(x, str) and x.strip()]
        normalized.append({
            "id": class_id.strip(),
            "record_id": record_id,
            "name": name.strip(),
            "tier": tier.strip(),
            "base": _perm9(item.get("base"), f"{class_id}.base"),
            "growth_bonus": _perm9(item.get("growth"), f"{class_id}.growth"),
            "base_growth": _perm9(item.get("baseGrowth"), f"{class_id}.baseGrowth"),
            "modifier": _perm9(item.get("modifier"), f"{class_id}.modifier"),
            "caps": _perm9(item.get("caps"), f"{class_id}.caps"),
            "mount_type": str(item.get("mountType", "")).strip(),
            "mount_multiplier": int(item.get("mountMultiplier", 1)),
            "allowed_records": allowed,
            "forbidden_people": forbidden,
        })
        seen_ids.add(class_id.strip())
    return normalized


def _valid_mounts(raw: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for item in raw:
        mount_id = item.get("id")
        mount_type = item.get("type")
        name = item.get("name")
        if (not isinstance(mount_id, str) or not mount_id.strip() or mount_id.strip() in seen_ids
                or not isinstance(mount_type, str) or not mount_type.strip()
                or not isinstance(name, str) or not name.strip()):
            raise ValueError("成长模拟器坐骑字段无效")
        attrs_raw = item.get("attributes", "{}")
        attrs_dict = json.loads(attrs_raw) if isinstance(attrs_raw, str) else {}
        attr_bonus = [int(attrs_dict.get(key, 0)) for key in BUDDY_STAT_KEYS]
        normalized.append({
            "id": mount_id.strip(),
            "type": mount_type.strip(),
            "name": name.strip(),
            "growth": _perm9(item.get("growth"), f"{mount_id}.growth"),
            "attr_bonus": attr_bonus,
            "effect": str(item.get("effect", "")).strip(),
            "location": str(item.get("location", "")).strip(),
        })
        seen_ids.add(mount_id.strip())
    return normalized


def extract_growth_simulator(page_html: str) -> dict[str, Any]:
    stream = _extract_next_stream(page_html)
    characters = _extract_unique_array(stream, "characters", _valid_characters)
    classes = _extract_unique_array(stream, "classes", _valid_classes)
    mounts = _extract_unique_array(stream, "mounts", _valid_mounts)
    page_hash = _sha256(page_html.encode("utf-8"))
    return {
        "schema_version": OUTPUT_SCHEMA_VERSION,
        "source": {
            "uri": PAGE_URI,
            "page_sha256": page_hash,
            "revision": page_hash,
        },
        "stat_names": list(BUDDY_STAT_KEYS),
        "characters": characters,
        "classes": classes,
        "mounts": mounts,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="提取多阶段成长模拟器角色、兵种与坐骑数据为结构化 JSON")
    parser.add_argument(
        "--page",
        type=Path,
        default=Path("data/research/site-plan-audit/growth-simulator.html"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("data/derived/growth-simulator.json"),
    )
    args = parser.parse_args()
    data = extract_growth_simulator(args.page.read_text(encoding="utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"Prepared {len(data['characters'])} simulator characters, {len(data['classes'])} classes, "
        f"and {len(data['mounts'])} mounts."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
