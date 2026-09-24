package kilo

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
)

func clientTo(t *testing.T, srv *httptest.Server) *Client {
	host := strings.TrimPrefix(srv.URL, "http://")
	parts := strings.Split(host, ":")
	port := 80
	if len(parts) == 2 {
		if n, err := strconv.Atoi(parts[1]); err == nil {
			port = n
		}
	}
	return NewClient(parts[0], port, "secret")
}

func TestBasicAuthHeader(t *testing.T) {
	var user, pass string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, pass, _ = r.BasicAuth()
		_, _ = w.Write([]byte(`[]`))
	}))
	t.Cleanup(srv.Close)
	c := clientTo(t, srv)
	if _, err := c.Sessions(context.Background()); err != nil {
		t.Fatal(err)
	}
	if user != "kilo" || pass != "secret" {
		t.Fatalf("basic auth = %q/%q", user, pass)
	}
}

func TestErrorMapping(t *testing.T) {
	cases := []struct {
		status int
		body   string
		want   string
		http   int
	}{
		{http.StatusNotFound, `{"error":"session gone"}`, "session_not_found", http.StatusNotFound},
		{http.StatusConflict, `{"error":"busy"}`, "busy", http.StatusConflict},
		{http.StatusUnauthorized, `{"error":"no"}`, "engine_auth", http.StatusBadGateway},
		{http.StatusTooManyRequests, `{}`, "rate_limited", http.StatusTooManyRequests},
		{http.StatusInternalServerError, `{"error":"boom"}`, "engine_error", http.StatusBadGateway},
		{http.StatusBadGateway, `{}`, "engine_unavailable", http.StatusBadGateway},
		{http.StatusBadRequest, `{"error":"bad input"}`, "engine_error", http.StatusBadGateway},
	}
	for _, c := range cases {
		t.Run(c.body, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(c.status)
				_, _ = w.Write([]byte(c.body))
			}))
			defer srv.Close()
			cl := clientTo(t, srv)
			_, err := cl.Sessions(context.Background())
			if err == nil {
				t.Fatal("expected error")
			}
			ke, ok := err.(*Error)
			if !ok {
				t.Fatalf("error type %T", err)
			}
			if ke.Code != c.want || ke.HTTPStatus != c.http {
				t.Fatalf("got %s/%d, want %s/%d", ke.Code, ke.HTTPStatus, c.want, c.http)
			}
		})
	}
}

func TestSendMessageBodyAndQuery(t *testing.T) {
	var gotPath, gotQuery string
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotQuery = r.URL.RawQuery
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		_, _ = w.Write([]byte(`{"info":{}}`))
	}))
	t.Cleanup(srv.Close)
	c := clientTo(t, srv)
	_, err := c.SendMessage(context.Background(), "ses_/x", map[string]any{
		"messageID": "msg_1",
		"parts":     []any{map[string]any{"type": "text", "text": "hi"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if gotPath != "/session/ses_%2Fx/message" && gotPath != "/session/ses_/x/message" {
		t.Fatalf("path = %q", gotPath)
	}
	if gotQuery != "" {
		t.Fatalf("query should be empty, got %q", gotQuery)
	}
	if gotBody["messageID"] != "msg_1" {
		t.Fatalf("body = %v", gotBody)
	}
}

func TestPermissionReplyBody(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		_, _ = w.Write([]byte(`{}`))
	}))
	t.Cleanup(srv.Close)
	c := clientTo(t, srv)
	if err := c.PermissionReply(context.Background(), "perm_1", "once", "ok", true); err != nil {
		t.Fatal(err)
	}
	if gotBody["reply"] != "once" || gotBody["message"] != "ok" || gotBody["interactive"] != true {
		t.Fatalf("body = %v", gotBody)
	}
}
