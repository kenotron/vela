package events

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestWriterAppendsJSONLines(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sub", "events.jsonl")

	w, err := NewWriter(path)
	if err != nil {
		t.Fatalf("NewWriter: %v", err)
	}

	if err := w.Write(Started("job-1", "shell", 123)); err != nil {
		t.Fatalf("Write started: %v", err)
	}
	if err := w.Write(Progress("job-1", "halfway", 50)); err != nil {
		t.Fatalf("Write progress: %v", err)
	}
	if err := w.Write(Attention("job-1", "pick one", []string{"a", "b"})); err != nil {
		t.Fatalf("Write attention: %v", err)
	}
	if err := w.Write(Finished("job-1", 0, map[string]any{"pr_url": "https://example"})); err != nil {
		t.Fatalf("Write finished: %v", err)
	}
	if err := w.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open written file: %v", err)
	}
	defer f.Close()

	var lines []Event
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		var ev Event
		if err := json.Unmarshal(sc.Bytes(), &ev); err != nil {
			t.Fatalf("unmarshal line %q: %v", sc.Text(), err)
		}
		lines = append(lines, ev)
	}
	if err := sc.Err(); err != nil {
		t.Fatalf("scan: %v", err)
	}

	if len(lines) != 4 {
		t.Fatalf("expected 4 lines, got %d", len(lines))
	}
	if lines[0].Kind != KindStarted || lines[0].PID != 123 {
		t.Errorf("line 0 = %+v, want started/pid=123", lines[0])
	}
	if lines[1].Kind != KindProgress || lines[1].Percent == nil || *lines[1].Percent != 50 {
		t.Errorf("line 1 = %+v, want progress/50", lines[1])
	}
	if lines[2].Kind != KindAttention || lines[2].Reason != "pick one" || len(lines[2].Options) != 2 {
		t.Errorf("line 2 = %+v, want attention", lines[2])
	}
	if lines[3].Kind != KindFinished || lines[3].ExitCode == nil || *lines[3].ExitCode != 0 {
		t.Errorf("line 3 = %+v, want finished/exit 0", lines[3])
	}
	for i, ev := range lines {
		if ev.TS == 0 {
			t.Errorf("line %d has no timestamp", i)
		}
	}
}

func TestWriterAppendsAcrossOpens(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.jsonl")

	w1, err := NewWriter(path)
	if err != nil {
		t.Fatalf("NewWriter: %v", err)
	}
	_ = w1.Write(Started("job-2", "shell", 1))
	_ = w1.Close()

	w2, err := NewWriter(path)
	if err != nil {
		t.Fatalf("NewWriter (reopen): %v", err)
	}
	_ = w2.Write(Finished("job-2", 0, nil))
	_ = w2.Close()

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	count := 0
	for sc.Scan() {
		count++
	}
	if count != 2 {
		t.Fatalf("expected 2 lines after reopen, got %d (raw: %s)", count, data)
	}
}
