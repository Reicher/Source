package security

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"
)

const (
	argonMemory      = 65_536
	argonPasses      = 3
	argonParallelism = 1
	argonTagLength   = 32
)

var rawURL = base64.RawURLEncoding

type NodeIdentity struct{ NodeID, PublicKey, PrivateKey string }

func ValidatePassword(password string) error {
	if len(password) < 12 || len(password) > 256 {
		return errors.New("Password must contain between 12 and 256 characters")
	}
	return nil
}

func HashPassword(password string) (string, error) {
	if err := ValidatePassword(password); err != nil {
		return "", err
	}
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	digest := argon2.IDKey([]byte(password), salt, argonPasses, argonMemory, argonParallelism, argonTagLength)
	return fmt.Sprintf("argon2id$v=19$m=%d,t=%d,p=%d$%s$%s", argonMemory, argonPasses, argonParallelism, rawURL.EncodeToString(salt), rawURL.EncodeToString(digest)), nil
}

func VerifyPassword(password, encoded string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 5 || parts[0] != "argon2id" || parts[1] != "v=19" {
		return false
	}
	var memory uint32
	var passes uint32
	var parallelism uint8
	if _, err := fmt.Sscanf(parts[2], "m=%d,t=%d,p=%d", &memory, &passes, &parallelism); err != nil {
		return false
	}
	if memory != argonMemory || passes != argonPasses || parallelism != argonParallelism {
		return false
	}
	salt, err := rawURL.DecodeString(parts[3])
	if err != nil || len(salt) != 16 {
		return false
	}
	expected, err := rawURL.DecodeString(parts[4])
	if err != nil || len(expected) != argonTagLength {
		return false
	}
	actual := argon2.IDKey([]byte(password), salt, passes, memory, parallelism, uint32(len(expected)))
	return subtle.ConstantTimeCompare(actual, expected) == 1
}

func GenerateToken() (string, error) {
	value := make([]byte, 32)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return rawURL.EncodeToString(value), nil
}
func TokenHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func SafeTokenHashEqual(token, expectedHash string) bool {
	if len(expectedHash) != 64 {
		return false
	}
	expected, err := hex.DecodeString(expectedHash)
	if err != nil {
		return false
	}
	actual := sha256.Sum256([]byte(token))
	return subtle.ConstantTimeCompare(actual[:], expected) == 1
}

func GenerateNodeIdentity() (NodeIdentity, error) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return NodeIdentity{}, err
	}
	publicDER, err := x509.MarshalPKIXPublicKey(publicKey)
	if err != nil {
		return NodeIdentity{}, err
	}
	privateDER, err := x509.MarshalPKCS8PrivateKey(privateKey)
	if err != nil {
		return NodeIdentity{}, err
	}
	sum := sha256.Sum256(publicDER)
	return NodeIdentity{
		NodeID:     "srcnode_" + rawURL.EncodeToString(sum[:]),
		PublicKey:  rawURL.EncodeToString(publicDER),
		PrivateKey: string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: privateDER})),
	}, nil
}

func ParseEd25519PublicKey(encoded string) (ed25519.PublicKey, []byte, string, error) {
	if len(encoded) < 40 || len(encoded) > 256 {
		return nil, nil, "", errors.New("invalid Ed25519 public key")
	}
	der, err := rawURL.DecodeString(encoded)
	if err != nil {
		return nil, nil, "", errors.New("invalid Ed25519 public key")
	}
	parsed, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, nil, "", errors.New("invalid Ed25519 public key")
	}
	key, ok := parsed.(ed25519.PublicKey)
	if !ok || len(key) != ed25519.PublicKeySize {
		return nil, nil, "", errors.New("invalid Ed25519 public key")
	}
	canonical, err := x509.MarshalPKIXPublicKey(key)
	if err != nil || subtle.ConstantTimeCompare(canonical, der) != 1 {
		return nil, nil, "", errors.New("invalid Ed25519 public key")
	}
	return key, canonical, rawURL.EncodeToString(canonical), nil
}

func LoadNodePrivateKey(encoded string) (ed25519.PrivateKey, error) {
	block, _ := pem.Decode([]byte(encoded))
	if block == nil {
		return nil, errors.New("invalid Node private key")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, errors.New("invalid Node private key")
	}
	key, ok := parsed.(ed25519.PrivateKey)
	if !ok {
		return nil, errors.New("invalid Node private key")
	}
	return key, nil
}

func ClientIDFromPublicKey(publicKeyDER []byte) string {
	sum := sha256.Sum256(publicKeyDER)
	return "srcclient_" + rawURL.EncodeToString(sum[:])
}

func UUID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}

func Base64URL(data []byte) string                 { return rawURL.EncodeToString(data) }
func DecodeBase64URL(value string) ([]byte, error) { return rawURL.DecodeString(value) }
