// Package store holds the in-memory view of kilo sessions, derived statuses and
// a small per-session message cache. It is fed by kilo SSE events and resync polls.
package store

import (
	"context"
	"encoding/json"
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
	mu          sync.RWMutex
	sessions    map[string]*sessionEntry
	order       []string // session ids in resync order (most recent first after sort on read)
	pendingPerm map[string]bool
	pendingQ    map[string]bool
	msgCache    map[string]msgCacheEntry
}

// New creates an empty Store.
func New() *Store {
	return &Store{
		sessions:    make(map[string]*sessionEntry),
		pendingPerm: make(map[string]bool),
		pendingQ:    make(map[string]bool),
		msgCache:    make(map[string]msgCacheEntry),
	}
}

// Resync fully refreshes sessions, statuses and pending lists from kilo.
func (s *Store) Resync(ctx context.Context, c *kilo.Client) error {
	sessions, err := c.Sessions(ctx)
	if err != nil {
		return err
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
	case "permission.replied":
		s.setPending(s.pendingPerm, e.SessionID, false)
	case "question.asked":
		s.setPending(s.pendingQ, e.SessionID, true)
	case "question.replied", "question.rejected":
		s.setPending(s.pendingQ, e.SessionID, false)
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
	key := sessionID + "|" + before
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
