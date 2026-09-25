package store

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// TestResyncUsesGlobalSessionList proves Resync seeds the store from kilo's own
// `db` tool (global, all projects) and falls back to the project-scoped /session
// HTTP endpoint when the kilo binary is unavailable.
func TestResyncUsesGlobalSessionList(t *testing.T) {
	// Fake kilo binary whose `db` command lists a session from another project
	// (one the /session HTTP mock below would never return).
	script := "#!/bin/sh\n" +
		"printf 'id\\tslug\\ttitle\\tdirectory\\tpath\\tversion\\tproject_id\\tparent_id\\tsummary_additions\\tsummary_deletions\\tsummary_files\\tcost\\ttokens_input\\ttokens_output\\ttokens_reasoning\\ttokens_cache_read\\ttokens_cache_write\\tagent\\tmodel\\tmetadata\\ttime_created\\ttime_updated\\ttime_compacting\\ttime_archived\\n'\n" +
		"printf 'ses_rankup\\tquick-falcon\\tRankup landing plan\\t/home/p/rankup-landing\\t\\t7.7.9\\tproj_rankup\\t\\t5\\t2\\t1\\t0\\t10\\t20\\t30\\t40\\t50\\timplementer\\t{\"id\":\"m2\"}\\t{}\\t1790000000000\\t1790000005000\\t\\t\\n'\n"
	bin := filepath.Join(t.TempDir(), "kilo")
	if err := os.WriteFile(bin, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}

	// HTTP /session only knows one scoped session; /session/status and the
	// pending endpoints answer with empty structures.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/session":
			_, _ = w.Write([]byte(`[{"id":"ses_local","slug":"local","projectID":"proj_local"}]`))
		case "/session/status":
			_, _ = w.Write([]byte(`{}`))
		case "/permission":
			_, _ = w.Write([]byte(`[]`))
		case "/question":
			_, _ = w.Write([]byte(`[]`))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)

	c := clientFor(t, srv)
	c.Bin = bin

	st := New()
	if err := st.Resync(context.Background(), c); err != nil {
		t.Fatalf("Resync: %v", err)
	}
	ss := st.Sessions()
	if len(ss) != 1 {
		t.Fatalf("got %d sessions, want 1 (global list replaces the scoped one)", len(ss))
	}
	rankup := ss[0]
	if rankup["id"] != "ses_rankup" || rankup["slug"] != "quick-falcon" {
		t.Fatalf("global session wrong: %#v", rankup)
	}
	if rankup["projectID"] != "proj_rankup" {
		t.Fatalf("projectID = %v, want proj_rankup", rankup["projectID"])
	}
	if rankup["status"] != "idle" {
		t.Fatalf("rankup status = %v, want idle (no status event)", rankup["status"])
	}

	// Fallback path: Bin empty → scoped /session list only.
	c2 := clientFor(t, srv)
	c2.Bin = ""
	st2 := New()
	if err := st2.Resync(context.Background(), c2); err != nil {
		t.Fatalf("Resync fallback: %v", err)
	}
	if got := len(st2.Sessions()); got != 1 {
		t.Fatalf("fallback sessions = %d, want 1 (scoped only)", got)
	}
}
