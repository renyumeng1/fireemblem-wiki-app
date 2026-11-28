# Third-Party Notices & Data Sources

This document lists the third-party repositories, community websites, models, and open-source libraries referenced or used by this personal non-commercial project. All copyrights, trademarks, game data, and artwork belong to their respective owners and contributors.

## Game Data & Algorithm References

### Fire Emblem: Fortune's Weave (《火焰纹章：万缕千丝》)
- **Community Online Spreadsheet (《火焰之纹章 万紫千红》在线数据表)**: https://docs.qq.com/sheet/DV0N0VUZLSXRmUWFq?tab=lnafol — characters, classes, crests, skills, combat arts, magic, weapons, accessories, items, paralogues, side quests, mounts, battalions, drinks, gifts, meals, timeline, and challenge book data.
- **fireemblem.top**: https://fireemblem.top/ (`/api/world-map`, `/topics/endings`, `/database/planting`, `/archive/supports`, `/archive/mechanics`, `/topics/growth-mechanics`, `/tools/growth-simulator`) — world map terrain/road network, endings, planting, supports, mechanics, and growth simulator references.
- **KeenGamer / RaiderKing**: https://www.keengamer.com/ & https://raiderking.com/ — Perfect Bird Time dialogue answers (as compiled and cross-checked in the community spreadsheet).
- **FE_Rigging_Buddy**: https://github.com/kskdmcph58-lgtm/FE_Rigging_Buddy (referenced revision `016f048b5c636035ac0b56b0c4b9d89a742fba4a`) — RNG seed recovery, growth forecast rule baseline, and character portrait index. Exact referenced files and SHA-256 digests are recorded in `core-domain/src/main/resources/buddy/sources-v1.json`.

### Fire Emblem: Three Houses (《火焰纹章：风花雪月》)
- **Fire Emblem Wiki**: https://fireemblemwiki.org/ (GNU Free Documentation License 1.3 unless page-specific notices state otherwise) — structured tables for characters, classes, skills, combat arts, magic, weapons, items, crests, battalions, chapters, supports, gifts, dining hall, tea party, and monastery activities.
- **Fire Emblem Wiki (Fandom)**: https://fireemblem.fandom.com/ — selected character portraits and icons.
- **Serenes Forest**: https://serenesforest.net/three-houses/ — cross-check reference for class growth rates and battalions.
- **Fedatamine**: https://houses.fedatamine.com/zh-cn/ — Simplified Chinese support dialogue, classroom lecture Q&A, advice box Q&A, lost items, and tea party topics/final comments.
- **Samurai Gamers**: https://samurai-gamers.com/fire-emblem-three-houses/ — 4th-round Tea Party valid reaction answers across all base and DLC characters (including Constance indoor/outdoor variants).
- **Moegirlpedia (萌娘百科)**: https://zh.moegirl.org.cn/火焰之纹章_风花雪月 (CC BY-NC-SA 4.0) — Simplified Chinese terminology reference.

### Fire Emblem Engage (《火焰纹章：Engage（结合）》)
- **Community Online Spreadsheet (《火焰之纹章 ENGAGE结合》在线数据表)** — structured character, class base/growth/cap, emblem, bond ring, skill, equipment, Somniel activity, and achievement data.
- **Fire Emblem Wiki**: https://fireemblemwiki.org/wiki/List_of_chapters_in_Fire_Emblem_Engage (GNU FDL 1.3) — chapter index tables and selected icons.

## On-Device OCR Models

- **RapidOCR / PaddleOCR PP-OCRv4 (Chinese Mobile ONNX)**: Downloaded on demand by the user at runtime (not bundled inside the APK) with SHA-256 verification from:
  - https://www.modelscope.cn/models/RapidAI/RapidOCR
  - https://huggingface.co/RapidAI/RapidOCR
  - https://hf-mirror.com/RapidAI/RapidOCR

## Open-Source Libraries & Frameworks

- **Jetpack Compose & AndroidX Material 3** (Apache 2.0)
- **ONNX Runtime Android** — https://github.com/microsoft/onnxruntime (MIT)
- **OkHttp** — https://github.com/square/okhttp (Apache 2.0)
- **Anthropic Java SDK** — https://github.com/anthropics/anthropic-sdk-java (MIT)
- **Kotlinx Serialization & Kotlinx Coroutines** (Apache 2.0)

## Takedown / Removal Notice

If you are a rights holder, site owner, or contributor and believe any referenced data, link, or build asset should be removed, please open an issue on this repository and it will be addressed promptly.
