package httpapi

import (
	"sync"
	"time"
)

// rateLimiter is a per-IP token bucket (refill `rate` tokens/sec, cap `burst`).
type rateLimiter struct {
	mu      sync.Mutex
	buckets map[string]*bucket
	rate    float64
	burst   float64
}

type bucket struct {
	tokens float64
	last   time.Time
}

func newRateLimiter(rate, burst float64) *rateLimiter {
	if rate <= 0 {
		rate = 30
	}
	if burst <= 0 {
		burst = 60
	}
	return &rateLimiter{buckets: make(map[string]*bucket), rate: rate, burst: burst}
}

// Allow reports whether a request from ip may proceed, consuming one token.
func (rl *rateLimiter) Allow(ip string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	now := time.Now()
	b, ok := rl.buckets[ip]
	if !ok {
		b = &bucket{tokens: rl.burst, last: now}
		rl.buckets[ip] = b
	}
	elapsed := now.Sub(b.last).Seconds()
	b.tokens += elapsed * rl.rate
	if b.tokens > rl.burst {
		b.tokens = rl.burst
	}
	b.last = now
	if b.tokens >= 1 {
		b.tokens--
		return true
	}
	return false
}

// Cleanup removes buckets that are full and idle for more than 10 minutes.
func (rl *rateLimiter) Cleanup() {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	cutoff := time.Now().Add(-10 * time.Minute)
	for ip, b := range rl.buckets {
		if b.tokens >= rl.burst && b.last.Before(cutoff) {
			delete(rl.buckets, ip)
		}
	}
}
