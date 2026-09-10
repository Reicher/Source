package auth

import (
	"crypto/ed25519"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"strings"
	"time"

	"source.local/node/internal/apperror"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
)

type Service struct {
	db  *database.DB
	now func() time.Time
}
type Session struct {
	ClientID string
	User     struct {
		ID          string `json:"id"`
		DisplayName string `json:"displayName"`
	} `json:"user"`
}

func New(db *database.DB, now func() time.Time) *Service { return &Service{db: db, now: now} }
func (s *Service) Authenticate(credential string) (*Session, error) {
	if len(credential) < 32 || len(credential) > 256 {
		return nil, nil
	}
	client, e := s.db.FindClientByCredentialHash(security.TokenHash(credential))
	if e != nil {
		return nil, e
	}
	if client == nil || client.RevokedAt != nil || client.DisabledAt != nil {
		return nil, nil
	}
	if e = s.db.TouchClient(client.ID, s.now().UnixMilli()); e != nil {
		return nil, e
	}
	session := &Session{ClientID: client.ID}
	session.User.ID = client.UserID
	session.User.DisplayName = client.UserDisplayName
	return session, nil
}

func ProveNodeIdentity(db *database.DB, session *Session, protocol int, nonce string) (map[string]any, error) {
	if protocol != 1 {
		return nil, apperror.New(400, "unsupported_node_auth_protocol", "Unsupported Node authentication protocol version.")
	}
	if len(nonce) != 43 || !base64URL(nonce) {
		return nil, apperror.New(400, "invalid_nonce", "Nonce must be a base64url value containing at least 256 bits.")
	}
	node, e := db.GetNodeState(true)
	if e != nil {
		return nil, e
	}
	payload := strings.Join([]string{"source-node-auth-v1", node.NodeID, session.ClientID, nonce, base64.RawURLEncoding.EncodeToString([]byte(node.DisplayName))}, "\n")
	block, _ := pem.Decode([]byte(node.PrivateKey))
	if block == nil {
		return nil, errors.New("invalid Node private key")
	}
	key, e := x509.ParsePKCS8PrivateKey(block.Bytes)
	if e != nil {
		return nil, e
	}
	private, ok := key.(ed25519.PrivateKey)
	if !ok {
		return nil, errors.New("invalid Node private key")
	}
	return map[string]any{"protocol": 1, "nodeId": node.NodeID, "nodePublicKey": node.PublicKey, "displayName": node.DisplayName, "clientId": session.ClientID, "nonce": nonce, "signingPayload": payload, "nodeSignature": base64.RawURLEncoding.EncodeToString(ed25519.Sign(private, []byte(payload)))}, nil
}
func base64URL(v string) bool {
	for _, c := range v {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}
