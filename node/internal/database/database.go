package database

import (
	"database/sql"
	"errors"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite"
	"source.local/node/internal/security"
)

type DB struct{ sql *sql.DB }

var ErrAlreadyInitialized = errors.New("Node is already initialized")

type NodeState struct {
	DisplayName, NodeID, PublicKey, PrivateKey, AdminPasswordHash string
	CreatedAt                                                     int64
}
type User struct {
	ID, DisplayName, StorageNamespace string
	QuotaBytes, CreatedAt             int64
	DisabledAt                        *int64
	RecoveryKeyHash, RecoveryEnvelope *string
	RecoveryConfigured                bool
	StorageUsedBytes                  int64
	ClientCount                       int
}
type Client struct {
	ID, UserID, ClientDisplayName, PublicKey string
	PairedAt                                 int64
	LastSeenAt, RevokedAt, DisabledAt        *int64
	UserDisplayName                          string
}
type NewClient struct {
	ID, DisplayName, PublicKey, CredentialHash string
	ProtocolVersion                            int
}
type Snapshot struct {
	ID        string `json:"id"`
	Bytes     int64  `json:"bytes"`
	SHA256    string `json:"sha256"`
	CreatedAt int64  `json:"createdAt"`
}

func Open(path string) (*DB, error) {
	if path != ":memory:" {
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			return nil, err
		}
	}
	sqldb, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	sqldb.SetMaxOpenConns(1)
	db := &DB{sql: sqldb}
	if _, err = sqldb.Exec(`PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL; PRAGMA synchronous = FULL; PRAGMA busy_timeout = 5000;`); err != nil {
		sqldb.Close()
		return nil, err
	}
	if err = applyMigrations(sqldb); err != nil {
		sqldb.Close()
		return nil, err
	}
	if path != ":memory:" {
		_ = os.Chmod(path, 0600)
	}
	return db, nil
}

func (d *DB) Close() error { return d.sql.Close() }
func (d *DB) IsInitialized() bool {
	var one int
	return d.sql.QueryRow(`SELECT 1 FROM node_state WHERE singleton = 1`).Scan(&one) == nil
}

func (d *DB) InitializeNode(displayName string, identity security.NodeIdentity, passwordHash string, now int64) (NodeState, error) {
	tx, err := d.sql.Begin()
	if err != nil {
		return NodeState{}, err
	}
	defer tx.Rollback()
	var one int
	err = tx.QueryRow(`SELECT 1 FROM node_state WHERE singleton = 1`).Scan(&one)
	if err == nil {
		return NodeState{}, ErrAlreadyInitialized
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return NodeState{}, err
	}
	_, err = tx.Exec(`INSERT INTO node_state (singleton,display_name,node_id,public_key,private_key,admin_password_hash,created_at) VALUES (1,?,?,?,?,?,?)`, displayName, identity.NodeID, identity.PublicKey, identity.PrivateKey, passwordHash, now)
	if err != nil {
		return NodeState{}, err
	}
	if err = tx.Commit(); err != nil {
		return NodeState{}, err
	}
	return d.GetNodeState(true)
}

func (d *DB) GetNodeState(includeSecrets bool) (NodeState, error) {
	var n NodeState
	err := d.sql.QueryRow(`SELECT display_name,node_id,public_key,private_key,admin_password_hash,created_at FROM node_state WHERE singleton=1`).Scan(&n.DisplayName, &n.NodeID, &n.PublicKey, &n.PrivateKey, &n.AdminPasswordHash, &n.CreatedAt)
	if err != nil {
		return NodeState{}, err
	}
	if !includeSecrets {
		n.PrivateKey = ""
		n.AdminPasswordHash = ""
	}
	return n, nil
}

func (d *DB) ListUsers() ([]User, error) {
	rows, err := d.sql.Query(`SELECT u.id,u.display_name,u.storage_namespace,u.quota_bytes,u.created_at,u.disabled_at,u.recovery_key_hash,u.recovery_envelope,(u.recovery_key_hash IS NOT NULL AND u.recovery_envelope IS NOT NULL),COALESCE((SELECT SUM(s.byte_count) FROM snapshots s WHERE s.user_id=u.id),0)+COALESCE((SELECT SUM(l.byte_count) FROM library_items l WHERE l.user_id=u.id AND l.deleted_at IS NULL),0),(SELECT COUNT(*) FROM clients c WHERE c.user_id=u.id AND c.revoked_at IS NULL) FROM users u ORDER BY u.created_at,u.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var users []User
	for rows.Next() {
		var u User
		if err = rows.Scan(&u.ID, &u.DisplayName, &u.StorageNamespace, &u.QuotaBytes, &u.CreatedAt, &u.DisabledAt, &u.RecoveryKeyHash, &u.RecoveryEnvelope, &u.RecoveryConfigured, &u.StorageUsedBytes, &u.ClientCount); err != nil {
			return nil, err
		}
		users = append(users, u)
	}
	return users, rows.Err()
}
func (d *DB) FindUser(id string) (*User, error) {
	var u User
	err := d.sql.QueryRow(`SELECT id,display_name,storage_namespace,quota_bytes,created_at,disabled_at,recovery_key_hash,recovery_envelope FROM users WHERE id=?`, id).Scan(&u.ID, &u.DisplayName, &u.StorageNamespace, &u.QuotaBytes, &u.CreatedAt, &u.DisabledAt, &u.RecoveryKeyHash, &u.RecoveryEnvelope)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &u, err
}
func (d *DB) FindClientByID(id string) (*Client, error) { return d.findClient(`WHERE c.id=?`, id) }
func (d *DB) FindClientByCredentialHash(hash string) (*Client, error) {
	return d.findClient(`WHERE c.credential_hash=?`, hash)
}
func (d *DB) findClient(clause, value string) (*Client, error) {
	var c Client
	err := d.sql.QueryRow(`SELECT c.id,c.user_id,c.display_name,c.public_key,c.paired_at,c.last_seen_at,c.revoked_at,u.display_name,u.disabled_at FROM clients c JOIN users u ON u.id=c.user_id `+clause, value).Scan(&c.ID, &c.UserID, &c.ClientDisplayName, &c.PublicKey, &c.PairedAt, &c.LastSeenAt, &c.RevokedAt, &c.UserDisplayName, &c.DisabledAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &c, err
}

func (d *DB) CreatePairedUser(displayName string, quota int64, recoveryHash, recoveryEnvelope string, client NewClient, now int64) (*User, *Client, error) {
	userID, err := security.UUID()
	if err != nil {
		return nil, nil, err
	}
	namespace, err := security.UUID()
	if err != nil {
		return nil, nil, err
	}
	tx, err := d.sql.Begin()
	if err != nil {
		return nil, nil, err
	}
	defer tx.Rollback()
	if _, err = tx.Exec(`INSERT INTO users(id,display_name,storage_namespace,quota_bytes,created_at,recovery_key_hash,recovery_envelope) VALUES(?,?,?,?,?,?,?)`, userID, displayName, namespace, quota, now, recoveryHash, recoveryEnvelope); err != nil {
		return nil, nil, err
	}
	if _, err = tx.Exec(`INSERT INTO clients(id,user_id,display_name,public_key,credential_hash,protocol_version,paired_at) VALUES(?,?,?,?,?,?,?)`, client.ID, userID, client.DisplayName, client.PublicKey, client.CredentialHash, client.ProtocolVersion, now); err != nil {
		return nil, nil, err
	}
	if err = tx.Commit(); err != nil {
		return nil, nil, err
	}
	u, err := d.FindUser(userID)
	if err != nil {
		return nil, nil, err
	}
	c, err := d.FindClientByID(client.ID)
	return u, c, err
}

func (d *DB) AddRecoveredClient(userID string, client NewClient, now int64) (*User, *Client, error) {
	tx, err := d.sql.Begin()
	if err != nil {
		return nil, nil, err
	}
	defer tx.Rollback()
	var disabled *int64
	if err = tx.QueryRow(`SELECT disabled_at FROM users WHERE id=?`, userID).Scan(&disabled); err != nil || disabled != nil {
		return nil, nil, errors.New("Recovery user is unavailable")
	}
	if _, err = tx.Exec(`UPDATE clients SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL`, now, userID); err != nil {
		return nil, nil, err
	}
	if _, err = tx.Exec(`INSERT INTO clients(id,user_id,display_name,public_key,credential_hash,protocol_version,paired_at) VALUES(?,?,?,?,?,?,?)`, client.ID, userID, client.DisplayName, client.PublicKey, client.CredentialHash, client.ProtocolVersion, now); err != nil {
		return nil, nil, err
	}
	if err = tx.Commit(); err != nil {
		return nil, nil, err
	}
	u, err := d.FindUser(userID)
	if err != nil {
		return nil, nil, err
	}
	c, err := d.FindClientByID(client.ID)
	return u, c, err
}

func (d *DB) ConfigureRecovery(userID, hash, envelope string) (bool, error) {
	r, err := d.sql.Exec(`UPDATE users SET recovery_key_hash=?,recovery_envelope=? WHERE id=? AND disabled_at IS NULL AND recovery_key_hash IS NULL AND recovery_envelope IS NULL`, hash, envelope, userID)
	if err != nil {
		return false, err
	}
	n, _ := r.RowsAffected()
	if n == 1 {
		return true, nil
	}
	u, err := d.FindUser(userID)
	if err != nil || u == nil || u.DisabledAt != nil || u.RecoveryKeyHash == nil || *u.RecoveryKeyHash != hash {
		return false, err
	}
	_, err = d.sql.Exec(`UPDATE users SET recovery_envelope=? WHERE id=?`, envelope, userID)
	return err == nil, err
}
func (d *DB) DisableUser(userID string, now int64) (*User, error) {
	tx, err := d.sql.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var u User
	err = tx.QueryRow(`SELECT id,display_name,storage_namespace,quota_bytes,created_at,disabled_at,recovery_key_hash,recovery_envelope FROM users WHERE id=?`, userID).Scan(&u.ID, &u.DisplayName, &u.StorageNamespace, &u.QuotaBytes, &u.CreatedAt, &u.DisabledAt, &u.RecoveryKeyHash, &u.RecoveryEnvelope)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, coded("user_not_found", "User not found")
	}
	if err != nil {
		return nil, err
	}
	if _, err = tx.Exec(`UPDATE users SET disabled_at=? WHERE id=?`, now, userID); err != nil {
		return nil, err
	}
	if _, err = tx.Exec(`UPDATE clients SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL`, now, userID); err != nil {
		return nil, err
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return &u, nil
}
func (d *DB) DeleteUser(id string) (bool, error) {
	r, e := d.sql.Exec(`DELETE FROM users WHERE id=?`, id)
	if e != nil {
		return false, e
	}
	n, e := r.RowsAffected()
	return n == 1, e
}
func (d *DB) TouchClient(id string, now int64) error {
	_, e := d.sql.Exec(`UPDATE clients SET last_seen_at=? WHERE id=?`, now, id)
	return e
}

func (d *DB) UpsertSnapshot(userID, appID, snapshotID string, bytes int64, sha string, created int64) error {
	_, e := d.sql.Exec(`INSERT INTO snapshots(user_id,app_id,snapshot_id,byte_count,sha256,created_at)VALUES(?,?,?,?,?,?) ON CONFLICT(user_id,app_id,snapshot_id) DO UPDATE SET byte_count=excluded.byte_count,sha256=excluded.sha256,created_at=excluded.created_at`, userID, appID, snapshotID, bytes, sha, created)
	return e
}
func (d *DB) ListSnapshots(userID, appID string) ([]Snapshot, error) {
	rows, e := d.sql.Query(`SELECT snapshot_id,byte_count,sha256,created_at FROM snapshots WHERE user_id=? AND app_id=? ORDER BY created_at DESC,snapshot_id DESC`, userID, appID)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	var out []Snapshot
	for rows.Next() {
		var s Snapshot
		if e = rows.Scan(&s.ID, &s.Bytes, &s.SHA256, &s.CreatedAt); e != nil {
			return nil, e
		}
		out = append(out, s)
	}
	return out, rows.Err()
}
func (d *DB) FindSnapshot(userID, appID, snapshotID string) (*Snapshot, error) {
	var s Snapshot
	e := d.sql.QueryRow(`SELECT snapshot_id,byte_count,sha256,created_at FROM snapshots WHERE user_id=? AND app_id=? AND snapshot_id=?`, userID, appID, snapshotID).Scan(&s.ID, &s.Bytes, &s.SHA256, &s.CreatedAt)
	if errors.Is(e, sql.ErrNoRows) {
		return nil, nil
	}
	return &s, e
}
func (d *DB) LatestSnapshot(userID, appID string) (*Snapshot, error) {
	var s Snapshot
	e := d.sql.QueryRow(`SELECT snapshot_id,byte_count,sha256,created_at FROM snapshots WHERE user_id=? AND app_id=? ORDER BY created_at DESC,snapshot_id DESC LIMIT 1`, userID, appID).Scan(&s.ID, &s.Bytes, &s.SHA256, &s.CreatedAt)
	if errors.Is(e, sql.ErrNoRows) {
		return nil, nil
	}
	return &s, e
}
func (d *DB) TotalStorageBytes(userID string) (int64, error) {
	var n int64
	e := d.sql.QueryRow(`SELECT
        COALESCE((SELECT SUM(byte_count) FROM snapshots WHERE user_id=?),0) +
        COALESCE((SELECT SUM(byte_count) FROM library_items WHERE user_id=? AND deleted_at IS NULL),0)`, userID, userID).Scan(&n)
	return n, e
}
func (d *DB) DeleteSnapshot(userID, appID, snapshotID string) error {
	_, e := d.sql.Exec(`DELETE FROM snapshots WHERE user_id=? AND app_id=? AND snapshot_id=?`, userID, appID, snapshotID)
	return e
}

type CodedError struct{ Code, Message string }

func (e CodedError) Error() string     { return e.Message }
func coded(code, message string) error { return CodedError{code, message} }
