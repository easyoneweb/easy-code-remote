// Package auth implements bearer-token authentication for the phone-facing API.
package auth

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

// ValidBearerToken reports whether the request carries a matching Authorization:
// Bearer <token> header. Comparison is constant-time.
func ValidBearerToken(r *http.Request, expected string) bool {
	header := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(header) <= len(prefix) || !strings.EqualFold(header[:len(prefix)], prefix) {
		return false
	}
	provided := header[len(prefix):]
	if expected == "" || provided == "" {
		return false
	}
	// Lengths differ -> ConstantTimeCompare returns 0 immediately; that is fine,
	// timing here does not reveal meaningful information beyond length.
	return subtle.ConstantTimeCompare([]byte(provided), []byte(expected)) == 1
}

// Middleware wraps a handler, rejecting requests without a valid bearer token.
func Middleware(next http.Handler, token string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !ValidBearerToken(r, token) {
			http.Error(w, `{"error":{"code":"unauthorized","message":"invalid or missing bearer token"}}`, http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}
