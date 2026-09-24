package api

import (
	"context"
	"encoding/json"
	"net/http"
	"sort"
	"sync"
	"time"
)

// ConfigCacheTTL bounds how often the kilo configuration is refreshed.
const ConfigCacheTTL = 60 * time.Second

type configCache struct {
	mu      sync.Mutex
	data    any
	fetched time.Time
}

// HandleConfig returns the cached kilo configuration for phone pickers.
func (s *Server) HandleConfig(w http.ResponseWriter, r *http.Request) {
	cfg, err := s.cachedConfig(r.Context())
	if err != nil {
		writeKiloError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, cfg)
}

func (s *Server) cachedConfig(ctx context.Context) (any, error) {
	s.configMu.Lock()
	defer s.configMu.Unlock()
	if s.configData != nil && time.Since(s.configFetched) < ConfigCacheTTL {
		return s.configData, nil
	}
	data, err := s.fetchConfig(ctx)
	if err != nil {
		return nil, err
	}
	s.configData = data
	s.configFetched = time.Now()
	return data, nil
}

func (s *Server) fetchConfig(ctx context.Context) (any, error) {
	// Serialize the independent kilo calls to keep this simple and load-friendly.
	agents, err := s.K.AgentList(ctx)
	if err != nil {
		return nil, err
	}
	skills, err := s.K.SkillList(ctx)
	if err != nil {
		return nil, err
	}
	commands, err := s.K.CommandList(ctx)
	if err != nil {
		return nil, err
	}
	mcpsRaw, err := s.K.MCPList(ctx)
	if err != nil {
		return nil, err
	}
	var mcps any
	_ = json.Unmarshal(mcpsRaw, &mcps)

	providersRaw, err := s.K.ProviderList(ctx)
	if err != nil {
		return nil, err
	}
	providers, models := flattenModels(providersRaw)

	return map[string]any{
		"agents":    agents,
		"skills":    skills,
		"commands":  commands,
		"mcps":      mcps,
		"providers": providers,
		"models":    models,
	}, nil
}

// flattenModels extracts providers and a flat model list from the /provider payload.
func flattenModels(raw json.RawMessage) ([]any, []any) {
	var payload struct {
		All []json.RawMessage `json:"all"`
	}
	if json.Unmarshal(raw, &payload) != nil {
		return []any{}, []any{}
	}
	providers := make([]any, 0, len(payload.All))
	var models []any
	for _, pr := range payload.All {
		var p map[string]any
		if json.Unmarshal(pr, &p) != nil {
			continue
		}
		providerID, _ := p["id"].(string)
		providers = append(providers, p)
		rawModels, _ := p["models"].(map[string]any)
		keys := make([]string, 0, len(rawModels))
		for k := range rawModels {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			m, ok := rawModels[k].(map[string]any)
			if !ok {
				continue
			}
			entry := make(map[string]any, len(m)+2)
			for mk, mv := range m {
				entry[mk] = mv
			}
			entry["id"] = k
			entry["providerID"] = providerID
			models = append(models, entry)
		}
	}
	return providers, models
}
