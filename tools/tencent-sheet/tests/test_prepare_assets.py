import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image


TOOL_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL_DIR))

from prepare_assets import WIKI_SHEET_IDS, prepare_assets


class WikiAssetPreparationTests(unittest.TestCase):
    def test_prepares_consumed_asset_cells_and_preserves_source_images(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "source"
            package = root / "package"
            (source / "assets").mkdir(parents=True)
            (package / "tables").mkdir(parents=True)
            source_images = [source / "assets" / name for name in ("portrait.png", "meal.png", "unused.png")]
            for image_path in source_images:
                Image.new("RGBA", (24, 12), (190, 70, 40, 180)).save(image_path)
            source_bytes = {image_path.name: image_path.read_bytes() for image_path in source_images}
            checksums = {name: hashlib.sha256(content).hexdigest() for name, content in source_bytes.items()}

            for sheet_id in WIKI_SHEET_IDS:
                cells = []
                if sheet_id == WIKI_SHEET_IDS[0]:
                    cells.append({"address": "C4", "column": 3, "image_reference": {
                        "local_file": "assets/portrait.png", "source_asset_sha256": checksums["portrait.png"]
                    }})
                if sheet_id == "lbz2o6":
                    cells.extend([
                        {"address": "H3", "column": 8, "image_reference": {
                            "local_file": "assets/meal.png", "source_asset_sha256": checksums["meal.png"]
                        }},
                        {"address": "A3", "column": 1, "image_reference": {
                            "local_file": "assets/unused.png", "source_asset_sha256": checksums["unused.png"]
                        }},
                    ])
                (package / "tables" / f"{sheet_id}.json").write_text(
                    json.dumps({"cells": cells}), encoding="utf-8"
                )
            (source / "assets-manifest.json").write_text(json.dumps([
                {"file": f"assets/{name}", "sha256": checksum, "status": "downloaded"}
                for name, checksum in checksums.items()
            ]), encoding="utf-8")

            summary = prepare_assets(source, package)

            optimized = package / "assets" / "portrait.webp"
            with Image.open(optimized) as image:
                self.assertEqual("WEBP", image.format)
                self.assertEqual((24, 12), image.size)
            self.assertEqual(2, summary["assets"])
            self.assertFalse((package / "assets" / "unused.webp").exists())
            self.assertEqual(source_bytes, {image_path.name: image_path.read_bytes() for image_path in source_images})
            manifest = json.loads((package / "image-assets-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual({"portrait.webp", "meal.webp"}, {Path(asset["file"]).name for asset in manifest["assets"]})


if __name__ == "__main__":
    unittest.main()
