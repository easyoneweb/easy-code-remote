// Package config loads, saves and first-run-initializes the server configuration.
package config

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

// DefaultListenAddr is the address the HTTPS listener binds to.
const DefaultListenAddr = "0.0.0.0:8443"

// DefaultKiloPort is the local port the supervised kilo serve binds to.
const DefaultKiloPort = 18500

// DefaultKiloHostname keeps the kilo engine bound to loopback only.
const DefaultKiloHostname = "127.0.0.1"

// DefaultRingSize is the capacity of the in-memory event ring buffer.
const DefaultRingSize = 10000

// DefaultRate / DefaultBurst: token-bucket limits per client IP (per second / burst).
const (
	DefaultRate  = 30
	DefaultBurst = 60
)

// TLSConfig holds the paths to the server certificate and key.
type TLSConfig struct {
	Cert string `json:"cert"`
	Key  string `json:"key"`
}

// KiloConfig holds how the supervised kilo engine is launched.
type KiloConfig struct {
	Bin      string `json:"bin"`
	Hostname string `json:"hostname"`
	Port     int    `json:"port"`
}

// RateLimitConfig is the per-IP token bucket configuration.
type RateLimitConfig struct {
	Rate  float64 `json:"rate"`
	Burst int     `json:"burst"`
}

// Config is the persisted server configuration.
type Config struct {
	ListenAddr string          `json:"listen_addr"`
	TLS        TLSConfig       `json:"tls"`
	Token      string          `json:"token"`
	Kilo       KiloConfig      `json:"kilo"`
	LogLevel   string          `json:"log_level"`
	RateLimit  RateLimitConfig `json:"rate_limit"`
	MTLS       bool            `json:"mtls"`
	ClientCA   string          `json:"client_ca,omitempty"`
}

// Paths carries the filesystem locations for config, TLS material and state.
type Paths struct {
	Dir       string // base config dir, e.g. ~/.config/easy-code-remote
	Config    string // config.json
	TLSDir    string // tls/
	CertFile  string
	KeyFile   string
	BinMarker string // easy-code-remote binary path marker (not used for state)
}

// DefaultPaths resolves the configuration paths honoring EASY_CODE_REMOTE_CONFIG_DIR.
func DefaultPaths() Paths {
	base := os.Getenv("EASY_CODE_REMOTE_CONFIG_DIR")
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil || home == "" {
			home = "."
		}
		base = filepath.Join(home, ".config", "easy-code-remote")
	}
	tlsDir := filepath.Join(base, "tls")
	return Paths{
		Dir:      base,
		Config:   filepath.Join(base, "config.json"),
		TLSDir:   tlsDir,
		CertFile: filepath.Join(tlsDir, "cert.pem"),
		KeyFile:  filepath.Join(tlsDir, "key.pem"),
	}
}

// Default returns a config populated with defaults (token empty until first run).
func Default() *Config {
	return &Config{
		ListenAddr: DefaultListenAddr,
		TLS:        TLSConfig{},
		Kilo:       KiloConfig{Hostname: DefaultKiloHostname, Port: DefaultKiloPort},
		LogLevel:   "info",
		RateLimit:  RateLimitConfig{Rate: DefaultRate, Burst: DefaultBurst},
	}
}

// Load reads the config file. Returns a Default config when the file does not exist.
func Load(path string) (*Config, error) {
	c := Default()
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return c, nil
		}
		return nil, fmt.Errorf("read config %s: %w", path, err)
	}
	if err := json.Unmarshal(data, c); err != nil {
		return nil, fmt.Errorf("parse config %s: %w", path, err)
	}
	c.applyDefaults()
	return c, nil
}

// applyDefaults fills in zero values so an edited config keeps sane defaults.
func (c *Config) applyDefaults() {
	if c.ListenAddr == "" {
		c.ListenAddr = DefaultListenAddr
	}
	if c.Kilo.Port == 0 {
		c.Kilo.Port = DefaultKiloPort
	}
	if c.Kilo.Hostname == "" {
		c.Kilo.Hostname = DefaultKiloHostname
	}
	if c.LogLevel == "" {
		c.LogLevel = "info"
	}
	if c.RateLimit.Rate == 0 {
		c.RateLimit.Rate = DefaultRate
	}
	if c.RateLimit.Burst == 0 {
		c.RateLimit.Burst = DefaultBurst
	}
}

// Save writes the config with mode 0600.
func (c *Config) Save(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return fmt.Errorf("create config dir: %w", err)
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return fmt.Errorf("encode config: %w", err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return fmt.Errorf("write config %s: %w", path, err)
	}
	return nil
}

// GenerateToken returns a fresh 256-bit random bearer token (64 hex chars).
func GenerateToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("generate token: %w", err)
	}
	return hex.EncodeToString(b), nil
}

// EnsureFirstRun fills missing runtime secrets (token) and generates the TLS material.
// It only regenerates values that are empty/missing; existing values are preserved.
func (c *Config) EnsureFirstRun(p Paths) (createdToken, createdCert bool, err error) {
	if c.TLS.Cert == "" {
		c.TLS.Cert = p.CertFile
	}
	if c.TLS.Key == "" {
		c.TLS.Key = p.KeyFile
	}
	if c.Token == "" {
		c.Token, err = GenerateToken()
		if err != nil {
			return false, false, err
		}
		createdToken = true
	}
	createdCert, err = EnsureCertificate(c.TLS.Cert, c.TLS.Key)
	if err != nil {
		return createdToken, false, err
	}
	if err := c.Save(p.Config); err != nil {
		return createdToken, createdCert, err
	}
	return createdToken, createdCert, nil
}

// RegenerateCertificate removes any existing cert/key and creates a fresh pair.
func RegenerateCertificate(certPath, keyPath string) (bool, error) {
	_ = os.Remove(certPath)
	_ = os.Remove(keyPath)
	return EnsureCertificate(certPath, keyPath)
}

// CertificateInfo loads the certificate at certPath and returns its parsed form.
func CertificateInfo(certPath string) (*x509.Certificate, error) {
	data, err := os.ReadFile(certPath)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, fmt.Errorf("no PEM block in %s", certPath)
	}
	return x509.ParseCertificate(block.Bytes)
}

// EnsureCertificate writes a self-signed certificate unless cert+key already exist.
// Returns whether the certificate was newly created.
func EnsureCertificate(certPath, keyPath string) (bool, error) {
	if _, err := os.Stat(certPath); err == nil {
		if _, err2 := os.Stat(keyPath); err2 == nil {
			return false, nil
		}
	}
	if err := os.MkdirAll(filepath.Dir(certPath), 0o700); err != nil {
		return false, fmt.Errorf("create tls dir: %w", err)
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return false, fmt.Errorf("generate tls key: %w", err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return false, fmt.Errorf("generate serial: %w", err)
	}
	now := time.Now()
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "easy-code-remote", Organization: []string{"easy-code-remote"}},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.AddDate(10, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment | x509.KeyUsageCertSign,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IsCA:         true,
		DNSNames:     []string{"localhost", "easy-code-remote.local"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return false, fmt.Errorf("create certificate: %w", err)
	}
	certOut, err := os.OpenFile(certPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return false, fmt.Errorf("write cert: %w", err)
	}
	defer certOut.Close()
	if err := pem.Encode(certOut, &pem.Block{Type: "CERTIFICATE", Bytes: der}); err != nil {
		return false, fmt.Errorf("encode cert: %w", err)
	}
	keyDer, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return false, fmt.Errorf("marshal key: %w", err)
	}
	keyOut, err := os.OpenFile(keyPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return false, fmt.Errorf("write key: %w", err)
	}
	defer keyOut.Close()
	if err := pem.Encode(keyOut, &pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDer}); err != nil {
		return false, fmt.Errorf("encode key: %w", err)
	}
	return true, nil
}
