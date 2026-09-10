package pairing

import (
	"crypto/ed25519"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
	"unicode"

	"source.local/node/internal/apperror"
	"source.local/node/internal/config"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
)

const ProtocolVersion = 1

var rawURL = base64.RawURLEncoding

type Service struct {
	db     *database.DB
	cfg    config.Config
	mu     sync.Mutex
	active *invitation
	last   map[string]any
}
type invitation struct {
	id, secret, secretHash, kind, userID, userDisplayName, caCertificate string
	quota                                                                int64
	created, expires                                                     time.Time
	failed                                                               int
	handshakes                                                           map[string]*handshake
}
type handshake struct {
	id, clientID, clientPublicKey, userDisplayName, clientDisplayName, signingPayload string
	key                                                                               ed25519.PublicKey
}
type StartRequest struct {
	Protocol          int    `json:"protocol"`
	InvitationID      string `json:"invitationId"`
	InvitationSecret  string `json:"invitationSecret"`
	ClientPublicKey   string `json:"clientPublicKey"`
	UserDisplayName   string `json:"userDisplayName"`
	ClientDisplayName string `json:"clientDisplayName"`
}
type CompleteRequest struct {
	Protocol         int    `json:"protocol"`
	InvitationID     string `json:"invitationId"`
	InvitationSecret string `json:"invitationSecret"`
	HandshakeID      string `json:"handshakeId"`
	Signature        string `json:"signature"`
	RecoveryKey      string `json:"recoveryKey"`
	RecoveryEnvelope string `json:"recoveryEnvelope"`
}

func New(db *database.DB, cfg config.Config) *Service { return &Service{db: db, cfg: cfg} }
func (s *Service) CreateInvitation(quota int64) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.db.IsInitialized() {
		return nil, apperror.New(409, "node_not_initialized", "Node is not initialized.")
	}
	s.expire()
	if s.active != nil {
		return nil, apperror.New(409, "invitation_already_active", "A pairing invitation is already active.")
	}
	if quota < 64*1024*1024 || quota > 16*1024*1024*1024*1024 {
		return nil, apperror.New(400, "invalid_quota", "Quota must be between 64 MiB and 16 TiB.")
	}
	return s.create("create", "", "", quota)
}
func (s *Service) CreateRecoveryInvitation(userID string) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.db.IsInitialized() {
		return nil, apperror.New(409, "node_not_initialized", "Node is not initialized.")
	}
	u, e := s.db.FindUser(userID)
	if e != nil {
		return nil, e
	}
	if u == nil || u.DisabledAt != nil {
		return nil, apperror.New(404, "user_not_found", "The user was not found.")
	}
	if u.RecoveryKeyHash == nil || u.RecoveryEnvelope == nil {
		return nil, apperror.New(409, "recovery_not_configured", "The user has no recovery key.")
	}
	s.expire()
	if s.active != nil {
		return nil, apperror.New(409, "invitation_already_active", "A pairing invitation is already active.")
	}
	return s.create("recover", u.ID, u.DisplayName, u.QuotaBytes)
}
func (s *Service) GetInvitation() (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expire()
	if s.active == nil {
		return s.last, nil
	}
	return s.public()
}
func (s *Service) CancelInvitation(id string) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.expire()
	if s.active == nil || s.active.id != id {
		return nil, apperror.New(404, "invitation_not_found", "No active invitation was found.")
	}
	s.active = nil
	s.last = map[string]any{"id": id, "state": "cancelled"}
	return s.last, nil
}
func (s *Service) CancelUserInvitation(userID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.active != nil && s.active.userID == userID {
		id := s.active.id
		s.active = nil
		s.last = map[string]any{"id": id, "state": "cancelled"}
	}
}

func (s *Service) Start(body StartRequest) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if body.Protocol != ProtocolVersion {
		return nil, unsupported()
	}
	inv, e := s.authorize(body.InvitationID, body.InvitationSecret)
	if e != nil {
		return nil, e
	}
	userName, e := name(body.UserDisplayName, "user_display_name")
	if e != nil {
		return nil, e
	}
	clientName, e := name(body.ClientDisplayName, "client_display_name")
	if e != nil {
		return nil, e
	}
	key, der, canonical, e := security.ParseEd25519PublicKey(body.ClientPublicKey)
	if e != nil {
		return nil, apperror.New(400, "invalid_client_public_key", "The client public key is invalid.")
	}
	clientID := security.ClientIDFromPublicKey(der)
	existing, e := s.db.FindClientByID(clientID)
	if e != nil {
		return nil, e
	}
	if existing != nil {
		return nil, apperror.New(409, "duplicate_client", "This client identity is already paired.")
	}
	handshakeID, e := security.UUID()
	if e != nil {
		return nil, e
	}
	challenge, e := security.GenerateToken()
	if e != nil {
		return nil, e
	}
	node, e := s.db.GetNodeState(true)
	if e != nil {
		return nil, e
	}
	payload := strings.Join([]string{"source-pairing-v1", node.NodeID, inv.id, handshakeID, challenge, clientID, rawURL.EncodeToString([]byte(userName)), rawURL.EncodeToString([]byte(clientName))}, "\n")
	inv.handshakes = map[string]*handshake{handshakeID: {id: handshakeID, clientID: clientID, clientPublicKey: canonical, userDisplayName: userName, clientDisplayName: clientName, signingPayload: payload, key: key}}
	private, e := security.LoadNodePrivateKey(node.PrivateKey)
	if e != nil {
		return nil, e
	}
	signature := ed25519.Sign(private, []byte(payload))
	return map[string]any{"protocol": 1, "handshakeId": handshakeID, "challenge": challenge, "signingPayload": payload, "nodeSignature": rawURL.EncodeToString(signature), "expiresAt": iso(inv.expires)}, nil
}

func (s *Service) Complete(body CompleteRequest) (map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if body.Protocol != 1 {
		return nil, apperror.New(400, "unsupported_pairing_protocol", "Unsupported pairing protocol version.")
	}
	inv, e := s.authorize(body.InvitationID, body.InvitationSecret)
	if e != nil {
		return nil, e
	}
	h := inv.handshakes[body.HandshakeID]
	signature, e := rawURL.DecodeString(body.Signature)
	if h == nil || e != nil || len(signature) != ed25519.SignatureSize || !ed25519.Verify(h.key, []byte(h.signingPayload), signature) {
		return nil, apperror.New(401, "pairing_proof_failed", "Pairing proof could not be verified.")
	}
	credential, e := security.GenerateToken()
	if e != nil {
		return nil, e
	}
	client := database.NewClient{ID: h.clientID, DisplayName: h.clientDisplayName, PublicKey: h.clientPublicKey, CredentialHash: security.TokenHash(credential), ProtocolVersion: 1}
	var user *database.User
	var persisted *database.Client
	if inv.kind == "recover" {
		u, err := s.db.FindUser(inv.userID)
		if err != nil {
			return nil, err
		}
		if u == nil || u.RecoveryKeyHash == nil || !security.SafeTokenHashEqual(body.RecoveryKey, *u.RecoveryKeyHash) {
			inv.failed++
			if inv.failed >= 5 {
				id := inv.id
				s.active = nil
				s.last = map[string]any{"id": id, "state": "cancelled"}
			}
			return nil, apperror.New(401, "invalid_recovery_key", "The recovery key is incorrect.")
		}
		user, persisted, e = s.db.AddRecoveredClient(u.ID, client, s.nowMillis())
	} else {
		if !validBase64URL(body.RecoveryKey, 43, 43) || !validBase64URL(body.RecoveryEnvelope, 80, 80) {
			return nil, apperror.New(400, "invalid_recovery_material", "Recovery material is invalid.")
		}
		user, persisted, e = s.db.CreatePairedUser(h.userDisplayName, inv.quota, security.TokenHash(body.RecoveryKey), body.RecoveryEnvelope, client, s.nowMillis())
	}
	if e != nil {
		if strings.Contains(e.Error(), "UNIQUE constraint failed") || strings.Contains(e.Error(), "constraint failed: UNIQUE") {
			return nil, apperror.New(409, "duplicate_client", "This client identity is already paired.")
		}
		return nil, apperror.Wrap(503, "pairing_persistence_failed", "Pairing could not be completed.", e)
	}
	id := inv.id
	s.active = nil
	s.last = map[string]any{"id": id, "state": "paired", "userId": user.ID, "clientId": persisted.ID}
	result := map[string]any{"protocol": 1, "nodeId": mustNodeID(s.db), "user": map[string]any{"id": user.ID, "displayName": user.DisplayName}, "client": map[string]any{"id": persisted.ID, "displayName": persisted.ClientDisplayName}, "clientCredential": credential}
	if inv.kind == "recover" && user.RecoveryEnvelope != nil {
		result["recoveryEnvelope"] = *user.RecoveryEnvelope
	}
	return result, nil
}

func (s *Service) create(kind, userID, userName string, quota int64) (map[string]any, error) {
	cert, e := caCertificate(s.cfg.PairingCACertificatePath)
	if e != nil {
		return nil, e
	}
	secret, e := security.GenerateToken()
	if e != nil {
		return nil, e
	}
	id, e := security.UUID()
	if e != nil {
		return nil, e
	}
	now := s.cfg.Now()
	s.active = &invitation{id: id, secret: secret, secretHash: security.TokenHash(secret), kind: kind, userID: userID, userDisplayName: userName, quota: quota, caCertificate: cert, created: now, expires: now.Add(s.cfg.PairingInvitationTTL), handshakes: map[string]*handshake{}}
	s.last = nil
	return s.public()
}
func (s *Service) authorize(id, secret string) (*invitation, error) {
	s.expire()
	if s.active == nil || id != s.active.id || !security.SafeTokenHashEqual(secret, s.active.secretHash) {
		return nil, apperror.New(404, "pairing_unavailable", "No valid pairing invitation is available.")
	}
	return s.active, nil
}
func (s *Service) expire() {
	if s.active != nil && !s.cfg.Now().Before(s.active.expires) {
		id := s.active.id
		s.active = nil
		s.last = map[string]any{"id": id, "state": "expired"}
	}
}
func (s *Service) public() (map[string]any, error) {
	node, e := s.db.GetNodeState(false)
	if e != nil {
		return nil, e
	}
	a := s.active
	u, e := url.Parse("source://pair")
	if e != nil {
		return nil, e
	}
	q := u.Query()
	q.Set("v", "1")
	q.Set("node_id", node.NodeID)
	q.Set("node_key", node.PublicKey)
	q.Set("ca", a.caCertificate)
	q.Set("name", node.DisplayName)
	q.Set("endpoint", s.cfg.PairingBaseURL)
	q.Set("invite", a.id)
	q.Set("secret", a.secret)
	q.Set("expires", iso(a.expires))
	if a.kind == "recover" {
		q.Set("action", "recover")
	}
	u.RawQuery = q.Encode()
	out := map[string]any{"id": a.id, "state": "active", "kind": a.kind, "quotaBytes": a.quota, "createdAt": iso(a.created), "expiresAt": iso(a.expires), "payload": u.String()}
	if a.userID != "" {
		out["userId"] = a.userID
		out["userDisplayName"] = a.userDisplayName
	}
	return out, nil
}

func caCertificate(path string) (string, error) {
	data, e := os.ReadFile(path)
	if e != nil {
		return "", apperror.New(503, "pairing_ca_unavailable", "The Source pairing CA is not available.")
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return "", apperror.New(503, "pairing_ca_unavailable", "The Source pairing CA is not available.")
	}
	cert, e := x509.ParseCertificate(block.Bytes)
	if e != nil || !cert.IsCA {
		return "", apperror.New(503, "pairing_ca_unavailable", "The Source pairing CA is not available.")
	}
	return rawURL.EncodeToString(cert.Raw), nil
}
func name(value, field string) (string, error) {
	v := strings.TrimSpace(value)
	if len(v) < 1 || len(v) > 100 {
		return "", apperror.New(400, "invalid_"+field, field+" must contain 1–100 printable characters.")
	}
	for _, r := range v {
		if unicode.IsControl(r) {
			return "", apperror.New(400, "invalid_"+field, field+" must contain 1–100 printable characters.")
		}
	}
	return v, nil
}
func validBase64URL(value string, min, max int) bool {
	if len(value) < min || len(value) > max {
		return false
	}
	for _, c := range value {
		if !(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}
func iso(t time.Time) string        { return t.UTC().Format("2006-01-02T15:04:05.000Z") }
func (s *Service) nowMillis() int64 { return s.cfg.Now().UnixMilli() }
func mustNodeID(db *database.DB) string {
	n, e := db.GetNodeState(false)
	if e != nil {
		return ""
	}
	return n.NodeID
}
func unsupported() error {
	return apperror.New(400, "unsupported_pairing_protocol", "Unsupported pairing protocol version.")
}
