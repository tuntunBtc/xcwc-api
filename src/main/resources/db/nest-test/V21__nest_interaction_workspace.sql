CREATE TABLE nest_interaction_workspace (
    character_id VARCHAR(64) NOT NULL PRIMARY KEY,
    draft_version BIGINT NOT NULL,
    published_version BIGINT NOT NULL DEFAULT 0,
    draft_json MEDIUMTEXT NOT NULL,
    published_json MEDIUMTEXT
);
