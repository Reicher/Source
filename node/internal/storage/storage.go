package storage

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"time"

	"source.local/node/internal/apperror"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
)

var snapshotID = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
var appID = regexp.MustCompile(`^[a-z][a-z0-9-]{1,31}$`)

type Storage struct {
	db        *database.DB
	root      string
	retention int
	now       func() time.Time
}
type Value struct {
	Metadata *database.Snapshot
	Body     []byte
}

func New(db *database.DB, root string, retention int, now func() time.Time) *Storage {
	return &Storage{db: db, root: filepath.Clean(root), retention: retention, now: now}
}
func (s *Storage) List(user, app string) ([]database.Snapshot, error) {
	if !appID.MatchString(app) {
		return nil, apperror.New(400, "invalid_app_id", "Invalid app_id")
	}
	return s.db.ListSnapshots(user, app)
}
func (s *Storage) Put(user, app, id string, body []byte) (*database.Snapshot, error) {
	if e := validate(app, id); e != nil {
		return nil, e
	}
	existing, e := s.db.FindSnapshot(user, app, id)
	if e != nil {
		return nil, e
	}
	used, e := s.db.TotalSnapshotBytes(user)
	if e != nil {
		return nil, e
	}
	if existing != nil {
		used -= existing.Bytes
	}
	u, e := s.db.FindUser(user)
	if e != nil {
		return nil, e
	}
	if u == nil {
		return nil, errors.New("User storage quota is unavailable")
	}
	if used+int64(len(body)) > u.QuotaBytes {
		return nil, apperror.New(413, "storage_quota_exceeded", "User storage quota exceeded")
	}
	dir, e := s.directory(user, app)
	if e != nil {
		return nil, e
	}
	if e = os.MkdirAll(dir, 0700); e != nil {
		return nil, e
	}
	token, e := security.UUID()
	if e != nil {
		return nil, e
	}
	temporary := filepath.Join(dir, "."+id+"."+token+".tmp")
	if e = os.WriteFile(temporary, body, 0600); e != nil {
		return nil, e
	}
	destination := filepath.Join(dir, id+".bin")
	if e = os.Rename(temporary, destination); e != nil {
		_ = os.Remove(temporary)
		return nil, e
	}
	sum := sha256.Sum256(body)
	if e = s.db.UpsertSnapshot(user, app, id, int64(len(body)), hex.EncodeToString(sum[:]), s.now().UnixMilli()); e != nil {
		return nil, e
	}
	items, e := s.db.ListSnapshots(user, app)
	if e != nil {
		return nil, e
	}
	if len(items) > s.retention {
		for _, old := range items[s.retention:] {
			if _, e = s.Delete(user, app, old.ID); e != nil {
				return nil, e
			}
		}
	}
	return s.db.FindSnapshot(user, app, id)
}
func (s *Storage) Get(user, app, id string) (*Value, error) {
	if e := validate(app, id); e != nil {
		return nil, e
	}
	metadata, e := s.db.FindSnapshot(user, app, id)
	if e != nil || metadata == nil {
		return nil, e
	}
	p, e := s.path(user, app, id)
	if e != nil {
		return nil, e
	}
	body, e := os.ReadFile(p)
	if os.IsNotExist(e) {
		return nil, nil
	}
	if e != nil {
		return nil, e
	}
	return &Value{Metadata: metadata, Body: body}, nil
}
func (s *Storage) Latest(user, app string) (*Value, error) {
	if !appID.MatchString(app) {
		return nil, apperror.New(400, "invalid_app_id", "Invalid app_id")
	}
	latest, e := s.db.LatestSnapshot(user, app)
	if e != nil || latest == nil {
		return nil, e
	}
	return s.Get(user, app, latest.ID)
}
func (s *Storage) Delete(user, app, id string) (bool, error) {
	if e := validate(app, id); e != nil {
		return false, e
	}
	existing, e := s.db.FindSnapshot(user, app, id)
	if e != nil || existing == nil {
		return false, e
	}
	p, e := s.path(user, app, id)
	if e != nil {
		return false, e
	}
	if e = os.Remove(p); e != nil && !os.IsNotExist(e) {
		return false, e
	}
	return true, s.db.DeleteSnapshot(user, app, id)
}
func (s *Storage) directory(user, app string) (string, error) {
	u, e := s.db.FindUser(user)
	if e != nil {
		return "", e
	}
	if u == nil {
		return "", errors.New("User storage namespace is unavailable")
	}
	return filepath.Join(s.root, u.StorageNamespace, app, "snapshots"), nil
}
func (s *Storage) path(user, app, id string) (string, error) {
	dir, e := s.directory(user, app)
	if e != nil {
		return "", e
	}
	return filepath.Join(dir, id+".bin"), nil
}
func validate(app, id string) error {
	if !appID.MatchString(app) {
		return apperror.New(400, "invalid_app_id", "Invalid app_id")
	}
	if !snapshotID.MatchString(id) {
		return apperror.New(400, "invalid_snapshot_id", "Invalid snapshot_id")
	}
	return nil
}
