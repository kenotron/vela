// Package events implements the velafleet-run JSONL event protocol.
//
// Every state change in a supervised job is emitted as one JSON object per
// line, appended to a sidecar file (by convention
// ~/.vela/jobs/<job_id>/events.jsonl). See docs/fleet/JOB_EVENTS.md for the
// full schema. This package never touches the job's PTY/terminal output —
// job state is derived from this file, never from screen scraping (design
// doc FB4).
package events

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Kind enumerates the event types in the JSONL protocol.
type Kind string

const (
	KindStarted  Kind = "started"
	KindProgress Kind = "progress"
	// KindAttention marks a job blocked on a human decision. Attention
	// events must never be coalesced or dropped by any consumer (design
	// doc §5.2).
	KindAttention Kind = "attention"
	KindCost      Kind = "cost"
	KindFinished  Kind = "finished"
	KindFailed    Kind = "failed"
)

// Event is a single JSONL record. Fields are optional per-kind; see
// docs/fleet/JOB_EVENTS.md for which fields apply to which kind.
type Event struct {
	TS      int64          `json:"ts"`
	Kind    Kind           `json:"kind"`
	JobID   string         `json:"job_id,omitempty"`
	Runtime string         `json:"runtime,omitempty"`
	PID     int            `json:"pid,omitempty"`
	Message string         `json:"message,omitempty"`
	Percent *int           `json:"percent,omitempty"`
	Reason  string         `json:"reason,omitempty"`
	Options []string       `json:"options,omitempty"`
	USD     *float64       `json:"usd,omitempty"`
	Tokens  *int           `json:"tokens,omitempty"`
	ExitCode *int          `json:"exit_code,omitempty"`
	Result  map[string]any `json:"result,omitempty"`
	Error   string         `json:"error,omitempty"`
}

// Writer appends Events to a JSONL file, one line per event, flushing after
// every write so a reader tailing the file (or a crash of this process)
// never sees a partial or missing line.
type Writer struct {
	mu   sync.Mutex
	path string
	f    *os.File
	enc  *json.Encoder
}

// NewWriter opens (creating parent directories and the file, appending if it
// already exists) the JSONL sidecar file at path.
func NewWriter(path string) (*Writer, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("events: create dir: %w", err)
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return nil, fmt.Errorf("events: open %s: %w", path, err)
	}
	return &Writer{path: path, f: f, enc: json.NewEncoder(f)}, nil
}

// Path returns the sidecar file path this writer is appending to.
func (w *Writer) Path() string { return w.path }

// Write appends ev as one JSON line, stamping TS if unset.
func (w *Writer) Write(ev Event) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if ev.TS == 0 {
		ev.TS = time.Now().Unix()
	}
	if err := w.enc.Encode(ev); err != nil {
		return fmt.Errorf("events: write: %w", err)
	}
	return w.f.Sync()
}

// Close closes the underlying file.
func (w *Writer) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.f.Close()
}

// Helper constructors for the common cases.

func Started(jobID, runtime string, pid int) Event {
	return Event{Kind: KindStarted, JobID: jobID, Runtime: runtime, PID: pid}
}

func Progress(jobID, message string, percent int) Event {
	p := percent
	return Event{Kind: KindProgress, JobID: jobID, Message: message, Percent: &p}
}

func Attention(jobID, reason string, options []string) Event {
	return Event{Kind: KindAttention, JobID: jobID, Reason: reason, Options: options}
}

func Cost(jobID string, usd float64, tokens int) Event {
	return Event{Kind: KindCost, JobID: jobID, USD: &usd, Tokens: &tokens}
}

func Finished(jobID string, exitCode int, result map[string]any) Event {
	ec := exitCode
	return Event{Kind: KindFinished, JobID: jobID, ExitCode: &ec, Result: result}
}

func Failed(jobID string, exitCode int, errMsg string) Event {
	ec := exitCode
	return Event{Kind: KindFailed, JobID: jobID, ExitCode: &ec, Error: errMsg}
}
