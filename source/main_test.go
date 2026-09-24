package main

import (
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestPrivateLANAddressValidation(t *testing.T) {
	for _, address := range []string{"10.0.0.2:8443", "172.16.4.3:1", "192.168.50.20:65535"} {
		if ip, err := privateLANAddress(address); err != nil || ip == nil {
			t.Errorf("private address %q rejected: ip=%v err=%v", address, ip, err)
		}
	}
	for _, address := range []string{
		"", ":8443", "0.0.0.0:8443", "127.0.0.1:8443", "8.8.8.8:8443",
		"[::]:8443", "[fd00::1]:8443", "[::ffff:192.168.1.4]:8443",
		"192.168.1.4:0", "192.168.1.4:65536",
	} {
		if _, err := privateLANAddress(address); err == nil {
			t.Errorf("unsafe address %q accepted", address)
		}
	}
}

func TestInterfaceForAddressRejectsUnassignedAddress(t *testing.T) {
	if _, err := interfaceForAddress(net.ParseIP("192.0.2.1")); err == nil {
		t.Fatal("unassigned address accepted")
	}
}

func TestHealthz(t *testing.T) {
	i, err := loadIdentity(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	response := httptest.NewRecorder()
	i.lanHandler().ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if response.Code != http.StatusNoContent {
		t.Fatalf("GET /healthz: got %d, want %d", response.Code, http.StatusNoContent)
	}
}
