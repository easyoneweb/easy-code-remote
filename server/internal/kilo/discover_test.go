package kilo

import "testing"

func TestIsKiloServe(t *testing.T) {
	cases := []struct {
		cmdline string
		want    bool
	}{
		{"/opt/kilo/bin/kilo\x00serve\x00--port\x000\x00", true},
		{"/opt/kilo/bin/kilo\x00serve\x00--port\x0018500\x00--hostname\x00127.0.0.1\x00", true},
		{"/opt/kilo/bin/kilo\x00run\x00hello", false},
		{"node\x00serve\x00--port\x000\x00", false}, // not a kilo binary
		{"kilo", false},
	}
	for _, c := range cases {
		if got := isKiloServe([]byte(c.cmdline)); got != c.want {
			t.Errorf("isKiloServe(%q) = %v, want %v", c.cmdline, got, c.want)
		}
	}
}

func TestEnvValue(t *testing.T) {
	env := []byte("PATH=/usr/bin\x00KILO_SERVER_PASSWORD=secret\x00KILO_CLIENT=vscode\x00")
	if got := envValue(env, "KILO_SERVER_PASSWORD"); got != "secret" {
		t.Errorf("password = %q", got)
	}
	if got := envValue(env, "KILO_CLIENT"); got != "vscode" {
		t.Errorf("client = %q", got)
	}
	if got := envValue(env, "MISSING"); got != "" {
		t.Errorf("missing = %q", got)
	}
}

func TestPortForInodes(t *testing.T) {
	tcp := "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n" +
		"   0: 0100007F:9217 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 123456 1 0000000000000000 100 0 0 10 0\n" +
		"   1: 0100007F:1B58 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 999999 1 0000000000000000 100 0 0 10 0\n"
	// 9217 hex = 37399, 1B58 hex = 7000.
	if got := portForInodes([]byte(tcp), map[string]bool{"123456": true}); got != 37399 {
		t.Errorf("port = %d, want 37399", got)
	}
	if got := portForInodes([]byte(tcp), map[string]bool{"999999": true}); got != 7000 {
		t.Errorf("port = %d, want 7000", got)
	}
	if got := portForInodes([]byte(tcp), map[string]bool{"nope": true}); got != 0 {
		t.Errorf("port = %d, want 0", got)
	}
}
