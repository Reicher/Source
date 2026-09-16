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
	"strconv"
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
	"source.local/node/internal/syncmodel"
)

type fakeAI struct{ received []localai.Message }

func (f *fakeAI) Status(context.Context) bool { return true }
func (f *fakeAI) State(context.Context) localai.RuntimeState {
	return localai.RuntimeState{Availability: "ready", Capabilities: f.Capabilities()}
}
func (f *fakeAI) Capabilities() map[string]any {
	return map[string]any{"contractVersion": 1, "modelId": "source-test-model", "parameterCount": int64(9_000_000_000), "modalities": []string{"text"}, "streaming": true, "cancellation": true, "maximumContextTokens": 8192, "promptPolicy": "none-v1", "reasoning": "off"}
}
func (f *fakeAI) StreamChat(_ context.Context, m []localai.Message, _ localai.ChatOptions, yield func(localai.Event) error) error {
	f.received = m
	if len(m) == 1 && strings.Contains(m[0].Content, "Bronze text:") {
		if e := yield(localai.Event{Type: "delta", Text: `{"entities":[{"key":"e1","name":"Source","type":"project"}],"claims":[{"subjectKey":"e1","predicate":"status","value":"active","confidence":0.9,"evidenceExcerpt":"Source is active"}]}`}); e != nil {
			return e
		}
		return yield(localai.Event{Type: "completed", FinishReason: "stop"})
	}
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
		MaximumSnapshotBytes:     64 * 1024, SnapshotRetention: 20,
		MaximumLibraryItemBytes: 1024,
		AllowedStorageApps:      map[string]struct{}{"thoughts": {}, "source-client": {}},
		AIModel:                 "source-qwen3.5-9b", AITimeout: time.Second,
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
	apiHandler := httpapi.New(db, cfg, ai, pairs, logger)
	defer apiHandler.Close()
	api := httptest.NewServer(apiHandler)
	defer api.Close()
	adm := httptest.NewServer(admin.New(db, cfg, ai, pairs, logger))
	defer adm.Close()
	status := request(t, http.MethodGet, api.URL+"/api/v1/status", nil, nil)
	wantStatus(t, status, 200)
	var statusBody map[string]any
	decode(t, status.Body, &statusBody)
	aiRuntime := statusBody["aiRuntime"].(map[string]any)
	if aiRuntime["availability"] != "ready" {
		t.Fatalf("unexpected AI runtime state: %#v", aiRuntime)
	}

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
	userID := paired["user"].(map[string]any)["id"].(string)
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
	selfEntityID := meBody["user"].(map[string]any)["selfEntityId"].(string)
	if paired["user"].(map[string]any)["selfEntityId"] != selfEntityID || !syncmodel.ValidUUID(selfEntityID) {
		t.Fatal("paired user Self entity is missing or unstable")
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

	bronzeText := "Source is active"
	bronzeHash := sha256.Sum256([]byte(bronzeText))
	refinementOperation, _ := security.UUID()
	refinementBody := map[string]any{
		"contractVersion": 1, "operationId": refinementOperation,
		"source": map[string]any{
			"id": "source-1", "name": "source.txt", "sourceType": "file",
			"contentSha256": hex.EncodeToString(bronzeHash[:]), "text": bronzeText,
		},
	}
	refined := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/silver/refinements", map[string]string{"Authorization": "Bearer " + credential}, refinementBody)
	wantStatus(t, refined, 202)
	var refinedBody map[string]any
	decode(t, refined.Body, &refinedBody)
	if refinedBody["bronzeAccepted"] != true {
		t.Fatalf("unexpected Silver refinement result: %#v", refinedBody)
	}
	jobID := refinedBody["job"].(map[string]any)["id"].(string)
	deadline := time.Now().Add(3 * time.Second)
	for {
		jobResponse := request(t, http.MethodGet, api.URL+"/api/v1/silver/refinements/"+jobID, map[string]string{"Authorization": "Bearer " + credential}, nil)
		wantStatus(t, jobResponse, 200)
		var job map[string]any
		decode(t, jobResponse.Body, &job)
		if job["state"] == "completed" {
			break
		}
		if job["state"] == "failed" || time.Now().After(deadline) {
			t.Fatalf("Silver job did not complete: %#v", job)
		}
		time.Sleep(time.Millisecond)
	}
	retriedRefinement := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/silver/refinements", map[string]string{"Authorization": "Bearer " + credential}, refinementBody)
	wantStatus(t, retriedRefinement, 200)
	decode(t, retriedRefinement.Body, &refinedBody)
	if refinedBody["job"].(map[string]any)["id"] != jobID || refinedBody["job"].(map[string]any)["state"] != "completed" {
		t.Fatalf("Silver retry created a competing revision: %#v", refinedBody)
	}
	silverChanges := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/sync/changes", map[string]string{"Authorization": "Bearer " + credential}, map[string]any{
		"contractVersion": 1, "collection": "silver-datasets",
		"objectId": "ab385774-7835-3e2c-afce-ad2702c8f8cb",
	})
	wantStatus(t, silverChanges, 200)
	var silverManifest struct {
		Heads []syncmodel.Revision `json:"heads"`
	}
	decode(t, silverChanges.Body, &silverManifest)
	if len(silverManifest.Heads) != 1 {
		t.Fatalf("authoritative Silver heads = %#v", silverManifest.Heads)
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

	canonicalBody := []byte(`{"version":3,"conversations":[],"tombstones":[]}`)
	canonicalSum := sha256.Sum256(canonicalBody)
	createdAt := now.UnixMilli()
	canonicalRevision := syncmodel.Revision{
		ObjectKey: syncmodel.ObjectKey{
			ProfileID: userID, Collection: "conversations",
			ObjectID: "22222222-2222-4222-8222-222222222222",
		},
		Kind: "content", ParentRevisionIDs: []string{},
		Payload: &syncmodel.Payload{
			Format: "source-client-conversation", FormatVersion: 3,
			ByteCount: int64(len(canonicalBody)), PlaintextSHA256: hex.EncodeToString(canonicalSum[:]),
		},
		CreatedAtMillis: &createdAt,
	}
	canonicalRevision.RevisionID, e = syncmodel.RevisionID(canonicalRevision)
	if e != nil {
		t.Fatal(e)
	}
	canonicalOperation, _ := security.UUID()
	canonicalEpoch, _ := security.UUID()
	emptyManifest := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/sync/changes", map[string]string{"Authorization": "Bearer " + credential}, map[string]any{
		"contractVersion": 1, "collection": "conversations", "objectId": canonicalRevision.ObjectKey.ObjectID,
	})
	wantStatus(t, emptyManifest, 200)
	var emptyManifestBody syncmodel.ChangesResponse
	decode(t, emptyManifest.Body, &emptyManifestBody)
	if emptyManifestBody.Heads == nil || emptyManifestBody.Changes == nil || len(emptyManifestBody.Heads) != 0 {
		t.Fatalf("empty manifest must use empty arrays: %#v", emptyManifestBody)
	}
	canonicalURL := api.URL + "/api/v1/sync/mutations/" + canonicalOperation
	canonicalHeaders := map[string]string{
		"Authorization": "Bearer " + credential, "Content-Type": "application/octet-stream",
		"X-Source-Contract-Version": "1", "X-Source-Origin-Epoch": canonicalEpoch,
		"X-Source-Origin-Sequence": "1", "X-Source-Collection": "conversations",
		"X-Source-Object-Id":   canonicalRevision.ObjectKey.ObjectID,
		"X-Source-Revision-Id": canonicalRevision.RevisionID, "X-Source-Revision-Kind": "content",
		"X-Source-Payload-Format":         canonicalRevision.Payload.Format,
		"X-Source-Payload-Format-Version": "3", "X-Source-Byte-Count": strconv.Itoa(len(canonicalBody)),
		"X-Source-Plaintext-SHA256":  canonicalRevision.Payload.PlaintextSHA256,
		"X-Source-Created-At-Millis": strconv.FormatInt(createdAt, 10),
	}
	canonicalCommit := request(t, http.MethodPost, canonicalURL, canonicalHeaders, canonicalBody)
	wantStatus(t, canonicalCommit, 201)
	var commitResult struct {
		Receipt syncmodel.CommitReceipt `json:"receipt"`
		Heads   []syncmodel.Revision    `json:"heads"`
	}
	decode(t, canonicalCommit.Body, &commitResult)
	if commitResult.Receipt.RevisionID != canonicalRevision.RevisionID || commitResult.Receipt.CommitSequence != 2 || len(commitResult.Heads) != 1 {
		t.Fatalf("unexpected canonical commit: %#v", commitResult)
	}
	canonicalRetry := request(t, http.MethodPost, canonicalURL, canonicalHeaders, canonicalBody)
	wantStatus(t, canonicalRetry, 200)
	canonicalRetry.Body.Close()

	changes := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/sync/changes", map[string]string{"Authorization": "Bearer " + credential}, map[string]any{
		"contractVersion": 1, "collection": "conversations", "objectId": canonicalRevision.ObjectKey.ObjectID,
	})
	wantStatus(t, changes, 200)
	var changesResult syncmodel.ChangesResponse
	decode(t, changes.Body, &changesResult)
	if !changesResult.RequiresManifest || changesResult.Cursor.CommitSequence != 2 || len(changesResult.Heads) != 1 {
		t.Fatalf("unexpected canonical manifest: %#v", changesResult)
	}
	canonicalPayload := request(t, http.MethodGet, api.URL+"/api/v1/sync/revisions/"+canonicalRevision.RevisionID+"/payload", map[string]string{"Authorization": "Bearer " + credential}, nil)
	wantStatus(t, canonicalPayload, 200)
	canonicalRestored, _ := io.ReadAll(canonicalPayload.Body)
	canonicalPayload.Body.Close()
	if !bytes.Equal(canonicalRestored, canonicalBody) {
		t.Fatal("canonical payload round trip changed bytes")
	}
	ack := jsonRequest(t, http.MethodPost, api.URL+"/api/v1/sync/ack", map[string]string{"Authorization": "Bearer " + credential}, map[string]any{
		"contractVersion": 1, "collection": "conversations",
		"objectId": canonicalRevision.ObjectKey.ObjectID, "cursor": changesResult.Cursor,
	})
	wantStatus(t, ack, 200)
	ack.Body.Close()

	libraryItemID, _ := security.UUID()
	libraryContentHash := strings.Repeat("a", 64)
	encryptedItem := bytes.Repeat([]byte{9}, 64)
	encryptedItemSum := sha256.Sum256(encryptedItem)
	libraryURL := api.URL + "/api/v1/library/items/" + libraryItemID
	libraryHeaders := map[string]string{
		"Authorization":           "Bearer " + credential,
		"Content-Type":            "application/octet-stream",
		"X-Source-Content-SHA256": libraryContentHash,
		"X-Content-SHA256":        hex.EncodeToString(encryptedItemSum[:]),
	}
	libraryUpload := request(t, http.MethodPut, libraryURL, libraryHeaders, encryptedItem)
	wantStatus(t, libraryUpload, 201)
	libraryUpload.Body.Close()
	libraryRetry := request(t, http.MethodPut, libraryURL, libraryHeaders, encryptedItem)
	wantStatus(t, libraryRetry, 200)
	libraryRetry.Body.Close()
	libraryDelete := request(t, http.MethodDelete, libraryURL, map[string]string{
		"Authorization": "Bearer " + credential, "X-Source-Content-SHA256": libraryContentHash,
	}, nil)
	wantStatus(t, libraryDelete, 204)
	libraryDelete.Body.Close()
	delayedRetry := request(t, http.MethodPut, libraryURL, libraryHeaders, encryptedItem)
	wantStatus(t, delayedRetry, 409)
	wantErrorCode(t, delayedRetry.Body, "library_item_deleted")
	pendingItemID, _ := security.UUID()
	pendingURL := api.URL + "/api/v1/library/items/" + pendingItemID
	pendingDelete := request(t, http.MethodDelete, pendingURL, map[string]string{
		"Authorization": "Bearer " + credential, "X-Source-Content-SHA256": strings.Repeat("b", 64),
	}, nil)
	wantStatus(t, pendingDelete, 204)
	pendingDelete.Body.Close()
	pendingHeaders := map[string]string{
		"Authorization":           libraryHeaders["Authorization"],
		"Content-Type":            libraryHeaders["Content-Type"],
		"X-Source-Content-SHA256": strings.Repeat("b", 64),
		"X-Content-SHA256":        libraryHeaders["X-Content-SHA256"],
	}
	pendingUpload := request(t, http.MethodPut, pendingURL, pendingHeaders, encryptedItem)
	wantStatus(t, pendingUpload, 409)
	wantErrorCode(t, pendingUpload.Body, "library_item_deleted")

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
