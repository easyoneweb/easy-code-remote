// Package supervisor spawns, supervises and stops the kilo serve child process.
package supervisor

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Supervisor manages one kilo serve child with crash-restart backoff.
type Supervisor struct {
	Bin      string
	Hostname string
	Port     int
	Password string
	Logf     func(format string, args ...any)

	mu       sync.Mutex
	cmd      *exec.Cmd
	up       bool
	restarts int
	lastErr  string
	pid      int
	stopping bool

	stop chan struct{}
	wg   sync.WaitGroup
	exit chan struct{} // closed when the current child exits
}

// New creates a Supervisor. bin is the resolved kilo binary path.
func New(bin, hostname string, port int, password string, logf func(string, ...any)) *Supervisor {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	return &Supervisor{Bin: bin, Hostname: hostname, Port: port, Password: password, Logf: logf}
}

// Start launches kilo serve and keeps it alive until Stop. It returns once the
// child is up (ready to answer HTTP) or ctx is cancelled.
func (s *Supervisor) Start(ctx context.Context) error {
	s.stop = make(chan struct{})
	s.wg.Add(1)
	go s.loop(ctx)
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if s.Up() {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		time.Sleep(250 * time.Millisecond)
	}
	return fmt.Errorf("kilo serve did not become ready within 30s (last error: %s)", s.LastError())
}

// Stop terminates the child (SIGTERM, then SIGKILL) and stops the restart loop.
func (s *Supervisor) Stop() {
	if s.stop != nil {
		select {
		case <-s.stop:
		default:
			close(s.stop)
		}
	}
	s.killChild()
	s.wg.Wait()
}

func (s *Supervisor) loop(ctx context.Context) {
	defer s.wg.Done()
	backoff := time.Second
	const maxBackoff = 30 * time.Second
	for {
		select {
		case <-s.stop:
			return
		case <-ctx.Done():
			return
		default:
		}
		s.spawn(ctx)
		// The child is now up (or spawn failed); wait for it to exit, then
		// back off and respawn. Only one restart per exit.
		exitCh := s.currentExit()
		select {
		case <-s.stop:
			return
		case <-ctx.Done():
			return
		case <-exitCh:
		}
		if s.Up() {
			// Unexpected exit while considered up.
			s.Logf("supervisor: kilo serve exited; restarting in %s", backoff)
		}
		select {
		case <-s.stop:
			return
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff *= 2
		if backoff > maxBackoff {
			backoff = maxBackoff
		}
	}
}

func (s *Supervisor) currentExit() chan struct{} {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.exit
}

func (s *Supervisor) spawn(ctx context.Context) {
	cmd := exec.Command(s.Bin, "serve", "--port", fmt.Sprintf("%d", s.Port), "--hostname", s.Hostname)
	cmd.Env = append(os.Environ(),
		"KILO_SERVER_PASSWORD="+s.Password,
		"KILO_SERVER_USERNAME=kilo",
	)
	// Route kilo's logs through the server logger.
	out, err := cmd.StdoutPipe()
	if err == nil {
		cmd.Stderr = cmd.Stdout
	}
	if err := cmd.Start(); err != nil {
		s.setDown(fmt.Sprintf("spawn kilo serve: %v", err))
		return
	}
	exitCh := make(chan struct{})
	s.mu.Lock()
	s.cmd = cmd
	s.pid = cmd.Process.Pid
	s.up = false
	s.exit = exitCh
	s.mu.Unlock()
	s.Logf("supervisor: kilo serve started pid=%d port=%d", cmd.Process.Pid, s.Port)
	if out != nil {
		go s.drain(out)
	}
	// Single watcher goroutine owns cmd.Wait(); it closes the exit channel.
	go func() {
		err := cmd.Wait()
		close(exitCh)
		s.mu.Lock()
		stopping := s.stopping
		s.mu.Unlock()
		if !stopping {
			s.setDown(fmt.Sprintf("kilo serve exited: %v", err))
			s.Logf("supervisor: kilo serve pid=%d exited (%v)", cmd.Process.Pid, err)
		}
	}()
	if s.waitReady(ctx, cmd) {
		s.setUp()
		s.Logf("supervisor: kilo serve ready on %s:%d", s.Hostname, s.Port)
		return
	}
	// Not ready (spawn failure, crash during startup, or readiness timeout).
	// If the process is still alive (e.g. hung), stop it so the exit channel
	// closes and the loop backs off and retries.
	if s.processAlive(cmd) {
		_ = cmd.Process.Signal(syscall.SIGTERM)
		deadline := time.Now().Add(3 * time.Second)
		for time.Now().Before(deadline) && s.processAlive(cmd) {
			time.Sleep(100 * time.Millisecond)
		}
		if s.processAlive(cmd) {
			_ = cmd.Process.Kill()
		}
	}
}

func (s *Supervisor) drain(r io.Reader) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 256*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line != "" {
			s.Logf("kilo: %s", line)
		}
	}
}

func (s *Supervisor) waitReady(ctx context.Context, cmd *exec.Cmd) bool {
	deadline := time.Now().Add(30 * time.Second)
	url := fmt.Sprintf("http://%s:%d/session", s.Hostname, s.Port)
	client := &http.Client{Timeout: 2 * time.Second}
	for time.Now().Before(deadline) {
		select {
		case <-s.stop:
			return false
		case <-ctx.Done():
			return false
		default:
		}
		if !s.processAlive(cmd) {
			return false
		}
		resp, err := client.Get(url)
		if err == nil {
			resp.Body.Close()
			// Any HTTP response (even 401) means the listener is up; our
			// basic-auth credentials are what count.
			return true
		}
		time.Sleep(200 * time.Millisecond)
	}
	return false
}

func (s *Supervisor) processAlive(cmd *exec.Cmd) bool {
	if cmd.Process == nil {
		return false
	}
	return cmd.Process.Signal(syscall.Signal(0)) == nil
}

func (s *Supervisor) killChild() {
	s.mu.Lock()
	cmd := s.cmd
	s.stopping = true
	s.mu.Unlock()
	if cmd == nil || cmd.Process == nil {
		return
	}
	_ = cmd.Process.Signal(syscall.SIGTERM)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if !s.processAlive(cmd) {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	_ = cmd.Process.Kill()
}

func (s *Supervisor) setUp() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.up = true
	s.lastErr = ""
}

func (s *Supervisor) setDown(errMsg string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.up = false
	s.lastErr = errMsg
	s.restarts++
}

// Up reports whether the kilo engine currently answers requests.
func (s *Supervisor) Up() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.up
}

// Status summarizes the supervised engine state.
func (s *Supervisor) Status() (up bool, pid, restarts int, lastErr string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.up, s.pid, s.restarts, s.lastErr
}

// LastError returns the most recent failure message.
func (s *Supervisor) LastError() string {
	_, _, _, e := s.Status()
	return e
}

// PortAlive reports whether something is already listening on the kilo port.
func PortAlive(hostname string, port int) bool {
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", hostname, port), 2*time.Second)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}
