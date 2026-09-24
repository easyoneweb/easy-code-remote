package httpapi

import (
	"testing"
	"time"
)

func TestRateLimiterBurstThenThrottle(t *testing.T) {
	rl := newRateLimiter(1, 3) // 1 token/sec, burst 3
	ip := "10.0.0.1"
	for i := 0; i < 3; i++ {
		if !rl.Allow(ip) {
			t.Fatalf("request %d should be allowed within burst", i+1)
		}
	}
	if rl.Allow(ip) {
		t.Fatal("request beyond burst should be denied")
	}
	// Refill over ~1.1s should grant 1 token.
	time.Sleep(1100 * time.Millisecond)
	if !rl.Allow(ip) {
		t.Fatal("expected refilled token to be available")
	}
	if rl.Allow(ip) {
		t.Fatal("second request should exceed the refilled single token")
	}
}

func TestRateLimiterPerIP(t *testing.T) {
	rl := newRateLimiter(0.1, 2)
	a, b := "10.0.0.1", "10.0.0.2"
	if !rl.Allow(a) || !rl.Allow(a) {
		t.Fatal("a should use its own burst")
	}
	if !rl.Allow(b) {
		t.Fatal("b must not be affected by a's burst")
	}
	if rl.Allow(a) {
		t.Fatal("a should be throttled after its own burst")
	}
}

func TestRateLimiterCleanup(t *testing.T) {
	rl := newRateLimiter(1, 1)
	ip := "10.0.0.9"
	if !rl.Allow(ip) {
		t.Fatal("first request must be allowed")
	}
	rl.buckets[ip].tokens = rl.burst // make it full
	rl.buckets[ip].last = time.Now().Add(-20 * time.Minute)
	rl.Cleanup()
	rl.mu.Lock()
	_, ok := rl.buckets[ip]
	rl.mu.Unlock()
	if ok {
		t.Fatal("idle full bucket should have been removed")
	}
}
