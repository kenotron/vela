// Command velafleet-run is the per-machine job wrapper described in
// docs/designs/2026-08-24-vela-fleet-execution-plane.md §4.3.
//
// It execs a runtime's real process (inheriting stdio, so a human attached
// to the hosting muxterm pane sees normal interactive output), and
// concurrently emits structured JSONL events describing the job's lifecycle
// to a sidecar file — never derived from the terminal screen (design doc
// FB4). See docs/fleet/JOB_EVENTS.md for the event schema.
//
// Usage:
//
//	velafleet-run --job <job_id> [--runtime <name>] [--events-dir <dir>] -- <argv...>
//
// If --runtime is omitted, it is inferred from the basename of argv[0].
// If --events-dir is omitted, it defaults to ~/.vela/jobs/<job_id>/ and the
// event file is <events-dir>/events.jsonl.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"vela/fleet/run/internal/supervisor"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	fs := flag.NewFlagSet("velafleet-run", flag.ContinueOnError)
	jobID := fs.String("job", "", "job id (required)")
	runtime := fs.String("runtime", "", "runtime adapter name (default: inferred from the command's basename)")
	eventsDir := fs.String("events-dir", "", "directory to write events.jsonl into (default: ~/.vela/jobs/<job_id>)")
	fs.Usage = func() {
		fmt.Fprintln(os.Stderr, "usage: velafleet-run --job <job_id> [--runtime <name>] [--events-dir <dir>] -- <argv...>")
		fs.PrintDefaults()
	}

	// Split at "--": everything after it is the job's own argv, untouched
	// by our flag parser.
	splitAt := -1
	for i, a := range args {
		if a == "--" {
			splitAt = i
			break
		}
	}
	var flagArgs, argv []string
	if splitAt >= 0 {
		flagArgs = args[:splitAt]
		argv = args[splitAt+1:]
	} else {
		flagArgs = args
	}

	if err := fs.Parse(flagArgs); err != nil {
		return 2
	}

	if *jobID == "" {
		fmt.Fprintln(os.Stderr, "velafleet-run: --job is required")
		fs.Usage()
		return 2
	}
	if len(argv) == 0 {
		fmt.Fprintln(os.Stderr, "velafleet-run: no command given (expected `-- <argv...>`)")
		fs.Usage()
		return 2
	}

	rt := *runtime
	if rt == "" {
		rt = filepath.Base(argv[0])
	}

	dir := *eventsDir
	if dir == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			home = "."
		}
		dir = filepath.Join(home, ".vela", "jobs", *jobID)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	code, err := supervisor.Run(ctx, supervisor.Request{
		JobID:      *jobID,
		Runtime:    rt,
		EventsPath: filepath.Join(dir, "events.jsonl"),
		Argv:       argv,
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, "velafleet-run:", err)
	}
	return code
}
