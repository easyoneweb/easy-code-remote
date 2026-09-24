package event

import "sync"

// Deduper drops duplicate kilo events by event.id. Kilo delivers every DB-sourced
// event twice (once wrapped as "sync", once live) with the same id, and may replay
// events on SSE reconnect; the seen-set prevents both.
type Deduper struct {
	mu      sync.Mutex
	seen    map[string]struct{}
	order   []string
	maxSize int
}

// NewDeduper returns a Deduper that remembers up to maxSize recent event ids.
func NewDeduper(maxSize int) *Deduper {
	if maxSize < 16 {
		maxSize = 16
	}
	return &Deduper{seen: make(map[string]struct{}, maxSize), maxSize: maxSize}
}

// Seen reports whether id was already seen and records it if new.
// Returns true when the event is a duplicate and should be dropped.
func (d *Deduper) Seen(id string) bool {
	if id == "" {
		return false // never dedupe events without an id (e.g. synthetic engine events)
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if _, ok := d.seen[id]; ok {
		return true
	}
	d.seen[id] = struct{}{}
	d.order = append(d.order, id)
	if len(d.order) > d.maxSize {
		old := d.order[0]
		d.order = d.order[1:]
		delete(d.seen, old)
	}
	return false
}
