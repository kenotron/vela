// Package supervisor wires the CLI's parsed request to an adapter and the
// JSONL event writer, and owns the process's final exit code.
package supervisor

import (
	"context"
	"fmt"

	"vela/fleet/run/internal/adapter"
	"vela/fleet/run/internal/events"
)

// Request is a fully-parsed velafleet-run invocation.
type Request struct {
	JobID      string
	Runtime    string
	EventsPath string
	Argv       []string
}

// Run opens the events sidecar file, resolves the runtime adapter, executes
// the job, and returns the process's exit code. It never panics on adapter
// failure — a failure to even launch the job is itself reported as a
// KindFailed event before Run returns.
func Run(ctx context.Context, req Request) (int, error) {
	if req.JobID == "" {
		return 2, fmt.Errorf("supervisor: job id is required")
	}
	if len(req.Argv) == 0 {
		return 2, fmt.Errorf("supervisor: no command given (expected `-- <runtime argv>`)")
	}

	w, err := events.NewWriter(req.EventsPath)
	if err != nil {
		return 2, fmt.Errorf("supervisor: %w", err)
	}
	defer w.Close()

	a, err := adapter.ForRuntime(req.Runtime)
	if err != nil {
		_ = w.Write(events.Failed(req.JobID, 2, err.Error()))
		return 2, err
	}

	return a.Run(ctx, req.JobID, req.Argv, w)
}
