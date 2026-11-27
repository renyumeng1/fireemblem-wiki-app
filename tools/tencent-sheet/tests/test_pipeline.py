import hashlib
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


TOOL_DIR = Path(__file__).resolve().parents[1]
FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "snapshot"
sys.path.insert(0, str(TOOL_DIR))

from pipeline import PipelineError, convert_snapshot, validate_package


class SnapshotPipelineTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.source = self.root / "source"
        shutil.copytree(FIXTURE_DIR, self.source)
        self.output = self.root / "derived" / "tencent-sheet"

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_conversion_is_byte_repeatable_and_validates(self) -> None:
        first = convert_snapshot(self.source, self.output)
        first_files = {
            path.relative_to(self.output).as_posix(): path.read_bytes()
            for path in self.output.rglob("*")
            if path.is_file()
        }
        second = convert_snapshot(self.source, self.output)
        second_files = {
            path.relative_to(self.output).as_posix(): path.read_bytes()
            for path in self.output.rglob("*")
            if path.is_file()
        }
        self.assertEqual(first_files, second_files)
        self.assertEqual(first["manifest_sha256"], second["manifest_sha256"])
        result = validate_package(self.source, self.output)
        self.assertTrue(result["valid"])
        self.assertEqual(result["available_sheets"], 1)
        self.assertEqual(result["unavailable_sheets"], 1)

    def test_preserves_provenance_and_rich_cell_semantics(self) -> None:
        convert_snapshot(self.source, self.output)
        manifest = json.loads((self.output / "manifest.json").read_text(encoding="utf-8"))
        table_path = self.output / "tables" / "demo1.json"
        table = json.loads(table_path.read_text(encoding="utf-8"))

        self.assertEqual(manifest["source"]["revision"], 9)
        self.assertEqual(manifest["source"]["files"][0]["sha256"], hashlib.sha256((self.source / "assets-manifest.json").read_bytes()).hexdigest())
        self.assertEqual(manifest["sheets"][0]["source_sha256"], hashlib.sha256((self.source / "tables" / "demo1.json").read_bytes()).hexdigest())
        self.assertEqual(table["metadata"]["merges"], json.loads((self.source / "tables" / "demo1.json").read_text(encoding="utf-8"))["merges"])

        cells = {cell["address"]: cell for cell in table["cells"]}
        self.assertEqual((cells["A1"]["row"], cells["A1"]["column"], cells["A1"]["address"]), (1, 1, "A1"))
        self.assertEqual(cells["B1"]["formula"]["cached_value"], 12)
        self.assertEqual(cells["B1"]["formula"]["model"], {"calculation": {"formulaModel": {"expression": "=SUM(A1)"}}})
        self.assertNotIn("value", cells["B1"])
        self.assertEqual(cells["A2"]["rich_text_runs"]["r"][1]["t"], " value")
        self.assertEqual(cells["A1"]["source_fields"]["$unknown_fields"], {"field": [1, 2]})
        self.assertEqual(cells["D3"]["image_reference"]["local_file"], "assets/picture.png")
        self.assertEqual(cells["D3"]["image_reference"]["source_asset_sha256"], "a" * 64)
        self.assertEqual(cells["D3"]["source_fields"]["extension"], {"type": 5, "value": "image-reference"})
        self.assertNotIn("assets", {path.relative_to(self.output).parts[0] for path in self.output.rglob("*") if path.is_file()})

    def test_unavailable_hidden_sheet_is_an_explicit_gap(self) -> None:
        convert_snapshot(self.source, self.output)
        manifest = json.loads((self.output / "manifest.json").read_text(encoding="utf-8"))
        hidden = next(sheet for sheet in manifest["unavailable_sheets"] if sheet["sheet_id"] == "dytwkr")

        self.assertEqual(hidden["status"], "unavailable")
        self.assertTrue(hidden["hidden"])
        self.assertEqual(hidden["reason"], "The ordinary viewer did not return this hidden sheet.")
        self.assertEqual(hidden["dimensions"], {"rows": 40, "columns": 5})
        self.assertFalse((self.output / "tables" / "dytwkr.json").exists())

    def test_missing_available_table_fails_without_publishing(self) -> None:
        (self.source / "tables" / "demo1.json").unlink()

        with self.assertRaises(PipelineError):
            convert_snapshot(self.source, self.output)

        self.assertFalse(self.output.exists())

    def test_table_path_cannot_escape_snapshot_directory(self) -> None:
        catalog_path = self.source / "catalog.json"
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        catalog[0]["table_file"] = "../outside.json"
        catalog_path.write_text(json.dumps(catalog), encoding="utf-8")

        with self.assertRaisesRegex(PipelineError, "escapes snapshot directory"):
            convert_snapshot(self.source, self.output)

        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
