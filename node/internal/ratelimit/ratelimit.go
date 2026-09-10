package ratelimit

import (
	"sync"
	"time"
)

type entry struct {
	count int
	reset time.Time
}
type Limiter struct {
	mu      sync.Mutex
	limit   int
	window  time.Duration
	now     func() time.Time
	entries map[string]entry
}

func New(limit int, window time.Duration, now func() time.Time) *Limiter {
	return &Limiter{limit: limit, window: window, now: now, entries: map[string]entry{}}
}
func (l *Limiter) Take(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	e, ok := l.entries[key]
	if !ok || !now.Before(e.reset) {
		l.entries[key] = entry{count: 1, reset: now.Add(l.window)}
		if len(l.entries) >= 1000 {
			for k, v := range l.entries {
				if !now.Before(v.reset) {
					delete(l.entries, k)
				}
			}
		}
		return true
	}
	if e.count >= l.limit {
		return false
	}
	e.count++
	l.entries[key] = e
	return true
}
