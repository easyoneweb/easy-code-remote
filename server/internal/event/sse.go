package event

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Client streams the kilo /event SSE endpoint with auto-reconnect and backoff.
type Client struct {
	BaseURL        string
	User           string
	Pass           string
	OnEvent        func(Event)
	OnConnected    func()
	OnDisconnected func(error)
	HTTP           *http.Client
	logf           func(format string, args ...any)
}

// NewClient builds an SSE client for a kilo endpoint.
func NewClient(baseURL, user, pass string, logf func(string, ...any)) *Client {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	return &Client{
		BaseURL: baseURL,
		User:    user,
		Pass:    pass,
		HTTP:    &http.Client{Timeout: 0}, // SSE must not have a total timeout
		logf:    logf,
	}
}

// Run blocks until ctx is cancelled, maintaining the SSE connection.
func (c *Client) Run(ctx context.Context) {
	backoff := time.Second
	const maxBackoff = 30 * time.Second
	for {
		err := c.runOnce(ctx)
		if ctx.Err() != nil {
			return
		}
		if c.OnDisconnected != nil {
			c.OnDisconnected(err)
		}
		c.logf("event: kilo SSE disconnected (%v); reconnecting in %s", err, backoff)
		select {
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

func (c *Client) runOnce(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.BaseURL+"/event", nil)
	if err != nil {
		return fmt.Errorf("build SSE request: %w", err)
	}
	req.SetBasicAuth(c.User, c.Pass)
	req.Header.Set("Accept", "text/event-stream")
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return fmt.Errorf("open SSE stream: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("kilo SSE returned status %d", resp.StatusCode)
	}
	if c.OnConnected != nil {
		c.OnConnected()
	}
	return c.readLoop(ctx, resp.Body)
}

func (c *Client) readLoop(ctx context.Context, body io.Reader) error {
	br := bufio.NewReaderSize(body, 64*1024)
	var dataLines []string
	flush := func() {
		if len(dataLines) == 0 {
			return
		}
		raw := strings.Join(dataLines, "\n")
		dataLines = dataLines[:0]
		ev, err := Normalize([]byte(raw))
		if err != nil {
			c.logf("event: drop unparseable kilo event: %v", err)
			return
		}
		if c.OnEvent != nil {
			c.OnEvent(*ev)
		}
	}
	for {
		line, err := br.ReadString('\n')
		if err != nil && line == "" {
			flush()
			if ctx.Err() != nil {
				return nil
			}
			if err == io.EOF {
				return fmt.Errorf("SSE stream closed")
			}
			return fmt.Errorf("read SSE stream: %w", err)
		}
		line = strings.TrimRight(line, "\r\n")
		switch {
		case strings.HasPrefix(line, "data:"):
			dataLines = append(dataLines, strings.TrimPrefix(line, "data:"))
		case line == "":
			flush()
		}
		if err != nil {
			flush()
			if ctx.Err() != nil {
				return nil
			}
			return fmt.Errorf("read SSE stream: %w", err)
		}
	}
}
