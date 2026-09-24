package event

import "sync"

// Ring is a bounded in-memory buffer of phone envelopes supporting cursor-based replay.
type Ring struct {
	mu     sync.Mutex
	buf    []Envelope
	head   int // next write slot
	count  int
	next   uint64 // next cursor value (strictly increasing, 1-based)
	oldest uint64 // cursor of the oldest buffered envelope (0 when empty)
}

// NewRing creates a Ring with the given capacity.
func NewRing(capacity int) *Ring {
	if capacity < 1 {
		capacity = 1
	}
	return &Ring{buf: make([]Envelope, capacity)}
}

// Append stores the envelope (assigning its cursor) and returns the new cursor.
func (r *Ring) Append(e Envelope) uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.next++
	e.Cursor = r.next
	slot := (r.head + r.count) % len(r.buf)
	r.buf[slot] = e
	if r.count < len(r.buf) {
		r.count++
	} else {
		r.head = (r.head + 1) % len(r.buf)
	}
	r.oldest = r.buf[r.head].Cursor
	return e.Cursor
}

// Latest returns the most recently assigned cursor (0 when empty).
func (r *Ring) Latest() uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.next
}

// Oldest returns the cursor of the oldest buffered envelope.
func (r *Ring) Oldest() uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.oldest
}

// Since returns envelopes with cursor > after, in order. ok is false when after is
// older than the buffer (caller must signal resync).
func (r *Ring) Since(after uint64) (out []Envelope, ok bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if after >= r.next {
		return nil, true
	}
	if after < r.oldest && r.count == len(r.buf) {
		return nil, false
	}
	// Find the first slot with cursor > after.
	start := -1
	for i := 0; i < r.count; i++ {
		idx := (r.head + i) % len(r.buf)
		if r.buf[idx].Cursor > after {
			start = i
			break
		}
	}
	if start < 0 {
		return nil, true
	}
	for i := start; i < r.count; i++ {
		idx := (r.head + i) % len(r.buf)
		out = append(out, r.buf[idx])
	}
	return out, true
}
