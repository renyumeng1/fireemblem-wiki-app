PRAGMA foreign_keys = ON;
PRAGMA application_id = 0x46454744;
PRAGMA user_version = 1;

CREATE TABLE content_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE sources (
    source_id TEXT PRIMARY KEY,
    source_type TEXT NOT NULL,
    title TEXT NOT NULL,
    url TEXT,
    language TEXT,
    game_version TEXT,
    revision TEXT,
    retrieved_at TEXT NOT NULL,
    access_status TEXT NOT NULL CHECK (access_status IN ('readable', 'restricted', 'http_403', 'unavailable', 'local_file')),
    rights_status TEXT NOT NULL CHECK (rights_status IN ('unknown', 'personal_use_only', 'licensed', 'public_domain', 'redistribution_prohibited')),
    sha256 TEXT,
    notes TEXT NOT NULL DEFAULT ''
);

CREATE TABLE categories (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    position INTEGER NOT NULL UNIQUE,
    applicable INTEGER NOT NULL DEFAULT 1 CHECK (applicable IN (0, 1)),
    coverage_status TEXT NOT NULL CHECK (coverage_status IN ('complete', 'partial', 'pending', 'not_applicable', 'blocked')),
    source_count INTEGER NOT NULL DEFAULT 0 CHECK (source_count >= 0),
    record_count INTEGER NOT NULL DEFAULT 0 CHECK (record_count >= 0)
);

CREATE TABLE content_entries (
    id TEXT PRIMARY KEY,
    category_id TEXT NOT NULL REFERENCES categories(id),
    stable_key TEXT NOT NULL,
    title TEXT NOT NULL,
    title_ja TEXT,
    title_en TEXT,
    subtitle TEXT,
    description TEXT,
    search_text TEXT NOT NULL DEFAULT '',
    verification_status TEXT NOT NULL CHECK (verification_status IN ('confirmed', 'pending_review', 'conflicting', 'unverified', 'source_only')),
    UNIQUE (category_id, stable_key)
);

CREATE INDEX content_entries_category_order ON content_entries(category_id, title COLLATE NOCASE, id);
CREATE INDEX content_entries_search ON content_entries(search_text);

CREATE TABLE entry_sources (
    entry_id TEXT NOT NULL REFERENCES content_entries(id) ON DELETE CASCADE,
    source_id TEXT NOT NULL REFERENCES sources(source_id),
    locator TEXT NOT NULL DEFAULT '',
    source_sha256 TEXT,
    source_language TEXT,
    translation_status TEXT NOT NULL DEFAULT 'not_translated' CHECK (translation_status IN ('not_translated', 'machine_draft', 'human_reviewed', 'not_applicable')),
    assertion_status TEXT NOT NULL CHECK (assertion_status IN ('confirmed', 'pending_review', 'conflicting', 'unverified')),
    PRIMARY KEY (entry_id, source_id, locator)
);

CREATE TABLE content_assets (
    asset_id TEXT PRIMARY KEY,
    original_file TEXT NOT NULL,
    app_file TEXT,
    source_sha256 TEXT NOT NULL,
    app_sha256 TEXT,
    mime_type TEXT,
    width INTEGER CHECK (width IS NULL OR width > 0),
    height INTEGER CHECK (height IS NULL OR height > 0),
    source_bytes INTEGER NOT NULL CHECK (source_bytes >= 0),
    app_bytes INTEGER CHECK (app_bytes IS NULL OR app_bytes >= 0),
    classification TEXT NOT NULL DEFAULT 'unclassified_pending_visual_review',
    classification_status TEXT NOT NULL DEFAULT 'pending_review' CHECK (classification_status IN ('reviewed', 'pending_review', 'not_applicable')),
    rights_status TEXT NOT NULL DEFAULT 'unknown' CHECK (rights_status IN ('unknown', 'personal_use_only', 'licensed', 'public_domain', 'redistribution_prohibited')),
    redistribution_allowed INTEGER NOT NULL DEFAULT 0 CHECK (redistribution_allowed IN (0, 1)),
    processing_json TEXT NOT NULL DEFAULT '{}'
);

CREATE TABLE entry_assets (
    entry_id TEXT NOT NULL REFERENCES content_entries(id) ON DELETE CASCADE,
    asset_id TEXT NOT NULL REFERENCES content_assets(asset_id),
    role TEXT NOT NULL DEFAULT 'illustration',
    locator TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (entry_id, asset_id, role, locator)
);

CREATE TABLE content_relations (
    source_entry_id TEXT NOT NULL REFERENCES content_entries(id) ON DELETE CASCADE,
    target_entry_id TEXT NOT NULL REFERENCES content_entries(id) ON DELETE CASCADE,
    relation_type TEXT NOT NULL,
    evidence TEXT NOT NULL,
    verification_status TEXT NOT NULL CHECK (verification_status IN ('confirmed', 'pending_review', 'conflicting', 'unverified')),
    source_id TEXT REFERENCES sources(source_id),
    locator TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (source_entry_id, target_entry_id, relation_type, locator)
);

CREATE TABLE character_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    gender TEXT,
    age TEXT,
    affiliation TEXT,
    join_condition TEXT,
    route_availability TEXT,
    base_stats_json TEXT,
    growths_json TEXT,
    caps_json TEXT,
    support_availability_json TEXT
);

CREATE TABLE class_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    tier TEXT,
    movement INTEGER,
    movement_type TEXT,
    weapon_ranks_json TEXT,
    base_stats_json TEXT,
    growths_json TEXT,
    caps_json TEXT,
    promotion_requirement TEXT,
    class_skill TEXT
);

CREATE TABLE skill_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    skill_type TEXT,
    weapon_type TEXT,
    requirement TEXT,
    cost TEXT,
    activation_condition TEXT,
    effect_json TEXT
);

CREATE TABLE equipment_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    equipment_type TEXT,
    subtype TEXT,
    rank TEXT,
    might REAL,
    hit REAL,
    critical REAL,
    weight REAL,
    durability REAL,
    price REAL,
    effect TEXT,
    acquisition TEXT,
    extra_stats_json TEXT
);

CREATE TABLE emblem_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    bond_level_effects_json TEXT,
    sync_skills_json TEXT,
    engage_skills_json TEXT,
    inherited_skills_json TEXT,
    voice_actor TEXT,
    profile TEXT
);

CREATE TABLE bond_ring_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    emblem_name TEXT NOT NULL,
    ring_rank TEXT NOT NULL,
    stat_bonuses_json TEXT NOT NULL DEFAULT '{}',
    skill_name TEXT,
    skill_effect TEXT
);

CREATE TABLE chapter_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    route TEXT,
    chapter_number TEXT,
    location TEXT,
    objective TEXT,
    defeat_condition TEXT,
    recommended_level TEXT,
    deployment_json TEXT,
    enemies_json TEXT,
    rewards_json TEXT,
    map_asset_id TEXT REFERENCES content_assets(asset_id)
);

CREATE TABLE support_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    character_a_id TEXT REFERENCES content_entries(id),
    character_b_id TEXT REFERENCES content_entries(id),
    support_rank TEXT,
    availability_condition TEXT,
    support_points TEXT,
    dialogue_summary TEXT
);

CREATE TABLE activity_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    activity_type TEXT,
    availability_condition TEXT,
    options_json TEXT,
    effects_json TEXT
);

CREATE TABLE mechanic_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    mechanic_type TEXT,
    game_version TEXT,
    rule_description TEXT,
    parameters_json TEXT,
    formula_text TEXT
);

CREATE TABLE achievement_details (
    entry_id TEXT PRIMARY KEY REFERENCES content_entries(id) ON DELETE CASCADE,
    achievement_type TEXT,
    requirement TEXT,
    reward TEXT,
    chapter TEXT,
    notes TEXT
);

CREATE TABLE workbook_sheets (
    sheet_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL REFERENCES sources(source_id),
    sheet_number INTEGER NOT NULL UNIQUE,
    name TEXT NOT NULL,
    visibility TEXT NOT NULL CHECK (visibility IN ('visible', 'hidden', 'veryHidden')),
    dimension TEXT,
    explicit_cell_count INTEGER NOT NULL CHECK (explicit_cell_count >= 0),
    formula_count INTEGER NOT NULL CHECK (formula_count >= 0),
    merge_count INTEGER NOT NULL CHECK (merge_count >= 0),
    json_file TEXT NOT NULL,
    json_sha256 TEXT NOT NULL,
    csv_file TEXT NOT NULL,
    csv_sha256 TEXT NOT NULL
);

CREATE TABLE workbook_cells (
    sheet_id TEXT NOT NULL REFERENCES workbook_sheets(sheet_id) ON DELETE CASCADE,
    address TEXT NOT NULL,
    row_number INTEGER NOT NULL CHECK (row_number > 0),
    column_number INTEGER NOT NULL CHECK (column_number > 0),
    cell_type TEXT NOT NULL,
    style_index INTEGER,
    value_json TEXT,
    raw_value TEXT,
    formula_json TEXT,
    PRIMARY KEY (sheet_id, address)
);

CREATE INDEX workbook_cells_by_position ON workbook_cells(sheet_id, row_number, column_number);

CREATE TABLE workbook_image_placements (
    placement_id TEXT PRIMARY KEY,
    sheet_id TEXT NOT NULL REFERENCES workbook_sheets(sheet_id) ON DELETE CASCADE,
    asset_id TEXT REFERENCES content_assets(asset_id),
    drawing_part TEXT NOT NULL,
    anchor_type TEXT NOT NULL,
    from_cell TEXT,
    from_anchor_json TEXT,
    to_anchor_json TEXT,
    shape_json TEXT NOT NULL DEFAULT '{}',
    relationship_status TEXT NOT NULL CHECK (relationship_status IN ('resolved', 'missing_or_external'))
);

CREATE TABLE image_ocr_results (
    ocr_id INTEGER PRIMARY KEY,
    asset_id TEXT NOT NULL REFERENCES content_assets(asset_id) ON DELETE CASCADE,
    source_id TEXT REFERENCES sources(source_id),
    locator TEXT NOT NULL DEFAULT '',
    engine TEXT,
    language TEXT,
    recognized_text TEXT NOT NULL DEFAULT '',
    boxes_json TEXT NOT NULL DEFAULT '[]',
    review_status TEXT NOT NULL CHECK (review_status IN ('pending_review', 'reviewed', 'unreadable', 'not_data_image')),
    reviewer_notes TEXT NOT NULL DEFAULT ''
);

CREATE TABLE validation_runs (
    run_id TEXT PRIMARY KEY,
    generated_at TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('passed', 'partial', 'failed')),
    summary_json TEXT NOT NULL
);

CREATE VIEW entry_catalog AS
SELECT e.id, e.category_id, c.title AS category_title, e.title, e.title_ja, e.title_en,
       e.subtitle, e.description, e.search_text, e.verification_status
FROM content_entries e
JOIN categories c ON c.id = e.category_id;
