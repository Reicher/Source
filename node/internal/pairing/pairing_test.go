package pairing

import (
	"errors"
	"path/filepath"
	"testing"
	"time"

	"source.local/node/internal/apperror"
	"source.local/node/internal/config"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
)

func TestInvitationFailsClosedWithoutPublicCA(t *testing.T) {
	db, err := database.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	identity, err := security.GenerateNodeIdentity()
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.InitializeNode("Source test", identity, "hash", 1); err != nil {
		t.Fatal(err)
	}
	service := New(db, config.Config{PairingCACertificatePath: filepath.Join(t.TempDir(), "missing.crt"), PairingInvitationTTL: time.Minute, Now: time.Now})
	_, err = service.CreateInvitation(64 * 1024 * 1024)
	var appErr *apperror.Error
	if !errors.As(err, &appErr) || appErr.Status != 503 || appErr.Code != "pairing_ca_unavailable" {
		t.Fatalf("expected pairing_ca_unavailable, got %v", err)
	}
	invitation, err := service.GetInvitation()
	if err != nil {
		t.Fatal(err)
	}
	if invitation != nil {
		t.Fatalf("failed invitation remained active: %#v", invitation)
	}
}
