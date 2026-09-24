package event

import (
	"fmt"
	"testing"
)

func TestStripVersion(t *testing.T) {
	cases := map[string]string{
		"session.updated.1":  "session.updated",
		"session.updated":    "session.updated",
		"server.connected":   "server.connected",
		"message.updated.99": "message.updated",
	}
	for in, want := range cases {
		if got := StripVersion(in); got != want {
			t.Fatalf("StripVersion(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestNormalizeSyncWrapper(t *testing.T) {
	raw := []byte(`{"type":"sync","syncEvent":{"id":"evt_1","type":"session.updated.1","seq":7,"aggregateID":"ses_abc","data":{"sessionID":"ses_abc","info":{"id":"ses_abc"}}},"id":"evt_1"}`)
	e, err := Normalize(raw)
	if err != nil {
		t.Fatal(err)
	}
	if e.ID != "evt_1" || e.Type != "session.updated" || e.Seq != 7 || e.SessionID != "ses_abc" {
		t.Fatalf("unexpected normalized event: %+v", e)
	}
	info, ok := e.Data["info"].(map[string]any)
	if !ok || info["id"] != "ses_abc" {
		t.Fatalf("data.info missing: %+v", e.Data)
	}
}

func TestNormalizeLiveEvent(t *testing.T) {
	raw := []byte(`{"id":"evt_2","type":"session.status","properties":{"sessionID":"ses_abc","status":{"type":"busy"}}}`)
	e, err := Normalize(raw)
	if err != nil {
		t.Fatal(err)
	}
	if e.Type != "session.status" || e.SessionID != "ses_abc" {
		t.Fatalf("unexpected: %+v", e)
	}
	if st, ok := e.Data["status"].(map[string]any); !ok || st["type"] != "busy" {
		t.Fatalf("status payload missing: %+v", e.Data)
	}
}

func TestNormalizeLiveMessageExtractsIDs(t *testing.T) {
	// Live message events carry the payload in properties (incl. info), not data.
	raw := []byte(`{"id":"evt_2","type":"message.updated","properties":{"sessionID":"ses_abc","info":{"id":"msg_7","role":"user"}}}`)
	e, err := Normalize(raw)
	if err != nil {
		t.Fatal(err)
	}
	if e.SessionID != "ses_abc" || e.MessageID != "msg_7" {
		t.Fatalf("ids not extracted from live properties: %+v", e)
	}
}

func TestNormalizeDeltaMapping(t *testing.T) {
	raw := []byte(`{"id":"evt_3","type":"message.part.delta","properties":{"sessionID":"ses_abc","messageID":"msg_1","partID":"prt_9","field":"text","delta":"Hel"}}`)
	e, err := Normalize(raw)
	if err != nil {
		t.Fatal(err)
	}
	if e.Type != "message.part.delta" || e.SessionID != "ses_abc" || e.MessageID != "msg_1" || e.PartID != "prt_9" {
		t.Fatalf("ids not extracted: %+v", e)
	}
	part, ok := e.Data["part"].(map[string]any)
	if !ok || part["text"] != "Hel" || part["id"] != "prt_9" {
		t.Fatalf("part mapping wrong: %+v", e.Data)
	}
	delta, ok := e.Data["delta"].(map[string]any)
	if !ok || delta["textDelta"] != "Hel" || delta["type"] != "text-delta" {
		t.Fatalf("delta mapping wrong: %+v", e.Data)
	}
}

func TestWhitelist(t *testing.T) {
	allowed := []string{"session.updated", "session.created", "session.deleted", "session.status",
		"session.wakeup", "session.turn.open", "session.turn.close", "session.idle", "session.error",
		"message.updated", "message.removed", "message.part.updated", "message.part.removed",
		"permission.asked", "permission.replied", "question.asked", "question.replied",
		"question.rejected", "server.connected", "engine.connected", "engine.disconnected",
		"todo.updated", "server.heartbeat", "session.diff"}
	for _, t2 := range allowed {
		if !Whitelisted(t2) {
			t.Fatalf("%q should be whitelisted", t2)
		}
	}
	blocked := []string{"sync", "unknown.thing", "file.written", "step_start"}
	for _, t2 := range blocked {
		if Whitelisted(t2) {
			t.Fatalf("%q should NOT be whitelisted", t2)
		}
	}
}

func TestDeduper(t *testing.T) {
	d := NewDeduper(64)
	if d.Seen("evt_1") {
		t.Fatal("first occurrence should be new")
	}
	if !d.Seen("evt_1") {
		t.Fatal("second occurrence must be a duplicate")
	}
	if d.Seen("") {
		t.Fatal("empty id must never be deduped (synthetic events)")
	}
	// Bounded: ids evicted after maxSize pushes are accepted again.
	d2 := NewDeduper(16)
	for i := 0; i < 16; i++ {
		if d2.Seen(fmt.Sprintf("id_%d", i)) {
			t.Fatalf("id_%d should be new", i)
		}
	}
	if !d2.Seen("id_0") {
		t.Fatal("id_0 should still be in the seen set")
	}
	d2.Seen("id_16") // evicts id_0
	if d2.Seen("id_0") {
		t.Fatal("id_0 was evicted and should be accepted again")
	}
}

func TestRingReplay(t *testing.T) {
	r := NewRing(3)
	var cursors []uint64
	for i := 0; i < 5; i++ {
		cursors = append(cursors, r.Append(Envelope{Type: "session.updated", Ts: int64(i)}))
	}
	// Buffer holds the last 3 (cursors 3,4,5).
	events, ok := r.Since(cursors[1])
	if ok {
		t.Fatal("cursor 2 is older than the buffer; Since should report !ok")
	}
	events, ok = r.Since(cursors[2])
	if !ok || len(events) != 2 {
		t.Fatalf("expected 2 replayable events, got %d ok=%v", len(events), ok)
	}
	if events[0].Ts != 3 || events[1].Ts != 4 {
		t.Fatalf("replay order wrong: %+v", events)
	}
	events, ok = r.Since(cursors[4])
	if !ok || len(events) != 0 {
		t.Fatalf("cursor at latest should replay nothing, got %d ok=%v", len(events), ok)
	}
	// Future cursor also replays nothing.
	events, ok = r.Since(999)
	if !ok || len(events) != 0 {
		t.Fatalf("future cursor should replay nothing, got %d ok=%v", len(events), ok)
	}
}

func TestRingCursorsMonotonic(t *testing.T) {
	r := NewRing(10)
	a := r.Append(Envelope{Type: "a"})
	b := r.Append(Envelope{Type: "b"})
	if b != a+1 {
		t.Fatalf("cursors not monotonic: %d then %d", a, b)
	}
	if r.Latest() != b || r.Oldest() != a {
		t.Fatalf("Latest=%d Oldest=%d, want %d %d", r.Latest(), r.Oldest(), b, a)
	}
}
