// Package syncmodel defines the versioned, transport-independent identity
// used by Source Clients and the authoritative Node.
package syncmodel

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
)

const ContractVersion = 1

var (
	uuidPattern       = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	collectionPattern = regexp.MustCompile(`^[a-z][a-z0-9-]{1,63}$`)
	shaPattern        = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

type ObjectKey struct {
	ProfileID  string `json:"profileId"`
	Collection string `json:"collection"`
	ObjectID   string `json:"objectId"`
}

type Payload struct {
	Format          string `json:"format"`
	FormatVersion   int    `json:"formatVersion"`
	ByteCount       int64  `json:"byteCount"`
	PlaintextSHA256 string `json:"plaintextSha256"`
}

type Revision struct {
	RevisionID        string    `json:"revisionId"`
	ObjectKey         ObjectKey `json:"objectKey"`
	Kind              string    `json:"kind"`
	ParentRevisionIDs []string  `json:"parentRevisionIds"`
	Payload           *Payload  `json:"payload"`
	CreatedAtMillis   *int64    `json:"createdAtMillis,omitempty"`
}

type Mutation struct {
	ContractVersion        int      `json:"contractVersion"`
	OperationID            string   `json:"operationId"`
	OriginID               string   `json:"originId"`
	OriginEpoch            string   `json:"originEpoch"`
	OriginSequence         int64    `json:"originSequence"`
	ExpectedAuthorityEpoch string   `json:"expectedAuthorityEpoch,omitempty"`
	Revision               Revision `json:"revision"`
}

type CommitReceipt struct {
	OperationID     string `json:"operationId"`
	RevisionID      string `json:"revisionId"`
	AuthorityNodeID string `json:"authorityNodeId"`
	AuthorityEpoch  string `json:"authorityEpoch"`
	CommitSequence  int64  `json:"commitSequence"`
}

type Cursor struct {
	AuthorityNodeID string `json:"authorityNodeId"`
	AuthorityEpoch  string `json:"authorityEpoch"`
	CommitSequence  int64  `json:"commitSequence"`
}

type Change struct {
	Receipt  CommitReceipt `json:"receipt"`
	Revision Revision      `json:"revision"`
}

type ChangesRequest struct {
	ContractVersion int     `json:"contractVersion"`
	Collection      string  `json:"collection"`
	ObjectID        string  `json:"objectId"`
	Cursor          *Cursor `json:"cursor,omitempty"`
}

type ChangesResponse struct {
	ContractVersion  int        `json:"contractVersion"`
	Cursor           Cursor     `json:"cursor"`
	RequiresManifest bool       `json:"requiresManifest"`
	Changes          []Change   `json:"changes"`
	Heads            []Revision `json:"heads"`
}

type AckRequest struct {
	ContractVersion int    `json:"contractVersion"`
	Collection      string `json:"collection"`
	ObjectID        string `json:"objectId"`
	Cursor          Cursor `json:"cursor"`
}

// revisionIdentity is deliberately declared in RFC 8785 lexicographic key
// order. encoding/json emits struct fields in declaration order, and this
// identity contains only strings, integers, arrays, and null.
type revisionIdentity struct {
	Collection string           `json:"collection"`
	Kind       string           `json:"kind"`
	ObjectID   string           `json:"objectId"`
	Parents    []string         `json:"parents"`
	Payload    *identityPayload `json:"payload"`
	ProfileID  string           `json:"profileId"`
}

type identityPayload struct {
	ByteCount       int64  `json:"byteCount"`
	Format          string `json:"format"`
	FormatVersion   int    `json:"formatVersion"`
	PlaintextSHA256 string `json:"plaintextSha256"`
}

func RevisionID(revision Revision) (string, error) {
	if err := ValidateRevision(revision, false); err != nil {
		return "", err
	}
	identity := revisionIdentity{
		Collection: revision.ObjectKey.Collection,
		Kind:       revision.Kind,
		ObjectID:   revision.ObjectKey.ObjectID,
		Parents:    revision.ParentRevisionIDs,
		ProfileID:  revision.ObjectKey.ProfileID,
	}
	if revision.Payload != nil {
		identity.Payload = &identityPayload{
			ByteCount: revision.Payload.ByteCount, Format: revision.Payload.Format,
			FormatVersion: revision.Payload.FormatVersion, PlaintextSHA256: revision.Payload.PlaintextSHA256,
		}
	}
	canonical, err := json.Marshal(identity)
	if err != nil {
		return "", err
	}
	digest := sha256.New()
	_, _ = digest.Write([]byte("source-storage-revision"))
	_, _ = digest.Write([]byte{0})
	_, _ = digest.Write(canonical)
	return hex.EncodeToString(digest.Sum(nil)), nil
}

func ValidateMutation(mutation Mutation) error {
	if mutation.ContractVersion != ContractVersion {
		return errors.New("unsupported storage contract version")
	}
	if !uuidPattern.MatchString(mutation.OperationID) || !uuidPattern.MatchString(mutation.OriginEpoch) {
		return errors.New("operation and origin epoch must be lowercase UUIDs")
	}
	if mutation.OriginID == "" || !validNFC(mutation.OriginID) || mutation.OriginSequence <= 0 {
		return errors.New("invalid mutation origin")
	}
	if mutation.ExpectedAuthorityEpoch != "" && !uuidPattern.MatchString(mutation.ExpectedAuthorityEpoch) {
		return errors.New("expected authority epoch must be a lowercase UUID")
	}
	if err := ValidateRevision(mutation.Revision, true); err != nil {
		return err
	}
	expected, err := RevisionID(mutation.Revision)
	if err != nil {
		return err
	}
	if mutation.Revision.RevisionID != expected {
		return errors.New("revision identifier does not match its canonical identity")
	}
	return nil
}

func ValidateRevision(revision Revision, requireID bool) error {
	if !uuidPattern.MatchString(revision.ObjectKey.ProfileID) {
		return errors.New("profile identifier must be a lowercase UUID")
	}
	if !collectionPattern.MatchString(revision.ObjectKey.Collection) {
		return errors.New("invalid collection identifier")
	}
	if !ValidObjectID(revision.ObjectKey.ObjectID) {
		return errors.New("invalid object identifier")
	}
	if requireID && !shaPattern.MatchString(revision.RevisionID) {
		return errors.New("invalid revision identifier")
	}
	if revision.Kind != "content" && revision.Kind != "tombstone" {
		return errors.New("revision kind must be content or tombstone")
	}
	if !sort.StringsAreSorted(revision.ParentRevisionIDs) {
		return errors.New("revision parents must be sorted")
	}
	for index, parent := range revision.ParentRevisionIDs {
		if !shaPattern.MatchString(parent) || (index > 0 && parent == revision.ParentRevisionIDs[index-1]) {
			return errors.New("revision parents must be valid and unique")
		}
	}
	if revision.Kind == "tombstone" {
		if revision.Payload != nil {
			return errors.New("tombstone revision cannot contain a payload")
		}
	} else if revision.Payload == nil {
		return errors.New("content revision requires a payload")
	} else if err := validatePayload(*revision.Payload); err != nil {
		return err
	}
	if revision.CreatedAtMillis != nil && *revision.CreatedAtMillis <= 0 {
		return errors.New("invalid producer timestamp")
	}
	return nil
}

func validatePayload(payload Payload) error {
	if payload.Format == "" || len(payload.Format) > 200 || !validNFC(payload.Format) {
		return errors.New("invalid payload format")
	}
	if payload.FormatVersion <= 0 || payload.ByteCount < 0 || !shaPattern.MatchString(payload.PlaintextSHA256) {
		return errors.New("invalid payload descriptor")
	}
	return nil
}

func MutationDigest(mutation Mutation) (string, error) {
	canonical, err := json.Marshal(mutation)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(canonical)
	return hex.EncodeToString(sum[:]), nil
}

func ValidUUID(value string) bool       { return uuidPattern.MatchString(value) }
func ValidSHA256(value string) bool     { return shaPattern.MatchString(value) }
func ValidCollection(value string) bool { return collectionPattern.MatchString(value) }
func ValidObjectID(value string) bool {
	return (uuidPattern.MatchString(value) || shaPattern.MatchString(value)) && validNFC(value)
}

func validNFC(value string) bool {
	return utf8.ValidString(value) && norm.NFC.IsNormalString(value)
}

func SameStrings(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}

func Scope(revision Revision) string {
	return fmt.Sprintf("%s/%s/%s", revision.ObjectKey.ProfileID, revision.ObjectKey.Collection, revision.ObjectKey.ObjectID)
}
