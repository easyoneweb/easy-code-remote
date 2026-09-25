// Package kilo discovery: find every `kilo serve` process on this Linux host.
//
// Kilo's /session, /session/status, /question and /permission endpoints, and the
// /event SSE stream, are all scoped to the project of the serve process's working
// directory. VSCode windows each spawn their own `kilo serve`, so the companion
// server must talk to all of them to observe every session. This module scans
// /proc (Linux only) for kilo serve processes and extracts their listening port
// and KILO_SERVER_PASSWORD from the process environment.
package kilo

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// Serve describes one running kilo serve process.
type Serve struct {
	PID  int
	Port int
	Pass string
}

// DiscoverServes scans /proc for `kilo serve` processes. skipPort excludes a
// port already managed elsewhere (the supervised serve). Returns serves sorted
// by port.
func DiscoverServes(skipPort int) ([]Serve, error) {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil, fmt.Errorf("read /proc: %w", err)
	}
	byPort := make(map[int]Serve)
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		cmdline, err := os.ReadFile(filepath.Join("/proc", e.Name(), "cmdline"))
		if err != nil {
			continue
		}
		if !isKiloServe(cmdline) {
			continue
		}
		env, err := os.ReadFile(filepath.Join("/proc", e.Name(), "environ"))
		if err != nil {
			continue
		}
		pass := envValue(env, "KILO_SERVER_PASSWORD")
		port := listenPort(pid)
		if pass == "" || port == 0 || port == skipPort {
			continue
		}
		byPort[port] = Serve{PID: pid, Port: port, Pass: pass}
	}
	out := make([]Serve, 0, len(byPort))
	for _, s := range byPort {
		out = append(out, s)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Port < out[j].Port })
	return out, nil
}

// isKiloServe reports whether a NUL-separated cmdline belongs to a kilo serve
// process (binary path containing "kilo" and an argument "serve").
func isKiloServe(cmdline []byte) bool {
	args := strings.Split(strings.TrimRight(string(cmdline), "\x00"), "\x00")
	if len(args) == 0 {
		return false
	}
	if !strings.Contains(args[0], "kilo") {
		return false
	}
	for _, a := range args[1:] {
		if a == "serve" {
			return true
		}
	}
	return false
}

// envValue extracts a KEY=VALUE entry from a NUL-separated environ blob.
func envValue(environ []byte, key string) string {
	for _, e := range strings.Split(strings.TrimRight(string(environ), "\x00"), "\x00") {
		if v, ok := strings.CutPrefix(e, key+"="); ok {
			return v
		}
	}
	return ""
}

// listenPort returns the TCP port of the given process's listening socket by
// matching its socket inodes against /proc/net/tcp and /proc/net/tcp6.
func listenPort(pid int) int {
	fdDir := filepath.Join("/proc", strconv.Itoa(pid), "fd")
	fds, err := os.ReadDir(fdDir)
	if err != nil {
		return 0
	}
	inodes := make(map[string]bool)
	for _, fd := range fds {
		target, err := os.Readlink(filepath.Join(fdDir, fd.Name()))
		if err == nil && strings.HasPrefix(target, "socket:[") && strings.HasSuffix(target, "]") {
			inodes[strings.TrimSuffix(strings.TrimPrefix(target, "socket:["), "]")] = true
		}
	}
	if len(inodes) == 0 {
		return 0
	}
	for _, f := range []string{"/proc/net/tcp", "/proc/net/tcp6"} {
		data, err := os.ReadFile(f)
		if err != nil {
			continue
		}
		if port := portForInodes(data, inodes); port != 0 {
			return port
		}
	}
	return 0
}

// portForInodes scans a /proc/net/tcp[6] blob for a socket whose inode is in
// the set and returns its local port (parses the `local_address` column).
func portForInodes(data []byte, inodes map[string]bool) int {
	lines := strings.Split(string(data), "\n")
	for _, line := range lines[1:] {
		fields := strings.Fields(line)
		if len(fields) < 10 {
			continue
		}
		if !inodes[fields[9]] {
			continue
		}
		// local_address is "HEXIP:HEXPORT".
		parts := strings.Split(fields[1], ":")
		if len(parts) == 2 {
			if port, err := strconv.ParseInt(parts[1], 16, 32); err == nil {
				return int(port)
			}
		}
	}
	return 0
}
