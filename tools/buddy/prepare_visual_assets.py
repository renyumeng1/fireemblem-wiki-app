import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import urllib.request


REVISION = "016f048b5c636035ac0b56b0c4b9d89a742fba4a"
REPOSITORY = "kskdmcph58-lgtm/FE_Rigging_Buddy"
ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "data/derived/buddy-visuals/wiki/buddy"


def main():
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPOSITORY}/git/trees/{REVISION}?recursive=1",
        headers={"User-Agent": "fireemblem-companion-asset-preparer"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        tree = json.load(response)
    blobs = {entry["path"]: entry["sha"] for entry in tree["tree"] if entry["type"] == "blob"}

    def read_verified(path):
        expected = blobs[path]
        target = OUTPUT / path
        data = target.read_bytes() if target.is_file() else None
        def valid(value):
            return value is not None and hashlib.sha1(f"blob {len(value)}\0".encode() + value).hexdigest() == expected
        if not valid(data):
            with urllib.request.urlopen(f"https://raw.githubusercontent.com/{REPOSITORY}/{REVISION}/{path}", timeout=60) as response:
                data = response.read()
            if not valid(data):
                raise ValueError(f"Asset hash mismatch: {path}")
        return data

    characters = json.loads(read_verified("characters.js").decode().split("=", 1)[1].strip().removesuffix(";"))
    professions_source = read_verified("data/professions.js").decode()
    icon_block = professions_source.split("const icons = {", 1)[1].split("};", 1)[0]
    professions = dict(re.findall(r"'([^']+)'\s*:\s*'([^']+)'", icon_block))
    rules = json.loads((ROOT / "core-domain/src/main/resources/buddy/rules-v1.json").read_text(encoding="utf-8"))
    character_ids = {row["id"] for row in rules["people"]}
    selected = [row for row in characters if row["id"] in character_ids]
    assert {row["id"] for row in selected} == character_ids
    groups = {
        "characters": {row["id"]: row["image"] for row in selected},
        "charactersPart3": {row["id"]: row.get("part3", {}).get("image", row["image"]) for row in selected},
        "professions": {row["name"]: f"assets/classes/{professions[row['name']]}.webp" for row in rules["professions"]},
    }
    paths = sorted({path for group in groups.values() for path in group.values()})
    def save(path):
        data = read_verified(path)
        target = OUTPUT / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return path, hashlib.sha256(data).hexdigest()
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        hashes = dict(executor.map(save, paths))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    manifest = {"revision": REVISION, "repository": REPOSITORY,
                "images": {category: {key: f"buddy/{path}" for key, path in group.items()} for category, group in groups.items()},
                "sha256": hashes}
    (OUTPUT / "index.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {len(paths)} assets; {len(selected)} characters; {len(groups['professions'])} professions")


if __name__ == "__main__":
    main()
