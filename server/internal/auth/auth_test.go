package auth

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestValidBearerToken(t *testing.T) {
	const token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	cases := []struct {
		name     string
		header   string
		expected string
		want     bool
	}{
		{"exact match", "Bearer " + token, token, true},
		{"scheme case-insensitive", "bearer " + token, token, true},
		{"wrong token", "Bearer deadbeef", token, false},
		{"no scheme", token, token, false},
		{"empty token", "", token, false},
		{"empty expected", "Bearer " + token, "", false},
		{"malformed short header", "Bea", token, false},
		{"double space not a valid bearer", "Bearer  " + token, token, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			if c.header != "" {
				req.Header.Set("Authorization", c.header)
			}
			if got := ValidBearerToken(req, c.expected); got != c.want {
				t.Fatalf("got %v want %v", got, c.want)
			}
		})
	}
}

func TestMiddleware(t *testing.T) {
	const token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	h := Middleware(inner, token)

	t.Run("missing header -> 401", func(t *testing.T) {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("got %d want 401", rec.Code)
		}
	})
	t.Run("wrong token -> 401", func(t *testing.T) {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.Header.Set("Authorization", "Bearer wrong")
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("got %d want 401", rec.Code)
		}
	})
	t.Run("correct token -> 200", func(t *testing.T) {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("got %d want 200", rec.Code)
		}
	})
}
