// Package store holds the in-memory view of kilo sessions, derived statuses and
// a small per-session message cache. It is fed by kilo SSE events and resync polls.
package store

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/easyoneweb/easy-code-remote/server/internal/event"
	"github.com/easyoneweb/easy-code-remote/server/internal/kilo"
)

// MessageCacheTTL bounds how long a fetched transcript is reused.
const MessageCacheTTL = 2 * time.Second

// PollInterval is how often pending permissions/questions are refreshed.
const PollInterval = 10 * time.Second

type sessionEntry struct {
	info   map[string]any
	status string // kilo raw status type: busy|idle|retry|offline
}

type msgCacheEntry struct {
	data    json.RawMessage
	fetched time.Time
}

// Store is the in-memory session/status store.
type Store struct {
	mu                  sync.RWMutex
	sessions            map[string]*sessionEntry
	order               []string // session ids in resync order (most recent first after sort on read)
	pendingPerm         map[string]bool
	pendingQ            map[string]bool
	pendingPermPayloads map[string][]json.RawMessage // raw kilo /permission payloads per session
	pendingQPayloads    map[string][]json.RawMessage // raw kilo /question payloads per session
	msgCache            map[string]msgCacheEntry
}

// New creates an empty Store.
func New() *Store {
	return &Store{
		sessions:            make(map[string]*sessionEntry),
		pendingPerm:         make(map[string]bool),
		pendingQ:            make(map[string]bool),
		pendingPermPayloads: make(map[string][]json.RawMessage),
		pendingQPayloads:    make(map[string][]json.RawMessage),
		msgCache:            make(map[string]msgCacheEntry),
	}
}

// Resync fully refreshes sessions, statuses and pending lists from kilo.
//
// Session list source: kilo's /session HTTP endpoint is scoped to the serve
// process's current project, so it cannot see sessions from other projects.
// SessionsAll uses kilo's own `db` CLI against the shared kilo.db instead and
// falls back to the scoped /session endpoint if that is unavailable.
func (s *Store) Resync(ctx context.Context, c *kilo.Client) error {
	sessions, err := c.SessionsAll(ctx)
	if err != nil {
		sessions, err = c.Sessions(ctx) // fallback: current project only
		if err != nil {
			return err
		}
	}
	statuses, err := c.SessionStatus(ctx)
	if err != nil {
		return err
	}
	perms, err := c.PermissionList(ctx)
	if err != nil {
		return err
	}
	questions, err := c.QuestionList(ctx)
	if err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions = make(map[string]*sessionEntry, len(sessions))
	s.order = s.order[:0]
	for _, raw := range sessions {
		var m map[string]any
		if json.Unmarshal(raw, &m) != nil || m["id"] == nil {
			continue
		}
		id, _ := m["id"].(string)
		if id == "" {
			continue
		}
		s.sessions[id] = &sessionEntry{info: m}
		s.order = append(s.order, id)
	}
	for id, st := range statuses {
		if e, ok := s.sessions[id]; ok {
			e.status = st.Type
		}
	}
	s.pendingPerm = pendingSet(perms)
	s.pendingQ = pendingSet(questions)
	s.pendingPermPayloads = groupPayloads(perms)
	s.pendingQPayloads = groupPayloads(questions)
	return nil
}

func pendingSet(entries []json.RawMessage) map[string]bool {
	out := make(map[string]bool)
	for _, raw := range entries {
		var m map[string]any
		if json.Unmarshal(raw, &m) != nil {
			continue
		}
		if id, ok := m["sessionID"].(string); ok && id != "" {
			out[id] = true
		}
	}
	return out
}

// groupPayloads groups raw kilo pending entries by their sessionID field.
func groupPayloads(entries []json.RawMessage) map[string][]json.RawMessage {
	out := make(map[string][]json.RawMessage)
	for _, raw := range entries {
		var m map[string]any
		if json.Unmarshal(raw, &m) != nil {
			continue
		}
		id, _ := m["sessionID"].(string)
		if id == "" {
			continue
		}
		out[id] = append(out[id], raw)
	}
	return out
}

// PollPending refreshes the pending permission/question sets.
func (s *Store) PollPending(ctx context.Context, c *kilo.Client) error {
	perms, err := c.PermissionList(ctx)
	if err != nil {
		return err
	}
	questions, err := c.QuestionList(ctx)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pendingPerm = pendingSet(perms)
	s.pendingQ = pendingSet(questions)
	s.pendingPermPayloads = groupPayloads(perms)
	s.pendingQPayloads = groupPayloads(questions)
	return nil
}

// ResetPending clears all retained pending permission/question state. Used
// before merging fresh lists from every kilo serve (each serve only sees its
// own project's pending items, so the union across serves is the global view).
func (s *Store) ResetPending() {
	s.mu.Lock()
	defer s.mu.Unlock()
	clear(s.pendingPerm)
	clear(s.pendingQ)
	clear(s.pendingPermPayloads)
	clear(s.pendingQPayloads)
}

// MergePending unions one kilo serve's pending permission/question lists into
// the store. Multiple serves cover different projects.
func (s *Store) MergePending(ctx context.Context, c *kilo.Client) error {
	perms, err := c.PermissionList(ctx)
	if err != nil {
		return err
	}
	questions, err := c.QuestionList(ctx)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for id := range pendingSet(perms) {
		s.pendingPerm[id] = true
	}
	for id := range pendingSet(questions) {
		s.pendingQ[id] = true
	}
	for sid, payloads := range groupPayloads(perms) {
		s.pendingPermPayloads[sid] = append(s.pendingPermPayloads[sid], payloads...)
	}
	for sid, payloads := range groupPayloads(questions) {
		s.pendingQPayloads[sid] = append(s.pendingQPayloads[sid], payloads...)
	}
	return nil
}

// MergeStatuses unions one kilo serve's /session/status map into the store
// (statuses are project-scoped per serve; the union is the global view).
func (s *Store) MergeStatuses(ctx context.Context, c *kilo.Client) error {
	statuses, err := c.SessionStatus(ctx)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for sid, st := range statuses {
		if e, ok := s.sessions[sid]; ok {
			e.status = st.Type
		}
	}
	return nil
}

// ApplyEvent updates the store from one normalized kilo event.
func (s *Store) ApplyEvent(e event.Event) {
	switch e.Type {
	case "session.created", "session.updated":
		info, ok := e.Data["info"].(map[string]any)
		if !ok {
			info = e.Data
		}
		id, _ := info["id"].(string)
		if id == "" {
			return
		}
		s.mu.Lock()
		if _, exists := s.sessions[id]; !exists {
			s.order = append(s.order, id)
		}
		s.sessions[id] = &sessionEntry{info: info}
		s.mu.Unlock()
	case "session.deleted":
		s.mu.Lock()
		if _, ok := s.sessions[e.SessionID]; ok {
			delete(s.sessions, e.SessionID)
		}
		delete(s.pendingPerm, e.SessionID)
		delete(s.pendingQ, e.SessionID)
		delete(s.pendingPermPayloads, e.SessionID)
		delete(s.pendingQPayloads, e.SessionID)
		s.mu.Unlock()
	case "session.status":
		var status string
		if st, ok := e.Data["status"].(map[string]any); ok {
			status, _ = st["type"].(string)
		}
		if status == "" {
			status, _ = e.Data["type"].(string)
		}
		s.mu.Lock()
		if e2, ok := s.sessions[e.SessionID]; ok {
			e2.status = status
		}
		s.mu.Unlock()
	case "permission.asked":
		s.setPending(s.pendingPerm, e.SessionID, true)
		s.addPendingPayload(s.pendingPermPayloads, e.SessionID, e.Data)
	case "permission.replied":
		s.setPending(s.pendingPerm, e.SessionID, false)
		s.clearPendingPayload(s.pendingPermPayloads, e.SessionID)
	case "question.asked":
		s.setPending(s.pendingQ, e.SessionID, true)
		s.addPendingPayload(s.pendingQPayloads, e.SessionID, e.Data)
	case "question.replied", "question.rejected":
		s.setPending(s.pendingQ, e.SessionID, false)
		s.clearPendingPayload(s.pendingQPayloads, e.SessionID)
	}
}

func (s *Store) setPending(m map[string]bool, sid string, v bool) {
	if sid == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if v {
		m[sid] = true
	} else {
		delete(m, sid)
	}
}

// addPendingPayload retains the raw kilo payload of a permission.asked /
// question.asked event so phones can render approvals after missed events.
func (s *Store) addPendingPayload(m map[string][]json.RawMessage, sid string, data map[string]any) {
	if sid == "" || data == nil {
		return
	}
	raw, err := json.Marshal(data)
	if err != nil || string(raw) == "null" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	m[sid] = append(m[sid], raw)
}

// clearPendingPayload drops all retained payloads for a session (resolved).
func (s *Store) clearPendingPayload(m map[string][]json.RawMessage, sid string) {
	if sid == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(m, sid)
}

// Session returns one session map (with derived status) or nil.
func (s *Store) Session(id string) map[string]any {
	s.mu.RLock()
	defer s.mu.RUnlock()
	e, ok := s.sessions[id]
	if !ok {
		return nil
	}
	return s.decorate(e)
}

// Sessions returns all sessions, most recently updated first, with derived status.
func (s *Store) Sessions() []map[string]any {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]map[string]any, 0, len(s.order))
	for _, id := range s.order {
		if e, ok := s.sessions[id]; ok {
			out = append(out, s.decorate(e))
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		return updatedTime(out[i]) > updatedTime(out[j])
	})
	return out
}

func updatedTime(m map[string]any) int64 {
	tm, ok := m["time"].(map[string]any)
	if !ok {
		return 0
	}
	v, _ := tm["updated"].(float64)
	return int64(v)
}

// Pending returns the raw pending permission/question payloads retained for a
// session. Both slices are non-nil (serialize as []) when nothing is pending.
func (s *Store) Pending(sessionID string) ([]json.RawMessage, []json.RawMessage) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	perms := make([]json.RawMessage, 0)
	if p, ok := s.pendingPermPayloads[sessionID]; ok {
		perms = append(perms, p...)
	}
	qs := make([]json.RawMessage, 0)
	if q, ok := s.pendingQPayloads[sessionID]; ok {
		qs = append(qs, q...)
	}
	return perms, qs
}

func (s *Store) decorate(e *sessionEntry) map[string]any {
	out := make(map[string]any, len(e.info)+2)
	for k, v := range e.info {
		out[k] = v
	}
	status, reason := s.deriveStatus(e)
	out["status"] = status
	if reason != "" {
		out["waitingReason"] = reason
	}
	return out
}

// deriveStatus implements design decision 12.
func (s *Store) deriveStatus(e *sessionEntry) (string, string) {
	id := e.info["id"].(string)
	if s.pendingPerm[id] {
		return "waiting", "permission"
	}
	if s.pendingQ[id] {
		return "waiting", "question"
	}
	switch e.status {
	case "busy", "retry":
		return "running", ""
	case "idle", "offline", "":
		return "idle", ""
	}
	return "idle", ""
}

// Messages returns a cached or freshly fetched transcript in the kilo shape.
func (s *Store) Messages(ctx context.Context, c *kilo.Client, sessionID string, limit int, before string) (json.RawMessage, error) {
	if limit <= 0 {
		limit = 50
	}
	if limit > 500 {
		limit = 500
	}
	key := fmt.Sprintf("%s|%d|%s", sessionID, limit, before)
	s.mu.RLock()
	ce, ok := s.msgCache[key]
	s.mu.RUnlock()
	if ok && time.Since(ce.fetched) < MessageCacheTTL {
		return ce.data, nil
	}
	data, err := c.Messages(ctx, sessionID, limit, before)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.msgCache[key] = msgCacheEntry{data: data, fetched: time.Now()}
	if len(s.msgCache) > 1000 { // lazy eviction of stale entries
		for k, v := range s.msgCache {
			if time.Since(v.fetched) > 30*time.Second {
				delete(s.msgCache, k)
			}
		}
	}
	s.mu.Unlock()
	return data, nil
}
