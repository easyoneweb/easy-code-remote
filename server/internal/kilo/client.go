// Package kilo implements the client for the local Kilo Code engine HTTP API.
package kilo

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// AuthUser is the basic-auth username kilo serve expects.
const AuthUser = "kilo"

// Client talks to a running kilo serve instance.
type Client struct {
	BaseURL string
	User    string
	Pass    string
	HTTP    *http.Client
}

// NewClient returns a Client for the given endpoint and password.
func NewClient(host string, port int, pass string) *Client {
	return &Client{
		BaseURL: fmt.Sprintf("http://%s:%d", host, port),
		User:    AuthUser,
		Pass:    pass,
		HTTP:    &http.Client{Timeout: 60 * time.Second},
	}
}

// Do performs a request against the kilo API and returns the raw body with status.
func (c *Client) Do(ctx context.Context, method, path string, query url.Values, body any) ([]byte, int, error) {
	var rd io.Reader
	if body != nil {
		enc, err := json.Marshal(body)
		if err != nil {
			return nil, 0, fmt.Errorf("encode request body: %w", err)
		}
		rd = bytes.NewReader(enc)
	}
	u := c.BaseURL + path
	if len(query) > 0 {
		u += "?" + query.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, method, u, rd)
	if err != nil {
		return nil, 0, fmt.Errorf("build request: %w", err)
	}
	req.SetBasicAuth(c.User, c.Pass)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, 0, &Error{Code: "engine_unavailable", HTTPStatus: http.StatusBadGateway,
			Message: fmt.Sprintf("kilo engine unreachable: %v", err)}
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return nil, resp.StatusCode, &Error{Code: "engine_error", HTTPStatus: http.StatusBadGateway,
			Message: fmt.Sprintf("read kilo response: %v", err)}
	}
	return data, resp.StatusCode, nil
}

// Get is Do for GET requests.
func (c *Client) Get(ctx context.Context, path string, query url.Values) ([]byte, int, error) {
	return c.Do(ctx, http.MethodGet, path, query, nil)
}

// Post is Do for POST requests.
func (c *Client) Post(ctx context.Context, path string, query url.Values, body any) ([]byte, int, error) {
	return c.Do(ctx, http.MethodPost, path, query, body)
}

// Error is a stable, phone-friendly error produced from a kilo failure.
type Error struct {
	Code       string
	Message    string
	HTTPStatus int
	Retryable  bool
}

func (e *Error) Error() string { return fmt.Sprintf("kilo error %s: %s", e.Code, e.Message) }

// statusToError converts a kilo HTTP status + body into a typed Error.
func statusToError(status int, body []byte) error {
	msg := strings.TrimSpace(string(body))
	var parsed struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(body, &parsed) == nil && parsed.Error != "" {
		msg = parsed.Error
	}
	switch status {
	case http.StatusUnauthorized, http.StatusForbidden:
		return &Error{Code: "engine_auth", HTTPStatus: http.StatusBadGateway,
			Message: fmt.Sprintf("kilo engine rejected credentials (status %d): %s", status, msg)}
	case http.StatusNotFound:
		return &Error{Code: "session_not_found", HTTPStatus: http.StatusNotFound,
			Message: "session not found on the kilo engine"}
	case http.StatusConflict:
		return &Error{Code: "busy", HTTPStatus: http.StatusConflict,
			Message: "session is busy; retry", Retryable: true}
	case http.StatusTooManyRequests:
		return &Error{Code: "rate_limited", HTTPStatus: http.StatusTooManyRequests,
			Message: "kilo engine rate limit exceeded", Retryable: true}
	case http.StatusBadGateway, http.StatusGatewayTimeout, http.StatusServiceUnavailable:
		return &Error{Code: "engine_unavailable", HTTPStatus: http.StatusBadGateway,
			Message: fmt.Sprintf("kilo engine unavailable (status %d)", status), Retryable: true}
	default:
		if status >= 500 {
			return &Error{Code: "engine_error", HTTPStatus: http.StatusBadGateway,
				Message: fmt.Sprintf("kilo engine error (status %d): %s", status, msg), Retryable: true}
		}
		return &Error{Code: "engine_error", HTTPStatus: http.StatusBadGateway,
			Message: fmt.Sprintf("kilo engine returned status %d: %s", status, msg)}
	}
}

// ExpectOK validates a response and returns a typed error for non-2xx statuses.
func (c *Client) ExpectOK(data []byte, status int, err error) ([]byte, error) {
	if err != nil {
		return nil, err
	}
	if status < 200 || status >= 300 {
		return nil, statusToError(status, data)
	}
	return data, nil
}

// StatusEntry is a kilo /session/status map value.
type StatusEntry struct {
	Type string `json:"type"`
}

// Sessions lists all sessions from kilo /session.
func (c *Client) Sessions(ctx context.Context) ([]json.RawMessage, error) {
	data, status, err := c.Get(ctx, "/session", nil)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	var out []json.RawMessage
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, fmt.Errorf("decode /session: %w", err)
	}
	return out, nil
}

// SessionStatus returns the kilo /session/status map.
func (c *Client) SessionStatus(ctx context.Context) (map[string]StatusEntry, error) {
	data, status, err := c.Get(ctx, "/session/status", nil)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	var out map[string]StatusEntry
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, fmt.Errorf("decode /session/status: %w", err)
	}
	return out, nil
}

// SessionExists reports whether the kilo engine knows the session (authoritative).
func (c *Client) SessionExists(ctx context.Context, sessionID string) (bool, error) {
	data, status, err := c.Get(ctx, "/session/"+url.PathEscape(sessionID), nil)
	if err != nil {
		return false, err
	}
	switch status {
	case http.StatusOK:
		return true, nil
	case http.StatusNotFound:
		return false, nil
	default:
		if _, err := c.ExpectOK(data, status, err); err != nil {
			return false, err
		}
		return false, nil
	}
}

// Messages fetches the transcript for a session (kilo shape: [{info,parts}]).
func (c *Client) Messages(ctx context.Context, sessionID string, limit int, before string) (json.RawMessage, error) {
	q := url.Values{}
	if limit > 0 {
		q.Set("limit", fmt.Sprintf("%d", limit))
	}
	if before != "" {
		q.Set("before", before)
	}
	data, status, err := c.Get(ctx, "/session/"+url.PathEscape(sessionID)+"/message", q)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	return data, nil
}

// Diff fetches the diff summary for a session.
func (c *Client) Diff(ctx context.Context, sessionID, base string) (json.RawMessage, error) {
	q := url.Values{}
	if base != "" {
		q.Set("base", base)
	}
	data, status, err := c.Get(ctx, "/session/"+url.PathEscape(sessionID)+"/diff", q)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	return data, nil
}

// PermissionList fetches pending permission requests.
func (c *Client) PermissionList(ctx context.Context) ([]json.RawMessage, error) {
	data, status, err := c.Get(ctx, "/permission", nil)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	var out []json.RawMessage
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, fmt.Errorf("decode /permission: %w", err)
	}
	return out, nil
}

// PermissionReply resolves a permission request ("once" or "reject").
func (c *Client) PermissionReply(ctx context.Context, requestID, reply, message string, interactive bool) error {
	body := map[string]any{"reply": reply}
	if message != "" {
		body["message"] = message
	}
	if interactive {
		body["interactive"] = true
	}
	data, status, err := c.Post(ctx, "/permission/"+url.PathEscape(requestID)+"/reply", nil, body)
	if err != nil {
		return err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return err
	}
	return nil
}

// PermissionAlwaysRules stores always-allow/always-deny rules for a permission request.
func (c *Client) PermissionAlwaysRules(ctx context.Context, requestID string, approved, denied []json.RawMessage) error {
	body := map[string]any{}
	if len(approved) > 0 {
		body["approvedAlways"] = approved
	}
	if len(denied) > 0 {
		body["deniedAlways"] = denied
	}
	data, status, err := c.Post(ctx, "/permission/"+url.PathEscape(requestID)+"/always-rules", nil, body)
	if err != nil {
		return err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return err
	}
	return nil
}

// QuestionList fetches pending questions.
func (c *Client) QuestionList(ctx context.Context) ([]json.RawMessage, error) {
	data, status, err := c.Get(ctx, "/question", nil)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	var out []json.RawMessage
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, fmt.Errorf("decode /question: %w", err)
	}
	return out, nil
}

// QuestionReply answers a question with the given option labels.
func (c *Client) QuestionReply(ctx context.Context, requestID string, answers []string) error {
	data, status, err := c.Post(ctx, "/question/"+url.PathEscape(requestID)+"/reply", nil, map[string]any{"answers": answers})
	if err != nil {
		return err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return err
	}
	return nil
}

// QuestionReject rejects a pending question.
func (c *Client) QuestionReject(ctx context.Context, requestID string) error {
	data, status, err := c.Post(ctx, "/question/"+url.PathEscape(requestID)+"/reject", nil, nil)
	if err != nil {
		return err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return err
	}
	return nil
}

// SendMessage posts a user message to a session and returns the created message body.
func (c *Client) SendMessage(ctx context.Context, sessionID string, body map[string]any) (json.RawMessage, error) {
	data, status, err := c.Post(ctx, "/session/"+url.PathEscape(sessionID)+"/message", nil, body)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	return data, nil
}

// Abort stops the running agent in a session.
func (c *Client) Abort(ctx context.Context, sessionID string) error {
	data, status, err := c.Post(ctx, "/session/"+url.PathEscape(sessionID)+"/abort", nil, nil)
	if err != nil {
		return err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return err
	}
	return nil
}

// RunCommand runs a slash command in a session.
func (c *Client) RunCommand(ctx context.Context, sessionID string, body map[string]any) (json.RawMessage, error) {
	data, status, err := c.Post(ctx, "/session/"+url.PathEscape(sessionID)+"/command", nil, body)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	return data, nil
}

// MCPList returns the kilo /mcp payload.
func (c *Client) MCPList(ctx context.Context) (json.RawMessage, error) {
	data, status, err := c.Get(ctx, "/mcp", nil)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	return data, nil
}

// AgentList lists configured agents (kilo /agent).
func (c *Client) AgentList(ctx context.Context) ([]json.RawMessage, error) {
	data, status, err := c.Get(ctx, "/agent", nil)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	var out []json.RawMessage
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, fmt.Errorf("decode /agent: %w", err)
	}
	return out, nil
}

// SkillList lists available skills (kilo /skill).
func (c *Client) SkillList(ctx context.Context) ([]json.RawMessage, error) {
	data, status, err := c.Get(ctx, "/skill", nil)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	var out []json.RawMessage
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, fmt.Errorf("decode /skill: %w", err)
	}
	return out, nil
}

// CommandList lists available slash commands (kilo /command).
func (c *Client) CommandList(ctx context.Context) ([]json.RawMessage, error) {
	data, status, err := c.Get(ctx, "/command", nil)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	var out []json.RawMessage
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, fmt.Errorf("decode /command: %w", err)
	}
	return out, nil
}

// ProviderList returns the kilo /provider payload (contains the models lists).
func (c *Client) ProviderList(ctx context.Context) (json.RawMessage, error) {
	data, status, err := c.Get(ctx, "/provider", nil)
	if err != nil {
		return nil, err
	}
	if _, err := c.ExpectOK(data, status, err); err != nil {
		return nil, err
	}
	return data, nil
}

// Discover resolves the kilo binary path per the documented discovery order.
func Discover(configured string) (string, error) {
	if configured != "" {
		if _, err := os.Stat(configured); err != nil {
			return "", fmt.Errorf("configured kilo bin %q not found: %w", configured, err)
		}
		return configured, nil
	}
	if env := os.Getenv("KILO_BIN"); env != "" {
		if _, err := os.Stat(env); err != nil {
			return "", fmt.Errorf("KILO_BIN %q not found: %w", env, err)
		}
		return env, nil
	}
	if p, err := exec.LookPath("kilo"); err == nil {
		return p, nil
	}
	home, err := os.UserHomeDir()
	if err == nil && home != "" {
		matches, _ := filepath.Glob(filepath.Join(home, ".vscode", "extensions", "kilocode.kilo-code-*", "bin", "kilo"))
		if len(matches) > 0 {
			sort.Slice(matches, func(i, j int) bool {
				fi, _ := os.Stat(matches[i])
				fj, _ := os.Stat(matches[j])
				return fi.ModTime().After(fj.ModTime())
			})
			return matches[0], nil
		}
	}
	return "", errors.New("kilo binary not found (set config kilo.bin, env KILO_BIN, add kilo to PATH, or install the Kilo Code VSCode extension)")
}

// Version runs `<kilo> --version` and returns the trimmed output.
func Version(binPath string) string {
	out, err := exec.Command(binPath, "--version").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// GeneratePassword returns a 64-char hex password for KILO_SERVER_PASSWORD.
func GeneratePassword() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("generate password: %w", err)
	}
	return fmt.Sprintf("%x", b), nil
}
