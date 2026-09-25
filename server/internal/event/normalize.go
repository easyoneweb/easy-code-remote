// Package event implements the kilo SSE client, event normalization, de-duplication
// and the bounded ring buffer used for phone reconnect replay.
package event

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
)

// Envelope is the normalized event sent to phones over the SSE stream.
type Envelope struct {
	Type      string         `json:"type"`
	SessionID string         `json:"sessionID,omitempty"`
	MessageID string         `json:"messageID,omitempty"`
	PartID    string         `json:"partID,omitempty"`
	Data      map[string]any `json:"data,omitempty"`
	Ts        int64          `json:"ts"`
	Cursor    uint64         `json:"cursor"`
}

// Event is the normalized internal representation of a kilo event.
type Event struct {
	ID         string
	Type       string
	Seq        int64
	SessionID  string
	MessageID  string
	PartID     string
	Data       map[string]any // payload for the phone envelope (already mapped)
	Properties map[string]any // raw properties (used by the store)
}

var versionSuffix = regexp.MustCompile(`\.\d+$`)

// StripVersion turns "session.updated.1" into "session.updated".
func StripVersion(t string) string { return versionSuffix.ReplaceAllString(t, "") }

// rawEvent mirrors the kilo event wire format.
type rawEvent struct {
	ID          string         `json:"id"`
	Type        string         `json:"type"`
	Seq         int64          `json:"seq"`
	AggregateID string         `json:"aggregateID"`
	Data        map[string]any `json:"data"`
	Properties  map[string]any `json:"properties"`
	SyncEvent   *rawEvent      `json:"syncEvent"`
}

// Normalize parses one kilo SSE data payload into a normalized Event.
func Normalize(raw []byte) (*Event, error) {
	var re rawEvent
	if err := json.Unmarshal(raw, &re); err != nil {
		return nil, fmt.Errorf("parse kilo event: %w", err)
	}
	if re.Type == "sync" && re.SyncEvent != nil {
		re = *re.SyncEvent
	}
	e := &Event{
		ID:         re.ID,
		Type:       StripVersion(re.Type),
		Seq:        re.Seq,
		Data:       re.Data,
		Properties: re.Properties,
	}
	if e.Data == nil {
		e.Data = e.Properties
	}
	if e.Properties == nil {
		e.Properties = e.Data
	}
	// Session id: aggregateID > data.sessionID > properties.sessionID > data.info.id
	switch {
	case re.AggregateID != "":
		e.SessionID = re.AggregateID
	case str(e.Data, "sessionID") != "":
		e.SessionID = str(e.Data, "sessionID")
	case str(e.Properties, "sessionID") != "":
		e.SessionID = str(e.Properties, "sessionID")
	}
	if e.SessionID == "" {
		if info, ok := e.Data["info"].(map[string]any); ok {
			e.SessionID = str(info, "id")
		}
	}
	// Message id: properties.messageID > data.messageID > data.part.messageID
	// (part events nest it inside the part object) > data.info.id (message events).
	if m := str(e.Properties, "messageID"); m != "" {
		e.MessageID = m
	} else if m = str(e.Data, "messageID"); m != "" {
		e.MessageID = m
	} else if strings.HasPrefix(e.Type, "message.part.") {
		if part, ok := e.Data["part"].(map[string]any); ok {
			e.MessageID = str(part, "messageID")
		}
	} else if strings.HasPrefix(e.Type, "message.") {
		if info, ok := e.Data["info"].(map[string]any); ok {
			e.MessageID = str(info, "id")
		}
	}
	// Part id: properties.partID > data.part.id > data.partID.
	if p := str(e.Properties, "partID"); p != "" {
		e.PartID = p
	} else if part, ok := e.Data["part"].(map[string]any); ok {
		e.PartID = str(part, "id")
	} else {
		e.PartID = str(e.Data, "partID")
	}
	// message.part.delta maps to an app-level message.part.updated carrying delta text.
	if e.Type == "message.part.delta" {
		delta := str(e.Properties, "delta")
		if delta == "" {
			delta = str(e.Data, "delta")
		}
		e.Data = map[string]any{
			"part": map[string]any{
				"id":   e.PartID,
				"type": "text",
				"text": delta,
			},
			"delta": map[string]any{
				"type":      "text-delta",
				"textDelta": delta,
			},
		}
	}
	return e, nil
}

func str(m map[string]any, key string) string {
	if m == nil {
		return ""
	}
	if v, ok := m[key].(string); ok {
		return v
	}
	return ""
}

// Whitelisted reports whether the normalized type may be forwarded to phones.
func Whitelisted(t string) bool {
	switch {
	case strings.HasPrefix(t, "session."),
		strings.HasPrefix(t, "message."),
		strings.HasPrefix(t, "permission."),
		strings.HasPrefix(t, "question."),
		strings.HasPrefix(t, "todo."),
		strings.HasPrefix(t, "engine."),
		t == "server.connected":
		return true
	}
	return false
}

// Envelope builds the phone-facing envelope for a normalized event.
// ts is unix milliseconds, cursor is the ring cursor assigned after dedup.
func (e *Event) Envelope(ts int64, cursor uint64) Envelope {
	return Envelope{
		Type:      e.Type,
		SessionID: e.SessionID,
		MessageID: e.MessageID,
		PartID:    e.PartID,
		Data:      e.Data,
		Ts:        ts,
		Cursor:    cursor,
	}
}
