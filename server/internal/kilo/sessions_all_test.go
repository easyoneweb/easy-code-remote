package kilo

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestParseTSVRowsAndSessionInfo covers the `kilo db` TSV → /session-shape
// mapping (global session list; kilo's /session HTTP endpoint is project-scoped).
func TestParseTSVRowsAndSessionInfo(t *testing.T) {
	out := "INFO log line that must be skipped\n" +
		"id\tslug\ttitle\tdirectory\tpath\tversion\tproject_id\tparent_id\tsummary_additions\tsummary_deletions\tsummary_files\tcost\ttokens_input\ttokens_output\ttokens_reasoning\ttokens_cache_read\ttokens_cache_write\tagent\tmodel\tmetadata\ttime_created\ttime_updated\ttime_compacting\ttime_archived\n" +
		"ses_1\tbrave-island\tSome title\t/home/p\t\t7.7.9\tproj_1\t\t10\t2\t1\t0\t100\t5\t50\t1000\t0\tplan\t{\"id\":\"m1\",\"variant\":\"default\"}\t{\"k\":1}\t1790000000000\t1790000001000\t\t\n" +
		"ses_2\tshort-row\tbad\n"
	rows, err := parseTSVRows([]byte(out))
	if err != nil {
		t.Fatalf("parseTSVRows: %v", err)
	}
	if len(rows) != 1 {
		t.Fatalf("got %d rows, want 1 (bad rows skipped)", len(rows))
	}
	info, ok := sessionInfoFromRow(rows[0])
	if !ok {
		t.Fatal("sessionInfoFromRow: rejected a valid row")
	}
	if info["id"] != "ses_1" || info["slug"] != "brave-island" || info["projectID"] != "proj_1" {
		t.Fatalf("basic fields wrong: %v", info)
	}
	if info["title"] != "Some title" {
		t.Fatalf("title = %v", info["title"])
	}
	model, _ := info["model"].(map[string]any)
	if model["id"] != "m1" || model["variant"] != "default" {
		t.Fatalf("model object not preserved: %#v", info["model"])
	}
	tokens := info["tokens"].(map[string]any)
	if tokens["input"] != float64(100) || tokens["cache"].(map[string]any)["read"] != float64(1000) {
		t.Fatalf("tokens wrong: %#v", tokens)
	}
	timeMap := info["time"].(map[string]any)
	if timeMap["created"] != float64(1790000000000) || timeMap["updated"] != float64(1790000001000) {
		t.Fatalf("time wrong: %#v", timeMap)
	}
	if _, hasArchived := info["archived"]; hasArchived {
		t.Fatal("unarchived session must not carry archived=true")
	}
	if info["agent"] != "plan" {
		t.Fatalf("agent = %v", info["agent"])
	}

	// Archived sessions must surface the flag.
	archivedRow := make(map[string]string, len(rows[0]))
	for k, v := range rows[0] {
		archivedRow[k] = v
	}
	archivedRow["time_archived"] = "1790000009000"
	info2, ok := sessionInfoFromRow(archivedRow)
	if !ok || info2["archived"] != true {
		t.Fatalf("archived flag missing: %#v", info2)
	}
}

// TestSessionsAllRunsKiloDB exercises the real `kilo db` invocation against a
// fake kilo binary so the exec path is covered without a live engine.
func TestSessionsAllRunsKiloDB(t *testing.T) {
	script := "#!/bin/sh\n" +
		"printf 'id\\tslug\\ttitle\\tdirectory\\tpath\\tversion\\tproject_id\\tparent_id\\tsummary_additions\\tsummary_deletions\\tsummary_files\\tcost\\ttokens_input\\ttokens_output\\ttokens_reasoning\\ttokens_cache_read\\ttokens_cache_write\\tagent\\tmodel\\tmetadata\\ttime_created\\ttime_updated\\ttime_compacting\\ttime_archived\\n'\n" +
		"printf 'ses_1\\trunning-session\\tRankup landing\\t/home/p/rankup-landing\\t\\t7.7.9\\tproj_1\\t\\t5\\t2\\t1\\t0\\t10\\t20\\t30\\t40\\t50\\timplementer\\t{\"id\":\"m2\"}\\t{}\\t1790000000000\\t1790000005000\\t\\t\\n'\n"
	bin := filepath.Join(t.TempDir(), "kilo")
	if err := os.WriteFile(bin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	c := &Client{Bin: bin}
	items, err := c.SessionsAll(context.Background())
	if err != nil {
		t.Fatalf("SessionsAll: %v", err)
	}
	if len(items) != 1 {
		t.Fatalf("got %d items, want 1", len(items))
	}
	var info map[string]any
	if err := json.Unmarshal(items[0], &info); err != nil {
		t.Fatal(err)
	}
	if info["slug"] != "running-session" {
		t.Fatalf("slug = %v", info["slug"])
	}
	if info["projectID"] != "proj_1" {
		t.Fatalf("projectID = %v", info["projectID"])
	}
	if tokens := info["tokens"].(map[string]any); tokens["input"] != float64(10) {
		t.Fatalf("tokens = %#v", tokens)
	}
}

// TestSessionsAllWithoutBinFails gives a clear error instead of a panic.
func TestSessionsAllWithoutBinFails(t *testing.T) {
	c := &Client{}
	if _, err := c.SessionsAll(context.Background()); err == nil {
		t.Fatal("expected an error when Bin is empty")
	} else if !strings.Contains(err.Error(), "kilo binary") {
		t.Fatalf("unexpected error: %v", err)
	}
}
