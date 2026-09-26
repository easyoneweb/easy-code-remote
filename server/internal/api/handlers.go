package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/easyoneweb/easy-code-remote/server/internal/event"
	"github.com/easyoneweb/easy-code-remote/server/internal/kilo"
)

// writeJSON writes a JSON response.
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// writeError writes the stable error body.
func writeError(w http.ResponseWriter, status int, code, msg string, retryable bool) {
	body := map[string]any{
		"error": map[string]any{"code": code, "message": msg},
	}
	if retryable {
		body["error"].(map[string]any)["retryable"] = true
	}
	writeJSON(w, status, body)
}

// writeKiloError maps a kilo failure to the phone-facing error.
func writeKiloError(w http.ResponseWriter, err error) {
	if ke, ok := err.(*kilo.Error); ok {
		writeError(w, ke.HTTPStatus, ke.Code, ke.Message, ke.Retryable)
		return
	}
	writeError(w, http.StatusBadGateway, "engine_error", err.Error(), true)
}

// HandleHealth reports liveness, versions and engine/session state (auth-free).
func (s *Server) HandleHealth(w http.ResponseWriter, r *http.Request) {
	engine := "down"
	if s.EngineUp() {
		engine = "up"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "ok",
		"serverVersion": s.Version,
		"kiloVersion":   s.KiloVersion,
		"engine":        engine,
		"sessions":      len(s.Store.Sessions()),
	})
}

// HandleSessions lists all sessions with derived status.
func (s *Server) HandleSessions(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.Store.Sessions())
}

// HandleSession returns one session.
func (s *Server) HandleSession(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	ses := s.Store.Session(sessionID)
	if ses == nil {
		writeError(w, http.StatusNotFound, "session_not_found", "session not found", false)
		return
	}
	writeJSON(w, http.StatusOK, ses)
}

// ensureSession verifies a session exists (store fast-path, then authoritative
// kilo check to avoid SSE-lag false negatives) and writes a 404 when unknown.
// Returns false when the response was already written.
func (s *Server) ensureSession(ctx context.Context, w http.ResponseWriter, sessionID string) bool {
	if s.Store.Session(sessionID) != nil {
		return true
	}
	exists, err := s.K.SessionExists(ctx, sessionID)
	if err != nil {
		writeKiloError(w, err)
		return false
	}
	if !exists {
		writeError(w, http.StatusNotFound, "session_not_found", "session not found", false)
		return false
	}
	return true
}

// HandlePending returns the retained pending permission/question payloads for
// a session (kilo passthrough arrays; empty arrays when nothing is pending).
func (s *Server) HandlePending(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	if !s.ensureSession(ctx, w, sessionID) {
		return
	}
	perms, questions := s.Store.Pending(sessionID)
	writeJSON(w, http.StatusOK, map[string]any{
		"permissions": perms,
		"questions":   questions,
	})
}

// HandleMessages returns the transcript for a session.
func (s *Server) HandleMessages(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	if !s.ensureSession(ctx, w, sessionID) {
		return
	}
	limit := 50
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			limit = n
		}
	}
	before := r.URL.Query().Get("before")
	data, err := s.Store.Messages(ctx, s.K, sessionID, limit, before)
	if err != nil {
		writeKiloError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

// HandleEvents streams the SSE live event feed with cursor-based replay.
func (s *Server) HandleEvents(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeError(w, http.StatusInternalServerError, "internal", "streaming unsupported", false)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")

	cursor, _ := strconv.ParseUint(r.URL.Query().Get("cursor"), 10, 64)
	writeSSE := func(env event.Envelope) {
		data, err := json.Marshal(env)
		if err != nil {
			return
		}
		_, _ = fmt.Fprintf(w, "data: %s\n\n", data)
		flusher.Flush()
	}

	// 1) Deterministic connection signal.
	writeSSE(event.Envelope{Type: "server.connected", Data: map[string]any{"kiloVersion": s.KiloVersion}, Ts: time.Now().UnixMilli(), Cursor: cursor})

	// 2) Replay from the ring buffer when a cursor is supplied.
	if cursor > 0 {
		replay, ok := s.Ring.Since(cursor)
		if !ok {
			writeSSE(event.Envelope{Type: "resync.required", Data: map[string]any{"reason": "event buffer rolled over; re-fetch /api/v1/sessions"}, Ts: time.Now().UnixMilli(), Cursor: s.Ring.Latest()})
		} else {
			for _, env := range replay {
				writeSSE(env)
			}
		}
	}

	// 3) Live events.
	ch := s.Hub.Subscribe()
	defer s.Hub.Unsubscribe(ch)

	heartbeat := time.NewTicker(15 * time.Second)
	defer heartbeat.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case env := <-ch:
			writeSSE(env)
		case <-heartbeat.C:
			_, _ = fmt.Fprintf(w, ": heartbeat\n\n")
			flusher.Flush()
		}
	}
}

type sendMessageReq struct {
	Text             string            `json:"text"`
	Agent            string            `json:"agent"`
	Model            any               `json:"model"`
	Variant          string            `json:"variant"`
	MessageID        string            `json:"messageID"`
	Queued           bool              `json:"queued"`
	Parts            []json.RawMessage `json:"parts"`
	ReplyToMessageID string            `json:"replyToMessageID"`
	System           string            `json:"system"`
}

// HandleSendMessage posts user input to a session.
func (s *Server) HandleSendMessage(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	if !s.ensureSession(ctx, w, sessionID) {
		return
	}
	var req sendMessageReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "invalid JSON body: "+err.Error(), false)
		return
	}
	if req.ReplyToMessageID != "" {
		writeError(w, http.StatusNotImplemented, "not_supported", "replyToMessageID requires a session fork, which is not part of v1; omit it", false)
		return
	}
	if req.Text == "" && len(req.Parts) == 0 {
		writeError(w, http.StatusBadRequest, "bad_request", "either text or parts is required", false)
		return
	}
	parts := req.Parts
	if req.Text != "" {
		parts = []json.RawMessage{mustJSON(map[string]any{"type": "text", "text": req.Text})}
	}
	if req.MessageID == "" {
		req.MessageID = newMessageID()
	}
	body := map[string]any{
		"messageID": req.MessageID,
		"parts":     parts,
	}
	if req.Queued {
		body["noReply"] = true
	}
	if req.Agent != "" {
		body["agent"] = req.Agent
	}
	if req.Model != nil {
		body["model"] = req.Model
	}
	if req.Variant != "" {
		body["variant"] = req.Variant
	}
	if req.System != "" {
		body["system"] = req.System
	}
	data, err := s.K.SendMessage(ctx, sessionID, body)
	if err != nil {
		writeKiloError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

// HandleAbort stops the agent in a session.
func (s *Server) HandleAbort(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	if !s.ensureSession(ctx, w, sessionID) {
		return
	}
	if err := s.K.Abort(ctx, sessionID); err != nil {
		writeKiloError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"aborted": true})
}

type commandReq struct {
	Command   string `json:"command"`
	Arguments string `json:"arguments"`
	Agent     string `json:"agent"`
	Model     any    `json:"model"`
	Variant   string `json:"variant"`
}

// HandleCommand runs a slash command in a session.
func (s *Server) HandleCommand(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	if !s.ensureSession(ctx, w, sessionID) {
		return
	}
	var req commandReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "invalid JSON body: "+err.Error(), false)
		return
	}
	if strings.TrimSpace(req.Command) == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "command is required", false)
		return
	}
	body := map[string]any{
		"messageID": newMessageID(),
		"command":   req.Command,
	}
	if req.Arguments != "" {
		body["arguments"] = req.Arguments
	}
	if req.Agent != "" {
		body["agent"] = req.Agent
	}
	if req.Model != nil {
		body["model"] = req.Model
	}
	if req.Variant != "" {
		body["variant"] = req.Variant
	}
	data, err := s.K.RunCommand(ctx, sessionID, body)
	if err != nil {
		writeKiloError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

type permissionReq struct {
	PermissionID   string            `json:"permissionID"`
	Action         string            `json:"action"`
	Message        string            `json:"message"`
	Always         bool              `json:"always"`
	ApprovedAlways []json.RawMessage `json:"approvedAlways"`
	DeniedAlways   []json.RawMessage `json:"deniedAlways"`
}

// HandlePermission resolves a permission request for a session.
func (s *Server) HandlePermission(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	if !s.ensureSession(ctx, w, sessionID) {
		return
	}
	var req permissionReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "invalid JSON body: "+err.Error(), false)
		return
	}
	if req.PermissionID == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "permissionID is required", false)
		return
	}
	reply := ""
	switch req.Action {
	case "allow":
		reply = "once"
	case "deny":
		reply = "reject"
	default:
		writeError(w, http.StatusBadRequest, "bad_request", "action must be allow or deny", false)
		return
	}
	if err := s.K.PermissionReply(ctx, req.PermissionID, reply, req.Message, false); err != nil {
		writeKiloError(w, err)
		return
	}
	if req.Always {
		approved, denied := req.ApprovedAlways, req.DeniedAlways
		if len(approved) == 0 && len(denied) == 0 {
			writeError(w, http.StatusBadRequest, "bad_request", "always:true requires approvedAlways or deniedAlways rules", false)
			return
		}
		for _, rule := range approved {
			if isWildcardRule(rule) {
				writeError(w, http.StatusBadRequest, "bad_request", "wildcard always-allow rules are rejected for safety; be specific", false)
				return
			}
		}
		if err := s.K.PermissionAlwaysRules(ctx, req.PermissionID, approved, denied); err != nil {
			writeKiloError(w, err)
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"replied": true})
}

func isWildcardRule(raw json.RawMessage) bool {
	var rule struct {
		Permission string `json:"permission"`
		Pattern    string `json:"pattern"`
	}
	if json.Unmarshal(raw, &rule) != nil {
		return false
	}
	return rule.Permission == "*" && (rule.Pattern == "" || rule.Pattern == "*")
}

type questionReq struct {
	QuestionID string     `json:"questionID"`
	Answers    [][]string `json:"answers"`
	Action     string     `json:"action"`
}

// HandleQuestion answers or rejects a question in a session.
func (s *Server) HandleQuestion(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	if !s.ensureSession(ctx, w, sessionID) {
		return
	}
	var req questionReq
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "invalid JSON body: "+err.Error(), false)
		return
	}
	if req.QuestionID == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "questionID is required", false)
		return
	}
	if req.Action == "reject" {
		if err := s.K.QuestionReject(ctx, req.QuestionID); err != nil {
			writeKiloError(w, err)
			return
		}
	} else {
		if len(req.Answers) == 0 {
			writeError(w, http.StatusBadRequest, "bad_request", "answers are required (or action:reject)", false)
			return
		}
		if err := s.K.QuestionReply(ctx, req.QuestionID, req.Answers); err != nil {
			writeKiloError(w, err)
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"replied": true})
}

// HandleDiff returns the session diff summary (passthrough).
func (s *Server) HandleDiff(w http.ResponseWriter, r *http.Request) {
	sessionID := r.PathValue("id")
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	if !s.ensureSession(ctx, w, sessionID) {
		return
	}
	data, err := s.K.Diff(ctx, sessionID, r.URL.Query().Get("base"))
	if err != nil {
		writeKiloError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func mustJSON(v any) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

func newMessageID() string {
	return "msg_" + randomHex(8)
}
