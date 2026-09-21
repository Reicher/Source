package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

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
