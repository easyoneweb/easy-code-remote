// Package httpapi boots the HTTPS listener with the middleware chain
// (recovery, access log, rate limit, bearer auth) and the v1 routes.
package httpapi

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/easyoneweb/easy-code-remote/server/internal/api"
	"github.com/easyoneweb/easy-code-remote/server/internal/auth"
	"github.com/easyoneweb/easy-code-remote/server/internal/config"
)

// Server owns the HTTP listener and middleware wiring.
type Server struct {
	Cfg   *config.Config
	API   *api.Server
	Log   *slog.Logger
	Limit *rateLimiter
}

// New assembles the HTTP server components.
func New(cfg *config.Config, apiSrv *api.Server, log *slog.Logger) *Server {
	return &Server{
		Cfg:   cfg,
		API:   apiSrv,
		Log:   log,
		Limit: newRateLimiter(cfg.RateLimit.Rate, float64(cfg.RateLimit.Burst)),
	}
}

// Handler builds the full middleware-wrapped route table.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	// Auth-free liveness.
	mux.HandleFunc("GET /health", s.API.HandleHealth)

	apiMux := http.NewServeMux()
	apiMux.HandleFunc("GET /api/v1/sessions", s.API.HandleSessions)
	apiMux.HandleFunc("GET /api/v1/sessions/{id}", s.API.HandleSession)
	apiMux.HandleFunc("GET /api/v1/sessions/{id}/messages", s.API.HandleMessages)
	apiMux.HandleFunc("GET /api/v1/sessions/{id}/diff", s.API.HandleDiff)
	apiMux.HandleFunc("POST /api/v1/sessions/{id}/message", s.API.HandleSendMessage)
	apiMux.HandleFunc("POST /api/v1/sessions/{id}/abort", s.API.HandleAbort)
	apiMux.HandleFunc("POST /api/v1/sessions/{id}/command", s.API.HandleCommand)
	apiMux.HandleFunc("POST /api/v1/sessions/{id}/permission", s.API.HandlePermission)
	apiMux.HandleFunc("POST /api/v1/sessions/{id}/question", s.API.HandleQuestion)
	apiMux.HandleFunc("GET /api/v1/config", s.API.HandleConfig)
	apiMux.HandleFunc("GET /api/v1/events", s.API.HandleEvents)
	apiMux.HandleFunc("/api/v1/", s.notFound)

	var h http.Handler = apiMux
	h = auth.Middleware(h, s.Cfg.Token)
	h = s.rateLimit(h)
	h = s.accessLog(h)
	h = s.recover(h)

	mux.Handle("/", h)
	return mux
}

// Serve starts the TLS listener and blocks until ctx is cancelled.
func (s *Server) Serve(ctx context.Context) error {
	tlsCfg, err := s.tlsConfig()
	if err != nil {
		return err
	}
	ln, err := net.Listen("tcp", s.Cfg.ListenAddr)
	if err != nil {
		return fmt.Errorf("listen %s: %w", s.Cfg.ListenAddr, err)
	}
	tlsLn := tls.NewListener(ln, tlsCfg)

	srv := &http.Server{
		Handler:           s.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}
	go func() {
		<-ctx.Done()
		shutCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutCtx)
	}()
	s.Log.Info("https listener started", "addr", s.Cfg.ListenAddr)
	err = srv.Serve(tlsLn)
	if ctx.Err() != nil {
		return nil // graceful shutdown
	}
	return err
}

func (s *Server) tlsConfig() (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(s.Cfg.TLS.Cert, s.Cfg.TLS.Key)
	if err != nil {
		return nil, fmt.Errorf("load TLS cert/key: %w", err)
	}
	tc := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	}
	if s.Cfg.MTLS {
		if s.Cfg.ClientCA == "" {
			return nil, fmt.Errorf("mtls enabled but client_ca is empty")
		}
		caPEM, err := os.ReadFile(s.Cfg.ClientCA)
		if err != nil {
			return nil, fmt.Errorf("read client_ca: %w", err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(caPEM) {
			return nil, fmt.Errorf("client_ca contains no usable certificates")
		}
		tc.ClientCAs = pool
		tc.ClientAuth = tls.RequireAndVerifyClientCert
	}
	return tc, nil
}

func (s *Server) notFound(w http.ResponseWriter, r *http.Request) {
	writeJSONError(w, http.StatusNotFound, "not_found", "unknown endpoint "+r.URL.Path)
}

func writeJSONError(w http.ResponseWriter, status int, code, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = fmt.Fprintf(w, `{"error":{"code":%q,"message":%q}}`, code, msg)
}

// --- middleware ---

func (s *Server) rateLimit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := clientIP(r)
		if !s.Limit.Allow(ip) {
			w.Header().Set("Retry-After", "1")
			writeJSONError(w, http.StatusTooManyRequests, "rate_limited", "too many requests; retry shortly")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) accessLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		s.Log.Info("request",
			"remote", clientIP(r),
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"bytes", rec.bytes,
			"dur_ms", time.Since(start).Milliseconds(),
			"ua", r.UserAgent(),
		)
	})
}

func (s *Server) recover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				s.Log.Error("panic", "path", r.URL.Path, "err", fmt.Sprint(v))
				writeJSONError(w, http.StatusInternalServerError, "internal", "internal server error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (r *statusRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	n, err := r.ResponseWriter.Write(b)
	r.bytes += n
	return n, err
}

// Flush forwards flushes so SSE streams work through the access-log wrapper.
func (r *statusRecorder) Flush() {
	if f, ok := r.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func clientIP(r *http.Request) string {
	// Trust X-Forwarded-For only from the first hop we control? v1: use the
	// direct peer address; the companion server is the TLS terminator.
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return strings.TrimSpace(r.RemoteAddr)
	}
	return host
}
