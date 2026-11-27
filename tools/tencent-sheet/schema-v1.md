# Package Schema v1

All generated JSON is UTF-8, pretty-printed with sorted object keys, and terminated by one newline. Array order follows the source catalog or source cell list. SHA-256 values are lowercase hexadecimal hashes of the exact source or generated file bytes.

## `manifest.json`

- `schema_version`: `1`.
- `generator`: stable owner identifier, `fireemblem/tools/tencent-sheet`.
- `source`: source URI/title, workbook revision, source retrieval time/state, and checksums for `catalog.json`, `source.json`, `sheets.json`, and `assets-manifest.json`.
- `coverage`: sheet, typed-cell, cell-type, rich-text, formula, image-reference, image-asset, and merge counts. Missing image asset metadata is listed explicitly.
- `sheets`: one entry for each available catalog sheet, including revision, source path/hash, generated path/hash, cell type counts, image reference count, and merge count.
- `unavailable_sheets`: unavailable catalog entries with hidden status, dimensions when known, and the recorded reason. These entries have no generated table file.

## `tables/<sheet-id>.json`

- `schema_version`: `1`.
- `provenance`: source table path and SHA-256.
- `metadata`: original table metadata other than `cells`, including source revision, dimensions, coordinate convention, cell counts, formula cache semantics, and merge objects.
- `cells`: source-ordered normalized cells. Every cell retains `row`, `column`, `address`, and `type` exactly as recorded by the snapshot.

Cell payload rules:

- Non-formula cells retain `value`.
- Formula cells contain `formula.cached_value` (the source's cached result) and `formula.model` (the original calculation model); formula cells do not expose a recalculated result.
- Rich-text payloads are retained as `rich_text_runs`, including the decoded run formatting data.
- Image metadata is retained as `image_reference`, with `source_asset_sha256` added from `assets-manifest.json`. No image data is embedded or copied.
- Any source cell keys outside the normalized fields are copied without interpretation to `source_fields`.

The converter deliberately does not infer headers, fill merged cells, coerce values, or claim that available tables are licensed for redistribution. The unavailable hidden sheet is represented as a coverage gap, not as an empty table.
