package store

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/easyoneweb/easy-code-remote/server/internal/event"
	"github.com/easyoneweb/easy-code-remote/server/internal/kilo"
)

func testKiloServer(t *testing.T) *httptest.Server {
	sessions := `[
	  {"id":"ses_1","title":"One","time":{"updated":100}},
	  {"id":"ses_2","title":"Two","time":{"updated":200}}
	]`
	mux := http.NewServeMux()
	mux.HandleFunc("/session/status", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"ses_1":{"type":"busy"},"ses_2":{"type":"idle"}}`))
	})
	mux.HandleFunc("/permission", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[{"sessionID":"ses_2","id":"perm_1"}]`))
	})
	mux.HandleFunc("/question", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[]`))
	})
	mux.HandleFunc("/session", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(sessions))
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func clientFor(t *testing.T, srv *httptest.Server) *kilo.Client {
	host := strings.TrimPrefix(srv.URL, "http://")
	parts := strings.Split(host, ":")
	port := 80
	if len(parts) == 2 {
		port = atoi(parts[1])
	}
	return kilo.NewClient(parts[0], port, "pw")
}

func atoi(s string) int {
	n := 0
	for _, c := range s {
		if c < '0' || c > '9' {
			break
		}
		n = n*10 + int(c-'0')
	}
	return n
}

func TestResyncAndDeriveStatus(t *testing.T) {
	srv := testKiloServer(t)
	st := New()
	if err := st.Resync(context.Background(), clientFor(t, srv)); err != nil {
		t.Fatal(err)
	}
	ss := st.Sessions()
	if len(ss) != 2 {
		t.Fatalf("got %d sessions", len(ss))
	}
	byID := map[string]map[string]any{}
	for _, s := range ss {
		byID[s["id"].(string)] = s
	}
	if byID["ses_1"]["status"] != "running" {
		t.Fatalf("ses_1 status = %v, want running (busy)", byID["ses_1"]["status"])
	}
	if byID["ses_2"]["status"] != "waiting" || byID["ses_2"]["waitingReason"] != "permission" {
		t.Fatalf("ses_2 status = %v reason=%v, want waiting/permission",
			byID["ses_2"]["status"], byID["ses_2"]["waitingReason"])
	}
	// Most recently updated first: ses_2 before ses_1.
	if ss[0]["id"] != "ses_2" {
		t.Fatalf("sort order wrong: %v first", ss[0]["id"])
	}
	// Unknown session -> nil.
	if st.Session("nope") != nil {
		t.Fatal("unknown session should return nil")
	}
}

func TestApplyEventDerivesStatus(t *testing.T) {
	st := New()
	// Seed with a session.
	st.ApplyEvent(event.Event{Type: "session.created", SessionID: "ses_x", Data: map[string]any{
		"info": map[string]any{"id": "ses_x", "title": "X"},
	}})
	if st.Session("ses_x")["status"] != "idle" {
		t.Fatalf("initial status = %v, want idle", st.Session("ses_x")["status"])
	}
	st.ApplyEvent(event.Event{Type: "session.status", SessionID: "ses_x", Data: map[string]any{
		"status": map[string]any{"type": "busy"},
	}})
	if st.Session("ses_x")["status"] != "running" {
		t.Fatalf("status = %v, want running", st.Session("ses_x")["status"])
	}
	st.ApplyEvent(event.Event{Type: "question.asked", SessionID: "ses_x"})
	if st.Session("ses_x")["status"] != "waiting" || st.Session("ses_x")["waitingReason"] != "question" {
		t.Fatalf("status = %v reason=%v, want waiting/question",
			st.Session("ses_x")["status"], st.Session("ses_x")["waitingReason"])
	}
	st.ApplyEvent(event.Event{Type: "question.replied", SessionID: "ses_x"})
	if st.Session("ses_x")["status"] != "running" {
		t.Fatalf("after reply status = %v, want running", st.Session("ses_x")["status"])
	}
	// Session update replaces info but keeps status key derivation.
	st.ApplyEvent(event.Event{Type: "session.updated", SessionID: "ses_x", Data: map[string]any{
		"info": map[string]any{"id": "ses_x", "title": "X2"},
	}})
	if st.Session("ses_x")["title"] != "X2" {
		t.Fatalf("title not updated: %v", st.Session("ses_x")["title"])
	}
	st.ApplyEvent(event.Event{Type: "session.deleted", SessionID: "ses_x"})
	if st.Session("ses_x") != nil {
		t.Fatal("session should be deleted")
	}
}

func TestMessagesPassthroughAndCache(t *testing.T) {
	var hits int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		_, _ = w.Write([]byte(`[{"info":{"id":"m1"}}]`))
	}))
	t.Cleanup(srv.Close)
	st := New()
	c := clientFor(t, srv)
	ctx := context.Background()
	d1, err := st.Messages(ctx, c, "ses_1", 50, "")
	if err != nil {
		t.Fatal(err)
	}
	d2, err := st.Messages(ctx, c, "ses_1", 50, "")
	if err != nil {
		t.Fatal(err)
	}
	if string(d1) != string(d2) {
		t.Fatal("cached payload differs")
	}
	if hits != 1 {
		t.Fatalf("expected 1 kilo hit due to cache, got %d", hits)
	}
	// Different session bypasses the cache.
	if _, err := st.Messages(ctx, c, "ses_2", 50, ""); err != nil {
		t.Fatal(err)
	}
	if hits != 2 {
		t.Fatalf("expected 2 kilo hits, got %d", hits)
	}
}

func TestMessagesLimitClamp(t *testing.T) {
	var gotLimit string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotLimit = r.URL.Query().Get("limit")
		_, _ = w.Write([]byte(`[]`))
	}))
	t.Cleanup(srv.Close)
	st := New()
	ctx := context.Background()
	_, _ = st.Messages(ctx, clientFor(t, srv), "ses_1", 999999, "")
	if gotLimit != "500" {
		t.Fatalf("limit clamp failed: %q", gotLimit)
	}
}

func TestPendingSet(t *testing.T) {
	raw := []json.RawMessage{
		json.RawMessage(`{"sessionID":"a"}`),
		json.RawMessage(`{"sessionID":"b"}`),
		json.RawMessage(`{"nope":true}`),
	}
	set := pendingSet(raw)
	if !set["a"] || !set["b"] || len(set) != 2 {
		t.Fatalf("pending set wrong: %v", set)
	}
}

func TestPendingPayloads(t *testing.T) {
	srv := testKiloServer(t) // /permission -> [{"sessionID":"ses_2","id":"perm_1"}]
	st := New()
	if err := st.Resync(context.Background(), clientFor(t, srv)); err != nil {
		t.Fatal(err)
	}
	perms, qs := st.Pending("ses_2")
	if len(perms) != 1 || len(qs) != 0 {
		t.Fatalf("after resync: perms=%d qs=%d", len(perms), len(qs))
	}
	if !strings.Contains(string(perms[0]), "perm_1") {
		t.Fatalf("permission payload: %s", string(perms[0]))
	}
	// ApplyEvent asked retains payloads; replied clears them.
	st.ApplyEvent(event.Event{Type: "question.asked", SessionID: "ses_2", Data: map[string]any{
		"id": "q_9", "sessionID": "ses_2", "question": "continue?",
	}})
	if _, qs := st.Pending("ses_2"); len(qs) != 1 {
		t.Fatalf("qs=%d want 1 after question.asked", len(qs))
	}
	st.ApplyEvent(event.Event{Type: "question.rejected", SessionID: "ses_2"})
	if _, qs := st.Pending("ses_2"); len(qs) != 0 {
		t.Fatalf("qs=%d want 0 after question.rejected", len(qs))
	}
	// A malformed/nil asked payload is not retained.
	st.ApplyEvent(event.Event{Type: "permission.asked", SessionID: "ses_2", Data: nil})
	if perms, _ := st.Pending("ses_2"); len(perms) != 1 {
		t.Fatalf("nil payload must not be retained; perms=%d want 1", len(perms))
	}
	// Unknown session -> empty (non-nil) slices.
	perms, qs = st.Pending("nope")
	if perms == nil || qs == nil || len(perms) != 0 || len(qs) != 0 {
		t.Fatalf("unknown session pending must be empty arrays: %v/%v", perms, qs)
	}
}

func TestSessionDeletedClearsPendingPayloads(t *testing.T) {
	st := New()
	st.ApplyEvent(event.Event{Type: "session.created", SessionID: "ses_x", Data: map[string]any{
		"info": map[string]any{"id": "ses_x", "title": "X"},
	}})
	st.ApplyEvent(event.Event{Type: "permission.asked", SessionID: "ses_x", Data: map[string]any{
		"id": "perm_1", "sessionID": "ses_x",
	}})
	if perms, _ := st.Pending("ses_x"); len(perms) != 1 {
		t.Fatalf("perms=%d want 1", len(perms))
	}
	st.ApplyEvent(event.Event{Type: "session.deleted", SessionID: "ses_x"})
	if perms, _ := st.Pending("ses_x"); len(perms) != 0 {
		t.Fatalf("perms=%d want 0 after delete", len(perms))
	}
}

func TestMergePendingUnion(t *testing.T) {
	// Two servers, each with its own project's pending items: the store's union
	// must expose both. Server A serves /permission with p1 and /question with q1;
	// server B serves /permission with p2 and /question with q2.
	srvA := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/permission":
			_, _ = w.Write([]byte(`[{"sessionID":"ses_a","type":"permission.asked"}]`))
		case "/question":
			_, _ = w.Write([]byte(`[{"sessionID":"ses_a","type":"question.asked"}]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srvA.Close()
	srvB := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/permission":
			_, _ = w.Write([]byte(`[{"sessionID":"ses_b","type":"permission.asked"}]`))
		case "/question":
			_, _ = w.Write([]byte(`[{"sessionID":"ses_b","type":"question.asked"}]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srvB.Close()

	st := New()
	st.ResetPending()
	if err := st.MergePending(context.Background(), clientFor(t, srvA)); err != nil {
		t.Fatal(err)
	}
	if err := st.MergePending(context.Background(), clientFor(t, srvB)); err != nil {
		t.Fatal(err)
	}
	permA, qA := st.Pending("ses_a")
	permB, qB := st.Pending("ses_b")
	if len(permA) == 0 || len(qA) == 0 || len(permB) == 0 || len(qB) == 0 {
		t.Fatalf("pending union wrong: a=(%d,%d) b=(%d,%d)", len(permA), len(qA), len(permB), len(qB))
	}
	permC, qC := st.Pending("ses_c")
	if len(permC)+len(qC) != 0 {
		t.Fatal("ses_c should not be pending")
	}
	// Reset clears everything.
	st.ResetPending()
	permA2, qA2 := st.Pending("ses_a")
	if len(permA2)+len(qA2) != 0 {
		t.Fatal("ResetPending did not clear the union")
	}
}
