// Package adapter implements per-runtime job execution for velafleet-run.
//
// Each adapter knows how to start a runtime's process and, where possible,
// translate that runtime's native structured output into the shim's JSONL
// event protocol (events.Writer). A runtime that cannot emit structured
// state (design doc FA5) still gets an adapter — it just degrades to
// started/finished only, derived from the shim's own process observation,
// never from screen scraping (design doc FB4).
package adapter

import (
	"context"
	"fmt"

	"vela/fleet/run/internal/events"
)

// Adapter runs a job's command and reports its lifecycle via w.
type Adapter interface {
	// Name is the runtime identifier (e.g. "amplifier-agent", "shell").
	Name() string

	// Run executes argv as the job's command, inheriting the current
	// process's stdio (so a human attached to the hosting PTY/pane sees
	// normal interactive output), and emits started/progress/attention/
	// finished/failed events to w as they occur. Run blocks until the
	// job's process exits or ctx is cancelled, and returns the process's
	// exit code (or a non-zero code plus err on the shim's own failure to
	// launch it).
	Run(ctx context.Context, jobID string, argv []string, w *events.Writer) (exitCode int, err error)
}

// Registry of built-in adapters, keyed by runtime name.
var registry = map[string]func() Adapter{
	"shell":           func() Adapter { return &ShellAdapter{} },
	"amplifier-agent": func() Adapter { return &AmplifierAgentAdapter{} },
}

// ForRuntime returns the adapter for the named runtime, or an error listing
// the known runtimes if it is not registered. Unknown runtimes are not
// silently coerced to "shell" — an operator asking for a specific runtime
// should get an explicit error if this shim does not support it yet.
func ForRuntime(name string) (Adapter, error) {
	factory, ok := registry[name]
	if !ok {
		known := make([]string, 0, len(registry))
		for k := range registry {
			known = append(known, k)
		}
		return nil, fmt.Errorf("adapter: unknown runtime %q (known: %v)", name, known)
	}
	return factory(), nil
}
