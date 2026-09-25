// Command easy-code-remote runs the companion server that exposes local Kilo Code
// sessions to Android phones over HTTPS.
package main

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/easyoneweb/easy-code-remote/server/internal/api"
	"github.com/easyoneweb/easy-code-remote/server/internal/config"
	"github.com/easyoneweb/easy-code-remote/server/internal/httpapi"
	"github.com/easyoneweb/easy-code-remote/server/internal/kilo"
	"github.com/easyoneweb/easy-code-remote/server/internal/store"
	"github.com/easyoneweb/easy-code-remote/server/internal/supervisor"
)

// version is overridable at build time via -ldflags "-X main.version=...".
var version = "0.1.0"

func main() {
	args := os.Args[1:]
	if len(args) > 0 {
		switch args[0] {
		case "-v", "--version":
			fmt.Println(version)
			return
		case "-h", "--help", "help":
			usage()
			return
		}
	}
	var err error
	switch {
	case len(args) == 0 || args[0] == "serve":
		err = runServe(args[1:])
	case args[0] == "status":
		err = runStatus()
	case args[0] == "doctor":
		err = runDoctor()
	case args[0] == "token" && len(args) > 1 && args[1] == "rotate":
		err = runTokenRotate()
	case args[0] == "cert" && len(args) > 1 && args[1] == "regen":
		err = runCertRegen()
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Print(`easy-code-remote ` + version + ` — companion server for Kilo Code sessions

Usage:
  easy-code-remote serve            run the server (default)
  easy-code-remote status           quick status summary
  easy-code-remote doctor           full environment check
  easy-code-remote token rotate     regenerate the bearer token
  easy-code-remote cert regen       regenerate the self-signed TLS certificate
  easy-code-remote -v | --version   print version

Config: ~/.config/easy-code-remote/config.json (override with EASY_CODE_REMOTE_CONFIG_DIR).
`)
}

func appLogger(level string) *slog.Logger {
	var lvl slog.Level
	switch strings.ToLower(level) {
	case "debug":
		lvl = slog.LevelDebug
	case "warn", "warning":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}
	if strings.ToLower(level) == "json" {
		return slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: lvl}))
	}
	return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: lvl}))
}

// accessLogger always emits JSON lines so the access log is machine-parseable.
func accessLogger() *slog.Logger {
	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
}

func runServe(args []string) error {
	// --config override (for tests/dev); otherwise default paths.
	paths := config.DefaultPaths()
	for i := 0; i < len(args)-1; i++ {
		if args[i] == "--config" {
			paths.Config = args[i+1]
		}
	}
	log := appLogger("info")
	cfg, err := config.Load(paths.Config)
	if err != nil {
		return err
	}
	log = appLogger(cfg.LogLevel)
	createdToken, createdCert, err := cfg.EnsureFirstRun(paths)
	if err != nil {
		return err
	}
	if createdToken || createdCert {
		log.Info("first-run setup complete",
			"config", paths.Config,
			"token_generated", createdToken,
			"cert_generated", createdCert,
			"tls_dir", paths.TLSDir)
		if createdToken {
			log.Info("phone setup",
				"url", "https://<pc-public-ip-or-domain>:"+listenPort(cfg.ListenAddr),
				"hint", "read the bearer token from "+paths.Config+" (or run `easy-code-remote token rotate`); "+
					"open the firewall (sudo ufw allow "+listenPort(cfg.ListenAddr)+"/tcp) and forward TCP "+
					listenPort(cfg.ListenAddr)+" on your router to this PC")
		}
	}

	bin, err := kilo.Discover(cfg.Kilo.Bin)
	if err != nil {
		return fmt.Errorf("%w\n\nInstall hint: install the Kilo Code VSCode extension, put `kilo` on PATH,\nset KILO_BIN, or set kilo.bin in %s.", err, paths.Config)
	}
	kiloVersion := kilo.Version(bin)

	if supervisor.PortAlive(cfg.Kilo.Hostname, cfg.Kilo.Port) {
		return fmt.Errorf("something is already listening on %s:%d (the supervised kilo serve could not bind);\nstop the conflicting process or change kilo.port in %s", cfg.Kilo.Hostname, cfg.Kilo.Port, paths.Config)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()

	password, err := kilo.GeneratePassword()
	if err != nil {
		return err
	}
	log.Info("starting", "version", version, "kilo_bin", bin, "kilo_version", kiloVersion, "kilo_port", cfg.Kilo.Port)

	// Wrap slog.Info so printf-style messages from the supervisor/API log cleanly.
	logf := func(format string, args ...any) { log.Info(fmt.Sprintf(format, args...)) }

	sup := supervisor.New(bin, cfg.Kilo.Hostname, cfg.Kilo.Port, password, logf)
	if err := sup.Start(ctx); err != nil {
		return fmt.Errorf("supervised kilo serve failed to start: %w", err)
	}
	defer sup.Stop()

	k := kilo.NewClient(cfg.Kilo.Hostname, cfg.Kilo.Port, password)
	k.Bin = bin
	st := store.New()
	apiSrv := api.New(k, st, sup, version, logf)
	apiSrv.KiloVersion = kiloVersion

	resyncCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	err = st.Resync(resyncCtx, k)
	cancel()
	if err != nil {
		log.Warn("initial resync failed (engine may be starting); retrying on SSE connect", "err", err)
	}

	go apiSrv.Run(ctx)

	hs := httpapi.New(cfg, apiSrv, accessLogger())
	if err := hs.Serve(ctx); err != nil {
		return err
	}
	log.Info("shutdown complete")
	return nil
}

func loadConfigForOps() (*config.Config, config.Paths, error) {
	paths := config.DefaultPaths()
	cfg, err := config.Load(paths.Config)
	if err != nil {
		return nil, paths, err
	}
	return cfg, paths, nil
}

func runStatus() error {
	cfg, paths, err := loadConfigForOps()
	if err != nil {
		return err
	}
	bin, berr := kilo.Discover(cfg.Kilo.Bin)
	kv := ""
	if berr == nil {
		kv = kilo.Version(bin)
	}
	fmt.Printf("config:          %s\n", paths.Config)
	fmt.Printf("listen:          %s\n", cfg.ListenAddr)
	fmt.Printf("token:           %s\n", presence(cfg.Token))
	fmt.Printf("tls cert:        %s (%s)\n", cfg.TLS.Cert, certStatus(cfg.TLS.Cert))
	fmt.Printf("kilo binary:     %s\n", ternary(berr == nil, bin, "NOT FOUND"))
	fmt.Printf("kilo version:    %s\n", kv)
	fmt.Printf("kilo endpoint:   %s:%d (%s)\n", cfg.Kilo.Hostname, cfg.Kilo.Port,
		ternary(supervisor.PortAlive(cfg.Kilo.Hostname, cfg.Kilo.Port), "listening", "not listening"))
	return nil
}

func runDoctor() error {
	cfg, paths, err := loadConfigForOps()
	if err != nil {
		return err
	}
	ok := true
	check := func(name string, pass bool, detail string) {
		mark := "OK "
		if !pass {
			mark = "FAIL"
			ok = false
		}
		fmt.Printf("[%s] %-28s %s\n", mark, name, detail)
	}

	bin, berr := kilo.Discover(cfg.Kilo.Bin)
	kv := ""
	if berr == nil {
		kv = kilo.Version(bin)
	}
	binDetail := bin
	if berr != nil {
		binDetail = berr.Error()
	}
	check("kilo binary", berr == nil, binDetail)
	check("kilo version", kv != "", kv)
	check("kilo port free", !supervisor.PortAlive(cfg.Kilo.Hostname, cfg.Kilo.Port),
		fmt.Sprintf("%s:%d", cfg.Kilo.Hostname, cfg.Kilo.Port))
	check("token set", cfg.Token != "", presence(cfg.Token))
	check("tls cert present", certStatus(cfg.TLS.Cert) != "missing", certStatus(cfg.TLS.Cert))
	check("tls key present", fileExists(cfg.TLS.Key), cfg.TLS.Key)
	if cfg.MTLS {
		check("mtls client_ca", fileExists(cfg.ClientCA), cfg.ClientCA)
	}
	check("config readable", true, paths.Config)
	if !ok {
		return fmt.Errorf("doctor found problems; see above")
	}
	fmt.Println("all checks passed")
	return nil
}

func runTokenRotate() error {
	cfg, paths, err := loadConfigForOps()
	if err != nil {
		return err
	}
	newToken, err := config.GenerateToken()
	if err != nil {
		return err
	}
	cfg.Token = newToken
	if err := cfg.Save(paths.Config); err != nil {
		return err
	}
	fmt.Println("token rotated. New bearer token:")
	fmt.Println(newToken)
	fmt.Println("(phones must re-authenticate with this token)")
	return nil
}

func runCertRegen() error {
	cfg, paths, err := loadConfigForOps()
	if err != nil {
		return err
	}
	if cfg.TLS.Cert == "" {
		cfg.TLS.Cert = paths.CertFile
		cfg.TLS.Key = paths.KeyFile
	}
	if _, err := config.RegenerateCertificate(cfg.TLS.Cert, cfg.TLS.Key); err != nil {
		return err
	}
	if err := cfg.Save(paths.Config); err != nil {
		return err
	}
	fmt.Printf("certificate regenerated:\n  cert: %s\n  key:  %s\nrestart the service to apply\n", cfg.TLS.Cert, cfg.TLS.Key)
	return nil
}

func presence(s string) string {
	if s == "" {
		return "NOT SET"
	}
	return "set"
}

// listenPort extracts the port from a listen address like "0.0.0.0:8443".
func listenPort(addr string) string {
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		if i := strings.LastIndex(addr, ":"); i >= 0 {
			return addr[i+1:]
		}
		return "8443"
	}
	return port
}

func ternary(cond bool, a, b string) string {
	if cond {
		return a
	}
	return b
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func certStatus(certPath string) string {
	created, err := config.CertificateInfo(certPath)
	if err != nil {
		return "missing"
	}
	return fmt.Sprintf("valid until %s", created.NotAfter.Format("2006-01-02"))
}
