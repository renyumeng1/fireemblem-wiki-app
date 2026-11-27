# Game data SQLite schema v1

Engage and Three Houses use this same SQLite schema in separate files. Each file identifies its game in `content_metadata` and records its own content version.

## Storage contract

- `content_entries` is the app-facing catalog. Category-specific details live in named tables (`character_details`, `class_details`, `skill_details`, `equipment_details`, `chapter_details`, `support_details`, `activity_details`, and `mechanic_details`). Raw spreadsheet cells and source snapshots are stored separately and are not treated as semantic app records.
- `sources`, `entry_sources`, and `content_relations` keep each claim linked to a source and a precise page, cell, or image location. Unreviewed or conflicting facts stay explicitly marked.
- `content_assets` stores paths and hashes only. Image bytes remain in the local `assets/original/` and `assets/app/` folders. Rights that have not been verified default to non-redistributable.
- `workbook_sheets` and `workbook_cells` preserve the Engage workbook for audit and lookup. `workbook_image_placements` preserves every drawing anchor, including repeated references to one deduplicated image.
- `image_ocr_results` records OCR text and review state; a pending OCR result is not a confirmed fact.
- `validation_runs` records database checks and package coverage without changing source data.

## Identifiers and versions

- `PRAGMA application_id` is `0x46454744` and `PRAGMA user_version` is `1`.
- Entry IDs are deterministic from `(game_id, category_id, stable_key)` using UUIDv5. Source row numbers are provenance only.
- `schema_version`, `content_version`, source revision, and final database SHA-256 are separate values in `content_metadata`.
- Paths are relative to the game package root and use forward slashes. Database rows never embed image BLOBs.

## Verification states

- `confirmed`: a human reviewed the fact against its cited source.
- `pending_review`: extracted or OCR content awaits human verification.
- `conflicting`: sources disagree and the conflict is retained.
- `unverified`: a source value has not been independently corroborated.
- `source_only`: retained for provenance or search, not promoted into a confirmed fact.

## Build boundary

The database builder creates a temporary SQLite file, runs `PRAGMA quick_check` and `PRAGMA foreign_key_check`, and only then publishes `database/content.db`. Source packages and the original Engage workbook remain untouched.
