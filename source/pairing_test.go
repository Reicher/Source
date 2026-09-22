package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func testClientCert(t *testing.T) tls.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Self"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

func testServer(t *testing.T, i *identity) *httptest.Server {
	t.Helper()
	cert, err := tls.LoadX509KeyPair(i.certPath, i.keyPath)
	if err != nil {
		t.Fatal(err)
	}
	s := httptest.NewUnstartedServer(i.lanHandler())
	s.TLS = i.tlsConfig()
	s.TLS.Certificates = []tls.Certificate{cert}
	s.StartTLS()
	t.Cleanup(s.Close)
	return s
}

func testHTTPClient(cert tls.Certificate) *http.Client {
	return &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true, Certificates: []tls.Certificate{cert}}}}
}

func postPair(t *testing.T, client *http.Client, url, id, token string) *http.Response {
	t.Helper()
	request, _ := json.Marshal(map[string]string{"id": id, "token": token})
	response, err := client.Post(url+"/v1/pair", "application/json", bytes.NewReader(request))
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	return response
}

func TestPairRestartAndTrustedReconnect(t *testing.T) {
	dir := t.TempDir()
	i, err := loadIdentity(dir)
	if err != nil {
		t.Fatal(err)
	}
	token, _, err := i.token()
	if err != nil {
		t.Fatal(err)
	}
	self := testHTTPClient(testClientCert(t))
	other := testHTTPClient(testClientCert(t))
	s := testServer(t, i)
	if got := postPair(t, self, s.URL, i.id, "wrong").StatusCode; got != http.StatusForbidden {
		t.Fatalf("wrong token: %d", got)
	}
	if got := postPair(t, self, s.URL, i.id, token).StatusCode; got != http.StatusOK {
		t.Fatalf("pair: %d", got)
	}
	setupRequest := httptest.NewRequest(http.MethodGet, "/", nil)
	setupRequest.Host = "127.0.0.1:8081"
	setupRequest.RemoteAddr = "127.0.0.1:12345"
	setupResponse := httptest.NewRecorder()
	i.setupHandler("127.0.0.1:8081").ServeHTTP(setupResponse, setupRequest)
	if !strings.Contains(setupResponse.Body.String(), "id='paired'") || strings.Contains(setupResponse.Body.String(), "connected") {
		t.Fatal("paired Source did not show its text-free status")
	}
	if got := postPair(t, other, s.URL, i.id, token).StatusCode; got != http.StatusConflict {
		t.Fatalf("second Self: %d", got)
	}
	if _, _, err := i.token(); err == nil {
		t.Fatal("paired Source returned QR token")
	}
	oldPin := i.certPin
	s.Close()
	i, err = loadIdentity(dir)
	if err != nil {
		t.Fatal(err)
	}
	if i.certPin != oldPin {
		t.Fatal("Source certificate changed on restart")
	}
	s = testServer(t, i)
	response, err := self.Get(s.URL + "/v1/status")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("trusted reconnect: %d", response.StatusCode)
	}
	response, err = other.Get(s.URL + "/v1/status")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusForbidden {
		t.Fatalf("untrusted reconnect: %d", response.StatusCode)
	}
}

func TestExpiredQRAndLocalSetup(t *testing.T) {
	i, err := loadIdentity(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	token, _, err := i.token()
	if err != nil {
		t.Fatal(err)
	}
	i.qrExpires = time.Now().Add(-time.Second)
	s := testServer(t, i)
	if got := postPair(t, testHTTPClient(testClientCert(t)), s.URL, i.id, token).StatusCode; got != http.StatusForbidden {
		t.Fatalf("expired QR: %d", got)
	}
	setup := i.setupHandler("127.0.0.1:8081")
	request := httptest.NewRequest(http.MethodGet, "/qr.png", nil)
	request.Host = "127.0.0.1:8081"
	request.RemoteAddr = "192.168.1.5:12345"
	response := httptest.NewRecorder()
	setup.ServeHTTP(response, request)
	if response.Code != http.StatusForbidden {
		t.Fatalf("remote setup: %d", response.Code)
	}
	request.RemoteAddr = "127.0.0.1:12345"
	response = httptest.NewRecorder()
	setup.ServeHTTP(response, request)
	if response.Code != http.StatusOK || response.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("local QR: %d", response.Code)
	}
	scriptRequest := httptest.NewRequest(http.MethodGet, "/setup.js", nil)
	scriptRequest.Host = "127.0.0.1:8081"
	scriptRequest.RemoteAddr = "127.0.0.1:12345"
	scriptResponse := httptest.NewRecorder()
	setup.ServeHTTP(scriptResponse, scriptRequest)
	if scriptResponse.Code != http.StatusOK || !strings.Contains(scriptResponse.Body.String(), "X-QR-Expires-In-Ms") {
		t.Fatal("setup script does not schedule QR rotation from its expiration")
	}
	if !strings.Contains(scriptResponse.Header().Get("Content-Security-Policy"), "connect-src 'self'") {
		t.Fatal("setup policy blocks its QR and pairing status requests")
	}
	remaining, err := strconv.ParseInt(response.Header().Get("X-QR-Expires-In-Ms"), 10, 64)
	if err != nil || remaining <= 0 || remaining > qrLifetime.Milliseconds() {
		t.Fatalf("invalid QR expiry header: %q", response.Header().Get("X-QR-Expires-In-Ms"))
	}
	newToken, _, err := i.token()
	if err != nil || newToken == token {
		t.Fatalf("expired QR was not rotated: %v", err)
	}
	if got := postPair(t, testHTTPClient(testClientCert(t)), s.URL, i.id, newToken).StatusCode; got != http.StatusOK {
		t.Fatalf("rotated QR: %d", got)
	}
	request.Host = "attacker.invalid"
	response = httptest.NewRecorder()
	setup.ServeHTTP(response, request)
	if response.Code != http.StatusForbidden {
		t.Fatalf("rebound host: %d", response.Code)
	}
}

func TestPartialIdentityFailsClosed(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "source.key"), []byte("partial"), 0600); err != nil {
		t.Fatal(err)
	}
	_, err := loadIdentity(dir)
	if err == nil || !strings.Contains(err.Error(), "incomplete") {
		t.Fatalf("partial identity: %v", err)
	}
}

func TestFreshIdentityActivatesTogether(t *testing.T) {
	parent := t.TempDir()
	dir := filepath.Join(parent, "pairing")
	// An interrupted staging attempt must not block the next first start.
	if err := os.Mkdir(filepath.Join(parent, ".source-identity-abandoned"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(parent, ".source-identity-abandoned", "source.key"), []byte("partial"), 0600); err != nil {
		t.Fatal(err)
	}
	i, err := loadIdentity(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"source.key", "source.crt", "state.json"} {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			t.Fatalf("activated identity missing %s: %v", name, err)
		}
	}
	reloaded, err := loadIdentity(dir)
	if err != nil || reloaded.id != i.id || reloaded.certPin != i.certPin {
		t.Fatalf("identity changed after restart: %v", err)
	}
	// A previously created empty identity directory is also safe to replace.
	empty := filepath.Join(parent, "empty")
	if err := os.Mkdir(empty, 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := loadIdentity(empty); err != nil {
		t.Fatalf("empty identity directory: %v", err)
	}
}
