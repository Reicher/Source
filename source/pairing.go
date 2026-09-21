package main

import (
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
	return os.Rename(f.Name(), path)
}

func loadIdentity(dir string) (*identity, error) {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	i := &identity{certPath: filepath.Join(dir, "source.crt"), keyPath: filepath.Join(dir, "source.key"), statePath: filepath.Join(dir, "state.json")}
	_, certErr := os.Stat(i.certPath)
	_, keyErr := os.Stat(i.keyPath)
	_, stateErr := os.Stat(i.statePath)
	if certErr != nil || keyErr != nil || stateErr != nil {
		if !errors.Is(certErr, os.ErrNotExist) || !errors.Is(keyErr, os.ErrNotExist) || !errors.Is(stateErr, os.ErrNotExist) {
			return nil, fmt.Errorf("incomplete Source identity in %s; refusing to regenerate it", dir)
		}
		if err := i.create(); err != nil {
			return nil, err
		}
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

func (i *identity) create() error {
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
	if err := savePrivate(i.keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})); err != nil {
		return err
	}
	if err := savePrivate(i.certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})); err != nil {
		return err
	}
	state, _ := json.Marshal(diskState{ID: id})
	return savePrivate(i.statePath, state)
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
	mux := http.NewServeMux()
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
		defer i.mu.Unlock()
		if i.state.SelfPin == "" || i.state.SelfPin != fingerprint(r.TLS.PeerCertificates[0]) {
			http.Error(w, "not paired", http.StatusForbidden)
			return
		}
		writeJSON(w, map[string]string{"id": i.id, "person_id": i.state.PersonID, "status": "connected"})
	})
	return mux
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
			_, _ = w.Write([]byte("<!doctype html><title>Source</title><h1>connected</h1>"))
			return
		}
		_, _ = w.Write([]byte("<!doctype html><title>Source setup</title><meta http-equiv=refresh content=30><style>body{text-align:center;font:24px system-ui;margin:5vh auto}img{width:min(80vw,520px)}</style><h1>Pair Self with Source</h1><p>Scan this QR code in Self. It changes every two minutes.</p><img id='pairing-qr' alt='Pairing QR code' src='/qr.png'><script src='/setup.js'></script>"))
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
