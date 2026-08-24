package adapter

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"os"
	"os/exec"
	"slices"
	"time"

	"vela/fleet/run/internal/events"
)

// tailPollInterval is how often the tee-file tailer checks for new lines
// while the job's process is still running.
const tailPollInterval = 200 * time.Millisecond

// AmplifierAgentAdapter runs `amplifier-agent` and forwards its native
// structured event stream into the shim's JSONL protocol.
//
// Two independent structured-output paths exist in amplifier-agent, and
// this adapter uses both, because they cover different invocation modes
// (verified live against the installed `amplifier-agent` binary in this
// lane, not assumed):
//
//  1. `--display ndjson` — for `amplifier-agent run` (single-turn / Mode A,
//     the mode a fleet job actually uses), one JSON-RPC-shaped notification
//     per line on **stderr**: {"method": "...", "params": {...}}. Verified
//     methods include thinking/delta, result/delta, result/final, usage,
//     tool/started, tool/completed. This adapter injects `--display ndjson`
//     into the argv itself (if the caller didn't already ask for a specific
//     --display) since enabling the runtime's own structured mode is
//     exactly this adapter's job. Stderr is tee'd: every line is forwarded
//     to this process's real stderr (so a human attached to the hosting
//     pane still sees it) *and* parsed for translation.
//  2. `AMPLIFIER_AGENT_EVENT_TEE_PATH` — a second, unfiltered consumer of
//     the internal event queue, but verified (spike S-1,
//     spikes/s1-event-tee/) to be wired only into the HTTP `serve` wire
//     face's chat_completions route, not into `run`. Still set here for
//     forward/alternate-invocation compatibility; if the job's argv never
//     goes through that code path the tee file simply never appears, which
//     is the FA5 degradation this adapter is required to tolerate rather
//     than treat as an error.
//
// If neither path produces output, the job still gets a valid
// started/finished pair from this adapter's own process observation —
// never fabricated progress/attention (design doc FB4, FA5).
type AmplifierAgentAdapter struct{}

func (a *AmplifierAgentAdapter) Name() string { return "amplifier-agent" }

func (a *AmplifierAgentAdapter) Run(ctx context.Context, jobID string, argv []string, w *events.Writer) (int, error) {
	if len(argv) == 0 {
		err := w.Write(events.Failed(jobID, -1, "amplifier-agent adapter: empty command"))
		return -1, err
	}

	teePath := teeFilePath(jobID)
	fullArgv := withNdjsonDisplay(argv)

	cmd := exec.CommandContext(ctx, fullArgv[0], fullArgv[1:]...)
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Env = append(os.Environ(), "AMPLIFIER_AGENT_EVENT_TEE_PATH="+teePath)

	stderrR, stderrW, err := os.Pipe()
	if err != nil {
		_ = w.Write(events.Failed(jobID, -1, "amplifier-agent adapter: pipe: "+err.Error()))
		return -1, err
	}
	cmd.Stderr = stderrW

	if err := cmd.Start(); err != nil {
		_ = w.Write(events.Failed(jobID, -1, "amplifier-agent adapter: start: "+err.Error()))
		return -1, err
	}
	_ = stderrW.Close() // parent's copy; the child holds its own

	if err := w.Write(events.Started(jobID, a.Name(), cmd.Process.Pid)); err != nil {
		return -1, err
	}

	stderrDone := make(chan struct{})
	go tailNdjsonStderr(stderrR, jobID, w, stderrDone)

	teeDone := make(chan struct{})
	go tailTeeFile(teePath, jobID, w, teeDone)

	waitErr := cmd.Wait()
	close(teeDone)
	time.Sleep(tailPollInterval) // let the tee tailer drain a final poll
	<-stderrDone                  // stderr tailer exits on its own on EOF

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

// withNdjsonDisplay returns argv with "--display ndjson" appended, unless
// the caller already passed a --display flag (in which case we respect
// their explicit choice rather than override it).
func withNdjsonDisplay(argv []string) []string {
	if slices.ContainsFunc(argv, func(a string) bool { return a == "--display" }) {
		return argv
	}
	out := make([]string, 0, len(argv)+2)
	out = append(out, argv...)
	out = append(out, "--display", "ndjson")
	return out
}

func teeFilePath(jobID string) string {
	dir := os.TempDir()
	return dir + "/velafleet-run-tee-" + jobID + ".jsonl"
}

// ndjsonNotification is the shape of one line of amplifier-agent's
// `--display ndjson` stderr stream: a JSON-RPC-style notification.
type ndjsonNotification struct {
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
}

// tailNdjsonStderr reads r line-by-line, forwarding every raw line to the
// real os.Stderr (so a human attached to the hosting pane still sees
// diagnostics) and translating recognized methods into events on w. It
// returns (closing done) when r hits EOF, i.e. when the child's stderr is
// closed at process exit.
func tailNdjsonStderr(r io.ReadCloser, jobID string, w *events.Writer, done chan<- struct{}) {
	defer close(done)
	defer r.Close()
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := sc.Text()
		_, _ = os.Stderr.WriteString(line + "\n")
		translateNdjsonLine(line, jobID, w)
	}
}

func translateNdjsonLine(line, jobID string, w *events.Writer) {
	var n ndjsonNotification
	if err := json.Unmarshal([]byte(line), &n); err != nil {
		return // not a structured line (e.g. a banner/log line) — nothing to translate
	}
	switch n.Method {
	case "thinking/delta", "result/delta":
		_ = w.Write(events.Progress(jobID, n.Method+": "+string(n.Params), 0))
	case "tool/started", "tool/completed":
		_ = w.Write(events.Progress(jobID, n.Method+": "+string(n.Params), 0))
	case "result/final":
		_ = w.Write(events.Progress(jobID, "result/final: "+string(n.Params), 100))
	case "usage":
		_ = w.Write(events.Progress(jobID, "usage: "+string(n.Params), 0))
	case "approval_request", "approval/requested", "attention", "needs_attention":
		_ = w.Write(events.Attention(jobID, string(n.Params), nil))
	}
}

// teeEvent is the shape amplifier-agent's HttpQueueDisplaySystem tee writes
// (kernel DisplayEvent, translated loosely; fields beyond "type"/"data" are
// runtime-internal and treated as opaque here).
type teeEvent struct {
	Type string          `json:"type"`
	Data json.RawMessage `json:"data"`
}

// tailTeeFile polls path for new lines and translates each into an event on
// w, until done is closed. It tolerates the file never existing (FA5: the
// producer isn't invoked via the code path that writes it, e.g. `run` mode
// per the verified finding above).
func tailTeeFile(path, jobID string, w *events.Writer, done <-chan struct{}) {
	var f *os.File
	var reader *bufio.Reader

	openIfNeeded := func() bool {
		if f != nil {
			return true
		}
		var err error
		f, err = os.Open(path)
		if err != nil {
			return false
		}
		reader = bufio.NewReader(f)
		return true
	}

	readAvailable := func() {
		if !openIfNeeded() {
			return
		}
		for {
			line, err := reader.ReadString('\n')
			if len(line) > 0 {
				translateTeeLine(line, jobID, w)
			}
			if err != nil {
				break // EOF (for now) or a transient read error; retry next tick
			}
		}
	}

	ticker := time.NewTicker(tailPollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			readAvailable() // final drain
			if f != nil {
				_ = f.Close()
			}
			return
		case <-ticker.C:
			readAvailable()
		}
	}
}

func translateTeeLine(line, jobID string, w *events.Writer) {
	var te teeEvent
	if err := json.Unmarshal([]byte(line), &te); err != nil {
		_ = w.Write(events.Progress(jobID, line, 0))
		return
	}
	switch te.Type {
	case "attention", "needs_attention", "approval_request":
		_ = w.Write(events.Attention(jobID, string(te.Data), nil))
	case "usage", "cost":
		_ = w.Write(events.Progress(jobID, "usage: "+string(te.Data), 0))
	default:
		_ = w.Write(events.Progress(jobID, te.Type+": "+string(te.Data), 0))
	}
}
