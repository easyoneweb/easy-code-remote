package api

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
	"sync"
	"time"

	"github.com/easyoneweb/easy-code-remote/server/internal/event"
	"github.com/easyoneweb/easy-code-remote/server/internal/kilo"
	"github.com/easyoneweb/easy-code-remote/server/internal/store"
	"github.com/easyoneweb/easy-code-remote/server/internal/supervisor"
)

// extraStream is a live connection to one additional kilo serve process
// (VSCode windows spawn their own project-scoped `kilo serve`).
type extraStream struct {
	ev     *event.Client
	kc     *kilo.Client
	port   int
	cancel context.CancelFunc
}

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

	extraMu      sync.Mutex
	extraStreams map[int]*extraStream // port -> stream to a discovered kilo serve

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
		K:            k,
		Store:        st,
		Hub:          NewHub(),
		Super:        sup,
		Ring:         event.NewRing(10000),
		Version:      version,
		Logf:         logf,
		dedup:        event.NewDeduper(4096),
		extraStreams: make(map[int]*extraStream),
	}
}

// Run starts the event pipeline: kilo SSE consumption, engine-health synthetic
// events, the pending-permission/question poller, and discovery of the other
// kilo serves on this host (VSCode windows). Blocks until ctx is done.
func (s *Server) Run(ctx context.Context) {
	sse := event.NewClient(s.K.BaseURL, s.K.User, s.K.Pass, s.Logf)
	sse.OnEvent = s.handleKiloEvent
	sse.OnConnected = s.handleEngineConnected
	sse.OnDisconnected = s.handleEngineDisconnected
	go sse.Run(ctx)
	go s.pollPendingLoop(ctx)
	go s.runDiscovery(ctx)
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
	s.mergeFromExtraStreams(ctx)
	s.emitSynthetic("engine.connected", map[string]any{"kiloVersion": s.KiloVersion})
}

// mergeFromExtraStreams unions pending + statuses from every discovered kilo
// serve (each one sees only its own project).
func (s *Server) mergeFromExtraStreams(ctx context.Context) {
	for _, x := range s.snapshotStreams() {
		if err := s.Store.MergePending(ctx, x.kc); err != nil {
			s.Logf("api: merge pending from :%d failed: %v", x.port, err)
		}
		if err := s.Store.MergeStatuses(ctx, x.kc); err != nil {
			s.Logf("api: merge statuses from :%d failed: %v", x.port, err)
		}
	}
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

// runDiscovery periodically scans for `kilo serve` processes (VSCode windows,
// CLI TUIs) and keeps an /event stream + kilo client for each, so sessions from
// every project reach the phone even though each serve is project-scoped.
func (s *Server) runDiscovery(ctx context.Context) {
	skipPort := supervisedPort(s.K.BaseURL)
	refresh := func() {
		serves, err := kilo.DiscoverServes(skipPort)
		if err != nil {
			s.Logf("api: discover kilo serves: %v", err)
			return
		}
		s.refreshStreams(ctx, serves)
	}
	refresh()
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			refresh()
		}
	}
}

func (s *Server) refreshStreams(ctx context.Context, serves []kilo.Serve) {
	s.extraMu.Lock()
	defer s.extraMu.Unlock()
	alive := make(map[int]bool, len(serves))
	for _, sv := range serves {
		alive[sv.Port] = true
		if _, ok := s.extraStreams[sv.Port]; ok {
			continue
		}
		base := fmt.Sprintf("http://127.0.0.1:%d", sv.Port)
		ev := event.NewClient(base, kilo.AuthUser, sv.Pass, s.Logf)
		ev.OnEvent = s.handleKiloEvent
		port := sv.Port
		ev.OnConnected = func() { s.Logf("api: extra kilo stream connected :%d", port) }
		ev.OnDisconnected = func(err error) { s.Logf("api: extra kilo stream :%d disconnected: %v", port, err) }
		kc := kilo.NewClient("127.0.0.1", sv.Port, sv.Pass)
		sctx, cancel := context.WithCancel(ctx)
		x := &extraStream{ev: ev, kc: kc, port: sv.Port, cancel: cancel}
		s.extraStreams[sv.Port] = x
		go ev.Run(sctx)
		// Fast pending/status seed from the newly discovered serve.
		go func(x *extraStream) {
			pctx, cancel := context.WithTimeout(ctx, 15*time.Second)
			defer cancel()
			if err := s.Store.MergePending(pctx, x.kc); err != nil {
				s.Logf("api: merge pending from :%d failed: %v", x.port, err)
			}
		}(x)
	}
	for port, x := range s.extraStreams {
		if !alive[port] {
			delete(s.extraStreams, port)
			x.cancel() // stop the event client's reconnect loop
		}
	}
}

// snapshotStreams returns a copy of the current extra streams.
func (s *Server) snapshotStreams() []*extraStream {
	s.extraMu.Lock()
	defer s.extraMu.Unlock()
	out := make([]*extraStream, 0, len(s.extraStreams))
	for _, x := range s.extraStreams {
		out = append(out, x)
	}
	return out
}

// pendingClients returns the supervised client plus every extra stream's client.
func (s *Server) pendingClients() []*kilo.Client {
	out := []*kilo.Client{s.K}
	for _, x := range s.snapshotStreams() {
		out = append(out, x.kc)
	}
	return out
}

func (s *Server) pollPendingLoop(ctx context.Context) {
	t := time.NewTicker(store.PollInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			pctx, cancel := context.WithTimeout(ctx, 15*time.Second)
			s.Store.ResetPending()
			for _, c := range s.pendingClients() {
				if err := s.Store.MergePending(pctx, c); err != nil {
					s.Logf("api: pending poll failed: %v", err)
				}
			}
			cancel()
		}
	}
}

// supervisedPort extracts the port from a kilo client base URL.
func supervisedPort(baseURL string) int {
	u, err := url.Parse(baseURL)
	if err != nil {
		return 0
	}
	port, _ := strconv.Atoi(u.Port())
	return port
}

// EngineUp reports whether the supervised kilo engine currently answers.
func (s *Server) EngineUp() bool {
	return s.Super.Up()
}
