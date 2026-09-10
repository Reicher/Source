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
