package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/easyoneweb/easy-code-remote/server/internal/event"
	"github.com/easyoneweb/easy-code-remote/server/internal/kilo"
	"github.com/easyoneweb/easy-code-remote/server/internal/store"
	"github.com/easyoneweb/easy-code-remote/server/internal/supervisor"
)

func TestHubPublishSubscribe(t *testing.T) {
	h := NewHub()
	ch := h.Subscribe()
	defer h.Unsubscribe(ch)

	env := event.Envelope{Type: "session.updated", SessionID: "ses_x", Cursor: 1}
	h.Publish(env)
	select {
	case got := <-ch:
		if got.Cursor != 1 || got.SessionID != "ses_x" {
			t.Fatalf("got %+v", got)
		}
	case <-time.After(time.Second):
		t.Fatal("no event delivered")
	}
}

func TestHubUnsubscribe(t *testing.T) {
	h := NewHub()
	ch := h.Subscribe()
	h.Unsubscribe(ch)
	// Publishing after unsubscribe must not block or panic.
	h.Publish(event.Envelope{Type: "x"})
	select {
	case _, open := <-ch:
		if open {
			t.Fatal("channel should be closed after unsubscribe")
		}
	case <-time.After(time.Second):
		t.Fatal("unsubscribed channel not closed")
	}
}

func TestHubSlowSubscriberDropped(t *testing.T) {
	h := NewHub()
	ch := h.Subscribe()
	defer h.Unsubscribe(ch)
	// Fill the subscriber buffer, then publish more: must not block.
	for i := 0; i < 512; i++ {
		h.Publish(event.Envelope{Type: "session.updated", Cursor: uint64(i)})
	}
	// Drain a bit to ensure we can still read.
	if got := <-ch; got.Cursor != 0 {
		t.Fatalf("first buffered event cursor = %d", got.Cursor)
	}
}

// safeRecorder is a concurrency-safe ResponseWriter that supports flushing.
type safeRecorder struct {
	mu     sync.Mutex
	header http.Header
	body   strings.Builder
	status int
}

func newSafeRecorder() *safeRecorder {
	return &safeRecorder{header: make(http.Header), status: http.StatusOK}
}

func (s *safeRecorder) Header() http.Header    { return s.header }
func (s *safeRecorder) WriteHeader(status int) { s.mu.Lock(); s.status = status; s.mu.Unlock() }
func (s *safeRecorder) Flush()                 {}
func (s *safeRecorder) Write(b []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.body.Write(b)
}
func (s *safeRecorder) String() string { s.mu.Lock(); defer s.mu.Unlock(); return s.body.String() }

func newTestAPI() *Server {
	return New(nil, store.New(), supervisor.New("/nonexistent/kilo", "127.0.0.1", 1, "x", nil), "test", nil)
}

// newKiloClientFor builds a kilo.Client pointed at a test HTTP server.
func newKiloClientFor(t *testing.T, srv *httptest.Server) *kilo.Client {
	host := strings.TrimPrefix(srv.URL, "http://")
	parts := strings.Split(host, ":")
	port := 80
	if len(parts) == 2 {
		if n, err := strconv.Atoi(parts[1]); err == nil {
			port = n
		}
	}
	return kilo.NewClient(parts[0], port, "pw")
}

func TestEventsHandlerNoCursorLiveOnly(t *testing.T) {
	s := newTestAPI()
	// Seed the ring; without a cursor nothing is replayed (live-only stream).
	s.Ring.Append(event.Envelope{Type: "session.updated", SessionID: "ses_a", Ts: 1})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/events", nil)
	ctx, cancel := context.WithCancel(req.Context())
	req = req.WithContext(ctx)
	rec := newSafeRecorder()
	done := make(chan struct{})
	go func() {
		s.HandleEvents(rec, req)
		close(done)
	}()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(rec.String(), "server.connected") {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	time.Sleep(100 * time.Millisecond) // give a moment to catch a mis-replay
	cancel()
	<-done
	out := rec.String()
	if !strings.Contains(out, "server.connected") {
		t.Fatalf("missing server.connected:\n%s", out)
	}
	if strings.Contains(out, `"session.updated"`) {
		t.Fatalf("no-cursor stream must not replay buffered events:\n%s", out)
	}
}

func TestEventsHandlerCursorSkipsSeen(t *testing.T) {
	s := newTestAPI()
	s.Ring.Append(event.Envelope{Type: "session.updated", SessionID: "ses_a", Ts: 1})
	s.Ring.Append(event.Envelope{Type: "message.part.updated", SessionID: "ses_a", Ts: 2})
	s.Ring.Append(event.Envelope{Type: "session.updated", SessionID: "ses_a", Ts: 3})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/events?cursor=1", nil)
	ctx, cancel := context.WithCancel(req.Context())
	req = req.WithContext(ctx)
	rec := newSafeRecorder()
	done := make(chan struct{})
	go func() {
		s.HandleEvents(rec, req)
		close(done)
	}()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(rec.String(), `"session.updated"`) && strings.Contains(rec.String(), `"message.part.updated"`) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	<-done
	out := rec.String()
	if !strings.Contains(out, `"message.part.updated"`) {
		t.Fatalf("cursor=1 should replay newer events:\n%s", out)
	}
	// The seed at cursor 1 itself must not be replayed; the seed at cursor 3
	// (a session.updated) must be replayed.
	if strings.Count(out, `"session.updated"`) != 1 {
		t.Fatalf("expected exactly one replayed session.updated (cursor 3), got:\n%s", out)
	}
}

func TestEventsHandlerResyncRequired(t *testing.T) {
	s := newTestAPI()
	// Ring with capacity 3, filled 3 times so cursor 1 is gone.
	s.Ring = event.NewRing(3)
	for i := 0; i < 6; i++ {
		s.Ring.Append(event.Envelope{Type: "session.updated", Ts: int64(i)})
	}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/events?cursor=1", nil)
	ctx, cancel := context.WithCancel(req.Context())
	req = req.WithContext(ctx)
	rec := newSafeRecorder()
	done := make(chan struct{})
	go func() {
		s.HandleEvents(rec, req)
		close(done)
	}()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(rec.String(), "resync.required") {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	<-done
	if !strings.Contains(rec.String(), "resync.required") {
		t.Fatalf("expected resync.required:\n%s", rec.String())
	}
}

func TestHandleHealth(t *testing.T) {
	s := newTestAPI()
	rec := httptest.NewRecorder()
	s.HandleHealth(rec, httptest.NewRequest(http.MethodGet, "/health", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["status"] != "ok" || body["serverVersion"] != "test" || body["engine"] != "down" {
		t.Fatalf("health body: %v", body)
	}
}

func TestWriteErrorBody(t *testing.T) {
	rec := httptest.NewRecorder()
	writeError(rec, http.StatusNotFound, "session_not_found", "nope", false)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status %d", rec.Code)
	}
	var body struct {
		Error struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Error.Code != "session_not_found" {
		t.Fatalf("body: %s", rec.Body.String())
	}
}

func TestHandleQuestionContractAnswersAreNestedArrays(t *testing.T) {
	// Kilo's schema (see kilo 7.7.9 bundle) is
	// `QuestionReply = { answers: QuestionAnswer[] }` where each QuestionAnswer is
	// an array of selected labels, one per asked question in order. So a
	// single-select reply is `{"answers":[["label"]]}` and the server must relay
	// exactly that shape to kilo.
	var gotAnswers any
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		gotAnswers = body["answers"]
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	t.Cleanup(srv.Close)
	s := New(newKiloClientFor(t, srv), store.New(), supervisor.New("/nonexistent/kilo", "127.0.0.1", 1, "x", nil), "test", nil)
	s.Store.ApplyEvent(event.Event{Type: "session.created", SessionID: "ses_x", Data: map[string]any{
		"info": map[string]any{"id": "ses_x", "title": "X"},
	}})

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/sessions/ses_x/question", strings.NewReader(
		`{"questionID":"q_1","answers":[["DB-draft + fast poll (Recommended)"]]}`,
	))
	req.SetPathValue("id", "ses_x")
	s.HandleQuestion(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d, body %s", rec.Code, rec.Body.String())
	}
	if gotPath != "/question/q_1/reply" {
		t.Fatalf("kilo path = %q", gotPath)
	}
	outer, ok := gotAnswers.([]any)
	if !ok || len(outer) != 1 {
		t.Fatalf("answers relayed as %#v (want nested arrays)", gotAnswers)
	}
	inner, ok := outer[0].([]any)
	if !ok || len(inner) != 1 || inner[0] != "DB-draft + fast poll (Recommended)" {
		t.Fatalf("answers relayed as %#v (want nested string array)", gotAnswers)
	}
}

func TestHandleQuestionRejectsFlatStringAnswers(t *testing.T) {
	// kilo expects `answers` to be `QuestionAnswer[]` (each entry itself an
	// array of labels). A flat `["label"]` is rejected by the server's own
	// [][]string decoder with a clear 400 before any kilo call.
	s := newTestAPI()
	s.Store.ApplyEvent(event.Event{Type: "session.created", SessionID: "ses_x", Data: map[string]any{
		"info": map[string]any{"id": "ses_x", "title": "X"},
	}})
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/sessions/ses_x/question", strings.NewReader(
		`{"questionID":"q_1","answers":["DB-draft + fast poll"]}`,
	))
	req.SetPathValue("id", "ses_x")
	s.HandleQuestion(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status %d, want 400; body %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "invalid JSON body") {
		t.Fatalf("body: %s", rec.Body.String())
	}
}

func TestHandleSessionNotFound(t *testing.T) {
	s := newTestAPI()
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/sessions/nope", nil)
	req.SetPathValue("id", "nope")
	s.HandleSession(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status %d, want 404", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "session_not_found") {
		t.Fatalf("body: %s", rec.Body.String())
	}
}

func TestWildcardRuleRejected(t *testing.T) {
	if !isWildcardRule(json.RawMessage(`{"permission":"*","pattern":"*"}`)) {
		t.Fatal("wildcard rule not detected")
	}
	if isWildcardRule(json.RawMessage(`{"permission":"bash","pattern":"git *"}`)) {
		t.Fatal("specific rule flagged as wildcard")
	}
}

func TestHandlePending(t *testing.T) {
	s := newTestAPI()
	// Seed the store so ensureSession's fast-path passes without kilo.
	s.Store.ApplyEvent(event.Event{Type: "session.created", SessionID: "ses_x", Data: map[string]any{
		"info": map[string]any{"id": "ses_x", "title": "X"},
	}})
	s.Store.ApplyEvent(event.Event{Type: "permission.asked", SessionID: "ses_x", Data: map[string]any{
		"id": "perm_1", "sessionID": "ses_x", "permission": "bash", "pattern": "git status",
	}})
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/sessions/ses_x/pending", nil)
	req.SetPathValue("id", "ses_x")
	s.HandlePending(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d, body %s", rec.Code, rec.Body.String())
	}
	var body struct {
		Permissions []json.RawMessage `json:"permissions"`
		Questions   []json.RawMessage `json:"questions"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Permissions) != 1 || len(body.Questions) != 0 {
		t.Fatalf("body: %s", rec.Body.String())
	}
	if !strings.Contains(string(body.Permissions[0]), "perm_1") {
		t.Fatalf("permission payload: %s", string(body.Permissions[0]))
	}
}

func TestHandlePendingEmptyAndNotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/session/ses_known" {
			_, _ = w.Write([]byte(`{"id":"ses_known"}`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	t.Cleanup(srv.Close)
	s := New(newKiloClientFor(t, srv), store.New(), supervisor.New("/nonexistent/kilo", "127.0.0.1", 1, "x", nil), "test", nil)
	s.Store.ApplyEvent(event.Event{Type: "session.created", SessionID: "ses_known", Data: map[string]any{
		"info": map[string]any{"id": "ses_known", "title": "Known"},
	}})

	// Known session with nothing pending -> empty arrays.
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/sessions/ses_known/pending", nil)
	req.SetPathValue("id", "ses_known")
	s.HandlePending(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	var body struct {
		Permissions []json.RawMessage `json:"permissions"`
		Questions   []json.RawMessage `json:"questions"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Permissions == nil || body.Questions == nil {
		t.Fatalf("arrays must serialize as [] not null: %s", rec.Body.String())
	}
	if len(body.Permissions) != 0 || len(body.Questions) != 0 {
		t.Fatalf("body: %s", rec.Body.String())
	}

	// Unknown session -> 404 session_not_found via the authoritative kilo check.
	rec = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodGet, "/api/v1/sessions/ses_nope/pending", nil)
	req.SetPathValue("id", "ses_nope")
	s.HandlePending(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status %d, want 404", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "session_not_found") {
		t.Fatalf("body: %s", rec.Body.String())
	}
}
