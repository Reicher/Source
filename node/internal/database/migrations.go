package database

import (
	"database/sql"
	"fmt"
)

type migration struct {
	version int
	up      string
}

var migrations = []migration{
	{
		version: 1,
		up: `
CREATE TABLE node_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),display_name TEXT NOT NULL,node_id TEXT NOT NULL UNIQUE,public_key TEXT NOT NULL,private_key TEXT NOT NULL,admin_password_hash TEXT NOT NULL,created_at INTEGER NOT NULL) STRICT;
CREATE TABLE users(id TEXT PRIMARY KEY,display_name TEXT NOT NULL,storage_namespace TEXT NOT NULL UNIQUE,quota_bytes INTEGER NOT NULL CHECK(quota_bytes>0),created_at INTEGER NOT NULL,disabled_at INTEGER) STRICT;
CREATE TABLE clients(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,display_name TEXT NOT NULL,public_key TEXT NOT NULL UNIQUE,credential_hash TEXT NOT NULL UNIQUE,protocol_version INTEGER NOT NULL,paired_at INTEGER NOT NULL,last_seen_at INTEGER,revoked_at INTEGER) STRICT;
CREATE INDEX clients_user_idx ON clients(user_id);
CREATE INDEX clients_credential_idx ON clients(credential_hash);
CREATE TABLE snapshots(user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,app_id TEXT NOT NULL,snapshot_id TEXT NOT NULL,byte_count INTEGER NOT NULL,sha256 TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(user_id,app_id,snapshot_id)) STRICT;
CREATE INDEX snapshots_latest_idx ON snapshots(user_id,app_id,created_at DESC);`,
	},
	{
		version: 2,
		up: `
ALTER TABLE users ADD COLUMN recovery_key_hash TEXT;
ALTER TABLE users ADD COLUMN recovery_envelope TEXT;`,
	},
	{
		version: 3,
		up: `
CREATE TABLE library_items(
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id TEXT NOT NULL,
    content_sha256 TEXT NOT NULL,
    encrypted_sha256 TEXT,
    byte_count INTEGER,
    created_at INTEGER NOT NULL,
    deleted_at INTEGER,
    PRIMARY KEY(user_id,item_id),
    CHECK(length(content_sha256)=64),
    CHECK((deleted_at IS NULL AND encrypted_sha256 IS NOT NULL AND byte_count>0) OR deleted_at IS NOT NULL)
) STRICT;
CREATE UNIQUE INDEX library_active_content_idx ON library_items(user_id,content_sha256) WHERE deleted_at IS NULL;
CREATE INDEX library_items_updated_idx ON library_items(user_id,COALESCE(deleted_at,created_at) DESC);`,
	},
	{
		version: 4,
		up: `
CREATE TABLE profile_sync_state(
    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    authority_node_id TEXT NOT NULL,
    authority_epoch TEXT NOT NULL,
    next_commit_sequence INTEGER NOT NULL CHECK(next_commit_sequence>0),
    retained_log_floor INTEGER NOT NULL DEFAULT 0 CHECK(retained_log_floor>=0),
    UNIQUE(user_id,authority_epoch)
) STRICT;
CREATE TABLE storage_revisions(
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    collection TEXT NOT NULL,
    object_id TEXT NOT NULL,
    revision_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK(kind IN ('content','tombstone')),
    parents_json TEXT NOT NULL,
    payload_format TEXT,
    payload_format_version INTEGER,
    byte_count INTEGER,
    plaintext_sha256 TEXT,
    created_at INTEGER,
    origin_id TEXT NOT NULL,
    origin_epoch TEXT NOT NULL,
    origin_sequence INTEGER NOT NULL CHECK(origin_sequence>0),
    authority_epoch TEXT NOT NULL,
    commit_sequence INTEGER NOT NULL CHECK(commit_sequence>0),
    operation_id TEXT NOT NULL,
    revision_digest TEXT NOT NULL,
    PRIMARY KEY(user_id,revision_id),
    UNIQUE(user_id,authority_epoch,commit_sequence)
) STRICT;
CREATE INDEX storage_revisions_object_idx ON storage_revisions(user_id,collection,object_id,commit_sequence);
CREATE TABLE storage_heads(
    user_id TEXT NOT NULL,
    collection TEXT NOT NULL,
    object_id TEXT NOT NULL,
    revision_id TEXT NOT NULL,
    PRIMARY KEY(user_id,collection,object_id,revision_id),
    FOREIGN KEY(user_id,revision_id) REFERENCES storage_revisions(user_id,revision_id) ON DELETE CASCADE
) STRICT;
CREATE TABLE storage_operations(
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_id TEXT NOT NULL,
    mutation_digest TEXT NOT NULL,
    origin_id TEXT NOT NULL,
    origin_epoch TEXT NOT NULL,
    origin_sequence INTEGER NOT NULL CHECK(origin_sequence>0),
    revision_id TEXT NOT NULL,
    authority_node_id TEXT NOT NULL,
    authority_epoch TEXT NOT NULL,
    commit_sequence INTEGER NOT NULL CHECK(commit_sequence>0),
    PRIMARY KEY(user_id,operation_id),
    UNIQUE(user_id,origin_id,origin_epoch,origin_sequence),
    FOREIGN KEY(user_id,revision_id) REFERENCES storage_revisions(user_id,revision_id) ON DELETE CASCADE
) STRICT;
CREATE TABLE client_sync_cursors(
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    collection TEXT NOT NULL,
    object_id TEXT NOT NULL,
    authority_node_id TEXT NOT NULL,
    authority_epoch TEXT NOT NULL,
    commit_sequence INTEGER NOT NULL CHECK(commit_sequence>=0),
    acknowledged_at INTEGER NOT NULL,
    PRIMARY KEY(user_id,client_id,collection,object_id)
) STRICT;`,
	},
	{
		version: 5,
		up: `
CREATE TABLE silver_refinement_sources(
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_id TEXT NOT NULL,
    source_name TEXT NOT NULL,
    source_type TEXT NOT NULL,
    content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64),
    plaintext TEXT NOT NULL,
    accepted_at INTEGER NOT NULL,
    removed_at INTEGER,
    refined_processor_version TEXT,
    refined_model_id TEXT,
    refined_at INTEGER,
    PRIMARY KEY(user_id,source_id)
) STRICT;
CREATE TABLE silver_refinement_operations(
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_id TEXT NOT NULL,
    request_digest TEXT NOT NULL CHECK(length(request_digest)=64),
    source_id TEXT NOT NULL,
    PRIMARY KEY(user_id,operation_id)
) STRICT;`,
	},
	{
		version: 6,
		up: `
ALTER TABLE silver_refinement_operations ADD COLUMN operation_kind TEXT NOT NULL DEFAULT 'refinement' CHECK(operation_kind IN ('refinement','removal'));
ALTER TABLE silver_refinement_operations ADD COLUMN requires_silver_change INTEGER NOT NULL DEFAULT 0 CHECK(requires_silver_change IN (0,1));
ALTER TABLE silver_refinement_operations ADD COLUMN completed_at INTEGER;`,
	},
}

const schemaVersionTable = `
CREATE TABLE IF NOT EXISTS schema_version(
    singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
    version INTEGER NOT NULL CHECK(version >= 0)
) STRICT;
INSERT INTO schema_version(singleton, version) VALUES(1, 0)
ON CONFLICT(singleton) DO NOTHING;`

func applyMigrations(db *sql.DB) error {
	if _, err := db.Exec(schemaVersionTable); err != nil {
		return fmt.Errorf("initialize schema version: %w", err)
	}

	var current int
	if err := db.QueryRow(`SELECT version FROM schema_version WHERE singleton = 1`).Scan(&current); err != nil {
		return fmt.Errorf("read schema version: %w", err)
	}
	if current > len(migrations) {
		return fmt.Errorf("database schema version %d is newer than supported version %d", current, len(migrations))
	}

	for _, migration := range migrations {
		if migration.version <= current {
			continue
		}
		if migration.version != current+1 {
			return fmt.Errorf("database migration sequence jumps from version %d to %d", current, migration.version)
		}
		if err := applyMigration(db, migration, current); err != nil {
			return err
		}
		current = migration.version
	}
	return nil
}

func applyMigration(db *sql.DB, migration migration, previousVersion int) error {
	tx, err := db.Begin()
	if err != nil {
		return fmt.Errorf("begin database migration %d: %w", migration.version, err)
	}
	defer tx.Rollback()

	if _, err = tx.Exec(migration.up); err != nil {
		return fmt.Errorf("apply database migration %d: %w", migration.version, err)
	}
	result, err := tx.Exec(
		`UPDATE schema_version SET version = ? WHERE singleton = 1 AND version = ?`,
		migration.version,
		previousVersion,
	)
	if err != nil {
		return fmt.Errorf("record database migration %d: %w", migration.version, err)
	}
	updated, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("verify database migration %d: %w", migration.version, err)
	}
	if updated != 1 {
		return fmt.Errorf("record database migration %d: schema version changed concurrently", migration.version)
	}
	if err = tx.Commit(); err != nil {
		return fmt.Errorf("commit database migration %d: %w", migration.version, err)
	}
	return nil
}
