package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLoadDefaults(t *testing.T) {
	cfg, err := Load(filepath.Join(t.TempDir(), "missing.json"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ListenAddr != DefaultListenAddr {
		t.Fatalf("default listen addr = %q", cfg.ListenAddr)
	}
	if cfg.Kilo.Port != DefaultKiloPort {
		t.Fatalf("default kilo port = %d", cfg.Kilo.Port)
	}
	if cfg.RateLimit.Rate != DefaultRate || cfg.RateLimit.Burst != DefaultBurst {
		t.Fatalf("default rate limits wrong: %+v", cfg.RateLimit)
	}
}

func TestSaveRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	cfg := Default()
	cfg.ListenAddr = "0.0.0.0:9999"
	cfg.Token = strings.Repeat("ab", 32)
	cfg.Kilo.Bin = "/tmp/kilo"
	if err := cfg.Save(path); err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("config mode = %o, want 600", fi.Mode().Perm())
	}
	got, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if got.ListenAddr != cfg.ListenAddr || got.Token != cfg.Token || got.Kilo.Bin != cfg.Kilo.Bin {
		t.Fatalf("round trip mismatch: %+v vs %+v", got, cfg)
	}
}

func TestTokenGeneration(t *testing.T) {
	a, err := GenerateToken()
	if err != nil {
		t.Fatal(err)
	}
	b, _ := GenerateToken()
	if len(a) != 64 || a == b {
		t.Fatalf("tokens should be 64 hex chars and unique: %q vs %q", a, b)
	}
}

func TestEnsureFirstRunGeneratesCertAndToken(t *testing.T) {
	dir := t.TempDir()
	p := Paths{
		Dir:      dir,
		Config:   filepath.Join(dir, "config.json"),
		TLSDir:   filepath.Join(dir, "tls"),
		CertFile: filepath.Join(dir, "tls", "cert.pem"),
		KeyFile:  filepath.Join(dir, "tls", "key.pem"),
	}
	cfg := Default()
	gotToken, gotCert, err := cfg.EnsureFirstRun(p)
	if err != nil {
		t.Fatal(err)
	}
	if !gotToken || !gotCert {
		t.Fatalf("expected both generated (token=%v cert=%v)", gotToken, gotCert)
	}
	if len(cfg.Token) != 64 {
		t.Fatalf("token length %d", len(cfg.Token))
	}
	info, err := CertificateInfo(p.CertFile)
	if err != nil {
		t.Fatal(err)
	}
	if time.Until(info.NotAfter) < 9*365*24*time.Hour {
		t.Fatalf("certificate lifetime too short: %s", info.NotAfter)
	}
	// Second run must not regenerate anything.
	gotToken2, gotCert2, err := cfg.EnsureFirstRun(p)
	if err != nil {
		t.Fatal(err)
	}
	if gotToken2 || gotCert2 {
		t.Fatalf("second run should be a no-op (token=%v cert=%v)", gotToken2, gotCert2)
	}
}

func TestRegenerateCertificate(t *testing.T) {
	dir := t.TempDir()
	cert := filepath.Join(dir, "cert.pem")
	key := filepath.Join(dir, "key.pem")
	if _, err := EnsureCertificate(cert, key); err != nil {
		t.Fatal(err)
	}
	first, _ := CertificateInfo(cert)
	if _, err := RegenerateCertificate(cert, key); err != nil {
		t.Fatal(err)
	}
	second, _ := CertificateInfo(cert)
	if first.SerialNumber.Cmp(second.SerialNumber) == 0 {
		t.Fatal("regenerated certificate has the same serial number")
	}
}
