package database

import (
	"database/sql"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

func TestOpenAppliesAndPersistsMigrations(t *testing.T) {
	path := filepath.Join(t.TempDir(), "source.sqlite")
	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	assertSchemaVersion(t, db.sql, len(migrations))
	assertUserColumn(t, db.sql, "recovery_key_hash")
	assertUserColumn(t, db.sql, "recovery_envelope")
	assertUserColumn(t, db.sql, "self_entity_id")
	assertTable(t, db.sql, "library_items")
	assertTable(t, db.sql, "profile_sync_state")
	assertTable(t, db.sql, "storage_revisions")
	assertTable(t, db.sql, "storage_heads")
	assertTable(t, db.sql, "storage_operations")
	assertTable(t, db.sql, "client_sync_cursors")
	assertTable(t, db.sql, "silver_refinement_sources")
	assertTable(t, db.sql, "silver_refinement_operations")
	assertTable(t, db.sql, "refinement_jobs")
	assertTable(t, db.sql, "refinement_checkpoints")
	assertMissingColumn(t, db.sql, "refinement_jobs", "pause_requested")
	if err = db.Close(); err != nil {
		t.Fatal(err)
	}

	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	assertSchemaVersion(t, db.sql, len(migrations))
}

func TestOpenMigratesVersionOneDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "source.sqlite")
	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Exec(schemaVersionTable); err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Exec(migrations[0].up); err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Exec(`UPDATE schema_version SET version = 1 WHERE singleton = 1`); err != nil {
		t.Fatal(err)
	}
	if err = raw.Close(); err != nil {
		t.Fatal(err)
	}

	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	assertSchemaVersion(t, db.sql, len(migrations))
	assertUserColumn(t, db.sql, "recovery_key_hash")
	assertUserColumn(t, db.sql, "recovery_envelope")
	assertTable(t, db.sql, "library_items")
}

func TestOpenMigratesVersionThreeWithoutChangingLegacyRows(t *testing.T) {
	path := filepath.Join(t.TempDir(), "source.sqlite")
	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Exec(schemaVersionTable); err != nil {
		t.Fatal(err)
	}
	for _, migration := range migrations[:3] {
		if _, err = raw.Exec(migration.up); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = raw.Exec(`UPDATE schema_version SET version=3;
INSERT INTO users(id,display_name,storage_namespace,quota_bytes,created_at) VALUES('11111111-1111-4111-8111-111111111111','Existing','namespace',1000,1);
INSERT INTO snapshots(user_id,app_id,snapshot_id,byte_count,sha256,created_at) VALUES('11111111-1111-4111-8111-111111111111','source-client','22222222-2222-4222-8222-222222222222',64,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',2);
INSERT INTO library_items(user_id,item_id,content_sha256,created_at,deleted_at) VALUES('11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',3,3);`); err != nil {
		t.Fatal(err)
	}
	if err = raw.Close(); err != nil {
		t.Fatal(err)
	}

	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	assertSchemaVersion(t, db.sql, len(migrations))
	var snapshots, tombstones int
	if err = db.sql.QueryRow(`SELECT COUNT(*) FROM snapshots`).Scan(&snapshots); err != nil {
		t.Fatal(err)
	}
	if err = db.sql.QueryRow(`SELECT COUNT(*) FROM library_items WHERE deleted_at IS NOT NULL`).Scan(&tombstones); err != nil {
		t.Fatal(err)
	}
	if snapshots != 1 || tombstones != 1 {
		t.Fatalf("legacy migration changed data: snapshots=%d tombstones=%d", snapshots, tombstones)
	}
	user, err := db.FindUser("11111111-1111-4111-8111-111111111111")
	if err != nil || user == nil || user.SelfEntityID == "" {
		t.Fatalf("legacy profile has no Self entity: user=%#v error=%v", user, err)
	}
	stableSelfID := user.SelfEntityID
	if err = db.Close(); err != nil {
		t.Fatal(err)
	}
	db, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	user, err = db.FindUser("11111111-1111-4111-8111-111111111111")
	if err != nil || user.SelfEntityID != stableSelfID {
		t.Fatalf("Self entity changed after reopen: user=%#v error=%v", user, err)
	}
}

func TestOpenMigratesVersionFiveSilverOperations(t *testing.T) {
	path := filepath.Join(t.TempDir(), "source.sqlite")
	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Exec(schemaVersionTable); err != nil {
		t.Fatal(err)
	}
	for _, migration := range migrations[:5] {
		if _, err = raw.Exec(migration.up); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = raw.Exec(`UPDATE schema_version SET version=5;
INSERT INTO users(id,display_name,storage_namespace,quota_bytes,created_at)
VALUES('11111111-1111-4111-8111-111111111111','Existing','namespace',1000,1);
INSERT INTO silver_refinement_operations(user_id,operation_id,request_digest,source_id)
VALUES('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','source-1');`); err != nil {
		t.Fatal(err)
	}
	if err = raw.Close(); err != nil {
		t.Fatal(err)
	}

	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	assertSchemaVersion(t, db.sql, len(migrations))
	var kind string
	var requiresChange int
	var completedAt *int64
	if err = db.sql.QueryRow(`SELECT operation_kind,requires_silver_change,completed_at
FROM silver_refinement_operations WHERE operation_id='22222222-2222-4222-8222-222222222222'`).
		Scan(&kind, &requiresChange, &completedAt); err != nil {
		t.Fatal(err)
	}
	if kind != "refinement" || requiresChange != 0 || completedAt != nil {
		t.Fatalf("migrated operation = kind:%q requires:%d completed:%v", kind, requiresChange, completedAt)
	}
}

func TestOpenRemovesPausedRefinementState(t *testing.T) {
	path := filepath.Join(t.TempDir(), "source.sqlite")
	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Exec(schemaVersionTable); err != nil {
		t.Fatal(err)
	}
	for _, migration := range migrations[:7] {
		if _, err = raw.Exec(migration.up); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = raw.Exec(`UPDATE schema_version SET version=7;
INSERT INTO users(id,display_name,storage_namespace,quota_bytes,created_at)
VALUES('11111111-1111-4111-8111-111111111111','Existing','namespace',1000,1);
INSERT INTO refinement_jobs(
    user_id,job_id,request_digest,source_id,source_name,source_type,content_sha256,plaintext,
    processor_id,processor_version,model_id,output_layer,state,pause_requested,total_batches,accepted_at,updated_at
) VALUES(
    '11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222',
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','source-1','source.txt','file',
    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','text',
    'processor','1','model','silver','paused',1,1,1,1
);`); err != nil {
		t.Fatal(err)
	}
	if err = raw.Close(); err != nil {
		t.Fatal(err)
	}

	db, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var state string
	if err = db.sql.QueryRow(`SELECT state FROM refinement_jobs WHERE source_id='source-1'`).Scan(&state); err != nil {
		t.Fatal(err)
	}
	if state != "queued" {
		t.Fatalf("migrated refinement state = %q, want queued", state)
	}
	assertMissingColumn(t, db.sql, "refinement_jobs", "pause_requested")
}

func TestOpenRejectsNewerSchemaVersion(t *testing.T) {
	path := filepath.Join(t.TempDir(), "source.sqlite")
	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Exec(schemaVersionTable); err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Exec(`UPDATE schema_version SET version = 99 WHERE singleton = 1`); err != nil {
		t.Fatal(err)
	}
	if err = raw.Close(); err != nil {
		t.Fatal(err)
	}

	if db, openErr := Open(path); openErr == nil {
		db.Close()
		t.Fatal("Open accepted a newer schema version")
	}
}

func assertSchemaVersion(t *testing.T, db *sql.DB, want int) {
	t.Helper()
	var got int
	if err := db.QueryRow(`SELECT version FROM schema_version WHERE singleton = 1`).Scan(&got); err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Fatalf("schema version = %d, want %d", got, want)
	}
}

func assertUserColumn(t *testing.T, db *sql.DB, want string) {
	t.Helper()
	rows, err := db.Query(`PRAGMA table_info(users)`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	for rows.Next() {
		var cid, notNull, primaryKey int
		var name, columnType string
		var defaultValue any
		if err = rows.Scan(&cid, &name, &columnType, &notNull, &defaultValue, &primaryKey); err != nil {
			t.Fatal(err)
		}
		if name == want {
			return
		}
	}
	if err = rows.Err(); err != nil {
		t.Fatal(err)
	}
	t.Fatalf("users column %q is missing", want)
}

func assertTable(t *testing.T, db *sql.DB, want string) {
	t.Helper()
	var name string
	if err := db.QueryRow(`SELECT name FROM sqlite_master WHERE type='table' AND name=?`, want).Scan(&name); err != nil {
		t.Fatalf("table %q is missing: %v", want, err)
	}
}

func assertMissingColumn(t *testing.T, db *sql.DB, table, unwanted string) {
	t.Helper()
	rows, err := db.Query(`SELECT name FROM pragma_table_info(?)`, table)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	for rows.Next() {
		var name string
		if err = rows.Scan(&name); err != nil {
			t.Fatal(err)
		}
		if name == unwanted {
			t.Fatalf("%s column %q still exists", table, unwanted)
		}
	}
	if err = rows.Err(); err != nil {
		t.Fatal(err)
	}
}
