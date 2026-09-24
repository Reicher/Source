package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"syscall"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

const qrLifetime = 2 * time.Minute

//go:embed setup.js
var setupScript string

type diskState struct {
	ID       string `json:"id"`
	SelfPin  string `json:"self_pin,omitempty"`
	PersonID string `json:"person_id,omitempty"`
}

type identity struct {
	mu        sync.Mutex
	state     diskState
	id        string
	certPin   string
	certPath  string
	keyPath   string
	statePath string
	qrToken   string
	qrExpires time.Time
	jobs      *sourceJobs
}

func randomString(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func fingerprint(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.Raw)
	return hex.EncodeToString(sum[:])
}

func savePrivate(path string, value []byte) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".pairing-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if err := f.Chmod(0600); err != nil {
		f.Close()
		return err
	}
	if _, err := f.Write(value); err != nil {
		f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Rename(f.Name(), path); err != nil {
		return err
	}
	return syncDirectory(filepath.Dir(path))
}

func syncDirectory(path string) error {
	dir, err := os.Open(path)
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}

func loadIdentity(dir string) (*identity, error) {
	if err := os.MkdirAll(filepath.Dir(dir), 0700); err != nil {
		return nil, err
	}
	i := &identity{certPath: filepath.Join(dir, "source.crt"), keyPath: filepath.Join(dir, "source.key"), statePath: filepath.Join(dir, "state.json")}
	entries, err := os.ReadDir(dir)
	if errors.Is(err, os.ErrNotExist) || (err == nil && len(entries) == 0) {
		if err := i.create(dir); err != nil {
			return nil, err
		}
	} else if err != nil {
		return nil, err
	}
	_, certErr := os.Stat(i.certPath)
	_, keyErr := os.Stat(i.keyPath)
	_, stateErr := os.Stat(i.statePath)
	if certErr != nil || keyErr != nil || stateErr != nil {
		return nil, fmt.Errorf("incomplete Source identity in %s; refusing to regenerate it", dir)
	}
	state, err := os.ReadFile(i.statePath)
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(state, &i.state); err != nil {
		return nil, err
	}
	if i.state.ID == "" || (i.state.SelfPin == "") != (i.state.PersonID == "") {
		return nil, errors.New("invalid Source state")
	}
	cert, err := tls.LoadX509KeyPair(i.certPath, i.keyPath)
	if err != nil {
		return nil, err
	}
	parsed, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		return nil, err
	}
	i.id, i.certPin = i.state.ID, fingerprint(parsed)
	return i, nil
}

func (i *identity) create(dir string) error {
	// The directory itself cannot be a mount point: activation renames its sibling.
	if err := identityDirRemovalError(dir, os.Remove(dir)); err != nil {
		return err
	}
	stage, err := os.MkdirTemp(filepath.Dir(dir), ".source-identity-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	id, err := randomString(16)
	if err != nil {
		return err
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return err
	}
	now := time.Now()
	template := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "Source-" + id}, NotBefore: now.Add(-time.Hour), NotAfter: now.AddDate(20, 0, 0), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, DNSNames: []string{"source.local"}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return err
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	if err := savePrivate(filepath.Join(stage, "source.key"), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})); err != nil {
		return err
	}
	if err := savePrivate(filepath.Join(stage, "source.crt"), pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})); err != nil {
		return err
	}
	state, _ := json.Marshal(diskState{ID: id})
	if err := savePrivate(filepath.Join(stage, "state.json"), state); err != nil {
		return err
	}
	if err := os.Rename(stage, dir); err != nil {
		return err
	}
	return syncDirectory(filepath.Dir(dir))
}

func identityDirRemovalError(dir string, err error) error {
	if err == nil || errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if errors.Is(err, syscall.EBUSY) {
		return fmt.Errorf("Source identity directory %s is a mount point; mount its parent directory instead: %w", dir, err)
	}
	return err
}

func (i *identity) tlsConfig() *tls.Config {
	return &tls.Config{MinVersion: tls.VersionTLS12, ClientAuth: tls.RequireAnyClientCert}
}

func (i *identity) token() (string, time.Time, error) {
	i.mu.Lock()
	defer i.mu.Unlock()
	if i.state.SelfPin != "" {
		return "", time.Time{}, errors.New("already paired")
	}
	if time.Now().After(i.qrExpires) {
		var err error
		i.qrToken, err = randomString(32)
		if err != nil {
			return "", time.Time{}, err
		}
		i.qrExpires = time.Now().Add(qrLifetime)
	}
	return i.qrToken, i.qrExpires, nil
}

func (i *identity) lanHandler() http.Handler {
	handler, err := i.newLanHandler(context.Background())
	if err != nil {
		panic(err)
	}
	return handler
}

func (i *identity) newLanHandler(ctx context.Context) (http.Handler, error) {
	mux := http.NewServeMux()
	dataDir := filepath.Dir(i.statePath)
	bronze := newBronzeStore(filepath.Join(dataDir, "bronze"))
	silver, err := newSilverService(filepath.Join(dataDir, "silver"), bronze)
	if err != nil {
		return nil, fmt.Errorf("load Silver processing state: %w", err)
	}
	syncJobs, err := newSyncJobStore(filepath.Join(dataDir, "jobs"))
	if err != nil {
		return nil, fmt.Errorf("load sync job state: %w", err)
	}
	jobs := &sourceJobs{sync: syncJobs, silver: silver}
	i.mu.Lock()
	i.jobs = jobs
	i.mu.Unlock()
	bronze.onCommit = func(item bronzeItem) error {
		_, err := silver.enqueue(item)
		return err
	}
	silver.start(ctx)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })
	mux.HandleFunc("POST /v1/pair", func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil || len(r.TLS.PeerCertificates) != 1 {
			http.Error(w, "client certificate required", http.StatusUnauthorized)
			return
		}
		var request struct {
			ID    string `json:"id"`
			Token string `json:"token"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&request); err != nil {
			http.Error(w, "invalid request", http.StatusBadRequest)
			return
		}
		i.mu.Lock()
		defer i.mu.Unlock()
		pin := fingerprint(r.TLS.PeerCertificates[0])
		if request.ID != i.id {
			http.Error(w, "wrong Source", http.StatusForbidden)
			return
		}
		if i.state.SelfPin != "" {
			if i.state.SelfPin != pin {
				http.Error(w, "Source already paired", http.StatusConflict)
				return
			}
			writeJSON(w, map[string]string{"id": i.id, "person_id": i.state.PersonID})
			return
		}
		if time.Now().After(i.qrExpires) || len(request.Token) != len(i.qrToken) || subtle.ConstantTimeCompare([]byte(request.Token), []byte(i.qrToken)) != 1 {
			http.Error(w, "expired or invalid QR", http.StatusForbidden)
			return
		}
		personID, err := randomString(16)
		if err != nil {
			http.Error(w, "identity creation failed", http.StatusInternalServerError)
			return
		}
		next := diskState{ID: i.id, SelfPin: pin, PersonID: personID}
		value, _ := json.Marshal(next)
		if err := savePrivate(i.statePath, value); err != nil {
			http.Error(w, "identity storage failed", http.StatusInternalServerError)
			return
		}
		i.state = next
		i.qrToken = ""
		writeJSON(w, map[string]string{"id": i.id, "person_id": personID})
	})
	mux.HandleFunc("GET /v1/status", func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil || len(r.TLS.PeerCertificates) != 1 {
			http.Error(w, "client certificate required", http.StatusUnauthorized)
			return
		}
		i.mu.Lock()
		if i.state.SelfPin == "" || i.state.SelfPin != fingerprint(r.TLS.PeerCertificates[0]) {
			i.mu.Unlock()
			http.Error(w, "not paired", http.StatusForbidden)
			return
		}
		personID := i.state.PersonID
		i.mu.Unlock()
		silverRevision, jobSnapshot, processing := jobs.refreshStatus()
		writeJSON(w, map[string]any{
			"id": i.id, "person_id": personID, "status": "connected",
			"silver_revision": silverRevision, "jobs_revision": jobSnapshot.Revision,
			"jobs": jobSnapshot, "processing": processing,
		})
	})
	mux.Handle("/v1/bronze", i.trusted(bronze))
	mux.Handle("/v1/bronze/", i.trusted(bronze))
	mux.Handle("GET /v1/silver", i.trusted(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		snapshot := silver.snapshot()
		snapshot.Jobs = jobs.snapshot()
		writeJSON(w, snapshot)
	})))
	mux.Handle("POST /v1/jobs/sync", i.trusted(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Jobs []syncJobPlanItem `json:"jobs"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256*1024)).Decode(&request); err != nil {
			http.Error(w, "invalid sync job plan", http.StatusBadRequest)
			return
		}
		result, err := syncJobs.plan(request.Jobs)
		if errors.Is(err, errInvalidSyncJobPlan) {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		} else if err != nil {
			http.Error(w, "job storage unavailable", http.StatusInternalServerError)
			return
		}
		writeJSON(w, map[string]any{"jobs": result})
	})))
	mux.Handle("POST /v1/jobs/sync/{id}/complete", i.trusted(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := syncJobs.complete(r.PathValue("id")); errors.Is(err, os.ErrNotExist) {
			http.NotFound(w, r)
			return
		} else if err != nil {
			http.Error(w, "job storage unavailable", http.StatusInternalServerError)
			return
		}
		writeJSON(w, map[string]string{"status": "completed"})
	})))
	return mux, nil
}

func (i *identity) jobSnapshot() sourceJobSnapshot {
	i.mu.Lock()
	jobs := i.jobs
	i.mu.Unlock()
	if jobs == nil {
		return sourceJobSnapshot{Queued: []sourceJob{}, Completed: []sourceJob{}}
	}
	return jobs.snapshot()
}

func (i *identity) trusted(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil || len(r.TLS.PeerCertificates) != 1 {
			http.Error(w, "client certificate required", http.StatusUnauthorized)
			return
		}
		i.mu.Lock()
		allowed := i.state.SelfPin != "" && i.state.SelfPin == fingerprint(r.TLS.PeerCertificates[0])
		i.mu.Unlock()
		if !allowed {
			http.Error(w, "not paired", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(value)
}

func (i *identity) setupHandler(host string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		i.mu.Lock()
		paired := i.state.SelfPin != ""
		i.mu.Unlock()
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		if paired {
			_, _ = w.Write([]byte("<!doctype html><meta name='viewport' content='width=device-width'><title>Source jobs</title><style>html{color-scheme:dark}body{box-sizing:border-box;max-width:720px;margin:0 auto;padding:28px 20px;background:#0e1415;color:#fff;font:15px system-ui}header{display:flex;align-items:center;gap:10px;margin-bottom:24px}h1{font-size:26px;margin:0}.paired{width:12px;height:12px;border-radius:50%;background:#74dca7}h2{font-size:17px;margin:22px 0 8px}.job{display:grid;grid-template-columns:1fr auto;gap:3px 12px;padding:10px 12px;margin:6px 0;border-radius:10px;background:#1d2727}.meta,.time,.empty,.more{color:#bdc9c3;font-size:13px}.time{grid-column:2;grid-row:1/3;align-self:center}.more{padding:6px 12px}</style><header><h1>Source</h1><div id='paired' class='paired'></div></header><main id='jobs'><section><h2>Queue <span id='queued-count'></span></h2><div id='queued'></div></section><section><h2>Completed <span id='completed-count'></span></h2><div id='completed'></div></section></main><script src='/setup.js'></script>"))
			return
		}
		_, _ = w.Write([]byte("<!doctype html><meta http-equiv=refresh content=30><style>html,body{height:100%;margin:0}body{display:grid;place-items:center}img{width:min(80vw,520px)}.paired{width:64px;height:64px;border-radius:50%;background:#2ba66a}</style><img id='pairing-qr' alt='' src='/qr.png'><script src='/setup.js'></script>"))
	})
	mux.HandleFunc("GET /paired", func(w http.ResponseWriter, _ *http.Request) {
		i.mu.Lock()
		paired := i.state.SelfPin != ""
		i.mu.Unlock()
		if paired {
			_, _ = w.Write([]byte("yes"))
		} else {
			_, _ = w.Write([]byte("no"))
		}
	})
	mux.HandleFunc("GET /jobs", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, i.jobSnapshot())
	})
	mux.HandleFunc("GET /setup.js", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
		_, _ = w.Write([]byte(setupScript))
	})
	mux.HandleFunc("GET /qr.png", func(w http.ResponseWriter, _ *http.Request) {
		token, expires, err := i.token()
		if err != nil {
			http.Error(w, "already paired", http.StatusGone)
			return
		}
		value, _ := json.Marshal(map[string]any{"v": 1, "id": i.id, "fp": i.certPin, "token": token})
		png, err := qrcode.Encode(string(value), qrcode.Medium, 768)
		if err != nil {
			http.Error(w, "QR generation failed", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("X-QR-Expires-In-Ms", strconv.FormatInt(max(0, time.Until(expires).Milliseconds()), 10))
		_, _ = w.Write(png)
	})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		remote, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil || net.ParseIP(remote) == nil || !net.ParseIP(remote).IsLoopback() || r.Host != host {
			http.Error(w, "local setup only", http.StatusForbidden)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; connect-src 'self'; img-src 'self' blob:; script-src 'self'; style-src 'unsafe-inline'")
		mux.ServeHTTP(w, r)
	})
}
