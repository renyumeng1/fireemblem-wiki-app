#!/usr/bin/env python3
"""Package prebuilt Gradle generated offline assets into offline-assets-pack.zip + .sha256
for GitHub Actions Strategy B builds (release assets-v1).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATED_ROOT = REPO_ROOT / "android-app" / "build" / "generated"

REQUIRED_PACK_FILES = (
    "wiki/content.db",
    "wiki/buddy/index.json",
    "world-map/maps.db",
    "world-map/terrain.webp",
    "games/three-houses/pack.db",
    "games/engage/pack.db",
)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def collect_entries(gen_root: Path) -> list[tuple[Path, str]]:
    """Map generated Gradle directories into exact zip archive paths expected by -PprebuiltAssetsDir."""
    mappings: list[tuple[Path, str]] = [
        (gen_root / "wiki-assets" / "wiki", "wiki"),
        (gen_root / "buddy-visual-assets" / "wiki" / "buddy", "wiki/buddy"),
        (gen_root / "map-assets" / "world-map", "world-map"),
        (gen_root / "multi-game-assets" / "games", "games"),
    ]
    seen: dict[str, Path] = {}
    for src_dir, arc_prefix in mappings:
        if not src_dir.is_dir():
            raise FileNotFoundError(f"Missing generated assets directory: {src_dir}")
        for file_path in sorted(p for p in src_dir.rglob("*") if p.is_file()):
            rel = file_path.relative_to(src_dir).as_posix()
            arc_name = f"{arc_prefix}/{rel}"
            seen[arc_name] = file_path
    return sorted(((p, arc) for arc, p in seen.items()), key=lambda item: item[1])


def verify_zip(zip_path: Path) -> None:
    with zipfile.ZipFile(zip_path, "r") as zf:
        names = set(zf.namelist())
        for req in REQUIRED_PACK_FILES:
            if req not in names:
                raise AssertionError(f"Missing required entry inside zip: {req}")

        th_db_bytes = zf.read("games/three-houses/pack.db")
        th_sha_expected = zf.read("games/three-houses/pack.sha256").decode("utf-8").strip()
        th_sha_actual = hashlib.sha256(th_db_bytes).hexdigest()
        if th_sha_actual != th_sha_expected:
            raise AssertionError(f"three-houses pack.db SHA mismatch: {th_sha_actual} != {th_sha_expected}")

        with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as tf:
            tf.write(th_db_bytes)
            tmp_db = Path(tf.name)
        try:
            conn = sqlite3.connect(tmp_db)
            tea_guides = conn.execute(
                "SELECT COUNT(*) FROM activity_details a JOIN content_entries e ON e.id=a.entry_id "
                "WHERE e.category_id='social' AND a.activity_type='茶会指南'"
            ).fetchone()[0]
            rhea_exists = conn.execute(
                "SELECT 1 FROM content_entries WHERE id='social:tea-guide-rhea'"
            ).fetchone()
            char_lecture_qa = sum(
                1
                for (desc,) in conn.execute(
                    "SELECT description FROM content_entries WHERE category_id='characters'"
                ).fetchall()
                if json.loads(desc).get("wiki_profile", {}).get("lecture_questions")
            )
            constance_row = conn.execute(
                "SELECT description FROM content_entries WHERE category_id='characters' AND title LIKE '康丝坦洁%'"
            ).fetchone()
            constance_fc = len(json.loads(constance_row[0]).get("wiki_profile", {}).get("tea_party", {}).get("final_comments", []))
            conn.close()
        finally:
            tmp_db.unlink(missing_ok=True)

        assert tea_guides == 40, f"Expected 40 tea_guides, got {tea_guides}"
        assert rhea_exists is not None, "Missing social:tea-guide-rhea"
        assert char_lecture_qa == 38, f"Expected 38 char_lecture_qa, got {char_lecture_qa}"
        assert constance_fc == 18, f"Expected Constance 18 final comments, got {constance_fc}"


def main() -> None:
    parser = argparse.ArgumentParser(description="Build offline-assets-pack.zip + .sha256 for CI")
    parser.add_argument(
        "--generated-root",
        type=Path,
        default=GENERATED_ROOT,
        help="Path to android-app/build/generated",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=REPO_ROOT / "android-app" / "build" / "ci-assets-pack",
        help="Output directory for offline-assets-pack.zip and .sha256",
    )
    args = parser.parse_args()

    entries = collect_entries(args.generated_root)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    zip_path = args.output_dir / "offline-assets-pack.zip"
    sha_path = args.output_dir / "offline-assets-pack.sha256"

    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for src, arc_name in entries:
            zf.write(src, arc_name)

    verify_zip(zip_path)
    digest = sha256_file(zip_path)
    sha_path.write_bytes(f"{digest}  offline-assets-pack.zip\n".encode("utf-8"))
    assert len(sha_path.read_bytes()) == 90, f"Unexpected sha256 file length: {len(sha_path.read_bytes())}"

    size_mb = zip_path.stat().st_size / (1024 * 1024)
    print(
        f"Built {zip_path}: files={len(entries)}, size={size_mb:.2f} MB, sha256={digest}"
    )


if __name__ == "__main__":
    main()
