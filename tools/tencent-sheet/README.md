# Tencent Sheet to Offline SQLite Pipeline

All spreadsheet interpretation happens on the developer machine. Android ships a prebuilt, read-only SQLite wiki database and offline WebP images; it does not load source-table JSON or run `TencentSheetLibrary` at runtime. The legacy Kotlin reader remains a development/test reference, not the app data path.

Provision the authorized snapshot under `data/tencent-sheet/` from a trusted local archive. These tools do not fetch or refresh it, and never modify the source. Source data, images, generated packages and databases stay in ignored `data/` or module `build/` directories, not Git.

## First-time preparation

From the repository root, with Python 3 available:

```powershell
py -3 -m pip install Pillow
py -3 tools/tencent-sheet/convert.py
py -3 tools/tencent-sheet/validate.py
py -3 tools/tencent-sheet/prepare_assets.py
py -3 tools/fireemblem-top/prepare_endings.py --page data/research/site-plan-audit/endings-2026-11-18/page.html --bundle data/research/site-plan-audit/endings-2026-11-18/page.bundle.js --output data/derived/fireemblem-top/endings.json
py -3 tools/fireemblem-top/prepare_planting.py --page data/research/site-plan-audit/database-planting.html --output data/derived/fireemblem-top/planting.json
py -3 tools/tencent-sheet/build_content_db.py --package data/derived/tencent-sheet --output data/derived/wiki/content.db --adapters tools/tencent-sheet/wiki-adapters.json --record-source endings=data/derived/fireemblem-top/endings.json --record-source planting=data/derived/fireemblem-top/planting.json
```

The stages have separate responsibilities:

1. `convert.py` preserves source cells and metadata in `data/derived/tencent-sheet/`; it does not infer entities. It requires `catalog.json`, `source.json`, `sheets.json`, `assets-manifest.json` and every table marked available.
2. `validate.py` recomputes and verifies that cell-preserving package against the snapshot. It is not a semantic-database or image validator.
3. `prepare_assets.py` verifies originals against table image references and the source asset manifest, then writes `assets/*.webp` and `image-assets-manifest.json` into that package. Images keep their aspect ratio, have a maximum edge of 1,600 pixels and use WebP quality 86; source images are unchanged.
4. `prepare_endings.py` converts the locally cached authorized page/bundle into a semantic record source; `prepare_planting.py` reads the local planting-page snapshot. Neither tool makes a network request. `build_content_db.py` uses `wiki-adapters.json` to validate expected headers, project semantic fields and generate `data/derived/wiki/content.db`, `content.sha256` and `content.report.json`. This final command is useful for inspection/tests; Gradle prepares both external sources and separately generates the APK copy.

`convert.py` and `validate.py` accept `--input-dir` / `--output-dir`; their CLI output must stay under ignored `data/derived/`. Input/output must not overlap. The converter only replaces its own complete cell-only package. **After image preparation, rerunning conversion against the same directory is rejected because it contains additional files.** For a refresh, use a fresh `--output-dir`, validate it and pass that path as `--package` to subsequent stages; deliberately provision the validated package at Gradle's configured source path rather than bypassing ownership checks.

`prepare_assets.py` accepts `--source` (default `data/tencent-sheet`) and `--package` (default `data/derived/tencent-sheet`). `build_content_db.py` accepts `--package`, `--output` and `--adapters`; the output must be a `.db` outside the input package. An existing output database must carry this generator's application ID. Keep custom outputs in ignored data/build locations. The Python APIs accept temporary paths for tests.

## Android packaging and runtime

`android-app/build.gradle.kts` registers `generateWikiAssets` for Android variants. It tracks the converted package, builder and adapter configuration, copies prepared WebP files, and invokes the database builder into `android-app/build/generated/wiki-assets/wiki/`. Generated assets include `content.db`, `content.sha256`, `content.report.json` and `assets/*.webp`; neither `tables/*.json` nor the original images are APK runtime inputs. The standalone `data/derived/wiki/content.db` is not copied by Gradle.

A fresh checkout must provision the snapshot, conversion output and images before an Android build. A missing package or a missing/corrupt referenced image fails generation/build; there is no optional empty-wiki build mode. On Windows Gradle uses `py -3`, and on other hosts it uses `python3`.

`WikiDatabaseRepository.open(context)` verifies the bundled SHA-256 and installs a validated file under `noBackupFilesDir/wiki-databases/content-<sha256>.db`. It opens SQLite read-only, checks schema/application IDs, integrity, foreign keys and catalog counts, then publishes `active.sha256` through `AtomicFile`. A failed candidate leaves a valid previous database usable, with `catalog.updateNotice`; without one, it throws `WikiDatabaseException` for the UI's retry state. Personal archives and seed rules are separate and are not overwritten.

**Upgrade limitation:** fallback retains the database only. Images are loaded from `wiki/assets/` in the current APK, not versioned asset storage. An old database may reference an image absent or changed in the new APK; arbitrary prior-version image compatibility is not guaranteed. Keep required historical paths compatible or add versioned assets before promising complete content rollback.

## Current coverage and limits

The current local report (source revision `84209`) contains 23 categories, **3,244 entries and 934 referenced image assets**. The `ps06wo` learning matrix contributes 3,848 character-skill records: 3,819 uniquely linked records, 29 unresolved/ambiguous names, 64 characters with source rows and 12 without. Dining source `lbz2o6` contributes 12 meals, 714 parsed preference tokens / 681 exact links, and 73 linked trios from 62 exact-matched character source rows. Bird-dialogue source `7j1awm` contributes 485 dialogue records, all uniquely linked to 49 characters; 447 have a correction marker, 38 are unmarked, and missing English/group fields plus one placeholder row remain explicit report gaps. Counts describe this snapshot, not a universal release invariant:

| Category ID | Display category | Entries |
|---|---|---:|
| `characters` | 角色 | 76 |
| `professions` | 职业 | 63 |
| `crests` | 血印 | 15 |
| `skills` | 特技 | 577 |
| `combat-arts` | 战技 | 435 |
| `magic` | 魔法 | 45 |
| `weapons` | 武器 | 478 |
| `accessories` | 防具与饰品 | 152 |
| `items` | 道具与材料 | 241 |
| `paralogues` | 外传 | 10 |
| `side-quests` | 支线任务 | 129 |
| `mounts` | 坐骑 | 20 |
| `battalions` | 军团 | 52 |
| `drinks` | 饮料 | 14 |
| `gifts` | 礼物 | 127 |
| `meals` | 料理 | 12 |
| `bird-dialogues` | 逗鸟问答 | 485 |
| `beginner-guide` | 新手指南 | 18 |
| `beginner-map-access` | 地图解锁 | 1 |
| `beginner-routes` | 路线简介 | 5 |
| `campaign-timeline` | 事件时间轴 | 74 |
| `challenge-book` | 挑战书 | 9 |
| `endings` | 结局 | 206 |

Weapons and accessories share source sheet `twr89r` but have different row ranges, headers and field mappings. Do not interpret accessory weight/effects as weapon level/durability. Across 38 catalog entries, 37 tables are readable; hidden `dytwkr` remains unavailable. The database report lists `unadapted_sheets` and `unavailable_sheets` separately, not fabricated empty entities. These 23 categories and skill records do not complete T1: full source coverage, exhaustive relationship verification, enhancement/material chains, identity migration and complete workflow acceptance remain outstanding.

IDs have the form `<category>:<UUIDv5>` over the category, title and configured identity-column text after whitespace/NFC normalization. Moving source rows does not change IDs; renaming a title or changing a variant identity does, requiring explicit migration. Duplicate identities fail generation rather than falling back to row IDs or name guessing. Cross-version aliases/tombstones, durable personal-reference migration and Wiki-to-Buddy mappings are not implemented. Wiki counts/values must not replace the independent pinned seed-rule roster or rule revision.

## Generated contracts

- The cell package's `manifest.json` records source URI/title/revision/retrieval time, input/output SHA-256 values, counts and unavailable sheets. `tables/<sheet-id>.json` retains one-based coordinates, merge metadata, formula cache/model, rich-text runs, image references and unknown `source_fields`; formulas are not recalculated. See [schema-v1.md](schema-v1.md).
- The builder checks each adapted table's hash, identity, revision, cell count and expected header before publication. Merge anchors fill only empty cells; formula cached values and rich-text fallbacks feed semantic fields. Zero survives, `--` / `×` field placeholders become missing values, and invalid numeric text stays visible with a null numeric companion rather than becoming zero.
- SQLite schema v10 uses application ID `0x46455749`: `content_metadata`, `content_assets`, semantic category/detail tables, the `wiki_fields` view, evidence links, character-learning projections, gift and meal preferences, dining combinations, bird dialogues, planting guides, support relationships/combat modifiers, restoration locations/material requirements, and theater plays/cue variants/rating rewards/weekly activities. Runtime accepts schema v1–v10; each typed projection explicitly reports unavailable on older schemas. `tfan0b` restoration totals are independently cross-checked against location requirements; unresolved item names remain report gaps. Theater reads `gmgn7m` and only the weekly `AI:AL` block of `f3zvkv`, never its adjacent divine-protection table; missing rewards stay null and character theater preferences remain explicitly unrecorded. See [theater-contracts.md](../../.trellis/spec/backend/theater-contracts.md). Current holdings, completion records and theater favorites belong only in the personal database, never this generated content package.
- `content_version` hashes the package manifest, adapter bytes, image manifest and external record sources; it is distinct from the final database SHA-256 and the independent Buddy rule revision. `content.report.json` records category counts, entries, assets, relationship gaps, character-learning/gift/dining/bird-dialogue coverage, planting counts and exact-link gaps, plus database bytes/hash.
- Generation stages a temporary database, checks foreign keys and `quick_check`, then replaces the owned database. Checksum/report files are published individually afterward; this is not an atomic three-file transaction. A bad header, identity conflict or corrupt referenced image must not publish a partial database.
- Repository requests support an optional category and literal substring query, `offset >= 0`, and `limit` in `1..100` (default 40). SQLite orders by title then ID and counts all matches; page summaries omit detail fields, and `entry(id)` loads them on demand. Missing IDs return null. No runtime raw-table fallback, FTS tokenizer or relationship inference is present.

## Checks and observed acceptance

```powershell
py -3 -B -m unittest discover -s tools/tencent-sheet/tests -v
```

Synthetic tests cover cell-package preservation, image preparation, semantic fields/numeric zero, merged labels, row-independent IDs, duplicate rejection, corrupt tables/images, changed headers preserving the old database, byte-repeatable generation, literal substring queries, pagination, four-tier meal links, duplicate/conflicting trios and data gaps. Bird-dialogue cases also cover exact character links, nullable English/group fields, ignored placeholders, correction state and rejecting a changed bird-specific header without replacing the previous DB. Android `WikiDatabaseRepositoryTest` separately covers bundled installation, all-page traversal, details/search/images, accessory mappings, bidirectional meal queries, all meal images, trio permutation and validation, bird dialogue counts/search/filter/source gaps, repair, previous-version fallback, failed initial install and absence of raw-table assets; the guide UI tests cover detail return, narrow filters and centered adaptive columns.

Latest schema-v10 acceptance (2026-11-24): **39 content-database Python tests and 151 core-domain tests passed**; lint and debug APK assembly pass. API 34 phone and tablet AVDs each passed 46 combined social-guide tests: 23 repository, 1 gift, 4 dining, 3 bird, 3 support, 2 planting, 4 restoration and 6 theater. Theater covers four plays, eight cue cells, twelve recorded rewards and four explicit null reward slots; current sources do not record character theater preferences. Reports and screenshots stay under ignored `data/acceptance/2026-11-24-theater/`. This is not a full-app pass: an earlier full-app run still has an unresolved phone level-journal quick-switch failure, tracked separately. The current semantic build contains **934 referenced assets**; this is not a claim about full T1 coverage, arbitrary historical-image compatibility or rename/variant migration. See [database-guidelines.md](../../.trellis/spec/backend/database-guidelines.md) for the complete runtime contract and acceptance gaps.
