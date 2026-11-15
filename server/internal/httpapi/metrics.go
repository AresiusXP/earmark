package httpapi

import (
	"errors"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
)

type metrics struct {
	requests *prometheus.CounterVec
	duration *prometheus.HistogramVec
	resolves *prometheus.CounterVec
}

func newMetrics(reg prometheus.Registerer) *metrics {
	return &metrics{
		requests: register(reg, prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "abk_http_requests_total", Help: "HTTP requests by route pattern and status.",
		}, []string{"route", "status"})),
		duration: register(reg, prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name: "abk_http_request_duration_seconds", Help: "HTTP request duration by route pattern.",
			Buckets: prometheus.DefBuckets,
		}, []string{"route"})),
		resolves: register(reg, prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "abk_resolve_total", Help: "Resolve outcomes by provider.",
		}, []string{"provider", "outcome"})),
	}
}

func register[C prometheus.Collector](reg prometheus.Registerer, c C) C {
	if err := reg.Register(c); err != nil {
		var are prometheus.AlreadyRegisteredError
		if errors.As(err, &are) {
			if existing, ok := are.ExistingCollector.(C); ok {
				return existing
			}
		}
		panic(err)
	}
	return c
}

func collectorsDefault() []prometheus.Collector {
	return []prometheus.Collector{collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{})}
}

// bucket is a token bucket: capacity tokens, refilled evenly over the period.
type bucket struct {
	mu     sync.Mutex
	tokens float64
	cap    float64
	rate   float64 // tokens per second
	last   time.Time
	now    func() time.Time
}

func newBucket(capacity int, perPeriod int, period time.Duration) *bucket {
	return &bucket{
		tokens: float64(capacity), cap: float64(capacity),
		rate: float64(perPeriod) / period.Seconds(), now: time.Now,
	}
}

func (b *bucket) allow() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	now := b.now()
	if !b.last.IsZero() {
		b.tokens = min(b.cap, b.tokens+now.Sub(b.last).Seconds()*b.rate)
	}
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}
