package api

import (
	"context"
	"sync"
	"time"

	"github.com/easyoneweb/easy-code-remote/server/internal/event"
	"github.com/easyoneweb/easy-code-remote/server/internal/kilo"
	"github.com/easyoneweb/easy-code-remote/server/internal/store"
	"github.com/easyoneweb/easy-code-remote/server/internal/supervisor"
)

// Server wires the kilo engine, the store, the event pipeline and the phone API.
type Server struct {
	K       *kilo.Client
	Store   *store.Store
	Hub     *Hub
	Super   *supervisor.Supervisor
	Ring    *event.Ring
	Version string
	Logf    func(format string, args ...any)

	KiloVersion string

	dedup *event.Deduper

	configMu      sync.Mutex
	configData    any
	configFetched time.Time
}

// New assembles the API server components.
func New(k *kilo.Client, st *store.Store, sup *supervisor.Supervisor, version string, logf func(string, ...any)) *Server {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	return &Server{
		K:       k,
		Store:   st,
		Hub:     NewHub(),
		Super:   sup,
		Ring:    event.NewRing(10000),
		Version: version,
		Logf:    logf,
		dedup:   event.NewDeduper(4096),
	}
}

// Run starts the event pipeline: kilo SSE consumption, engine-health synthetic
// events, and the pending-permission/question poller. Blocks until ctx is done.
func (s *Server) Run(ctx context.Context) {
	sse := event.NewClient(s.K.BaseURL, s.K.User, s.K.Pass, s.Logf)
	sse.OnEvent = s.handleKiloEvent
	sse.OnConnected = s.handleEngineConnected
	sse.OnDisconnected = s.handleEngineDisconnected
	go sse.Run(ctx)
	go s.pollPendingLoop(ctx)
	<-ctx.Done()
}

func (s *Server) handleKiloEvent(e event.Event) {
	if s.dedup.Seen(e.ID) {
		return // kilo delivers every DB event twice (sync wrapper + live) and replays on reconnect
	}
	s.Store.ApplyEvent(e)
	if !event.Whitelisted(e.Type) {
		return
	}
	env := e.Envelope(time.Now().UnixMilli(), 0)
	env.Cursor = s.Ring.Append(env)
	s.Hub.Publish(env)
}

func (s *Server) handleEngineConnected() {
	// Full resync after an SSE (re)connect: sessions/status/pending may have
	// drifted while the stream was down.
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := s.Store.Resync(ctx, s.K); err != nil {
		s.Logf("api: resync failed: %v", err)
	}
	s.emitSynthetic("engine.connected", map[string]any{"kiloVersion": s.KiloVersion})
}

func (s *Server) handleEngineDisconnected(err error) {
	s.emitSynthetic("engine.disconnected", map[string]any{"reason": err.Error()})
}

// emitSynthetic publishes a server-generated event (no kilo id -> never deduped).
func (s *Server) emitSynthetic(typ string, data map[string]any) {
	env := event.Envelope{Type: typ, Data: data, Ts: time.Now().UnixMilli()}
	env.Cursor = s.Ring.Append(env)
	s.Hub.Publish(env)
}

func (s *Server) pollPendingLoop(ctx context.Context) {
	t := time.NewTicker(store.PollInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			pctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := s.Store.PollPending(pctx, s.K)
			cancel()
			if err != nil {
				s.Logf("api: pending poll failed: %v", err)
			}
		}
	}
}

// EngineUp reports whether the supervised kilo engine currently answers.
func (s *Server) EngineUp() bool {
	return s.Super.Up()
}
