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
