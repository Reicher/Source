package integration_test

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"source.local/node/internal/admin"
	localai "source.local/node/internal/ai"
	"source.local/node/internal/config"
	"source.local/node/internal/database"
	"source.local/node/internal/httpapi"
	"source.local/node/internal/pairing"
	"source.local/node/internal/security"
)

type fakeAI struct{ received []localai.Message }

func (f *fakeAI) Status(context.Context) bool { return true }
func (f *fakeAI) Capabilities() map[string]any {
	return map[string]any{"contractVersion": 1, "modalities": []string{"text"}, "streaming": true, "cancellation": true, "maximumContextTokens": 8192, "promptPolicy": "none-v1", "reasoning": "off"}
}
func (f *fakeAI) StreamChat(_ context.Context, m []localai.Message, yield func(localai.Event) error) error {
	f.received = m
	if e := yield(localai.Event{Type: "delta", Text: "Lokalt "}); e != nil {
		return e
	}
	if e := yield(localai.Event{Type: "delta", Text: "answer"}); e != nil {
		return e
	}
	return yield(localai.Event{Type: "completed", FinishReason: "stop"})
}

func TestSourceAPIEndToEnd(t *testing.T) {
	root := t.TempDir()
	now := time.UnixMilli(1_800_000_000_000)
	cfg := config.Config{
		Host: "127.0.0.1", Port: 8080, HTTPSPort: 8443,
		AdminHost: "127.0.0.1", AdminPort: 9090,
		AdminSessionTTL: 8 * time.Hour, PairingInvitationTTL: 5 * time.Minute,
		PairingBaseURL:           "https://192.168.1.10:8443/api/v1/pairing",
		PairingCACertificatePath: filepath.Join("testdata", "source-test-ca.crt"),
		SuggestedNodeName:        "test-node",
		DatabasePath:             filepath.Join(root, "state", "source-node.sqlite"),
		StorageRoot:              filepath.Join(root, "vaults"),
		MaximumSnapshotBytes:     1024, SnapshotRetention: 20,
		AllowedStorageApps: map[string]struct{}{"thoughts": {}, "source-client": {}},
		LlamaModel:         "source-qwen3.5-9b", LlamaTimeout: time.Second,
		Now: func() time.Time { return now },
	}
	if e := os.MkdirAll(cfg.StorageRoot, 0700); e != nil {
		t.Fatal(e)
	}
	db, e := database.Open(cfg.DatabasePath)
	if e != nil {
		t.Fatal(e)
	}
	defer func() { _ = db.Close() }()
	ai := &fakeAI{}
	pairs := pairing.New(db, cfg)
	logger := log.New(io.Discard, "", 0)
	api := httptest.NewServer(httpapi.New(db, cfg, ai, pairs, logger))
	defer api.Close()
	adm := httptest.NewServer(admin.New(db, cfg, ai, pairs, logger))
	defer adm.Close()

	state := request(t, http.MethodGet, adm.URL+"/admin/api/state", nil, nil)
	wantStatus(t, state, 200)
	varBody(t, state.Body, map[string]any{"initialized": false, "authenticated": false, "suggestedNodeName": "test-node"})
	initialize := request(t, http.MethodPost, adm.URL+"/admin/api/initialize", map[string]string{"Content-Type": "application/json", "Origin": adm.URL}, []byte(`{"displayName":"Source at home","password":"correct horse source battery","passwordConfirmation":"correct horse source battery"}`))
	wantStatus(t, initialize, 201)
	initialize.Body.Close()
	login := request(t, http.MethodPost, adm.URL+"/admin/api/login", map[string]string{"Content-Type": "application/json", "Origin": adm.URL}, []byte(`{"password":"correct horse source battery"}`))
	wantStatus(t, login, 200)
	var logged map[string]any
	decode(t, login.Body, &logged)
	cookie := strings.Split(login.Header.Get("Set-Cookie"), ";")[0]
	csrf := logged["csrfToken"].(string)
	invite := request(t, http.MethodPost, adm.URL+"/admin/api/pairing-invitations", map[string]string{"Content-Type": "application/json", "Origin": adm.URL, "Cookie": cookie, "X-Source-Csrf": csrf}, []byte(`{"quotaBytes":5368709120}`))
	wantStatus(t, invite, 201)
	var invitationResult struct {
		Invitation map[string]any `json:"invitation"`
	}
	decode(t, invite.Body, &invitationResult)
	payload, e := url.Parse(invitationResult.Invitation["payload"].(string))
	if e != nil {
		t.Fatal(e)
	}
	qr := request(t, http.MethodGet, adm.URL+"/admin/api/pairing-invitations/"+invitationResult.Invitation["id"].(string)+"/qr.svg", map[string]string{"Cookie": cookie}, nil)
	wantStatus(t, qr, 200)
	qrBody, _ := io.ReadAll(qr.Body)
	qr.Body.Close()
	if !bytes.Contains(qrBody, []byte("<svg")) {
		t.Fatal("admin QR is not SVG")
	}

	clientPublic, clientPrivate, e := ed25519.GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	clientDER, e := x509.MarshalPKIXPublicKey(clientPublic)
	if e != nil {
		t.Fatal(e)
	}
	startBody := map[string]any{"protocol": 1, "invitationId": payload.Query().Get("invite"), "invitationSecret": payload.Query().Get("secret"), "clientPublicKey": base64.RawURLEncoding.EncodeToString(clientDER), "userDisplayName": "Robin", "clientDisplayName": "Testtelefon"}
	started := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/pairing/start", nil, startBody)
	wantStatus(t, started, 200)
	var challenge map[string]any
	decode(t, started.Body, &challenge)
	nodeDER, e := base64.RawURLEncoding.DecodeString(payload.Query().Get("node_key"))
	if e != nil {
		t.Fatal(e)
	}
	parsed, e := x509.ParsePKIXPublicKey(nodeDER)
	if e != nil {
		t.Fatal(e)
	}
	nodePublic := parsed.(ed25519.PublicKey)
	nodeSignature, _ := base64.RawURLEncoding.DecodeString(challenge["nodeSignature"].(string))
	if !ed25519.Verify(nodePublic, []byte(challenge["signingPayload"].(string)), nodeSignature) {
		t.Fatal("pairing challenge Node signature is invalid")
	}
	proof := ed25519.Sign(clientPrivate, []byte(challenge["signingPayload"].(string)))
	completeBody := map[string]any{"protocol": 1, "invitationId": payload.Query().Get("invite"), "invitationSecret": payload.Query().Get("secret"), "handshakeId": challenge["handshakeId"], "signature": base64.RawURLEncoding.EncodeToString(proof), "recoveryKey": strings.Repeat("r", 43), "recoveryEnvelope": strings.Repeat("e", 80)}
	completed := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/pairing/complete", nil, completeBody)
	wantStatus(t, completed, 201)
	var paired map[string]any
	decode(t, completed.Body, &paired)
	credential := paired["clientCredential"].(string)
	replay := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/pairing/complete", nil, completeBody)
	wantStatus(t, replay, 404)
	wantErrorCode(t, replay.Body, "pairing_unavailable")

	unauthenticatedMe := request(t, http.MethodGet, api.URL+"/api/v1/me", nil, nil)
	wantStatus(t, unauthenticatedMe, 401)
	wantErrorCode(t, unauthenticatedMe.Body, "authentication_required")
	me := request(t, http.MethodGet, api.URL+"/api/v1/me", map[string]string{"Authorization": "Bearer " + credential}, nil)
	wantStatus(t, me, 200)
	var meBody map[string]any
	decode(t, me.Body, &meBody)
	if meBody["user"].(map[string]any)["displayName"] != "Robin" {
		t.Fatal("paired user identity changed")
	}
	nonce, _ := security.GenerateToken()
	identity := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/identity/challenge", map[string]string{"Authorization": "Bearer " + credential}, map[string]any{"protocol": 1, "nonce": nonce})
	wantStatus(t, identity, 200)
	var identityBody map[string]any
	decode(t, identity.Body, &identityBody)
	signature, _ := base64.RawURLEncoding.DecodeString(identityBody["nodeSignature"].(string))
	if !ed25519.Verify(nodePublic, []byte(identityBody["signingPayload"].(string)), signature) {
		t.Fatal("reconnect identity proof is invalid")
	}

	runID, _ := security.UUID()
	stream := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/ai/stream", map[string]string{"Authorization": "Bearer " + credential}, map[string]any{"contractVersion": 1, "runId": runID, "conversationId": "conversation-test", "messages": []any{map[string]any{"role": "user", "content": []any{map[string]any{"type": "text", "text": "Hello"}}}}})
	wantStatus(t, stream, 200)
	streamBody, _ := io.ReadAll(stream.Body)
	stream.Body.Close()
	lines := strings.Split(strings.TrimSpace(string(streamBody)), "\n")
	if len(lines) != 4 || !strings.Contains(lines[1], `"text":"Lokalt "`) || !strings.Contains(lines[3], `"type":"completed"`) {
		t.Fatalf("unexpected NDJSON stream: %s", streamBody)
	}

	snapshotID, _ := security.UUID()
	ciphertext := bytes.Repeat([]byte{7}, 96)
	sum := sha256.Sum256(ciphertext)
	snapshotURL := api.URL + "/api/v1/storage/source-client/snapshots/" + snapshotID
	upload := request(t, http.MethodPut, snapshotURL, map[string]string{"Authorization": "Bearer " + credential, "Content-Type": "application/octet-stream", "X-Content-SHA256": hex.EncodeToString(sum[:])}, ciphertext)
	wantStatus(t, upload, 201)
	upload.Body.Close()
	latest := request(t, http.MethodGet, api.URL+"/api/v1/storage/source-client/snapshots/latest", map[string]string{"Authorization": "Bearer " + credential}, nil)
	wantStatus(t, latest, 200)
	restored, _ := io.ReadAll(latest.Body)
	latest.Body.Close()
	if !bytes.Equal(restored, ciphertext) || latest.Header.Get("X-Snapshot-Id") != snapshotID {
		t.Fatal("snapshot round trip changed data or metadata")
	}

	userID := paired["user"].(map[string]any)["id"].(string)
	recoveryInvite := request(t, http.MethodPost, adm.URL+"/admin/api/users/"+userID+"/recovery-invitations", map[string]string{"Origin": adm.URL, "Cookie": cookie, "X-Source-Csrf": csrf}, nil)
	wantStatus(t, recoveryInvite, 201)
	var recoveryResult struct {
		Invitation map[string]any `json:"invitation"`
	}
	decode(t, recoveryInvite.Body, &recoveryResult)
	recoveryPayload, e := url.Parse(recoveryResult.Invitation["payload"].(string))
	if e != nil {
		t.Fatal(e)
	}
	newPublic, newPrivate, e := ed25519.GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	newDER, e := x509.MarshalPKIXPublicKey(newPublic)
	if e != nil {
		t.Fatal(e)
	}
	recoveryStart := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/pairing/start", nil, map[string]any{"protocol": 1, "invitationId": recoveryPayload.Query().Get("invite"), "invitationSecret": recoveryPayload.Query().Get("secret"), "clientPublicKey": base64.RawURLEncoding.EncodeToString(newDER), "userDisplayName": "Robin", "clientDisplayName": "New phone"})
	wantStatus(t, recoveryStart, 200)
	var recoveryChallenge map[string]any
	decode(t, recoveryStart.Body, &recoveryChallenge)
	recoveryProof := ed25519.Sign(newPrivate, []byte(recoveryChallenge["signingPayload"].(string)))
	recoveryComplete := map[string]any{"protocol": 1, "invitationId": recoveryPayload.Query().Get("invite"), "invitationSecret": recoveryPayload.Query().Get("secret"), "handshakeId": recoveryChallenge["handshakeId"], "signature": base64.RawURLEncoding.EncodeToString(recoveryProof), "recoveryKey": strings.Repeat("r", 43)}
	wrongRecovery := mapsClone(recoveryComplete)
	wrongRecovery["recoveryKey"] = strings.Repeat("x", 43)
	wrong := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/pairing/complete", nil, wrongRecovery)
	wantStatus(t, wrong, 401)
	wrong.Body.Close()
	recovered := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/pairing/complete", nil, recoveryComplete)
	wantStatus(t, recovered, 201)
	var recoveredBody map[string]any
	decode(t, recovered.Body, &recoveredBody)
	newCredential := recoveredBody["clientCredential"].(string)
	oldMe := request(t, http.MethodGet, api.URL+"/api/v1/me", map[string]string{"Authorization": "Bearer " + credential}, nil)
	wantStatus(t, oldMe, 401)
	oldMe.Body.Close()
	recoveredLatest := request(t, http.MethodGet, api.URL+"/api/v1/storage/source-client/snapshots/latest", map[string]string{"Authorization": "Bearer " + newCredential}, nil)
	wantStatus(t, recoveredLatest, 200)
	recoveredSnapshot, _ := io.ReadAll(recoveredLatest.Body)
	recoveredLatest.Body.Close()
	if !bytes.Equal(recoveredSnapshot, ciphertext) {
		t.Fatal("recovery did not preserve snapshot storage")
	}

	if e = db.Close(); e != nil {
		t.Fatal(e)
	}
	db, e = database.Open(cfg.DatabasePath)
	if e != nil {
		t.Fatal(e)
	}
	users, e := db.ListUsers()
	if e != nil || len(users) != 1 || users[0].DisplayName != "Robin" {
		t.Fatalf("persistent SQLite state was not preserved: %#v %v", users, e)
	}
	if _, e = os.Stat(filepath.Join(cfg.StorageRoot, users[0].StorageNamespace, "source-client", "snapshots", snapshotID+".bin")); e != nil {
		t.Fatal(e)
	}
}

func request(t *testing.T, method, target string, headers map[string]string, body []byte) *http.Response {
	t.Helper()
	req, e := http.NewRequest(method, target, bytes.NewReader(body))
	if e != nil {
		t.Fatal(e)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, e := http.DefaultClient.Do(req)
	if e != nil {
		t.Fatal(e)
	}
	return res
}
func jsonRequest(t *testing.T, method, target string, headers map[string]string, value any) *http.Response {
	t.Helper()
	body, e := json.Marshal(value)
	if e != nil {
		t.Fatal(e)
	}
	if headers == nil {
		headers = map[string]string{}
	}
	headers["Content-Type"] = "application/json"
	return request(t, method, target, headers, body)
}
func wantStatus(t *testing.T, res *http.Response, want int) {
	t.Helper()
	if res.StatusCode != want {
		body, _ := io.ReadAll(res.Body)
		res.Body.Close()
		t.Fatalf("HTTP %d, want %d: %s", res.StatusCode, want, body)
	}
}
func wantErrorCode(t *testing.T, body io.ReadCloser, expected string) {
	t.Helper()
	defer body.Close()
	var envelope struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	if e := json.NewDecoder(body).Decode(&envelope); e != nil {
		t.Fatal(e)
	}
	if envelope.Error.Code != expected {
		t.Fatalf("expected error code %q, got %q", expected, envelope.Error.Code)
	}
}
func decode(t *testing.T, r io.ReadCloser, value any) {
	t.Helper()
	defer r.Close()
	if e := json.NewDecoder(r).Decode(value); e != nil {
		t.Fatal(e)
	}
}
func varBody(t *testing.T, r io.ReadCloser, want any) {
	t.Helper()
	var actual any
	decode(t, r, &actual)
	a, _ := json.Marshal(actual)
	w, _ := json.Marshal(want)
	if !bytes.Equal(a, w) {
		t.Fatalf("JSON %s, want %s", a, w)
	}
}

func mapsClone(value map[string]any) map[string]any {
	clone := make(map[string]any, len(value))
	for key, item := range value {
		clone[key] = item
	}
	return clone
}
