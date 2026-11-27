from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PACKAGE = REPO_ROOT / "data" / "games" / "engage"


def _column_name(number: int) -> str:
    label = ""
    while number:
        number, remainder = divmod(number - 1, 26)
        label = chr(65 + remainder) + label
    return label


def preview(package: Path, sheet_numbers: list[int], rows: int, max_columns: int) -> None:
    manifest = json.loads((package / "manifest.json").read_text(encoding="utf-8"))
    for number in sheet_numbers:
        sheet = next(item for item in manifest["sheets"] if item["sheet_number"] == number)
        table = json.loads((package / sheet["json_file"]).read_text(encoding="utf-8"))
        grid: dict[int, dict[int, Any]] = {}
        for cell in table["cells"]:
            row = cell.get("row")
            column = cell.get("column")
            if isinstance(row, int) and isinstance(column, int):
                value = cell.get("value")
                if value not in (None, ""):
                    grid.setdefault(row, {})[column] = value
        for merge in table.get("merges", []):
            start, _, end = merge.partition(":")
            def parse(address: str) -> tuple[int, int]:
                letters = "".join(ch for ch in address if ch.isalpha())
                digits = "".join(ch for ch in address if ch.isdigit())
                column = 0
                for character in letters:
                    column = column * 26 + ord(character.upper()) - 64
                return int(digits), column
            top_row, left_column = parse(start)
            bottom_row, right_column = parse(end or start)
            anchor = grid.get(top_row, {}).get(left_column)
            if anchor in (None, ""):
                continue
            for row in range(top_row, bottom_row + 1):
                for column in range(left_column, right_column + 1):
                    grid.setdefault(row, {}).setdefault(column, anchor)
        print(f"\n[{number:02d}] {sheet['name']} | {sheet.get('observed_range')} | {sheet['state']}")
        displayed_rows = 0
        for row in sorted(grid):
            if row > rows:
                break
            values = []
            for column, value in sorted(grid[row].items()):
                if column > max_columns:
                    continue
                text = str(value).replace("\n", " / ")
                if len(text) > 90:
                    text = text[:87] + "..."
                values.append(f"{_column_name(column)}={text}")
            if values:
                print(f"{row}: " + " | ".join(values))
                displayed_rows += 1
        if displayed_rows == 0:
            print("No non-empty cell values in the selected range.")


def main() -> int:
    parser = argparse.ArgumentParser(description="只读预览 Engage 指定工作表的非空单元格")
    parser.add_argument("--package", type=Path, default=DEFAULT_PACKAGE)
    parser.add_argument("--sheet", type=int, nargs="+", required=True)
    parser.add_argument("--rows", type=int, default=10)
    parser.add_argument("--max-columns", type=int, default=48)
    arguments = parser.parse_args()
    preview(arguments.package, arguments.sheet, arguments.rows, arguments.max_columns)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
