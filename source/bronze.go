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
	// Discard blobs left by interrupted uploads or old item revisions.
	_ = s.pruneUnused()
	return s
}

func (s *bronzeStore) itemPath(id string) string   { return filepath.Join(s.dir, "items", id+".json") }
func (s *bronzeStore) blobPath(hash string) string { return filepath.Join(s.dir, "blobs", hash) }

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
		s.mu.Lock()
		defer s.mu.Unlock()
		entries, err := os.ReadDir(filepath.Join(s.dir, "items"))
		if errors.Is(err, os.ErrNotExist) {
			writeJSON(w, []bronzeItem{})
			return
		}
		if err != nil {
			http.Error(w, "storage unavailable", http.StatusInternalServerError)
			return
		}
		items := make([]bronzeItem, 0, len(entries))
		for _, entry := range entries {
			if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".json") {
				continue
			}
			id := strings.TrimSuffix(entry.Name(), ".json")
			item, err := s.load(id)
			if err != nil || !validBronze(item) {
				http.Error(w, "stored Bronze metadata invalid", http.StatusInternalServerError)
				return
			}
			items = append(items, item)
		}
		sort.Slice(items, func(a, b int) bool { return items[a].ID < items[b].ID })
		writeJSON(w, items)
		return
	}
	id := strings.TrimPrefix(r.URL.Path, "/v1/bronze/")
	if !strings.HasPrefix(r.URL.Path, "/v1/bronze/") || !bronzeID.MatchString(id) || strings.Contains(id, "/") {
		http.NotFound(w, r)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	current, err := s.load(id)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		http.Error(w, "storage unavailable", http.StatusInternalServerError)
		return
	}
	switch r.Method {
	case http.MethodGet:
		if errors.Is(err, os.ErrNotExist) || current.Deleted {
			http.NotFound(w, r)
			return
		}
		file, openErr := os.Open(s.blobPath(current.Hash))
		if openErr != nil {
			http.Error(w, "content unavailable", http.StatusInternalServerError)
			return
		}
		defer file.Close()
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Length", fmt.Sprint(current.Size))
		_, _ = io.Copy(w, file)
	case http.MethodPut, http.MethodDelete:
		item, metaErr := readBronzeHeader(r)
		if metaErr != nil || item.ID != id || item.Deleted != (r.Method == http.MethodDelete) {
			http.Error(w, "invalid Bronze metadata", http.StatusBadRequest)
			return
		}
		if err == nil {
			if item.Revision < current.Revision || (item.Revision == current.Revision && item != current) {
				http.Error(w, "revision conflict", http.StatusConflict)
				return
			}
			if item.Revision == current.Revision {
				writeJSON(w, current)
				return
			}
		}
		if r.Method == http.MethodPut {
			if err := s.storeBlob(r.Body, item); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
		}
		if err := os.MkdirAll(filepath.Join(s.dir, "items"), 0700); err != nil {
			http.Error(w, "storage unavailable", http.StatusInternalServerError)
			return
		}
		value, _ := json.Marshal(item)
		if err := savePrivate(s.itemPath(id), value); err != nil {
			http.Error(w, "storage unavailable", http.StatusInternalServerError)
			return
		}
		if err == nil && !current.Deleted && (item.Deleted || item.Hash != current.Hash) {
			_ = s.pruneUnused()
		}
		writeJSON(w, item)
	default:
		w.Header().Set("Allow", "GET, PUT, DELETE")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
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
		if !blob.IsDir() && (strings.HasPrefix(blob.Name(), ".upload-") ||
			(bronzeHash.MatchString(blob.Name()) && !used[blob.Name()])) {
			if err := os.Remove(s.blobPath(blob.Name())); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *bronzeStore) storeBlob(body io.Reader, item bronzeItem) error {
	dir := filepath.Join(s.dir, "blobs")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	file, err := os.CreateTemp(dir, ".upload-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	if err := file.Chmod(0600); err != nil {
		return err
	}
	hash := sha256.New()
	n, err := io.Copy(io.MultiWriter(file, hash), io.LimitReader(body, item.Size+1))
	if err != nil {
		return err
	}
	if n != item.Size || hex.EncodeToString(hash.Sum(nil)) != item.Hash {
		return errors.New("content size or hash mismatch")
	}
	if err := file.Sync(); err != nil {
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	path := s.blobPath(item.Hash)
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		if err := os.Rename(file.Name(), path); err != nil {
			return err
		}
		return syncDirectory(dir)
	} else if err != nil {
		return err
	}
	return nil
}
