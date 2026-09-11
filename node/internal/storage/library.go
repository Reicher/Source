package storage

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"os"
	"path/filepath"
	"regexp"

	"source.local/node/internal/apperror"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
)

var libraryItemID = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
var sha256Pattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// PutLibraryItem atomically stores an opaque, client-encrypted raw item. A
// repeated PUT with the same identity is successful without rewriting bytes.
func (s *Storage) PutLibraryItem(
	user, id, contentSHA256, encryptedSHA256 string,
	bytes int64,
	body io.Reader,
	maximumBytes int64,
) (*database.LibraryItem, bool, error) {
	if err := validateLibraryIdentity(id, contentSHA256); err != nil {
		return nil, false, err
	}
	if !sha256Pattern.MatchString(encryptedSHA256) {
		return nil, false, apperror.New(400, "invalid_encrypted_hash", "The encrypted item checksum is invalid.")
	}
	if bytes < 36 || bytes > maximumBytes {
		return nil, false, apperror.New(413, "library_item_too_large", "The library item is too large.")
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	existing, err := s.db.FindLibraryItem(user, id)
	if err != nil {
		return nil, false, err
	}
	if existing != nil {
		if existing.DeletedAt != nil {
			return nil, false, apperror.New(409, "library_item_deleted", "A deleted item cannot be restored by an upload retry.")
		}
		if existing.ContentSHA256 != contentSHA256 {
			return nil, false, apperror.New(409, "library_item_identity_conflict", "The item identifier belongs to different content.")
		}
		return existing, false, nil
	}
	duplicate, err := s.db.FindActiveLibraryItemByContent(user, contentSHA256)
	if err != nil {
		return nil, false, err
	}
	if duplicate != nil {
		return nil, false, apperror.New(409, "library_content_exists", "This content is already stored.")
	}
	used, err := s.db.TotalStorageBytes(user)
	if err != nil {
		return nil, false, err
	}
	u, err := s.db.FindUser(user)
	if err != nil {
		return nil, false, err
	}
	if u == nil {
		return nil, false, apperror.New(404, "user_not_found", "User storage namespace is unavailable.")
	}
	if used+bytes > u.QuotaBytes {
		return nil, false, apperror.New(413, "storage_quota_exceeded", "User storage quota exceeded")
	}
	dir, err := s.libraryDirectory(user)
	if err != nil {
		return nil, false, err
	}
	if err = os.MkdirAll(dir, 0700); err != nil {
		return nil, false, err
	}
	// A retry after a process or connection interruption removes only staging
	// files for this validated item identity. Completed items never use a dot prefix.
	stale, _ := filepath.Glob(filepath.Join(dir, "."+id+".*.tmp"))
	for _, path := range stale {
		_ = os.Remove(path)
	}
	token, err := security.UUID()
	if err != nil {
		return nil, false, err
	}
	temporary := filepath.Join(dir, "."+id+"."+token+".tmp")
	file, err := os.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return nil, false, err
	}
	removeTemporary := true
	defer func() {
		_ = file.Close()
		if removeTemporary {
			_ = os.Remove(temporary)
		}
	}()
	hash := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(file, hash), io.LimitReader(body, maximumBytes+1))
	closeErr := file.Close()
	if copyErr != nil {
		return nil, false, copyErr
	}
	if closeErr != nil {
		return nil, false, closeErr
	}
	if written != bytes {
		return nil, false, apperror.New(400, "library_item_size_mismatch", "The library item size does not match.")
	}
	if hex.EncodeToString(hash.Sum(nil)) != encryptedSHA256 {
		return nil, false, apperror.New(400, "library_item_hash_mismatch", "The encrypted item checksum does not match.")
	}
	destination := filepath.Join(dir, id+".bin")
	if err = os.Rename(temporary, destination); err != nil {
		return nil, false, err
	}
	removeTemporary = false
	item := database.LibraryItem{
		ID: id, ContentSHA256: contentSHA256, EncryptedSHA256: encryptedSHA256,
		Bytes: bytes, CreatedAt: s.now().UnixMilli(),
	}
	if err = s.db.InsertLibraryItem(user, item); err != nil {
		_ = os.Remove(destination)
		return nil, false, err
	}
	stored, err := s.db.FindLibraryItem(user, id)
	return stored, true, err
}

// DeleteLibraryItem records a tombstone even if the upload never completed.
// This makes delayed or retried PUTs unable to resurrect deleted content.
func (s *Storage) DeleteLibraryItem(user, id, contentSHA256 string) error {
	if err := validateLibraryIdentity(id, contentSHA256); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	existing, err := s.db.FindLibraryItem(user, id)
	if err != nil {
		return err
	}
	if existing != nil && existing.ContentSHA256 != contentSHA256 {
		return apperror.New(409, "library_item_identity_conflict", "The item identifier belongs to different content.")
	}
	deletedAt := s.now().UnixMilli()
	if err = s.db.PutLibraryTombstone(user, id, contentSHA256, deletedAt); err != nil {
		return err
	}
	dir, err := s.libraryDirectory(user)
	if err != nil {
		return err
	}
	if err = os.Remove(filepath.Join(dir, id+".bin")); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

func (s *Storage) libraryDirectory(user string) (string, error) {
	u, err := s.db.FindUser(user)
	if err != nil {
		return "", err
	}
	if u == nil {
		return "", apperror.New(404, "user_not_found", "User storage namespace is unavailable.")
	}
	return filepath.Join(s.root, u.StorageNamespace, "library", "items"), nil
}

func validateLibraryIdentity(id, contentSHA256 string) error {
	if !libraryItemID.MatchString(id) {
		return apperror.New(400, "invalid_library_item_id", "The library item identifier is invalid.")
	}
	if !sha256Pattern.MatchString(contentSHA256) {
		return apperror.New(400, "invalid_content_hash", "The content checksum is invalid.")
	}
	return nil
}
