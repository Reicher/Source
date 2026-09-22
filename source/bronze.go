package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
)

const bronzeMetaHeader = "X-Bronze-Metadata"

var bronzeID = regexp.MustCompile(`^[0-9a-fA-F-]{36}$`)
var bronzeHash = regexp.MustCompile(`^[0-9a-f]{64}$`)
var errRevisionConflict = errors.New("revision conflict")

type bronzeItem struct {
	ID       string `json:"id"`
	Revision int64  `json:"revision"`
	Hash     string `json:"hash"`
	Deleted  bool   `json:"deleted"`
	Title    string `json:"title"`
	Mime     string `json:"mime"`
	Size     int64  `json:"size"`
	Created  int64  `json:"created"`
	Modified int64  `json:"modified"`
}

type bronzeStore struct {
	mu  sync.Mutex
	dir string
}

func newBronzeStore(dir string) *bronzeStore {
	s := &bronzeStore{dir: dir}
	// Staged uploads are never committed metadata and can be discarded on restart.
	_ = os.RemoveAll(s.uploadDir())
	_ = s.pruneUnused()
	return s
}

func (s *bronzeStore) itemPath(id string) string   { return filepath.Join(s.dir, "items", id+".json") }
func (s *bronzeStore) blobPath(hash string) string { return filepath.Join(s.dir, "blobs", hash) }
func (s *bronzeStore) uploadDir() string           { return filepath.Join(s.dir, "uploads") }

func (s *bronzeStore) load(id string) (bronzeItem, error) {
	var item bronzeItem
	value, err := os.ReadFile(s.itemPath(id))
	if err != nil {
		return item, err
	}
	err = json.Unmarshal(value, &item)
	if err == nil && item.ID != id {
		err = errors.New("Bronze item ID mismatch")
	}
	return item, err
}

func validBronze(item bronzeItem) bool {
	return bronzeID.MatchString(item.ID) && item.Revision > 0 && item.Created > 0 && item.Modified >= item.Created &&
		len(item.Title) > 0 && len(item.Title) <= 512 && len(item.Mime) > 0 && len(item.Mime) <= 255 &&
		item.Size >= 0 && (item.Deleted || bronzeHash.MatchString(item.Hash))
}

func readBronzeHeader(r *http.Request) (bronzeItem, error) {
	var item bronzeItem
	encoded := r.Header.Get(bronzeMetaHeader)
	if len(encoded) > 4096 {
		return item, errors.New("metadata too large")
	}
	value, err := base64.RawURLEncoding.DecodeString(encoded)
	if err == nil {
		err = json.Unmarshal(value, &item)
	}
	if err != nil || !validBronze(item) {
		return item, errors.New("invalid Bronze metadata")
	}
	return item, nil
}

func (s *bronzeStore) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.URL.Path == "/v1/bronze" && r.Method == http.MethodGet {
		items, err := s.manifest()
		if err != nil {
			http.Error(w, "storage unavailable", http.StatusInternalServerError)
			return
		}
		writeJSON(w, items)
		return
	}
	id := strings.TrimPrefix(r.URL.Path, "/v1/bronze/")
	if !strings.HasPrefix(r.URL.Path, "/v1/bronze/") || !bronzeID.MatchString(id) || strings.Contains(id, "/") {
		http.NotFound(w, r)
		return
	}
	switch r.Method {
	case http.MethodGet:
		item, file, err := s.openContent(id)
		if errors.Is(err, os.ErrNotExist) {
			http.NotFound(w, r)
			return
		}
		if err != nil {
			http.Error(w, "content unavailable", http.StatusInternalServerError)
			return
		}
		defer file.Close()
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Length", fmt.Sprint(item.Size))
		_, _ = io.Copy(w, file)
	case http.MethodPut, http.MethodDelete:
		item, metaErr := readBronzeHeader(r)
		if metaErr != nil || item.ID != id || item.Deleted != (r.Method == http.MethodDelete) {
			http.Error(w, "invalid Bronze metadata", http.StatusBadRequest)
			return
		}
		staged := ""
		if r.Method == http.MethodPut {
			var err error
			staged, err = s.stageBlob(r.Body, item)
			if err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			defer os.Remove(staged)
		}
		stored, err := s.commit(item, staged)
		if errors.Is(err, errRevisionConflict) {
			http.Error(w, err.Error(), http.StatusConflict)
			return
		}
		if err != nil {
			http.Error(w, "storage unavailable", http.StatusInternalServerError)
			return
		}
		writeJSON(w, stored)
	default:
		w.Header().Set("Allow", "GET, PUT, DELETE")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *bronzeStore) manifest() ([]bronzeItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	entries, err := os.ReadDir(filepath.Join(s.dir, "items"))
	if errors.Is(err, os.ErrNotExist) {
		return []bronzeItem{}, nil
	}
	if err != nil {
		return nil, err
	}
	items := make([]bronzeItem, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		item, err := s.load(strings.TrimSuffix(entry.Name(), ".json"))
		if err != nil || !validBronze(item) {
			return nil, errors.New("stored Bronze metadata invalid")
		}
		items = append(items, item)
	}
	sort.Slice(items, func(a, b int) bool { return items[a].ID < items[b].ID })
	return items, nil
}

func (s *bronzeStore) openContent(id string) (bronzeItem, *os.File, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	item, err := s.load(id)
	if err != nil || item.Deleted {
		if err == nil {
			err = os.ErrNotExist
		}
		return bronzeItem{}, nil, err
	}
	file, err := os.Open(s.blobPath(item.Hash))
	return item, file, err
}

func (s *bronzeStore) commit(item bronzeItem, staged string) (bronzeItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	current, err := s.load(item.ID)
	exists := err == nil
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return bronzeItem{}, err
	}
	if exists {
		if item.Revision < current.Revision || (item.Revision == current.Revision && item != current) {
			return bronzeItem{}, errRevisionConflict
		}
		if item.Revision == current.Revision {
			return current, nil
		}
	}
	if staged != "" {
		if err := s.commitBlob(staged, item.Hash); err != nil {
			return bronzeItem{}, err
		}
	}
	if err := os.MkdirAll(filepath.Join(s.dir, "items"), 0700); err != nil {
		return bronzeItem{}, err
	}
	value, _ := json.Marshal(item)
	if err := savePrivate(s.itemPath(item.ID), value); err != nil {
		_ = s.pruneUnused()
		return bronzeItem{}, err
	}
	if exists && !current.Deleted && (item.Deleted || item.Hash != current.Hash) {
		_ = s.pruneUnused()
	}
	return item, nil
}

func (s *bronzeStore) pruneUnused() error {
	entries, err := os.ReadDir(filepath.Join(s.dir, "items"))
	if errors.Is(err, os.ErrNotExist) {
		entries = nil
	} else if err != nil {
		return err
	}
	used := make(map[string]bool)
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		item, err := s.load(strings.TrimSuffix(entry.Name(), ".json"))
		if err != nil || !validBronze(item) {
			return errors.New("stored Bronze metadata invalid")
		}
		if !item.Deleted {
			used[item.Hash] = true
		}
	}
	blobs, err := os.ReadDir(filepath.Join(s.dir, "blobs"))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	for _, blob := range blobs {
		if !blob.IsDir() && bronzeHash.MatchString(blob.Name()) && !used[blob.Name()] {
			if err := os.Remove(s.blobPath(blob.Name())); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *bronzeStore) stageBlob(body io.Reader, item bronzeItem) (string, error) {
	dir := s.uploadDir()
	if err := os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	file, err := os.CreateTemp(dir, "upload-*")
	if err != nil {
		return "", err
	}
	staged := file.Name()
	keep := false
	defer func() {
		_ = file.Close()
		if !keep {
			_ = os.Remove(staged)
		}
	}()
	if err := file.Chmod(0600); err != nil {
		return "", err
	}
	hash := sha256.New()
	n, err := io.Copy(io.MultiWriter(file, hash), io.LimitReader(body, item.Size+1))
	if err != nil {
		return "", err
	}
	if n != item.Size || hex.EncodeToString(hash.Sum(nil)) != item.Hash {
		return "", errors.New("content size or hash mismatch")
	}
	if err := file.Sync(); err != nil {
		return "", err
	}
	if err := file.Close(); err != nil {
		return "", err
	}
	keep = true
	return staged, nil
}

func (s *bronzeStore) commitBlob(staged, hash string) error {
	dir := filepath.Join(s.dir, "blobs")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	path := s.blobPath(hash)
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		if err := os.Rename(staged, path); err != nil {
			return err
		}
		return syncDirectory(dir)
	} else if err != nil {
		return err
	}
	return nil
}
