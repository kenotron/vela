package adapter

import (
	"context"
	"os"
	"os/exec"

	"vela/fleet/run/internal/events"
)

// ShellAdapter runs an arbitrary shell command. It has no structured event
// source of its own (design doc FA5 "degraded" case): it only ever emits
// started (with the child's pid) and finished/failed (with the child's exit
// code), derived from the shim's own process observation. It never scrapes
// stdout/stderr for state — those streams are inherited straight through to
// the hosting terminal for a human to read (design doc FB4).
type ShellAdapter struct{}

func (a *ShellAdapter) Name() string { return "shell" }

func (a *ShellAdapter) Run(ctx context.Context, jobID string, argv []string, w *events.Writer) (int, error) {
	if len(argv) == 0 {
		err := w.Write(events.Failed(jobID, -1, "shell adapter: empty command"))
		return -1, err
	}

	cmd := exec.CommandContext(ctx, argv[0], argv[1:]...)
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Start(); err != nil {
		_ = w.Write(events.Failed(jobID, -1, "shell adapter: start: "+err.Error()))
		return -1, err
	}

	if err := w.Write(events.Started(jobID, a.Name(), cmd.Process.Pid)); err != nil {
		return -1, err
	}

	waitErr := cmd.Wait()
	exitCode := 0
	if waitErr != nil {
		if exitErr, ok := waitErr.(*exec.ExitError); ok {
			exitCode = exitErr.ExitCode()
		} else {
			exitCode = -1
		}
	}

	if exitCode == 0 {
		return 0, w.Write(events.Finished(jobID, 0, nil))
	}
	return exitCode, w.Write(events.Failed(jobID, exitCode, errString(waitErr)))
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
