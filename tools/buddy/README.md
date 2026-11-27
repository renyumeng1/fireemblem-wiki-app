# Buddy visual assets

Run `py -3 tools/buddy/prepare_visual_assets.py` from the repository root before building Android.
The preparer downloads character and profession images from the same pinned FE_Rigging_Buddy revision as the engine, verifies Git blob hashes against the revision tree, and writes the manifest and WebP assets below ignored `data/derived/buddy-visuals/`.
Gradle packages this directory offline; app startup never downloads artwork. The manifest retains repository/revision and SHA-256 provenance. Original assets are used under the user's stated permission; do not substitute generated portraits.

Characters are keyed by Buddy ID, including distinct same-name protagonists and third-part portraits. Professions use the pinned upstream icon map. Mount imagery currently uses unique exact-name lookups from the bundled Wiki category. Asset selection never changes growth rules or stored journal identity.
