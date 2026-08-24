package adapter

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"vela/fleet/run/internal/events"
)

func readEvents(t *testing.T, path string) []events.Event {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open %s: %v", path, err)
	}
	defer f.Close()
	var out []events.Event
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		var ev events.Event
		if err := json.Unmarshal(sc.Bytes(), &ev); err != nil {
			t.Fatalf("unmarshal %q: %v", sc.Text(), err)
		}
		out = append(out, ev)
	}
	return out
}

func TestShellAdapterSuccess(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.jsonl")
	w, err := events.NewWriter(path)
	if err != nil {
		t.Fatal(err)
	}
	defer w.Close()

	a := &ShellAdapter{}
	code, err := a.Run(context.Background(), "job-shell-1", []string{"true"}, w)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if code != 0 {
		t.Fatalf("exit code = %d, want 0", code)
	}

	evs := readEvents(t, path)
	if len(evs) != 2 {
		t.Fatalf("expected 2 events (started, finished), got %d: %+v", len(evs), evs)
	}
	if evs[0].Kind != events.KindStarted || evs[0].PID == 0 {
		t.Errorf("event 0 = %+v, want started with a pid", evs[0])
	}
	if evs[1].Kind != events.KindFinished || evs[1].ExitCode == nil || *evs[1].ExitCode != 0 {
		t.Errorf("event 1 = %+v, want finished/exit 0", evs[1])
	}
}

func TestShellAdapterFailure(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.jsonl")
	w, err := events.NewWriter(path)
	if err != nil {
		t.Fatal(err)
	}
	defer w.Close()

	a := &ShellAdapter{}
	code, err := a.Run(context.Background(), "job-shell-2", []string{"false"}, w)
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}

	evs := readEvents(t, path)
	if len(evs) != 2 {
		t.Fatalf("expected 2 events, got %d: %+v", len(evs), evs)
	}
	if evs[1].Kind != events.KindFailed || evs[1].ExitCode == nil || *evs[1].ExitCode != 1 {
		t.Errorf("event 1 = %+v, want failed/exit 1", evs[1])
	}
}

func TestForRuntimeUnknown(t *testing.T) {
	if _, err := ForRuntime("does-not-exist"); err == nil {
		t.Fatal("expected error for unknown runtime")
	}
}

func TestForRuntimeKnown(t *testing.T) {
	for _, name := range []string{"shell", "amplifier-agent"} {
		a, err := ForRuntime(name)
		if err != nil {
			t.Fatalf("ForRuntime(%q): %v", name, err)
		}
		if a.Name() != name {
			t.Errorf("ForRuntime(%q).Name() = %q", name, a.Name())
		}
	}
}
