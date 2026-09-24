package api

import (
	"sync"

	"github.com/easyoneweb/easy-code-remote/server/internal/event"
)

// Hub fans envelopes out to phone SSE subscribers. A slow subscriber is dropped
// (events are recoverable via the ring buffer cursor).
type Hub struct {
	mu   sync.Mutex
	subs map[chan event.Envelope]struct{}
}

// NewHub creates an empty Hub.
func NewHub() *Hub {
	return &Hub{subs: make(map[chan event.Envelope]struct{})}
}

// Subscribe registers a subscriber channel (buffered).
func (h *Hub) Subscribe() chan event.Envelope {
	ch := make(chan event.Envelope, 256)
	h.mu.Lock()
	h.subs[ch] = struct{}{}
	h.mu.Unlock()
	return ch
}

// Unsubscribe removes a subscriber channel.
func (h *Hub) Unsubscribe(ch chan event.Envelope) {
	h.mu.Lock()
	if _, ok := h.subs[ch]; ok {
		delete(h.subs, ch)
		close(ch)
	}
	h.mu.Unlock()
}

// Publish delivers an envelope to every subscriber without blocking.
func (h *Hub) Publish(env event.Envelope) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs {
		select {
		case ch <- env:
		default:
			// Subscriber is slow; drop the event. It can recover via cursor replay.
		}
	}
}
