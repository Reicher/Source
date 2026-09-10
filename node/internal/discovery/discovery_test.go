package discovery

import (
	"io"
	"log"
	"strings"
	"testing"

	"source.local/node/internal/config"
	"source.local/node/internal/database"
	"source.local/node/internal/security"
)

type fakeRegistration struct{ stopped bool }

func (f *fakeRegistration) Shutdown() { f.stopped = true }

func TestAdvertisementContainsOnlyPublicRoutingHints(t *testing.T) {
	db, e := database.Open(":memory:")
	if e != nil {
		t.Fatal(e)
	}
	defer db.Close()
	identity, e := security.GenerateNodeIdentity()
	if e != nil {
		t.Fatal(e)
	}
	if _, e = db.InitializeNode("Source hemma", identity, "hash", 1); e != nil {
		t.Fatal(e)
	}
	service := New(db, config.Config{HTTPSPort: 8443}, log.New(io.Discard, "", 0))
	var name, kind, domain string
	var port int
	var text []string
	fake := &fakeRegistration{}
	service.register = func(n, k, d string, p int, txt []string) (registration, error) {
		name, kind, domain, port, text = n, k, d, p, txt
		return fake, nil
	}
	if e = service.publishIfReady(); e != nil {
		t.Fatal(e)
	}
	if name != "Source "+identity.NodeID[len(identity.NodeID)-8:] || kind != "_source._tcp" || domain != "local." || port != 8443 {
		t.Fatalf("unexpected service: %q %q %q %d", name, kind, domain, port)
	}
	joined := strings.Join(text, "\n")
	for _, want := range []string{"v=1", "id=" + identity.NodeID, "name=Source hemma", "api=/api/v1"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("TXT missing %q: %v", want, text)
		}
	}
	if strings.Contains(strings.ToLower(joined), "secret") {
		t.Fatalf("TXT leaked a secret: %v", text)
	}
	service.Stop()
	if !fake.stopped {
		t.Fatal("registration was not stopped")
	}
}
